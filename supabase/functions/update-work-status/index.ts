// Staff status changes for Firebase Spark projects. Firebase identity is verified
// through Identity Toolkit; Firestore writes use a server-only service account.
import { createInAppNotification, sendPush } from "../_shared/push-notifications.ts";
const encoder = new TextEncoder();
const OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";
const FIRESTORE_SCOPE = "https://www.googleapis.com/auth/datastore";
const IDENTITY_LOOKUP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:lookup";
const ALLOWED_ORIGINS = new Set(["https://barbershop-e85e1.web.app", "https://barbershop-e85e1.firebaseapp.com", "http://localhost:5000", "http://127.0.0.1:5000", "http://localhost:8080", "http://127.0.0.1:8080"]);
let cachedAccessToken: { value: string; expiresAt: number } | null = null;
type JsonRecord = Record<string, any>;
class HttpError extends Error { constructor(message: string, readonly status: number) { super(message); } }
function corsHeaders(request: Request): Headers {
  const headers = new Headers({ "content-type": "application/json; charset=utf-8", "access-control-allow-methods": "POST, OPTIONS", "access-control-allow-headers": "authorization, apikey, content-type, x-client-info", "vary": "Origin" });
  const origin = request.headers.get("origin"); if (origin && ALLOWED_ORIGINS.has(origin)) headers.set("access-control-allow-origin", origin); return headers;
}
function json(request: Request, body: JsonRecord, status = 200): Response { return new Response(JSON.stringify(body), { status, headers: corsHeaders(request) }); }
function fail(request: Request, message: string, status = 400): Response { return json(request, { error: message }, status); }
function base64Url(bytes: Uint8Array): string { let binary = ""; for (const byte of bytes) binary += String.fromCharCode(byte); return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, ""); }
function pemBytes(pem: string): Uint8Array { return Uint8Array.from(atob(pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "")), c => c.charCodeAt(0)); }
async function verifyStaff(request: Request): Promise<string> {
  const idToken = request.headers.get("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1], apiKey = Deno.env.get("FIREBASE_WEB_API_KEY");
  if (!idToken) throw new HttpError("Vui lòng đăng nhập lại.", 401); if (!apiKey) throw new Error("FIREBASE_WEB_API_KEY is not configured.");
  const response = await fetch(`${IDENTITY_LOOKUP_URL}?key=${encodeURIComponent(apiKey)}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ idToken }) });
  if (!response.ok) throw new HttpError("Phiên đăng nhập không hợp lệ hoặc đã hết hạn.", 401);
  const result = await response.json(), user = Array.isArray(result.users) ? result.users[0] : null;
  if (typeof user?.localId !== "string") throw new HttpError("Không xác minh được tài khoản.", 401);
  let claims: JsonRecord = {}; try { claims = typeof user.customAttributes === "string" ? JSON.parse(user.customAttributes) : {}; } catch { /* invalid claims stay empty */ }
  if (claims.admin !== true && claims.staff !== true) throw new HttpError("Tài khoản chưa được cấp quyền nhân viên.", 403); return user.localId;
}
async function firestoreToken(): Promise<string> {
  if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 60_000) return cachedAccessToken.value;
  const email = Deno.env.get("FIREBASE_CLIENT_EMAIL"), privateKeyPem = (Deno.env.get("FIREBASE_PRIVATE_KEY") || "").replace(/\\n/g, "\n");
  if (!email || !privateKeyPem) throw new Error("Firebase service credentials are not configured.");
  const now = Math.floor(Date.now() / 1000), header = base64Url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" }))), claim = base64Url(encoder.encode(JSON.stringify({ iss: email, scope: FIRESTORE_SCOPE, aud: OAUTH_TOKEN_URL, iat: now, exp: now + 3600 }))), unsigned = `${header}.${claim}`;
  const key = await crypto.subtle.importKey("pkcs8", pemBytes(privateKeyPem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]), signed = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
  const response = await fetch(OAUTH_TOKEN_URL, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${base64Url(new Uint8Array(signed))}` }) }), result = await response.json();
  if (!response.ok || typeof result.access_token !== "string") throw new Error(`Could not obtain Firestore access token (${response.status}).`);
  cachedAccessToken = { value: result.access_token, expiresAt: Date.now() + Number(result.expires_in || 3600) * 1000 }; return cachedAccessToken.value;
}
function projectId(): string { const id = Deno.env.get("FIREBASE_PROJECT_ID"); if (!id) throw new Error("FIREBASE_PROJECT_ID is not configured."); return id; }
function docsUrl(): string { return `https://firestore.googleapis.com/v1/projects/${projectId()}/databases/(default)/documents`; }
function resourcePrefix(): string { return `projects/${projectId()}/databases/(default)/documents`; }
function resource(path: string): string { return `${resourcePrefix()}/${path}`; }
async function fs(path: string, init: RequestInit = {}): Promise<Response> { const headers = new Headers(init.headers); headers.set("authorization", `Bearer ${await firestoreToken()}`); headers.set("content-type", "application/json"); return fetch(`${docsUrl()}${path}`, { ...init, headers }); }
function decode(value: JsonRecord | undefined): any {
  if (!value) return null; if ("stringValue" in value) return value.stringValue; if ("integerValue" in value) return Number(value.integerValue); if ("doubleValue" in value) return Number(value.doubleValue); if ("booleanValue" in value) return value.booleanValue; if ("timestampValue" in value) return Date.parse(value.timestampValue); if ("nullValue" in value) return null;
  if ("arrayValue" in value) return (value.arrayValue.values || []).map(decode); if ("mapValue" in value) return Object.fromEntries(Object.entries(value.mapValue.fields || {}).map(([k, v]) => [k, decode(v as JsonRecord)])); return null;
}
function encode(value: any): JsonRecord {
  if (value === null || value === undefined) return { nullValue: null }; if (typeof value === "string") return { stringValue: value }; if (typeof value === "boolean") return { booleanValue: value }; if (typeof value === "number" && Number.isInteger(value)) return { integerValue: String(value) }; if (typeof value === "number") return { doubleValue: value }; if (value instanceof Date) return { timestampValue: value.toISOString() }; if (Array.isArray(value)) return { arrayValue: { values: value.map(encode) } }; if (typeof value === "object") return { mapValue: { fields: Object.fromEntries(Object.entries(value).map(([k, v]) => [k, encode(v)])) } }; throw new Error("Unsupported Firestore value.");
}
function encodedFields(value: JsonRecord): JsonRecord { return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, encode(v)])); }
function field(doc: JsonRecord, key: string): any { return decode(doc?.fields?.[key]); }
async function readTransaction(transaction: string, paths: string[]): Promise<Map<string, JsonRecord | null>> {
  const prefix = `${resourcePrefix()}/`, result = await Promise.all(paths.map(async path => {
    if (!path.startsWith(prefix)) throw new Error("Invalid Firestore document name."); const response = await fs(`/${path.slice(prefix.length)}?transaction=${encodeURIComponent(transaction)}`);
    if (response.status === 404) return [path, null] as const; if (!response.ok) throw new Error(`Firestore transaction read failed (${response.status}).`); return [path, await response.json()] as const;
  })); return new Map(result);
}
async function beginTransaction(): Promise<string> { const response = await fs(":beginTransaction", { method: "POST", body: JSON.stringify({ options: { readWrite: {} } }) }); if (!response.ok) throw new Error(`Could not start Firestore transaction (${response.status}).`); return (await response.json()).transaction; }
async function commit(transaction: string, writes: JsonRecord[]): Promise<void> { const response = await fs(":commit", { method: "POST", body: JSON.stringify({ transaction, writes }) }); if (!response.ok) throw new Error(`Firestore transaction commit failed (${response.status}).`); }
function updateWrite(path: string, document: JsonRecord, values: JsonRecord, fieldPaths: string[]): JsonRecord { return { update: { name: path, fields: encodedFields(values) }, updateMask: { fieldPaths }, currentDocument: { updateTime: document.updateTime } }; }
async function notifyBestEffort(uid: string, subject: string, body: string): Promise<void> {
  try {
    const notificationId = await createInAppNotification({ recipientUid: uid, type: "status_update", from: "Chi Phèo BarberShop", subject, body, read: false });
    await sendPush(uid, notificationId, subject, body);
  } catch (error) {
    console.error("Could not create status notification", error instanceof Error ? error.message : "Unknown error");
  }
}
async function updateOrder(body: JsonRecord): Promise<void> {
  const { orderId, status, paymentStatus } = body; if (typeof orderId !== "string" || !orderId || orderId.includes("/")) throw new HttpError("Đơn hàng không hợp lệ.", 400);
  if (status !== undefined && !["shipping", "delivered", "completed", "canceled"].includes(status)) throw new HttpError("Trạng thái đơn hàng không hợp lệ.", 400); if (paymentStatus !== undefined && paymentStatus !== "paid") throw new HttpError("Trạng thái thanh toán không hợp lệ.", 400); if (status === "canceled" && paymentStatus === "paid") throw new HttpError("Không thể vừa hủy đơn vừa xác nhận thanh toán.", 400);
  const path = resource(`orders/${orderId}`), transaction = await beginTransaction(), docs = await readTransaction(transaction, [path]), order = docs.get(path); if (!order) throw new HttpError("Không tìm thấy đơn hàng.", 404);
  const current = field(order, "status"), allowed: Record<string, string[]> = { pending: ["shipping", "completed", "canceled"], shipping: ["delivered"], delivered: [], completed: [], canceled: [] }; if (status && !allowed[current]?.includes(status)) throw new HttpError("Không thể chuyển đơn sang trạng thái này.", 409);
  const currentPayment = field(order, "paymentStatus") || "unpaid"; if (status === "completed" && currentPayment !== "paid" && paymentStatus !== "paid") throw new HttpError("Chỉ hoàn thành đơn sau khi đã nhận thanh toán.", 409); if ((status === "shipping" || status === "delivered") && currentPayment !== "paid" && paymentStatus !== "paid") throw new HttpError("Chỉ giao đơn hàng sau khi đã xác nhận thanh toán.", 409); if (status === "canceled" && currentPayment === "paid") throw new HttpError("Đơn đã thanh toán không thể hủy tự động; hãy xử lý hoàn tiền trước.", 409);
  if (paymentStatus === "paid" && currentPayment !== "paid" && !["pending", "shipping"].includes(current)) throw new HttpError("Không thể xác nhận thanh toán cho đơn đã hủy hoặc đã kết thúc.", 409);
  const items = field(order, "items") || [], refundStock = status === "canceled" && current === "pending"; if (refundStock && (!Array.isArray(items) || items.some((item: JsonRecord) => typeof item.productId !== "string" || !item.productId || item.productId.includes("/")))) throw new HttpError("Không thể hoàn kho vì đơn hàng có dữ liệu sản phẩm không hợp lệ.", 409);
  const quantities = new Map<string, number>(); if (refundStock) for (const item of items) { const qty = Number(item.quantity ?? item.qty); if (!Number.isInteger(qty) || qty <= 0) throw new HttpError("Không thể hoàn kho vì số lượng sản phẩm không hợp lệ.", 409); quantities.set(item.productId, (quantities.get(item.productId) || 0) + qty); }
  const productPaths = [...quantities.keys()].map(id => resource(`products/${id}`)), all = await readTransaction(transaction, productPaths); if (productPaths.some(p => !all.get(p))) throw new HttpError("Không thể hoàn kho vì một sản phẩm trong đơn đã bị xóa.", 409);
  for (const productPath of productPaths) { const stock = Number(field(all.get(productPath)!, "stock")); if (!Number.isInteger(stock) || stock < 0 || stock + (quantities.get(productPath.slice(productPath.lastIndexOf("/") + 1)) || 0) > Number.MAX_SAFE_INTEGER) throw new HttpError("Không thể hoàn kho vì tồn kho có dữ liệu không hợp lệ.", 409); }
  const updates: JsonRecord = { updatedAt: new Date() }, mask = ["updatedAt"]; if (status) { updates.status = status; mask.push("status"); } if (status === "completed" || status === "delivered") { updates.completedAt = new Date(); mask.push("completedAt"); } if (paymentStatus) { updates.paymentStatus = "paid"; mask.push("paymentStatus"); }
  const writes: JsonRecord[] = [updateWrite(path, order, updates, mask)]; for (const productPath of productPaths) { const product = all.get(productPath)!, id = productPath.slice(productPath.lastIndexOf("/") + 1); writes.push(updateWrite(productPath, product, { stock: Number(field(product, "stock")) + (quantities.get(id) || 0) }, ["stock"])); } await commit(transaction, writes);
  const uid = field(order, "userId");
  if (typeof uid === "string" && uid && (status || paymentStatus)) {
    const labels: Record<string, string> = { pending: "Chờ xử lý", shipping: "Đang giao", delivered: "Đã giao", completed: "Hoàn thành", canceled: "Đã hủy" };
    const messages = [status ? `Trạng thái mới: ${labels[status] || status}.` : "", paymentStatus === "paid" ? "Đã xác nhận thanh toán." : ""].filter(Boolean);
    await notifyBestEffort(uid, "Đơn hàng của bạn", messages.join(" "));
  }
}
async function updateAppointment(body: JsonRecord): Promise<void> {
  const { appointmentId, status } = body; if (typeof appointmentId !== "string" || !appointmentId || appointmentId.includes("/") || !["confirmed", "in_service", "completed", "canceled"].includes(status)) throw new HttpError("Thông tin cập nhật lịch hẹn không hợp lệ.", 400);
  const path = resource(`appointments/${appointmentId}`), transaction = await beginTransaction(), docs = await readTransaction(transaction, [path]), appointment = docs.get(path); if (!appointment) throw new HttpError("Không tìm thấy lịch hẹn.", 404);
  const current = field(appointment, "status"), allowed: Record<string, string[]> = { pending: ["confirmed", "canceled"], confirmed: ["in_service", "canceled"], in_service: ["completed", "canceled"], completed: [], canceled: [] }; if (!allowed[current]?.includes(status)) throw new HttpError("Không thể chuyển lịch hẹn sang trạng thái này.", 409);
  const ids = field(appointment, "slotIds") || [field(appointment, "slotId")].filter(Boolean); if (!Array.isArray(ids) || ids.some(id => typeof id !== "string" || id.includes("/"))) throw new HttpError("Dữ liệu khung giờ không hợp lệ.", 409);
  const slotPaths = status === "canceled" ? ids.map(id => resource(`slots/${id}`)) : [], slots = await readTransaction(transaction, slotPaths), values: JsonRecord = { status, updatedAt: new Date() }, mask = ["status", "updatedAt"];
  if (status === "completed" || status === "canceled") { values.closedAt = new Date(); mask.push("closedAt"); }
  const writes: JsonRecord[] = [updateWrite(path, appointment, values, mask)]; if (status === "canceled") for (const slotPath of slotPaths) { const slot = slots.get(slotPath); if (slot && field(slot, "appointmentId") === appointmentId) writes.push({ delete: slotPath, currentDocument: { updateTime: slot.updateTime } }); } await commit(transaction, writes);
  const uid = field(appointment, "userId");
  const labels: Record<string, string> = { confirmed: "Đã xác nhận lịch", in_service: "Đang cắt tóc", completed: "Hoàn thành", canceled: "Đã hủy" };
  if (typeof uid === "string" && uid) await notifyBestEffort(uid, "Lịch hẹn của bạn", `Trạng thái mới: ${labels[status] || status}.`);
}
async function updateContact(body: JsonRecord): Promise<void> {
  const { contactId, status } = body;
  if (typeof contactId !== "string" || !contactId || contactId.includes("/") || !["resolved", "open"].includes(status)) {
    throw new HttpError("Thông tin cập nhật tin nhắn không hợp lệ.", 400);
  }
  const path = resource(`contacts/${contactId}`), transaction = await beginTransaction();
  const docs = await readTransaction(transaction, [path]), contact = docs.get(path);
  if (!contact) throw new HttpError("Không tìm thấy tin nhắn.", 404);
  const current = field(contact, "status") || "open";
  if (!["open", "resolved"].includes(current)) throw new HttpError("Trạng thái tin nhắn hiện tại không hợp lệ.", 409);
  await commit(transaction, [updateWrite(path, contact, {
    status,
    resolvedAt: status === "resolved" ? new Date() : null,
    updatedAt: new Date(),
  }, ["status", "resolvedAt", "updatedAt"])]);
}
Deno.serve(async request => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(request) }); if (request.method !== "POST") return fail(request, "Method not allowed.", 405);
  try { await verifyStaff(request); const body = await request.json(); if (!body || typeof body !== "object" || Array.isArray(body)) throw new HttpError("Yêu cầu không hợp lệ.", 400); if (body.kind === "order") await updateOrder(body); else if (body.kind === "appointment") await updateAppointment(body); else if (body.kind === "contact") await updateContact(body); else throw new HttpError("Loại cập nhật không hợp lệ.", 400); return json(request, { ok: true }); }
  catch (error) { if (error instanceof HttpError) return fail(request, error.message, error.status); console.error("update-work-status failed", error instanceof Error ? error.message : "Unknown error"); return fail(request, "Không thể cập nhật trạng thái. Hãy thử lại hoặc kiểm tra cấu hình backend.", 500); }
});
