import { decodeFirestore, firestoreRequest, HttpError, verifyUser } from "../_shared/push-notifications.ts";

type JsonRecord = Record<string, any>;
const MODEL = "gemini-3.5-flash-lite";
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
    const message = clean(body?.message, 1200);
    if (!message) throw new HttpError("Hãy nhập câu hỏi hoặc điều bạn muốn thay đổi.", 400);
    const preferences = body?.preferences && typeof body.preferences === "object" ? body.preferences : {};
    const [services, products] = await Promise.all([shopCatalog("services"), shopCatalog("products")]);
    const catalog = {
      services: services.slice(0, 40).map(item => ({ name: clean(item.name, 100), description: clean(item.description, 220), priceVnd: Number(item.price) || 0, durationMinutes: Number(item.durationMinutes) || null })),
      products: products.slice(0, 60).map(item => ({ name: clean(item.name, 100), description: clean(item.description, 220), category: clean(item.category, 80), priceVnd: Number(item.price) || 0, inStock: Number(item.stock) > 0 })),
    };
    const context = [
      `Tùy chọn khách: ${JSON.stringify({
        hairType: clean(preferences.hairType, 80),
        hairLength: clean(preferences.hairLength, 80),
        goal: clean(preferences.goal, 100),
        color: clean(preferences.color, 100),
      })}`,
      `Danh mục hiện tại của cửa hàng (giá VND, tồn kho theo lúc truy vấn): ${JSON.stringify(catalog)}`,
      `Chính sách cửa hàng: có thể hủy/đổi lịch trước giờ hẹn tối thiểu 2 giờ; hàng chưa mở có thể trả trong 7 ngày kèm hóa đơn; hàng lỗi hoặc giao sai có thể đổi trong 7 ngày. Chỉ nêu chính sách này khi liên quan.`,
      `Câu hỏi mới: ${message}`,
    ].join("\n");
    const history = Array.isArray(body?.history) ? body.history.slice(-6).flatMap((turn: JsonRecord) => {
      const role = turn?.role === "model" ? "model" : turn?.role === "user" ? "user" : "";
      const text = clean(turn?.text, 1200);
      return role && text ? [{ role, parts: [{ text }] }] : [];
    }) : [];
    if (!history.length || history.at(-1)?.role !== "user" || history.at(-1)?.parts?.[0]?.text !== message) {
      history.push({ role: "user", parts: [{ text: message }] });
    }
    const lastUserTurn = history.at(-1);
    if (lastUserTurn?.role === "user") {
      lastUserTurn.parts[0].text = context;
    }

    const apiKey = Deno.env.get("GEMINI_API_KEY");
    if (!apiKey) throw new Error("GEMINI_API_KEY is not configured.");
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": apiKey },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: `Bạn là chuyên viên tư vấn tóc bằng tiếng Việt của Chí Phèo Barber Shop tại Việt Nam. Đây là tư vấn hội thoại bằng văn bản; không giả vờ đã nhìn thấy khách. Trả lời tự nhiên, thân thiện, đúng chính tả và ngữ pháp; tự đọc lại để sửa câu trước khi gửi. Khi khách xin gợi ý kiểu tóc, hãy nêu 2-3 kiểu quen thuộc; mỗi kiểu là một câu hoàn chỉnh gồm tên kiểu, lý do phù hợp và chỉ dẫn ngắn để nói với thợ. Đảm bảo đủ ý, không kết thúc giữa câu hoặc giữa một gợi ý. Đề xuất phù hợp với loại tóc, độ dài, thời gian chăm sóc và ngân sách khách nêu; không dùng danh sách đánh số trừ khi khách yêu cầu. Dùng thuật ngữ làm tóc tự nhiên trong tiếng Việt; không dịch sát từng từ từ tiếng Anh hoặc tạo cụm vô nghĩa như "tóc dài tay". Nếu không chắc tên kiểu tóc, hãy mô tả đơn giản như "tóc dài" hoặc hỏi thêm. Không lặp nguyên văn câu trả lời cũ nếu câu đó có thuật ngữ lạ hoặc sai; nếu khách muốn nhắc lại, hãy tóm tắt câu trả lời đã sửa bằng tối đa 3 câu hoàn chỉnh. Nêu rõ ưu/nhược điểm, cách yêu cầu thợ cắt và chăm sóc tại nhà khi cần. Không chẩn đoán bệnh hoặc kê thuốc; nếu khách mô tả rụng tóc nhiều, đau, ngứa, viêm hay dị ứng, khuyên gặp bác sĩ da liễu. Dữ liệu dịch vụ/sản phẩm dưới đây là thông tin duy nhất được phép dùng cho tên, giá, thời lượng và tồn kho: không bịa hoặc bảo đảm kết quả. Chỉ nhắc dịch vụ/sản phẩm của tiệm khi thực sự liên quan; nếu không có mặt hàng phù hợp, nói rõ. Không tự tuyên bố đã được fine-tune/train trên dữ liệu riêng.` }] },
        contents: history,
        generationConfig: { temperature: 0.45, maxOutputTokens: 700 },
      }),
    });
    if (!response.ok) {
      const detail = (await response.text()).slice(0, 1500);
      console.error("Gemini hair-advisor request failed", response.status, detail);
      if (response.status === 429) return json(request, { error: "AI đang chạm giới hạn miễn phí hoặc tốc độ gửi. Hãy thử lại sau ít phút." }, 429);
      if (response.status === 401 || response.status === 403) return json(request, { error: "Khóa Gemini chưa hợp lệ hoặc chưa được cấp quyền cho model miễn phí. Quản trị viên cần kiểm tra GEMINI_API_KEY và quyền Gemini API." }, 502);
      if (response.status === 404) return json(request, { error: "Model Gemini hiện không khả dụng cho dự án API này. Quản trị viên cần kiểm tra model trong cấu hình trợ lý." }, 502);
      if (response.status === 400) return json(request, { error: "Gemini từ chối định dạng yêu cầu. Lỗi đã được ghi lại để quản trị viên kiểm tra." }, 502);
      return json(request, { error: "Dịch vụ Gemini tạm thời gặp sự cố. Hãy thử lại sau." }, 502);
    }
    const result = await response.json();
    const answer = keepCompleteSentences((result?.candidates?.[0]?.content?.parts || [])
      .map((part: JsonRecord) => typeof part.text === "string" ? part.text : "").join("\n")
      .replace(/tóc\s+dài\s+tay/gi, "tóc dài")
      .replace(/cắt theo hình bàn chèn hoặc cái ca lô/gi, "có hai bên gọn và phần đỉnh tạo độ phồng nhẹ"));
    if (!answer) {
      const reason = result?.promptFeedback?.blockReason;
      return json(request, { error: reason ? "Câu hỏi bị bộ lọc an toàn từ chối. Hãy thử diễn đạt lại về tóc, màu tóc hoặc chăm sóc tóc." : "AI chưa tạo được câu trả lời. Hãy thử hỏi theo cách khác." }, 502);
    }
    return json(request, { answer });
  } catch (error) {
    if (error instanceof HttpError) return json(request, { error: error.message }, error.status);
    console.error("hair-advisor failed", error instanceof Error ? error.message : "Unknown error");
    return json(request, { error: "Chưa lấy được dữ liệu tiệm hoặc cấu hình trợ lý chưa hoàn tất. Hãy thử lại; nếu vẫn lỗi, báo cửa hàng kiểm tra Edge Function." }, 500);
  }
});
