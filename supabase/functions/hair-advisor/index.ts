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
    const gatewayUrl = Deno.env.get("HAIR_AI_GATEWAY_URL")?.trim().replace(/\/$/, "");
    const gatewayToken = Deno.env.get("HAIR_AI_GATEWAY_TOKEN")?.trim();
    if (!gatewayUrl || !gatewayToken) throw new Error("Hair-advisor gateway is not configured.");
    // Wake Render's free instance while the shop catalog is being fetched. This
    // keeps its cold-start delay out of the model request's time window.
    const gatewayWake = fetch(`${gatewayUrl}/health`, {
      signal: AbortSignal.timeout(50_000),
    }).then(response => {
      if (!response.ok) throw new Error(`Gateway warm-up returned HTTP ${response.status}.`);
    }).catch(error => {
      console.warn("Hair-advisor gateway warm-up did not finish before the deadline.",
        error instanceof Error ? error.name : "Unknown error");
    });
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

    const gatewayRequest = {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${gatewayToken}` },
      body: JSON.stringify({ message: context, preferences, history, max_tokens: 420 }),
    };
    await gatewayWake;
    // Keep the combined warm-up and model request below Supabase Free's
    // 150-second execution limit, including time spent building shop context.
    const response = await fetch(`${gatewayUrl}/api/chat`, {
      ...gatewayRequest,
      signal: AbortSignal.timeout(90_000),
    });
    if (!response.ok) {
      const detail = (await response.text()).slice(0, 1500);
      console.error("Hair-advisor gateway request failed", response.status, detail);
      if (response.status === 401) return json(request, { error: "Gateway tư vấn chưa được xác thực đúng cấu hình." }, 502);
      if (response.status === 429) return json(request, { error: "Máy chủ model đang bận. Hãy thử lại sau ít phút." }, 429);
      return json(request, { error: "Model Kaggle chưa sẵn sàng hoặc tunnel đã hết hạn. Hãy thử lại sau khi khởi động notebook." }, 502);
    }
    const result = await response.json();
    const answer = keepCompleteSentences((typeof result?.answer === "string" ? result.answer : typeof result?.reply === "string" ? result.reply : "")
      .replace(/tóc\s+dài\s+tay/gi, "tóc dài")
      .replace(/cắt theo hình bàn chèn hoặc cái ca lô/gi, "có hai bên gọn và phần đỉnh tạo độ phồng nhẹ"));
    if (!answer) {
      return json(request, { error: "Model chưa tạo được câu trả lời. Hãy thử hỏi theo cách khác." }, 502);
    }
    return json(request, { answer });
  } catch (error) {
    if (error instanceof HttpError) return json(request, { error: error.message }, error.status);
    console.error("hair-advisor failed", error instanceof Error ? error.message : "Unknown error");
    return json(request, { error: "Chưa lấy được dữ liệu tiệm hoặc cấu hình trợ lý chưa hoàn tất. Hãy thử lại; nếu vẫn lỗi, báo cửa hàng kiểm tra Edge Function." }, 500);
  }
});
