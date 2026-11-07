"""Kaggle GPU inference API for the fused Qwen hair-advisor model."""

import os
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

import torch
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from transformers import AutoModelForCausalLM, AutoTokenizer


MODEL_DIR = Path(os.environ.get("MODEL_DIR", "/kaggle/input/chipheo-barbershop-ai/fused_model"))
API_TOKEN = os.environ.get("KAGGLE_SERVER_TOKEN", "").strip()
app = FastAPI(title="Chi Pheo BarberShop model inference")
generation_lock = threading.Lock()
tokenizer = None
model = None

SYSTEM_PROMPT = (
    "Bạn là chuyên viên tư vấn tóc bằng tiếng Việt của Chí Phèo Barber Shop. "
    "Trả lời tự nhiên, thân thiện, đúng chính tả. Khi khách xin gợi ý kiểu tóc, "
    "nêu 2-3 kiểu quen thuộc, giải thích ngắn lý do và cách nói với thợ. "
    "Không giả vờ đã nhìn thấy khách. Không bịa tên, giá, thời lượng hay tồn kho dịch vụ. "
    "Không chẩn đoán bệnh hoặc kê thuốc. Nếu khách mô tả rụng tóc nhiều, đau, ngứa, "
    "viêm hay dị ứng, khuyên gặp bác sĩ da liễu."
)


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    preferences: Optional[Dict[str, Any]] = None
    history: Optional[List[Dict[str, Any]]] = Field(default=None, max_length=12)
    max_tokens: int = Field(default=384, ge=32, le=700)


class FaceRequest(BaseModel):
    face_aspect_ratio: float = Field(gt=0, le=10)
    forehead_to_cheek_ratio: float = Field(gt=0, le=10)
    jaw_to_cheek_ratio: float = Field(gt=0, le=10)
    forehead_to_jaw_ratio: float = Field(gt=0, le=10)


def require_gateway_token(authorization: Optional[str]) -> None:
    if not API_TOKEN:
        raise HTTPException(status_code=503, detail="Inference token is not configured.")
    if authorization != f"Bearer {API_TOKEN}":
        raise HTTPException(status_code=401, detail="Unauthorized.")


@app.on_event("startup")
def load_model() -> None:
    global tokenizer, model
    if not MODEL_DIR.is_dir():
        raise RuntimeError(f"Model directory not found: {MODEL_DIR}")
    tokenizer = AutoTokenizer.from_pretrained(str(MODEL_DIR), trust_remote_code=True)
    dtype = torch.float16 if torch.cuda.is_available() else torch.float32
    model = AutoModelForCausalLM.from_pretrained(
        str(MODEL_DIR),
        torch_dtype=dtype,
        low_cpu_mem_usage=True,
        trust_remote_code=True,
    )
    if torch.cuda.is_available():
        model.to("cuda")
    model.eval()
    print(f"Loaded model from {MODEL_DIR}; cuda={torch.cuda.is_available()}", flush=True)


def generate(messages: List[Dict[str, str]], max_tokens: int) -> str:
    if model is None or tokenizer is None:
        raise HTTPException(status_code=503, detail="Model is still loading.")
    prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = tokenizer(prompt, return_tensors="pt", truncation=True, max_length=4096)
    if torch.cuda.is_available():
        inputs = {key: value.to("cuda") for key, value in inputs.items()}
    with generation_lock, torch.inference_mode():
        output = model.generate(
            **inputs,
            max_new_tokens=max_tokens,
            do_sample=True,
            temperature=0.55,
            top_p=0.85,
            repetition_penalty=1.08,
            pad_token_id=tokenizer.eos_token_id,
        )
    generated = output[0, inputs["input_ids"].shape[1]:]
    return tokenizer.decode(generated, skip_special_tokens=True).strip()


@app.get("/health")
def health():
    return {"success": True, "model_loaded": model is not None, "cuda": torch.cuda.is_available()}


@app.post("/api/chat")
def chat(request: ChatRequest, authorization: Optional[str] = Header(default=None)):
    require_gateway_token(authorization)
    messages: List[Dict[str, str]] = [{"role": "system", "content": SYSTEM_PROMPT}]
    history = (request.history or [])[-8:]
    if history and history[-1].get("role") == "user" and history[-1].get("text") != request.message:
        history = history[:-1]
    for turn in history:
        role = turn.get("role")
        content = turn.get("text")
        if role in ("user", "model", "assistant") and isinstance(content, str) and content.strip():
            messages.append({"role": "assistant" if role in ("model", "assistant") else "user", "content": content[:1200]})
    if not messages or messages[-1]["role"] != "user" or messages[-1]["content"] != request.message:
        messages.append({"role": "user", "content": request.message.strip()})
    answer = generate(messages, request.max_tokens)
    if not answer:
        raise HTTPException(status_code=502, detail="The model returned an empty response.")
    return {"success": True, "answer": answer, "reply": answer, "response": answer}


def classify_face(request: FaceRequest) -> str:
    if request.forehead_to_jaw_ratio >= 1.16 and request.jaw_to_cheek_ratio < 0.84:
        return "Heart"
    if request.jaw_to_cheek_ratio >= 0.88 and 0.90 <= request.forehead_to_jaw_ratio <= 1.10:
        return "Square"
    if request.face_aspect_ratio >= 1.70:
        return "Oblong"
    if request.face_aspect_ratio <= 1.27:
        return "Round"
    return "Oval"


@app.post("/api/consult-face")
def consult_face(request: FaceRequest, authorization: Optional[str] = Header(default=None)):
    require_gateway_token(authorization)
    shape = classify_face(request)
    prompt = (
        f"Số đo hình học ước tính khuôn mặt khách cho thấy dáng {shape}. "
        "Hãy tư vấn 2-3 kiểu tóc phù hợp bằng tiếng Việt, nói rõ đây là gợi ý tham khảo "
        "từ tỷ lệ hình học vì bạn không nhìn thấy ảnh. Mỗi kiểu nêu lý do và cách dặn thợ."
    )
    answer = generate(
        [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": prompt}],
        320,
    )
    return {"success": True, "face_shape": shape, "reply": answer}
