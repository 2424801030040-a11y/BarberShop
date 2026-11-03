import { HttpError, encodeFirestore } from "../_shared/push-notifications.ts";

const enc = new TextEncoder(), TOKEN_URL = "https://oauth2.googleapis.com/token";
const LOOKUP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:lookup";
const SCOPE = "https://www.googleapis.com/auth/datastore";
const MIN_NOTICE = 2 * 60 * 60 * 1000;
let cached: { value: string; until: number } | null = null;
type Doc = Record<string, any>;
const json = (body: Doc, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
const b64 = (a: Uint8Array) => { let s = ""; for (const b of a) s += String.fromCharCode(b); return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, ""); };
const project = () => { const p = Deno.env.get("FIREBASE_PROJECT_ID"); if (!p) throw new Error("FIREBASE_PROJECT_ID is not configured."); return p; };
const res = (path: string) => "projects/" + project() + "/databases/(default)/documents/" + path;

async function uidFor(req: Request): Promise<string> {
  const token = req.headers.get("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1], key = Deno.env.get("FIREBASE_WEB_API_KEY");
  if (!token) throw new HttpError("Vui lòng đăng nhập lại.", 401);
  if (!key) throw new Error("FIREBASE_WEB_API_KEY is not configured.");
  const r = await fetch(LOOKUP_URL + "?key=" + encodeURIComponent(key), { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ idToken: token }) });
  if (!r.ok) throw new HttpError("Phiên đăng nhập không hợp lệ hoặc đã hết hạn.", 401);
  const u = (await r.json()).users?.[0]?.localId;
  if (typeof u !== "string") throw new HttpError("Không xác minh được tài khoản.", 401);
  return u;
}
async function accessToken(): Promise<string> {
  if (cached && cached.until > Date.now() + 60000) return cached.value;
  const email = Deno.env.get("FIREBASE_CLIENT_EMAIL"), pem = (Deno.env.get("FIREBASE_PRIVATE_KEY") || "").replace(/\\n/g, "\n");
  if (!email || !pem) throw new Error("Firebase service credentials are not configured.");
  const now = Math.floor(Date.now() / 1000), head = b64(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = b64(enc.encode(JSON.stringify({ iss: email, scope: SCOPE, aud: TOKEN_URL, iat: now, exp: now + 3600 }))), unsigned = head + "." + claim;
  const der = Uint8Array.from(atob(pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "")), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(unsigned));
  const r = await fetch(TOKEN_URL, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: unsigned + "." + b64(new Uint8Array(sig)) }) });
  const data = await r.json();
  if (!r.ok || typeof data.access_token !== "string") throw new Error("Could not obtain Firestore access token.");
  cached = { value: data.access_token, until: Date.now() + Number(data.expires_in || 3600) * 1000 }; return cached.value;
}
async function fs(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers); headers.set("authorization", "Bearer " + await accessToken()); headers.set("content-type", "application/json");
  return fetch("https://firestore.googleapis.com/v1/projects/" + project() + "/databases/(default)/documents" + path, { ...init, headers });
}
function val(x: Doc | undefined): any {
  if (!x) return null;
  if ("stringValue" in x) return x.stringValue;
  if ("integerValue" in x) return Number(x.integerValue);
  if ("doubleValue" in x) return Number(x.doubleValue);
  if ("timestampValue" in x) return Date.parse(x.timestampValue);
  if ("arrayValue" in x) return (x.arrayValue.values || []).map(val);
  return null;
}
const field = (doc: Doc, key: string) => val(doc.fields?.[key]);
async function txRead(tx: string, paths: string[]): Promise<Map<string, Doc | null>> {
  const prefix = "projects/" + project() + "/databases/(default)/documents/";
  const results = await Promise.all(paths.map(async path => {
    if (!path.startsWith(prefix)) throw new Error("Invalid document path.");
    const r = await fs("/" + path.slice(prefix.length) + "?transaction=" + encodeURIComponent(tx));
    if (r.status === 404) return [path, null] as const;
    if (!r.ok) throw new Error("Firestore read failed (" + r.status + ").");
    return [path, await r.json()] as const;
  }));
  return new Map(results);
}
function local(date: Date) {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Ho_Chi_Minh", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", hourCycle: "h23", minute: "2-digit", second: "2-digit" }).formatToParts(date);
  return Object.fromEntries(parts.map(p => [p.type, Number(p.value)]));
}
function update(path: string, old: Doc, data: Doc, mask: string[]): Doc {
  return { update: { name: path, fields: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, encodeFirestore(v)])) }, updateMask: { fieldPaths: mask }, currentDocument: { updateTime: old.updateTime } };
}
async function run(uid: string, body: Doc): Promise<Doc> {
  const action = body.action, id = body.appointmentId;
  if (!["cancel", "reschedule"].includes(action) || typeof id !== "string" || !id || id.includes("/")) throw new HttpError("Thông tin lịch hẹn không hợp lệ.", 400);
  const ap = res("appointments/" + id), initialResponse = await fs("/appointments/" + encodeURIComponent(id));
  if (initialResponse.status === 404) throw new HttpError("Không tìm thấy lịch hẹn.", 404);
  if (!initialResponse.ok) throw new Error("Appointment lookup failed.");
  const initial = await initialResponse.json(), status = field(initial, "status");
  if (field(initial, "userId") !== uid) throw new HttpError("Không tìm thấy lịch hẹn.", 404);
  if (!["pending", "confirmed"].includes(status)) throw new HttpError("Lịch hẹn này không thể đổi hoặc hủy.", 409);
  if (typeof field(initial, "start") !== "number" || field(initial, "start") - Date.now() < MIN_NOTICE) throw new HttpError("Chỉ có thể đổi hoặc hủy trước giờ hẹn ít nhất 2 tiếng.", 409);
  const ids = field(initial, "slotIds") || [field(initial, "slotId")].filter(Boolean);
  if (!Array.isArray(ids) || ids.some(x => typeof x !== "string" || x.includes("/"))) throw new HttpError("Dữ liệu khung giờ không hợp lệ.", 409);
  const oldPaths = ids.map(x => res("slots/" + x));
  const begin = await fs(":beginTransaction", { method: "POST", body: JSON.stringify({ options: { readWrite: {} } }) });
  if (!begin.ok) throw new Error("Could not start transaction.");
  const tx = (await begin.json()).transaction;
  if (action === "cancel") {
    const docs = await txRead(tx, [ap, ...oldPaths]), current = docs.get(ap);
    if (!current || field(current, "userId") !== uid || !["pending", "confirmed"].includes(field(current, "status")) || typeof field(current, "start") !== "number" || field(current, "start") - Date.now() < MIN_NOTICE) throw new HttpError("Lịch hẹn vừa thay đổi. Hãy làm mới rồi thử lại.", 409);
    const writes: Doc[] = [update(ap, current, { status: "canceled", canceledAt: new Date(), updatedAt: new Date() }, ["status", "canceledAt", "updatedAt"])];
    for (const p of oldPaths) { const slot = docs.get(p); if (slot && field(slot, "appointmentId") === id) writes.push({ delete: p, currentDocument: { updateTime: slot.updateTime } }); }
    const commit = await fs(":commit", { method: "POST", body: JSON.stringify({ transaction: tx, writes }) });
    if (!commit.ok) throw new HttpError("Lịch hẹn vừa thay đổi. Hãy làm mới rồi thử lại.", 409);
    return { ok: true, action };
  }
  const branch = field(initial, "branchId"), stylist = field(initial, "stylistId"), serviceId = field(initial, "serviceId");
  if (![branch, stylist, serviceId].every(x => typeof x === "string" && x.length && !x.includes("/"))) throw new HttpError("Lịch hẹn không hợp lệ.", 409);
  const servicePath = res("services/" + serviceId), sr = await fs("/services/" + encodeURIComponent(serviceId));
  if (!sr.ok) throw new HttpError("Dịch vụ không còn khả dụng.", 409);
  const duration = Number(field(await sr.json(), "durationMinutes") || 60);
  if (!Number.isInteger(duration) || duration < 15 || duration > 240) throw new HttpError("Thời lượng dịch vụ không hợp lệ.", 409);
  const timestamp = Number(body.startAt);
  if (!Number.isSafeInteger(timestamp) || timestamp <= Date.now()) throw new HttpError("Ngày giờ đổi lịch không hợp lệ.", 400);
  const start = new Date(timestamp), p = local(start);
  if (p.minute !== 0 || p.second !== 0 || p.hour < 9 || p.hour * 60 + duration > 19 * 60) throw new HttpError("Chọn giờ bắt đầu tròn giờ trong khung 09:00–19:00.", 400);
  const day = String(p.year) + String(p.month).padStart(2, "0") + String(p.day).padStart(2, "0");
  const newIds = Array.from({ length: Math.ceil(duration / 60) }, (_, i) => branch + "_" + stylist + "_" + day + "_" + String(p.hour + i).padStart(2, "0") + "00");
  const newPaths = newIds.map(x => res("slots/" + x)), unique = [...new Set([ap, servicePath, ...oldPaths, ...newPaths])], docs = await txRead(tx, unique);
  const current = docs.get(ap), service = docs.get(servicePath);
  if (!current || !service || field(current, "userId") !== uid || !["pending", "confirmed"].includes(field(current, "status")) || typeof field(current, "start") !== "number" || field(current, "start") - Date.now() < MIN_NOTICE) throw new HttpError("Lịch hẹn vừa thay đổi. Hãy làm mới rồi thử lại.", 409);
  if (Number(field(service, "durationMinutes") || 60) !== duration) throw new HttpError("Thời lượng dịch vụ vừa thay đổi. Vui lòng tải lại lịch rồi thử lại.", 409);
  for (const path of newPaths) { const slot = docs.get(path); if (slot && field(slot, "appointmentId") !== id) throw new HttpError("Khung giờ vừa được đặt. Vui lòng chọn giờ khác.", 409); }
  const end = new Date(timestamp + duration * 60000), keep = new Set(newPaths);
  const writes: Doc[] = [update(ap, current, { start, end, durationMinutes: duration, slotIds: newIds, slotId: newIds[0], updatedAt: new Date() }, ["start", "end", "durationMinutes", "slotIds", "slotId", "updatedAt"])];
  for (const path of oldPaths) { const slot = docs.get(path); if (!keep.has(path) && slot && field(slot, "appointmentId") === id) writes.push({ delete: path, currentDocument: { updateTime: slot.updateTime } }); }
  for (const path of newPaths) {
    const slot = docs.get(path), data = { appointmentId: id, branchId: branch, stylistId: stylist, start, end };
    if (slot) writes.push(update(path, slot, data, ["appointmentId", "branchId", "stylistId", "start", "end"]));
    else writes.push({ update: { name: path, fields: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, encodeFirestore(v)])) }, currentDocument: { exists: false } });
  }
  const commit = await fs(":commit", { method: "POST", body: JSON.stringify({ transaction: tx, writes }) });
  if (!commit.ok) throw new HttpError("Khung giờ vừa được đặt hoặc lịch hẹn vừa thay đổi. Hãy chọn giờ khác.", 409);
  return { ok: true, action, startAt: timestamp, durationMinutes: duration };
}
Deno.serve(async req => {
  if (req.method !== "POST") return json({ error: "Method not allowed." }, 405);
  try { const uid = await uidFor(req), body = await req.json(); if (!body || typeof body !== "object" || Array.isArray(body)) throw new HttpError("Yêu cầu không hợp lệ.", 400); return json(await run(uid, body)); }
  catch (e) { if (e instanceof HttpError) return json({ error: e.message }, e.status); console.error("manage-appointment failed", e instanceof Error ? e.message : "Unknown error"); return json({ error: "Không thể cập nhật lịch hẹn. Hãy thử lại." }, 500); }
});
