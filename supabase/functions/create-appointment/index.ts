// Authenticated appointment creation for Firebase Spark projects. Firebase ID
// tokens are verified through Identity Toolkit; Firestore writes use a private
// service account and an atomic transaction to reserve all appointment slots.
const encoder = new TextEncoder();
const OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";
const FIRESTORE_SCOPE = "https://www.googleapis.com/auth/datastore";
const IDENTITY_LOOKUP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:lookup";
const SHOP_TIME_ZONE = "Asia/Ho_Chi_Minh";
const OPEN_HOUR = 9;
const CLOSE_HOUR = 19;
let cachedAccessToken: { value: string; expiresAt: number } | null = null;
type JsonRecord = Record<string, any>;
class HttpError extends Error { constructor(message: string, readonly status: number) { super(message); } }
function json(body: JsonRecord, status = 200): Response { return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } }); }
function base64Url(bytes: Uint8Array): string { let binary = ""; for (const byte of bytes) binary += String.fromCharCode(byte); return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, ""); }
function pemBytes(pem: string): Uint8Array { return Uint8Array.from(atob(pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "")), c => c.charCodeAt(0)); }
async function verifyFirebaseUser(request: Request): Promise<string> {
  const token = request.headers.get("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1], apiKey = Deno.env.get("FIREBASE_WEB_API_KEY");
  if (!token) throw new HttpError("Vui lòng đăng nhập lại.", 401); if (!apiKey) throw new Error("FIREBASE_WEB_API_KEY is not configured.");
  const response = await fetch(`${IDENTITY_LOOKUP_URL}?key=${encodeURIComponent(apiKey)}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ idToken: token }) });
  if (!response.ok) throw new HttpError("Phiên đăng nhập không hợp lệ hoặc đã hết hạn.", 401);
  const result = await response.json(), user = Array.isArray(result.users) ? result.users[0] : null;
  if (typeof user?.localId !== "string") throw new HttpError("Không xác minh được tài khoản.", 401); return user.localId;
}
async function firestoreToken(): Promise<string> {
  if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 60_000) return cachedAccessToken.value;
  const email = Deno.env.get("FIREBASE_CLIENT_EMAIL"), pem = (Deno.env.get("FIREBASE_PRIVATE_KEY") || "").replace(/\\n/g, "\n");
  if (!email || !pem) throw new Error("Firebase service credentials are not configured.");
  const now = Math.floor(Date.now() / 1000), header = base64Url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" }))), claim = base64Url(encoder.encode(JSON.stringify({ iss: email, scope: FIRESTORE_SCOPE, aud: OAUTH_TOKEN_URL, iat: now, exp: now + 3600 }))), unsigned = `${header}.${claim}`;
  const key = await crypto.subtle.importKey("pkcs8", pemBytes(pem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]), signed = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
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
async function transactionRead(transaction: string, paths: string[]): Promise<Map<string, JsonRecord | null>> {
  const prefix = `${resourcePrefix()}/`, result = await Promise.all(paths.map(async path => {
    if (!path.startsWith(prefix)) throw new Error("Invalid Firestore document name."); const response = await fs(`/${path.slice(prefix.length)}?transaction=${encodeURIComponent(transaction)}`);
    if (response.status === 404) return [path, null] as const; if (!response.ok) throw new Error(`Firestore transaction read failed (${response.status}).`); return [path, await response.json()] as const;
  })); return new Map(result);
}
function shopDateParts(date: Date): { year: string; month: string; day: string } {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: SHOP_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(date);
  const values = Object.fromEntries(parts.map(part => [part.type, part.value])); return { year: values.year, month: values.month, day: values.day } as { year: string; month: string; day: string };
}
function shopHour(date: Date): number { return Number(new Intl.DateTimeFormat("en-GB", { timeZone: SHOP_TIME_ZONE, hour: "2-digit", hourCycle: "h23" }).format(date)); }
function makeSlotIds(branchId: string, stylistId: string, start: Date, duration: number): string[] {
  const date = shopDateParts(start), datePart = `${date.year}${date.month}${date.day}`, firstHour = shopHour(start), count = Math.ceil(duration / 60);
  return Array.from({ length: count }, (_, index) => `${branchId}_${stylistId}_${datePart}_${String(firstHour + index).padStart(2, "0")}00`);
}
function validateStart(startAt: number, duration: number): Date {
  if (!Number.isFinite(startAt)) throw new HttpError("Ngày giờ đặt lịch không hợp lệ.", 400);
  const start = new Date(startAt), parts = new Intl.DateTimeFormat("en-GB", { timeZone: SHOP_TIME_ZONE, minute: "2-digit", second: "2-digit" }).formatToParts(start), values = Object.fromEntries(parts.map(part => [part.type, part.value]));
  const endAt = startAt + duration * 60_000, localHour = shopHour(start), localMidnight = startAt - localHour * 3_600_000 - Number(values.minute) * 60_000 - Number(values.second) * 1000 - start.getUTCMilliseconds();
  if (startAt <= Date.now() || Number(values.minute) !== 0 || Number(values.second) !== 0 || localHour < OPEN_HOUR || endAt > localMidnight + CLOSE_HOUR * 3_600_000) throw new HttpError("Chọn giờ bắt đầu tròn giờ trong khung 09:00–19:00; dịch vụ phải kết thúc trước giờ đóng cửa.", 400);
  return start;
}
async function createAppointment(uid: string, body: JsonRecord): Promise<JsonRecord> {
  const { branchId, stylistId, serviceId } = body, startAt = Number(body.startAt);
  if (![branchId, stylistId, serviceId].every(value => typeof value === "string" && value.length > 0 && !value.includes("/"))) throw new HttpError("Chọn chi nhánh, thợ cắt tóc và dịch vụ.", 400);
  const paths = [resource(`branches/${branchId}`), resource(`stylists/${stylistId}`), resource(`services/${serviceId}`)];
  const records = await Promise.all(paths.map(async path => { const response = await fs(`/${path.slice(resourcePrefix().length + 1)}`); if (response.status === 404) return null; if (!response.ok) throw new Error(`Firestore catalog lookup failed (${response.status}).`); return await response.json(); }));
  const [branch, stylist, service] = records; if (!branch || !stylist || !service) throw new HttpError("Chi nhánh, thợ hoặc dịch vụ không còn khả dụng.", 404);
  const branchData = branch.fields || {}, stylistData = stylist.fields || {}, serviceData = service.fields || {};
  if (decode(stylistData.branchId) !== branchId) throw new HttpError("Thợ này không làm việc tại chi nhánh đã chọn.", 409);
  const duration = Number(decode(serviceData.durationMinutes) || 60); if (!Number.isInteger(duration) || duration < 15 || duration > 240) throw new HttpError("Dịch vụ chưa có thời lượng hợp lệ.", 409);
  const start = validateStart(startAt, duration), end = new Date(start.getTime() + duration * 60_000), slotIds = makeSlotIds(branchId, stylistId, start, duration), appointmentId = crypto.randomUUID();
  const appointmentPath = resource(`appointments/${appointmentId}`), slotPaths = slotIds.map(id => resource(`slots/${id}`));
  const begin = await fs(":beginTransaction", { method: "POST", body: JSON.stringify({ options: { readWrite: {} } }) }); if (!begin.ok) throw new Error(`Could not start Firestore transaction (${begin.status}).`);
  const { transaction } = await begin.json(), docs = await transactionRead(transaction, [appointmentPath, ...slotPaths]);
  if (docs.get(appointmentPath)) throw new HttpError("Mã lịch hẹn đã được sử dụng. Vui lòng thử lại.", 409);
  if (slotPaths.some(path => docs.get(path))) throw new HttpError("Khung giờ vừa được đặt. Vui lòng chọn giờ khác.", 409);
  const appointment = {
    userId: uid, branchId, branchName: decode(branchData.name) || "", stylistId, stylistName: decode(stylistData.name) || "", serviceId, serviceName: decode(serviceData.name) || "",
    durationMinutes: duration, start, end, status: "pending", slotIds, slotId: slotIds[0], total: Number(decode(serviceData.price) || 0), createdAt: new Date(),
  };
  const writes: JsonRecord[] = slotPaths.map((path, index) => ({ update: { name: path, fields: encodedFields({ appointmentId, branchId, stylistId, start, end }) }, currentDocument: { exists: false } }));
  writes.push({ update: { name: appointmentPath, fields: encodedFields(appointment) }, currentDocument: { exists: false } });
  const commit = await fs(":commit", { method: "POST", body: JSON.stringify({ transaction, writes }) });
  if (commit.status === 409 || commit.status === 400) throw new HttpError("Khung giờ vừa được đặt. Vui lòng chọn giờ khác.", 409);
  if (!commit.ok) throw new Error(`Firestore appointment commit failed (${commit.status}).`);
  return { appointmentId, startAt: start.getTime(), durationMinutes: duration };
}
Deno.serve(async request => {
  if (request.method !== "POST") return json({ error: "Method not allowed." }, 405);
  try { const uid = await verifyFirebaseUser(request), body = await request.json(); if (!body || typeof body !== "object" || Array.isArray(body)) throw new HttpError("Yêu cầu không hợp lệ.", 400); return json(await createAppointment(uid, body)); }
  catch (error) { if (error instanceof HttpError) return json({ error: error.message }, error.status); console.error("create-appointment failed", error instanceof Error ? error.message : "Unknown error"); return json({ error: "Không thể tạo lịch hẹn. Hãy thử lại hoặc kiểm tra cấu hình backend." }, 500); }
});
