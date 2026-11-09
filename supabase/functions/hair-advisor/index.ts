import { decodeFirestore, firestoreRequest, HttpError, verifyUser } from "../_shared/push-notifications.ts";

type JsonRecord = Record<string, any>;
const ALLOWED_ORIGINS = new Set(["https://barbershop-e85e1.web.app", "https://barbershop-e85e1.firebaseapp.com", "http://localhost:5000", "http://127.0.0.1:5000", "http://localhost:8080", "http://127.0.0.1:8080"]);

function headers(request: Request): Headers {
  const result = new Headers({ "content-type": "application/json; charset=utf-8", "access-control-allow-methods": "POST, OPTIONS", "access-control-allow-headers": "authorization, apikey, content-type, x-client-info", vary: "Origin" });
  const origin = request.headers.get("origin");
  if (origin && ALLOWED_ORIGINS.has(origin)) result.set("access-control-allow-origin", origin);
  return result;
}
function json(request: Request, body: JsonRecord, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: headers(request) });
}
function clean(value: unknown, max: number): string {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}
function keepCompleteSentences(value: string): string {
  const text = value.trim();
  if (/(?<!\d)[.!?…]$/u.test(text)) return text;
  const endings = [...text.matchAll(/(?<!\d)[.!?…](?=\s|$)/gu)];
  const last = endings.at(-1);
  return last?.index === undefined ? text : text.slice(0, last.index + 1).trimEnd();
}

const HAIR_SYSTEM_PROMPT = "Bạn là thợ tư vấn tóc nam của Chí Phèo Barber Shop tại Sài Gòn. Luôn trả lời bằng tiếng Việt tự nhiên, ngắn gọn và bám sát câu hỏi. Chỉ gợi ý kiểu tóc nam thực tế ở barber shop như textured crop, crew cut, side part, quiff hoặc fade; không gợi ý kiểu tóc nữ. Nêu tối đa 3 lựa chọn, giải thích lý do và gợi ý khách có thể nói gì với thợ. Chỉ dùng danh mục được cung cấp để nói tên dịch vụ, giá, thời lượng và tồn kho; thiếu dữ liệu thì nói rõ. Không giả vờ đã nhìn thấy khách. Không chẩn đoán bệnh hoặc kê thuốc; khi có dấu hiệu bệnh da đầu, khuyên gặp bác sĩ da liễu.";

async function generateWithGemini(contents: JsonRecord[], maxOutputTokens: number): Promise<string> {
  const apiKey = Deno.env.get("GEMINI_API_KEY")?.trim();
  if (!apiKey) throw new Error("GEMINI_API_KEY is not configured.");
  const model = Deno.env.get("GEMINI_MODEL")?.trim() || "gemini-3.5-flash-lite";
  const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-goog-api-key": apiKey },
    body: JSON.stringify({
      system_instruction: { parts: [{ text: HAIR_SYSTEM_PROMPT }] },
      contents,
      generationConfig: { maxOutputTokens },
    }),
    signal: AbortSignal.timeout(75_000),
  });
  if (!response.ok) {
    const errorBody = await response.json().catch(() => null);
    const providerMessage = clean(errorBody?.error?.message, 300);
    console.error("Gemini request failed", response.status, providerMessage || "No provider message.");
    if (response.status === 429) throw new HttpError("AI đang bận. Vui lòng thử lại sau ít phút.", 429);
    throw new HttpError("Chưa kết nối được AI. Vui lòng thử lại sau.", 502);
  }
  const result = await response.json();
  const text = Array.isArray(result?.candidates?.[0]?.content?.parts)
    ? result.candidates[0].content.parts.map((part: JsonRecord) => typeof part.text === "string" ? part.text : "").join("").trim()
    : "";
  if (!text) throw new HttpError("AI chưa tạo được câu trả lời. Vui lòng thử hỏi theo cách khác.", 502);
  return text;
}

function classifyFace(body: JsonRecord): string {
  const aspect = Number(body.face_aspect_ratio);
  const foreheadJaw = Number(body.forehead_to_jaw_ratio);
  const jawCheek = Number(body.jaw_to_cheek_ratio);
  if (foreheadJaw >= 1.16 && foreheadJaw <= 2.5 && jawCheek >= 0.3 && jawCheek < 0.84) return "Heart";
  if (jawCheek >= 0.88 && jawCheek <= 1.5 && foreheadJaw >= 0.9 && foreheadJaw <= 1.1) return "Square";
  if (aspect >= 1.7) return "Oblong";
  if (aspect <= 1.27) return "Round";
  return "Oval";
}

function validFaceMeasurements(body: JsonRecord): boolean {
  return Number.isFinite(Number(body.face_aspect_ratio)) && Number(body.face_aspect_ratio) > 0 && Number(body.face_aspect_ratio) <= 10
    && Number.isFinite(Number(body.forehead_to_cheek_ratio)) && Number(body.forehead_to_cheek_ratio) > 0 && Number(body.forehead_to_cheek_ratio) <= 10
    && Number.isFinite(Number(body.jaw_to_cheek_ratio)) && Number(body.jaw_to_cheek_ratio) > 0 && Number(body.jaw_to_cheek_ratio) <= 10
    && Number.isFinite(Number(body.forehead_to_jaw_ratio)) && Number(body.forehead_to_jaw_ratio) > 0 && Number(body.forehead_to_jaw_ratio) <= 10;
}

async function shopCatalog(collection: "services" | "products"): Promise<JsonRecord[]> {
  const response = await firestoreRequest(`/${collection}?pageSize=100`);
  if (!response.ok) throw new Error(`Shop catalog read failed (${collection}, HTTP ${response.status}).`);
  const result = await response.json();
  return (Array.isArray(result.documents) ? result.documents : []).map((document: JsonRecord) => {
    const fields = Object.fromEntries(Object.entries(document.fields || {}).map(([key, value]) => [key, decodeFirestore(value as JsonRecord)]));
    return { id: String(document.name || "").split("/").pop(), ...fields };
  });
}

Deno.serve(async request => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: headers(request) });
  if (request.method !== "POST") return json(request, { error: "Method not allowed." }, 405);
  try {
    await verifyUser(request);
    const body = await request.json();
    if (body?.mode === "face") {
      if (!validFaceMeasurements(body)) throw new HttpError("Số đo khuôn mặt không hợp lệ. Vui lòng quét lại.", 400);
      const shape = classifyFace(body);
      const prompt = `Tỷ lệ hình học trên thiết bị ước tính khuôn mặt khách có dáng ${shape}. Hãy tư vấn 2-3 kiểu tóc nam phù hợp, nêu ngắn lý do và cách dặn thợ. Nói rõ đây là gợi ý tham khảo từ tỷ lệ hình học; hệ thống không gửi hoặc nhìn thấy ảnh. Không suy đoán thêm đặc điểm khuôn mặt.`;
      const reply = keepCompleteSentences(await generateWithGemini([{ role: "user", parts: [{ text: prompt }] }], 320));
      return json(request, { answer: reply, reply, face_shape: shape });
    }
    const message = clean(body?.message, 1200);
    if (!message) throw new HttpError("Hãy nhập câu hỏi hoặc điều bạn muốn thay đổi.", 400);
    const preferences = body?.preferences && typeof body.preferences === "object" ? body.preferences : {};
    const [services, products] = await Promise.all([shopCatalog("services"), shopCatalog("products")]);
    const catalog = {
      services: services.slice(0, 8).map(item => ({ name: clean(item.name, 80), description: clean(item.description, 100), priceVnd: Number(item.price) || 0, durationMinutes: Number(item.durationMinutes) || null })),
      products: products.slice(0, 8).map(item => ({ name: clean(item.name, 80), description: clean(item.description, 100), category: clean(item.category, 50), priceVnd: Number(item.price) || 0, inStock: Number(item.stock) > 0 })),
    };
    const context = [
      `Câu hỏi mới: ${message}`,
      `Tùy chọn khách: ${JSON.stringify({
        hairType: clean(preferences.hairType, 80),
        hairLength: clean(preferences.hairLength, 80),
        goal: clean(preferences.goal, 100),
        color: clean(preferences.color, 100),
      })}`,
      `Danh mục hiện tại của cửa hàng (giá VND, tồn kho theo lúc truy vấn): ${JSON.stringify(catalog)}`,
      `Chính sách cửa hàng: có thể hủy/đổi lịch trước giờ hẹn tối thiểu 2 giờ; hàng chưa mở có thể trả trong 7 ngày kèm hóa đơn; hàng lỗi hoặc giao sai có thể đổi trong 7 ngày. Chỉ nêu chính sách này khi liên quan.`,
    ].join("\n").slice(0, 3500);
    const history = Array.isArray(body?.history) ? body.history.slice(-8).flatMap((turn: JsonRecord) => {
      const role = turn?.role === "model" ? "model" : turn?.role === "user" ? "user" : "";
      const text = clean(turn?.text, 1200);
      return role && text && !(role === "user" && text === message) ? [{ role: role === "model" ? "model" : "user", parts: [{ text }] }] : [];
    }) : [];
    const contents = [...history, { role: "user", parts: [{ text: context }] }];
    const answer = keepCompleteSentences((await generateWithGemini(contents, 420))
      .replace(/tóc\s+dài\s+tay/gi, "tóc dài")
      .replace(/cắt theo hình bàn chèn hoặc cái ca lô/gi, "có hai bên gọn và phần đỉnh tạo độ phồng nhẹ"));
    return json(request, { answer });
  } catch (error) {
    if (error instanceof HttpError) return json(request, { error: error.message }, error.status);
    console.error("hair-advisor failed", error instanceof Error ? error.message : "Unknown error");
    return json(request, { error: "Chưa lấy được dữ liệu tiệm hoặc cấu hình trợ lý chưa hoàn tất. Hãy thử lại; nếu vẫn lỗi, báo cửa hàng kiểm tra Edge Function." }, 500);
  }
});
