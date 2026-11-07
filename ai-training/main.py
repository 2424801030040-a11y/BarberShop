"""Lightweight Render API gateway for the Chi Pheo BarberShop AI service.

The Render Free instance must not load the local Transformers weights. If
KAGGLE_SERVER_URL is configured, generation requests are forwarded to that
server; otherwise the API returns safe Vietnamese fallback guidance.
"""

import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

import firebase_admin
import requests
from fastapi import FastAPI
from firebase_admin import credentials, firestore
from pydantic import BaseModel, Field


app = FastAPI(title="Chi Pheo BarberShop AI Gateway", version="1.0.0")


def initialize_firebase():
    """Initialize Firebase when credentials are available; otherwise use fallback mode."""
    try:
        if firebase_admin._apps:
            return firestore.client()

        firebase_json = os.environ.get("FIREBASE_JSON_CONTENT", "").strip()
        credentials_path = os.environ.get(
            "FIREBASE_CREDENTIALS",
            str(Path(__file__).resolve().parent / "serviceAccountKey.json"),
        )

        if firebase_json:
            try:
                credential_data = json.loads(firebase_json)
            except json.JSONDecodeError as error:
                print(f"Firebase credentials JSON is invalid ({error.__class__.__name__}); using fallback mode.")
                return None
            credential = credentials.Certificate(credential_data)
        elif os.path.isfile(credentials_path):
            credential = credentials.Certificate(credentials_path)
        else:
            print("Firebase credentials were not found; using fallback mode.")
            return None

        firebase_admin.initialize_app(credential)
        print("Firebase Admin initialized.")
        return firestore.client()
    except Exception as error:
        # Firebase is optional for the lightweight service and must not prevent startup.
        print(f"Firebase initialization failed ({error.__class__.__name__}); using fallback mode.")
        return None


db = initialize_firebase()


class Preferences(BaseModel):
    hairType: Optional[str] = Field(default=None, max_length=100)
    hairLength: Optional[str] = Field(default=None, max_length=100)
    goal: Optional[str] = Field(default=None, max_length=200)
    color: Optional[str] = Field(default=None, max_length=100)


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    preferences: Optional[Preferences] = None
    history: Optional[List[Dict[str, Any]]] = Field(default=None, max_length=9)
    max_tokens: int = Field(default=512, ge=32, le=1024)


class FaceConsultRequest(BaseModel):
    # Accept small values from older Android builds; classification ignores
    # width ratios outside realistic ranges instead of returning HTTP 422.
    face_aspect_ratio: float = Field(gt=0, le=10)
    forehead_to_cheek_ratio: float = Field(gt=0, le=10)
    jaw_to_cheek_ratio: float = Field(gt=0, le=10)
    forehead_to_jaw_ratio: float = Field(gt=0, le=10)


def _format_price(value: Any, unit: str = "đ") -> str:
    try:
        return f"{int(float(value)):,}".replace(",", ".") + f" {unit}"
    except (TypeError, ValueError, OverflowError):
        return f"{value} {unit}"


def get_store_data_from_firebase() -> str:
    """Read a small, bounded catalog context. Return a safe fallback on Firestore errors."""
    if db is None:
        return "Chưa kết nối được danh mục cửa hàng; không tự suy đoán tên hoặc giá."

    sections = []
    for collection, label in (("services", "Dịch vụ"), ("products", "Sản phẩm")):
        try:
            rows = []
            for doc in db.collection(collection).limit(40).stream():
                data = doc.to_dict() or {}
                name = data.get("name") or data.get("title")
                if not isinstance(name, str) or not name.strip():
                    continue
                price = data.get("price", data.get("cost"))
                description = data.get("description")
                detail = f" — {_format_price(price, str(data.get('unit') or 'đ'))}" if price is not None else ""
                if isinstance(description, str) and description.strip():
                    detail += "; " + description.strip().replace("\n", " ")[:160]
                rows.append(f"• {name.strip()[:100]}{detail}")
            if rows:
                sections.append(label + ":\n" + "\n".join(rows))
        except Exception as error:
            print(f"Firestore catalog read failed for {collection} ({error.__class__.__name__}).")

    return "\n\n".join(sections) if sections else "Chưa có dữ liệu danh mục; không tự suy đoán tên hoặc giá."


def classify_face_shape(measurements: FaceConsultRequest) -> str:
    aspect = measurements.face_aspect_ratio
    forehead_jaw = measurements.forehead_to_jaw_ratio
    jaw_cheek = measurements.jaw_to_cheek_ratio

    # Ignore corrupted/legacy normalized coordinates instead of rejecting the scan.
    ratios_reliable = 0.3 <= forehead_jaw <= 2.5 and 0.3 <= jaw_cheek <= 1.5
    if ratios_reliable:
        if forehead_jaw >= 1.16 and jaw_cheek < 0.84:
            return "Heart"
        if jaw_cheek >= 0.88 and 0.90 <= forehead_jaw <= 1.10:
            return "Square"

    if aspect >= 1.70:
        return "Oblong"
    if aspect <= 1.27:
        return "Round"
    return "Oval"


def _face_fallback(shape: str) -> str:
    advice = {
        "Oval": "Bạn có thể thử kiểu side part để gương mặt trông cân đối, textured crop để tạo vẻ trẻ trung, hoặc crew cut nếu muốn chăm sóc đơn giản. Hãy nhờ thợ điều chỉnh độ dài hai bên theo chất tóc và phong cách của bạn.",
        "Round": "Bạn có thể thử side part hoặc textured crop với phần đỉnh tạo độ phồng vừa phải để gương mặt trông dài và gọn hơn. Nếu thích tóc ngắn, crew cut với hai bên gọn cũng là lựa chọn dễ chăm sóc.",
        "Square": "Bạn có thể thử side part để làm mềm đường nét, textured crop để tạo vẻ tự nhiên, hoặc crew cut nếu thích kiểu gọn gàng. Hãy trao đổi với thợ về độ chuyển ở hai bên để giữ tổng thể hài hòa.",
        "Heart": "Bạn có thể thử kiểu rẽ ngôi lệch hoặc tóc mái chải ngang để tạo sự cân bằng cho phần trán. Tóc layer có độ dài vừa phải cũng phù hợp; nên giữ phần đỉnh tự nhiên, không tạo quá nhiều độ phồng.",
        "Oblong": "Bạn có thể thử textured crop hoặc kiểu tóc có mái nhẹ để tạo cảm giác gương mặt cân đối hơn. Side part với phần đỉnh vừa phải cũng là lựa chọn phù hợp; nên tránh vuốt dựng quá cao nếu muốn hạn chế cảm giác mặt dài.",
    }
    localized_shape = {
        "Oval": "oval",
        "Round": "tròn",
        "Square": "vuông",
        "Heart": "trái tim",
        "Oblong": "dài",
    }.get(shape, "oval")
    return (
        f"Dựa trên số đo hiện có, khuôn mặt của bạn có vẻ thuộc dáng {localized_shape}. "
        + advice.get(shape, advice["Oval"])
        + " Đây là gợi ý tham khảo từ tỷ lệ hình học; thợ cắt tóc có thể điều chỉnh theo đường chân tóc, chất tóc và sở thích của bạn."
    )


def _chat_fallback(request: ChatRequest, store_context: str) -> str:
    message = request.message.strip().lower()
    if any(keyword in message for keyword in ("giá", "bao nhiêu", "dịch vụ", "sản phẩm")):
        if db is not None and ("Dịch vụ:" in store_context or "Sản phẩm:" in store_context):
            return (
                "Mình gửi bạn thông tin danh mục hiện có của tiệm:\n"
                + store_context
                + "\n\nBạn hãy xem lại giá và tình trạng còn hàng trong ứng dụng trước khi đặt nhé."
            )
        return "Hiện mình chưa lấy được bảng giá mới nhất. Bạn vui lòng xem mục Cửa hàng trong ứng dụng hoặc liên hệ trực tiếp Chi Phèo BarberShop để được báo giá chính xác."
    return (
        "Máy chủ tư vấn AI đang tạm thời không kết nối được với mô hình. Bạn có thể cho mình biết chất tóc, "
        "độ dài hiện tại và kiểu tóc mong muốn; trong lúc chờ, hãy xem mục Cửa hàng trong ứng dụng để kiểm tra "
        "dịch vụ và sản phẩm đang có."
    )


def _proxy_request(path: str, payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    base_url = os.environ.get("KAGGLE_SERVER_URL", "").strip().rstrip("/")
    if not base_url:
        return None

    timeout = 25
    try:
        timeout = max(3, min(30, int(os.environ.get("KAGGLE_PROXY_TIMEOUT_SECONDS", "25"))))
    except ValueError:
        pass

    headers = {"content-type": "application/json"}
    token = os.environ.get("KAGGLE_SERVER_TOKEN", "").strip()
    if token:
        headers["authorization"] = f"Bearer {token}"

    try:
        response = requests.post(
            base_url + path,
            json=payload,
            headers=headers,
            timeout=(4, timeout),
        )
        response.raise_for_status()
        result = response.json()
        return result if isinstance(result, dict) else None
    except (requests.RequestException, ValueError) as error:
        # Do not log request bodies, credentials, or customer messages.
        print(f"Kaggle inference proxy failed ({error.__class__.__name__}).")
        return None


@app.get("/")
def root():
    return {"success": True, "service": "Chi Pheo BarberShop AI Gateway", "health": "/health"}


@app.get("/health")
def health_endpoint():
    return {
        "success": True,
        "model_loaded": False,
        "mode": "proxy" if os.environ.get("KAGGLE_SERVER_URL", "").strip() else "fallback",
        "kaggle_proxy_configured": bool(os.environ.get("KAGGLE_SERVER_URL", "").strip()),
        "firebase_available": db is not None,
    }


@app.post("/api/consult-face")
def consult_face_endpoint(request: FaceConsultRequest):
    shape = classify_face_shape(request)
    store_context = get_store_data_from_firebase()
    payload = request.model_dump()
    payload["message"] = (
        f"Khách hàng có khuôn mặt dáng {shape}. Hãy tư vấn 2-3 kiểu tóc phù hợp bằng tiếng Việt tự nhiên, "
        "giải thích ngắn gọn lý do và mẹo tạo kiểu. Không bịa dịch vụ, sản phẩm hoặc giá.\n\n"
        f"Thông tin cửa hàng:\n{store_context[:2500]}"
    )

    result = _proxy_request("/api/consult-face", payload)
    if result:
        reply = result.get("reply") or result.get("answer") or result.get("response")
        if isinstance(reply, str) and reply.strip():
            return {"success": True, "face_shape": result.get("face_shape") or shape, "reply": reply.strip()}

    return {"success": True, "face_shape": shape, "reply": _face_fallback(shape), "fallback": True}


@app.post("/api/chat")
def chat_endpoint(request: ChatRequest):
    message = request.message.strip()
    if not message:
        return {"success": True, "answer": "Bạn hãy nhập câu hỏi về kiểu tóc, chăm sóc tóc hoặc dịch vụ của tiệm nhé.", "reply": "Bạn hãy nhập câu hỏi về kiểu tóc, chăm sóc tóc hoặc dịch vụ của tiệm nhé."}

    store_context = get_store_data_from_firebase()
    payload = request.model_dump(exclude_none=True)
    payload["message"] = (
        f"{message}\n\n[Thông tin tham khảo từ Firestore; chỉ dùng đúng dữ liệu này, không tự đoán giá.]\n"
        f"{store_context[:2200]}"
    )[:3900]
    result = _proxy_request("/api/chat", payload)
    if result:
        answer = result.get("answer") or result.get("reply") or result.get("response")
        if isinstance(answer, str) and answer.strip():
            return {"success": True, "answer": answer.strip(), "reply": answer.strip(), "response": answer.strip()}

    answer = _chat_fallback(request, store_context)
    return {"success": True, "answer": answer, "reply": answer, "response": answer, "fallback": True}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=int(os.environ.get("PORT", "8000")),
    )
