import json
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException
import firebase_admin
from firebase_admin import credentials, firestore
import kagglehub
from pydantic import BaseModel, Field
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

app = FastAPI(title="BarberShop AI Advisory API")

# ---------------------------------------------------------
# 1. Khởi tạo Firebase Admin SDK (An toàn tuyệt đối trên Cloud)
# ---------------------------------------------------------
CRED_PATH = os.environ.get("FIREBASE_CREDENTIALS", "ai-training/serviceAccountKey.json")
FIREBASE_JSON = os.environ.get("FIREBASE_JSON_CONTENT")

try:
    if not firebase_admin._apps:
        if FIREBASE_JSON:
            # Ưu tiên đọc từ biến môi trường FIREBASE_JSON_CONTENT trên Render
            cred_dict = json.loads(FIREBASE_JSON)
            cred = credentials.Certificate(cred_dict)
            firebase_admin.initialize_app(cred)
            db = firestore.client()
            print("Đã kết nối Firebase qua biến môi trường Cloud thành công!")
        elif os.path.exists(CRED_PATH):
            # Đọc từ file local khi chạy trên máy Mac
            cred = credentials.Certificate(CRED_PATH)
            firebase_admin.initialize_app(cred)
            db = firestore.client()
            print("Đã kết nối Firebase qua file local thành công!")
        else:
            print("[Cảnh báo] Không tìm thấy credentials Firebase. Chạy ở chế độ fallback.")
            db = None
    else:
        db = firestore.client()
except Exception as firebase_err:
    print(f"[Cảnh báo] Không thể kết nối Firebase: {firebase_err}")
    db = None


def get_store_data_from_firebase() -> str:
    """Truy vấn dịch vụ và sản phẩm từ Firestore (Bảo vệ khi không kết nối được Firebase)."""
    if db is None:
        return "Vui lòng tham khảo thông tin dịch vụ và sản phẩm trực tiếp trên ứng dụng.\n"

    info_parts = []

    # Lấy danh mục Dịch vụ
    try:
        services_docs = list(db.collection("services").stream())
        service_list = []
        for doc in services_docs:
            data = doc.to_dict()
            name = data.get("name") or data.get("title") or doc.id
            price = data.get("price") if data.get("price") is not None else data.get("cost")
            unit = data.get("unit", "đ")
            if price is not None:
                try:
                    formatted_price = f"{int(price):,}".replace(",", ".") + f" {unit}"
                    service_list.append(f"  + {name}: {formatted_price}")
                except (ValueError, TypeError):
                    service_list.append(f"  + {name}: {price} {unit}")
            else:
                service_list.append(f"  + {name}")

        if service_list:
            info_parts.append("Dịch vụ cắt / uốn / nhuộm:\n" + "\n".join(service_list))
    except Exception as e:
        print(f"[Cảnh báo] Lỗi truy vấn dịch vụ: {e}")

    # Lấy danh mục Sản phẩm
    try:
        products_docs = list(db.collection("products").stream())
        product_list = []
        for doc in products_docs:
            data = doc.to_dict()
            name = data.get("name") or data.get("title") or doc.id
            price = data.get("price") if data.get("price") is not None else data.get("cost")
            unit = data.get("unit", "đ")
            if price is not None:
                try:
                    formatted_price = f"{int(price):,}".replace(",", ".") + f" {unit}"
                    product_list.append(f"  + {name}: {formatted_price}")
                except (ValueError, TypeError):
                    product_list.append(f"  + {name}: {price} {unit}")
            else:
                product_list.append(f"  + {name}")

        if product_list:
            info_parts.append("Sản phẩm chăm sóc & tạo kiểu tóc đang bán tại tiệm:\n" + "\n".join(product_list))
    except Exception as e:
        print(f"[Cảnh báo] Lỗi truy vấn sản phẩm: {e}")

    if info_parts:
        return "THÔNG TIN BẢNG GIÁ VÀ SẢN PHẨM CỦA TIỆM (cập nhật từ hệ thống):\n" + "\n\n".join(info_parts)
    return "Vui lòng tham khảo thông tin chi tiết trên ứng dụng.\n"


# ---------------------------------------------------------
# 2. Xử lý đường dẫn và Nạp mô hình Transformers (Cross-Platform)
# ---------------------------------------------------------
kaggle_dataset = os.environ.get("KAGGLE_DATASET_PATH")

if kaggle_dataset:
    print(f"Đang tải model weights từ Kaggle Dataset: {kaggle_dataset}...")
    download_dir = kagglehub.dataset_download(kaggle_dataset)
    MODEL_PATH = Path(download_dir) / "fused_model"
else:
    MODEL_PATH = Path(
        os.environ.get(
            "HAIR_AI_MODEL_PATH",
            Path(__file__).resolve().parent / "fused_model",
        )
    ).expanduser()
    if not MODEL_PATH.is_absolute():
        MODEL_PATH = (Path(__file__).resolve().parent / MODEL_PATH).resolve()

if not MODEL_PATH.is_dir():
    raise RuntimeError(f"Không tìm thấy thư mục mô hình: {MODEL_PATH}")

print(f"Đang nạp mô hình từ {MODEL_PATH}...")
tokenizer = AutoTokenizer.from_pretrained(str(MODEL_PATH))
model = AutoModelForCausalLM.from_pretrained(
    str(MODEL_PATH),
    torch_dtype=torch.float32,
    device_map="auto"
)
print("Đã nạp mô hình thành công!")


# ---------------------------------------------------------
# 3. Schema Pydantic
# ---------------------------------------------------------
class Preferences(BaseModel):
    hairType: Optional[str] = None
    hairLength: Optional[str] = None
    goal: Optional[str] = None
    color: Optional[str] = None


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    preferences: Optional[Preferences] = None
    history: Optional[List[Dict[str, Any]]] = Field(default=None, max_length=9)
    max_tokens: int = Field(default=512, ge=32, le=1024)


class FaceConsultRequest(BaseModel):
    face_aspect_ratio: float = Field(gt=0.5, le=3.0)
    forehead_to_cheek_ratio: float = Field(gt=0.3, le=1.5)
    jaw_to_cheek_ratio: float = Field(gt=0.3, le=1.5)
    forehead_to_jaw_ratio: float = Field(gt=0.3, le=2.5)


# ---------------------------------------------------------
# 4. Helper Function: Sinh văn bản qua Transformers
# ---------------------------------------------------------
def generate_response(messages: List[Dict[str, str]], max_new_tokens: int = 512, temperature: float = 0.6) -> str:
    formatted_prompt = tokenizer.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=True
    )
    inputs = tokenizer(formatted_prompt, return_tensors="pt").to(model.device)

    with torch.no_grad():
        outputs = model.generate(
            **inputs,
            max_new_tokens=max_new_tokens,
            temperature=temperature,
            top_p=0.9,
            repetition_penalty=1.15,
            do_sample=True,
            pad_token_id=tokenizer.eos_token_id
        )

    input_length = inputs["input_ids"].shape[1]
    generated_tokens = outputs[0][input_length:]
    return tokenizer.decode(generated_tokens, skip_special_tokens=True)


# ---------------------------------------------------------
# 5. API Endpoints
# ---------------------------------------------------------
@app.get("/health")
async def health_endpoint():
    return {"success": True, "model_loaded": True, "model": MODEL_PATH.name}


def classify_face_shape(measurements: FaceConsultRequest) -> str:
    aspect = measurements.face_aspect_ratio
    forehead_jaw = measurements.forehead_to_jaw_ratio
    jaw_cheek = measurements.jaw_to_cheek_ratio

    if forehead_jaw >= 1.16 and jaw_cheek < 0.84:
        return "Heart"
    if jaw_cheek >= 0.88 and 0.90 <= forehead_jaw <= 1.10:
        return "Square"
    if aspect >= 1.70:
        return "Oblong"
    if aspect <= 1.27 and jaw_cheek >= 0.78 and forehead_jaw <= 1.12:
        return "Round"
    return "Oval"


@app.post("/api/consult-face")
async def consult_face_endpoint(request: FaceConsultRequest):
    try:
        shape = classify_face_shape(request)
        dynamic_store_info = get_store_data_from_firebase()
        prompt = (
            f"Customer's face shape detected: {shape}. As a professional Barber Consultant at "
            "Chi Phèo BarberShop, recommend 2-3 suitable hairstyles that complement this face "
            "shape, explain briefly why, and suggest styling tips. Reply in natural, grammatically "
            "correct Vietnamese. Use familiar Vietnamese hairstyle names and complete sentences. "
            "Do not invent services or products."
        )
        system_prompt = (
            "Bạn là chuyên viên tư vấn tóc của Chí Phèo BarberShop Saigon. "
            "Trả lời bằng tiếng Việt tự nhiên, đúng chính tả và ngữ pháp. Đưa ra 2-3 kiểu tóc "
            "phù hợp, giải thích ngắn gọn lý do và cách tạo kiểu. Dùng tên kiểu tóc quen thuộc; "
            "không dịch sát từng từ tiếng Anh hoặc tạo cụm từ vô nghĩa. Mỗi gợi ý phải là câu "
            "hoàn chỉnh. Không bịa tên dịch vụ, sản phẩm hay giá.\n\n"
            f"{dynamic_store_info}"
        )
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": prompt},
        ]

        response_text = generate_response(messages, max_new_tokens=512, temperature=0.45)
        reply = re.sub(r"\*\*|__|`", "", response_text.strip()).strip()
        reply = re.sub(r"tóc\s+dài\s+tay", "tóc dài", reply, flags=re.IGNORECASE)

        if not reply:
            raise HTTPException(status_code=502, detail="Mô hình chưa tạo được tư vấn.")
        return {"face_shape": shape, "reply": reply, "success": True}
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(status_code=500, detail="Không thể tạo tư vấn khuôn mặt.") from error


@app.post("/api/chat")
async def chat_endpoint(request: ChatRequest):
    try:
        prompt = request.message.strip()
        if not prompt:
            raise HTTPException(status_code=422, detail="Vui lòng nhập câu hỏi.")

        dynamic_store_info = get_store_data_from_firebase()

        system_prompt = (
            "Bạn là chuyên viên tư vấn AI thân thiện và chuyên nghiệp của Chí Phèo BarberShop Saigon.\n"
            f"{dynamic_store_info}\n\n"
            "Quy tắc phản hồi:\n"
            "1. Vai trò của bạn luôn là nhân viên tư vấn của cửa hàng, không phải khách hàng. Hãy trả lời trực tiếp câu hỏi mới nhất; tuyệt đối không viết thay câu hỏi của khách, không giả làm người dùng và không lặp lại yêu cầu dưới góc nhìn khách.\n"
            "2. Tiệm có bán sản phẩm chăm sóc tóc và cung cấp dịch vụ cắt, uốn, nhuộm. Chỉ nêu mặt hàng hoặc dịch vụ có trong thông tin cửa hàng ở trên; không tự bịa tên, giá hay cam kết.\n"
            "3. Trả lời bằng tiếng Việt tự nhiên, đúng chính tả và ngữ pháp; tự đọc lại câu trả lời trước khi gửi. Khi khách xin gợi ý kiểu tóc, nêu 2-3 kiểu quen thuộc, mỗi kiểu trong một câu hoàn chỉnh gồm tên kiểu, lý do phù hợp và một chỉ dẫn ngắn cho thợ. Không cắt cụt ý để giữ câu trả lời ngắn. Đọc lịch sử để giữ ngữ cảnh, nhưng chỉ phản hồi tin nhắn mới nhất.\n"
            "4. Dùng thuật ngữ làm tóc tự nhiên trong tiếng Việt; không dịch sát từng từ từ tiếng Anh và không tạo cụm vô nghĩa như 'tóc dài tay'. Nếu không chắc tên kiểu tóc, hãy mô tả đơn giản như 'tóc dài' hoặc hỏi thêm. Không dùng danh sách đánh số nếu khách không yêu cầu.\n"
            "5. Không lặp lại nguyên văn câu trả lời cũ nếu câu đó có thuật ngữ lạ hoặc sai; hãy tự sửa và trả lời đúng yêu cầu mới nhất. Nếu khách muốn nhắc lại, hãy tóm tắt câu trả lời đã sửa thành tối đa 3 câu hoàn chỉnh, không lặp danh sách dài.\n"
            "6. Không chẩn đoán bệnh hoặc kê thuốc; nếu khách bị dị ứng/viêm da đầu, khuyên gặp bác sĩ da liễu."
        )

        pref_info = ""
        if request.preferences:
            p = request.preferences
            pref_details = []
            if p.hairType and "chọn" not in p.hairType:
                pref_details.append(f"Chất tóc: {p.hairType}")
            if p.hairLength and "chọn" not in p.hairLength:
                pref_details.append(f"Độ dài: {p.hairLength}")
            if p.goal and "chọn" not in p.goal:
                pref_details.append(f"Mục tiêu: {p.goal}")
            if p.color and "chọn" not in p.color:
                pref_details.append(f"Màu tóc: {p.color}")
            if pref_details:
                pref_info = "\nThông tin tóc khách hàng: " + ", ".join(pref_details)

        messages = [{"role": "system", "content": system_prompt + pref_info}]

        history = request.history or []
        for item in history[-8:]:
            if not isinstance(item, dict):
                continue
            text = str(item.get("text", item.get("content", ""))).strip()
            if not text:
                continue
            source_role = item.get("role")
            if source_role not in ["user", "model", "assistant"]:
                continue
            role = "assistant" if source_role in ["model", "assistant"] else "user"
            messages.append({"role": role, "content": text})

        last = history[-1] if history else {}
        last_text = (
            str(last.get("text", last.get("content", ""))).strip()
            if isinstance(last, dict)
            else ""
        )
        if not (
            isinstance(last, dict)
            and last.get("role") == "user"
            and last_text == prompt
        ):
            messages.append({"role": "user", "content": prompt})

        response_text = generate_response(messages, max_new_tokens=request.max_tokens, temperature=0.6)

        answer = re.sub(r"tóc\s+dài\s+tay", "tóc dài", response_text.strip(), flags=re.IGNORECASE)
        answer = re.sub(
            r"cắt theo hình bàn chèn hoặc cái ca lô",
            "có hai bên gọn và phần đỉnh tạo độ phồng nhẹ",
            answer,
            flags=re.IGNORECASE,
        )
        answer = re.sub(r"\*\*|__|`", "", answer).strip()

        if answer and not re.search(r"(?<!\d)[.!?…。！？]$", answer):
            endings = list(re.finditer(r"(?<!\d)[.!?…](?=\s|$)", answer))
            if endings:
                answer = answer[:endings[-1].end()].rstrip()

        if not answer:
            raise HTTPException(
                status_code=502, detail="Mô hình chưa tạo được câu trả lời."
            )

        return {
            "answer": answer,
            "reply": answer,
            "response": answer,
            "success": True,
        }
    except HTTPException:
        raise
    except Exception as e:
        print(f"Chat generation failed: {type(e).__name__}")
        raise HTTPException(status_code=500, detail="Không thể tạo câu trả lời.") from e


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host=os.environ.get("HAIR_AI_HOST", "0.0.0.0"),
        port=int(os.environ.get("HAIR_AI_PORT", "8000")),
    )