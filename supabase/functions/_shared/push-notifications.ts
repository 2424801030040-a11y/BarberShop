const encoder = new TextEncoder();
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPES = "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging";
const LOOKUP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:lookup";
let cachedToken: { value: string; expiresAt: number } | null = null;
type JsonRecord = Record<string, any>;

export class HttpError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

export async function verifyStaff(request: Request): Promise<string> {
  const idToken = request.headers.get("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1];
  const apiKey = Deno.env.get("FIREBASE_WEB_API_KEY");
  if (!idToken) throw new HttpError("Vui lòng đăng nhập lại.", 401);
  if (!apiKey) throw new Error("FIREBASE_WEB_API_KEY is not configured.");
  const response = await fetch(`${LOOKUP_URL}?key=${encodeURIComponent(apiKey)}`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ idToken }),
  });
  if (!response.ok) throw new HttpError("Phiên đăng nhập không hợp lệ hoặc đã hết hạn.", 401);
  const result = await response.json();
  const user = Array.isArray(result.users) ? result.users[0] : null;
  if (typeof user?.localId !== "string") throw new HttpError("Không xác minh được tài khoản.", 401);
  let claims: JsonRecord = {};
  try { claims = typeof user.customAttributes === "string" ? JSON.parse(user.customAttributes) : {}; } catch { /* stay unauthorized */ }
  if (claims.admin !== true && claims.staff !== true) throw new HttpError("Tài khoản chưa được cấp quyền nhân viên.", 403);
  return user.localId;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function pemBytes(pem: string): Uint8Array {
  return Uint8Array.from(atob(pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "")), c => c.charCodeAt(0));
}

async function googleAccessToken(): Promise<string> {
  if (cachedToken && cachedToken.expiresAt > Date.now() + 60_000) return cachedToken.value;
  const email = Deno.env.get("FIREBASE_CLIENT_EMAIL");
  const pem = (Deno.env.get("FIREBASE_PRIVATE_KEY") || "").replace(/\\n/g, "\n");
  if (!email || !pem) throw new Error("Firebase service credentials are not configured.");
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = base64Url(encoder.encode(JSON.stringify({ iss: email, scope: SCOPES, aud: TOKEN_URL, iat: now, exp: now + 3600 })));
  const unsigned = `${header}.${claim}`;
  const key = await crypto.subtle.importKey("pkcs8", pemBytes(pem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signed = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
  const response = await fetch(TOKEN_URL, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${base64Url(new Uint8Array(signed))}` }),
  });
  const result = await response.json();
  if (!response.ok || typeof result.access_token !== "string") throw new Error(`Could not obtain Google access token (${response.status}).`);
  cachedToken = { value: result.access_token, expiresAt: Date.now() + Number(result.expires_in || 3600) * 1000 };
  return cachedToken.value;
}

function projectId(): string {
  const id = Deno.env.get("FIREBASE_PROJECT_ID");
  if (!id) throw new Error("FIREBASE_PROJECT_ID is not configured.");
  return id;
}

function docsUrl(): string { return `https://firestore.googleapis.com/v1/projects/${projectId()}/databases/(default)/documents`; }

export function decodeFirestore(value: JsonRecord | undefined): any {
  if (!value) return null;
  if ("stringValue" in value) return value.stringValue;
  if ("integerValue" in value) return Number(value.integerValue);
  if ("doubleValue" in value) return Number(value.doubleValue);
  if ("booleanValue" in value) return value.booleanValue;
  if ("timestampValue" in value) return Date.parse(value.timestampValue);
  if ("mapValue" in value) return Object.fromEntries(Object.entries(value.mapValue.fields || {}).map(([key, item]) => [key, decodeFirestore(item as JsonRecord)]));
  if ("arrayValue" in value) return (value.arrayValue.values || []).map((item: JsonRecord) => decodeFirestore(item));
  if ("nullValue" in value) return null;
  return null;
}

export function encodeFirestore(value: any): JsonRecord {
  if (value === null || value === undefined) return { nullValue: null };
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number" && Number.isInteger(value)) return { integerValue: String(value) };
  if (typeof value === "number") return { doubleValue: value };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(encodeFirestore) } };
  if (typeof value === "object") return { mapValue: { fields: Object.fromEntries(Object.entries(value).map(([key, item]) => [key, encodeFirestore(item)])) } };
  throw new Error("Unsupported Firestore value.");
}

export function firestoreResource(path: string): string {
  return `projects/${projectId()}/databases/(default)/documents/${path}`;
}

export async function firestoreRequest(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("authorization", `Bearer ${await googleAccessToken()}`);
  headers.set("content-type", "application/json");
  return fetch(`${docsUrl()}${path}`, { ...init, headers });
}

export async function readFirestoreDocument(path: string): Promise<JsonRecord | null> {
  const response = await firestoreRequest(`/${path}`);
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Firestore read failed (${response.status}).`);
  return await response.json();
}

export function firestoreField(document: JsonRecord | null, name: string): any {
  return decodeFirestore(document?.fields?.[name]);
}

export async function createInAppNotification(notification: JsonRecord): Promise<string> {
  const id = crypto.randomUUID();
  const fields = Object.fromEntries(Object.entries({ ...notification, createdAt: new Date() }).map(([key, value]) => [key, encodeFirestore(value)]));
  const response = await firestoreRequest(`/notifications?documentId=${encodeURIComponent(id)}`, { method: "POST", body: JSON.stringify({ fields }) });
  if (!response.ok) throw new Error(`Firestore notification write failed (${response.status}).`);
  return id;
}

export async function sendPush(uid: string, notificationId: string, title: string, body: string): Promise<boolean> {
  const user = await readFirestoreDocument(`users/${encodeURIComponent(uid)}`);
  const token = firestoreField(user, "fcmToken");
  if (typeof token !== "string" || !token) return false;
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId())}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${await googleAccessToken()}`, "content-type": "application/json" },
    body: JSON.stringify({ message: { token, notification: { title: title.slice(0, 200), body: body.slice(0, 500) }, data: { notificationId } } }),
  });
  if (!response.ok) {
    const detail = await response.text();
    console.error("FCM delivery failed", response.status, detail.slice(0, 500));
    return false;
  }
  return true;
}
