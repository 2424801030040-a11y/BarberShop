// Authenticated order creation for Spark projects. Firebase ID tokens are
// validated with Firebase Auth; Firestore writes use a server-only service account.
const encoder = new TextEncoder();
const OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";
const FIRESTORE_SCOPE = "https://www.googleapis.com/auth/datastore";
const IDENTITY_LOOKUP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:lookup";

type CartLine = { productId: string; quantity: number };
let cachedAccessToken: { value: string; expiresAt: number } | null = null;

function json(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function pemBytes(pem: string): Uint8Array {
  return Uint8Array.from(atob(pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "")), (c) => c.charCodeAt(0));
}

async function responseProblem(response: Response): Promise<string> {
  const raw = await response.text();
  let detail = "";
  try {
    const body = JSON.parse(raw);
    detail = body.error?.message || (typeof body.error === "string" ? body.error : "") || body.error_description || body.message || "";
  } catch { /* Avoid logging unstructured response bodies. */ }
  if (!detail && raw) detail = raw;
  return `${response.status}${detail ? `: ${String(detail).replace(/\s+/g, " ").slice(0, 300)}` : ""}`;
}

async function verifyFirebaseUser(request: Request): Promise<string | null> {
  const idToken = request.headers.get("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1];
  const apiKey = Deno.env.get("FIREBASE_WEB_API_KEY");
  if (!idToken) return null;
  if (!apiKey) throw new Error("FIREBASE_WEB_API_KEY is not configured.");
  const response = await fetch(`${IDENTITY_LOOKUP_URL}?key=${encodeURIComponent(apiKey)}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ idToken }),
  });
  if (!response.ok) {
    console.warn("Firebase ID token verification rejected", await responseProblem(response));
    return null;
  }
  const result = await response.json();
  const user = Array.isArray(result.users) ? result.users[0] : null;
  return typeof user?.localId === "string" ? user.localId : null;
}

async function firestoreToken(): Promise<string> {
  if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 60_000) return cachedAccessToken.value;
  const email = Deno.env.get("FIREBASE_CLIENT_EMAIL");
  const privateKeyPem = (Deno.env.get("FIREBASE_PRIVATE_KEY") || "").replace(/\\n/g, "\n");
  if (!email || !privateKeyPem) throw new Error("Firebase service credentials are not configured.");
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = base64Url(encoder.encode(JSON.stringify({ iss: email, scope: FIRESTORE_SCOPE, aud: OAUTH_TOKEN_URL, iat: now, exp: now + 3600 })));
  const unsigned = `${header}.${claim}`;
  const key = await crypto.subtle.importKey("pkcs8", pemBytes(privateKeyPem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signed = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
  const response = await fetch(OAUTH_TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${base64Url(new Uint8Array(signed))}` }),
  });
  const result = await response.json();
  if (!response.ok || typeof result.access_token !== "string") {
    const detail = result.error_description || result.error || "unknown OAuth error";
    throw new Error(`Could not obtain a Firestore access token (${response.status}: ${String(detail).slice(0, 300)}).`);
  }
  cachedAccessToken = { value: result.access_token, expiresAt: Date.now() + Number(result.expires_in || 3600) * 1000 };
  return cachedAccessToken.value;
}

function documentsUrl(): string {
  const projectId = Deno.env.get("FIREBASE_PROJECT_ID");
  if (!projectId) throw new Error("FIREBASE_PROJECT_ID is not configured.");
  return `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents`;
}

function documentResourcePrefix(): string {
  const projectId = Deno.env.get("FIREBASE_PROJECT_ID");
  if (!projectId) throw new Error("FIREBASE_PROJECT_ID is not configured.");
  return `projects/${projectId}/databases/(default)/documents`;
}

function documentName(path: string): string {
  return `${documentResourcePrefix()}/${path}`;
}

async function voucherUsageKey(code: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(code)));
  return [...digest].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function fs(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("authorization", `Bearer ${await firestoreToken()}`);
  headers.set("content-type", "application/json");
  return fetch(`${documentsUrl()}${path}`, { ...init, headers });
}

function fromValue(value: Record<string, any> | undefined): unknown {
  if (!value) return null;
  if ("stringValue" in value) return value.stringValue;
  if ("integerValue" in value) return Number(value.integerValue);
  if ("doubleValue" in value) return Number(value.doubleValue);
  if ("booleanValue" in value) return value.booleanValue;
  if ("timestampValue" in value) return Date.parse(value.timestampValue);
  if ("nullValue" in value) return null;
  return null;
}

function read(document: Record<string, any> | null, field: string): unknown {
  return document?.fields?.[field] ? fromValue(document.fields[field]) : null;
}

function toValue(value: unknown): Record<string, unknown> {
  if (value === null || value === undefined) return { nullValue: null };
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number" && Number.isInteger(value)) return { integerValue: String(value) };
  if (typeof value === "number") return { doubleValue: value };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(toValue) } };
  if (typeof value === "object") return { mapValue: { fields: Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([k, v]) => [k, toValue(v)])) } };
  throw new Error("Unsupported Firestore value.");
}

function fields(value: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, toValue(item)]));
}

async function runQuery(collectionId: string, field: string, value: string): Promise<Record<string, any>[]> {
  const response = await fs(":runQuery", { method: "POST", body: JSON.stringify({ structuredQuery: {
    from: [{ collectionId }],
    where: { fieldFilter: { field: { fieldPath: field }, op: "EQUAL", value: { stringValue: value } } },
    limit: 2,
  } }) });
  if (!response.ok) throw new Error(`Firestore ${collectionId} lookup failed (${await responseProblem(response)}).`);
  const result = await response.json();
  return (result as Array<Record<string, any>>).flatMap((row) => row.document ? [row.document] : []);
}

async function findPersonalVoucher(uid: string, code: string): Promise<Record<string, any> | null> {
  const response = await fs(`/users/${encodeURIComponent(uid)}:runQuery`, { method: "POST", body: JSON.stringify({ structuredQuery: {
    from: [{ collectionId: "vouchers" }],
    where: { fieldFilter: { field: { fieldPath: "code" }, op: "EQUAL", value: { stringValue: code } } },
    limit: 2,
  } }) });
  if (!response.ok) throw new Error(`Personal voucher lookup failed (${await responseProblem(response)}).`);
  const rows = await response.json() as Array<Record<string, any>>;
  const matches = rows.flatMap(row => row.document ? [row.document] : []);
  if (matches.length > 1) throw new Error("Duplicate personal voucher codes found.");
  return matches[0] || null;
}

async function transactionGet(transaction: string, names: string[]): Promise<Map<string, Record<string, any> | null>> {
  const docs = new Map<string, Record<string, any> | null>();
  const prefix = `${documentResourcePrefix()}/`;
  const results = await Promise.all(names.map(async (name) => {
    if (!name.startsWith(prefix)) throw new Error("Invalid Firestore document resource name.");
    const path = name.slice(prefix.length);
    const response = await fs(`/${path}?transaction=${encodeURIComponent(transaction)}`, { method: "GET" });
    if (response.status === 404) return { name, document: null };
    if (!response.ok) throw new Error(`Firestore transaction read failed (${await responseProblem(response)}).`);
    return { name, document: await response.json() as Record<string, any> };
  }));
  for (const result of results) docs.set(result.name, result.document);
  return docs;
}

function failed(message: string, status = 400): Response {
  return json({ error: message }, status);
}

Deno.serve(async (request: Request) => {
  if (request.method !== "POST") return failed("Method not allowed", 405);
  let stage = "verify_firebase_auth";
  try {
    const uid = await verifyFirebaseUser(request);
    if (!uid) return failed("Vui lòng đăng nhập lại.", 401);
    stage = "validate_order";
    const body = await request.json();
    const { items, address, payment, promoCode, requestId } = body || {};
    if (typeof address !== "string" || address.trim().length < 5 || address.trim().length > 500) return failed("Nhập địa chỉ giao hàng đầy đủ (tối đa 500 ký tự).");
    if (!["cash", "bank"].includes(payment)) return failed("Phương thức thanh toán không hợp lệ.");
    if (!Array.isArray(items) || items.length < 1 || items.length > 30) return failed("Giỏ hàng không hợp lệ.");
    if (typeof requestId !== "string" || !/^[A-Za-z0-9_-]{12,64}$/.test(requestId)) return failed("Mã yêu cầu thanh toán không hợp lệ.");

    const quantities = new Map<string, number>();
    for (const item of items as CartLine[]) {
      if (!item || typeof item.productId !== "string" || !item.productId || !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 99) return failed("Số lượng sản phẩm trong giỏ hàng không hợp lệ.");
      quantities.set(item.productId, (quantities.get(item.productId) || 0) + item.quantity);
    }
    if (quantities.size > 30 || [...quantities.values()].some((qty) => qty > 99)) return failed("Giỏ hàng vượt quá giới hạn số lượng.");

    const productIds = [...quantities.keys()];
    if (productIds.some((id) => id.includes("/"))) return failed("Sản phẩm trong giỏ hàng không hợp lệ.");
    const orderName = documentName(`orders/${requestId}`);
    const productNames = productIds.map((id) => documentName(`products/${id}`));
    const promo = typeof promoCode === "string" ? promoCode.trim().toUpperCase() : "";
    const voucherUsageId = promo ? await voucherUsageKey("code:" + promo) : null;
    const voucherUsagePath = voucherUsageId ? `users/${uid}/voucherUsages/${voucherUsageId}` : null;
    const voucherUsageName = voucherUsagePath ? documentName(voucherUsagePath) : null;
    stage = "lookup_promotion";
    const promoMatches = promo ? await runQuery("promotions", "code", promo) : [];
    if (promoMatches.length > 1) return failed("Có nhiều mã giảm giá trùng nhau. Hãy liên hệ cửa hàng.", 409);
    const personalVoucher = promo && promoMatches.length === 0 ? await findPersonalVoucher(uid, promo) : null;
    if (promo && promoMatches.length === 0 && !personalVoucher) return failed("Mã khuyến mãi hoặc voucher không hợp lệ.");
    const promoName = promoMatches[0]?.name;
    const personalVoucherName = personalVoucher?.name;

    stage = "begin_firestore_transaction";
    const begin = await fs(":beginTransaction", { method: "POST", body: JSON.stringify({ options: { readWrite: {} } }) });
    if (!begin.ok) throw new Error(`Could not start Firestore transaction (${await responseProblem(begin)}).`);
    const { transaction } = await begin.json();
    const names = [orderName, ...productNames, ...(promoName ? [promoName] : []), ...(personalVoucherName ? [personalVoucherName] : []), ...(voucherUsageName ? [voucherUsageName] : [])];
    stage = "read_firestore_transaction";
    const docs = await transactionGet(transaction, names);
    const existing = docs.get(orderName);
    if (existing) {
      if (read(existing, "userId") !== uid) return failed("Mã đơn đã được sử dụng.", 409);
      const reservationExpiry = read(existing, "reservationExpiresAt");
      const existingPayment = read(existing, "payment");
      const existingCreatedAt = read(existing, "createdAt");
      const legacyWindow = existingPayment === "bank" ? 15 * 60_000 : existingPayment === "cash" ? 24 * 60 * 60_000 : 0;
      const effectiveExpiry = typeof reservationExpiry === "number"
        ? reservationExpiry
        : typeof existingCreatedAt === "number" ? existingCreatedAt + legacyWindow : Infinity;
      if (read(existing, "status") === "canceled" ||
          (read(existing, "paymentStatus") === "unpaid" && effectiveExpiry <= Date.now())) {
        return failed("Đơn trước đã hết hạn hoặc bị hủy. Vui lòng thử đặt lại.", 409);
      }
      const result = { orderId: requestId, orderCode: read(existing, "orderCode"), total: read(existing, "total"), reused: true };
      return json(result);
    }
    if (voucherUsageName && docs.get(voucherUsageName)) {
      return failed("Mỗi tài khoản chỉ được sử dụng mã voucher này một lần.", 409);
    }

    stage = "validate_stock_and_promotion";
    let subtotal = 0;
    const orderItems: Record<string, unknown>[] = [];
    for (let index = 0; index < productIds.length; index++) {
      const product = docs.get(productNames[index]);
      if (!product) return failed("Một sản phẩm trong giỏ hàng không còn tồn tại.", 404);
      const price = read(product, "price");
      const stock = read(product, "stock");
      const quantity = quantities.get(productIds[index])!;
      const name = read(product, "name");
      if (!Number.isSafeInteger(price) || Number(price) < 0 || !Number.isInteger(stock) || Number(stock) < quantity) return failed(`${typeof name === "string" ? name : "Sản phẩm"} hiện không đủ hàng.`, 409);
      subtotal += Number(price) * quantity;
      if (!Number.isSafeInteger(subtotal)) return failed("Tổng đơn hàng vượt quá giới hạn xử lý.");
      orderItems.push({ productId: productIds[index], name: String(name || "Sản phẩm"), quantity, price: Number(price) });
    }

    let discountPercent = 0;
    let configuredDiscountAmount = 0;
    let fixedDiscount = false;
    let usedVoucherPath: string | null = null;
    if (promoName) {
      const promotion = docs.get(promoName);
      if (!promotion) return failed("Mã khuyến mãi không còn tồn tại.", 409);
      const startsAt = read(promotion, "startsAt");
      const endsAt = read(promotion, "endsAt");
      const minSubtotal = Number(read(promotion, "minSubtotal") || 0);
      if (read(promotion, "active") === false || (typeof startsAt === "number" && startsAt > Date.now()) || (typeof endsAt === "number" && endsAt < Date.now()) || subtotal < minSubtotal) return failed("Mã khuyến mãi chưa có hiệu lực hoặc chưa đủ điều kiện áp dụng.", 409);
      const percent = read(promotion, "percent"), amount = read(promotion, "discountAmount");
      if ((percent !== null && amount !== null) || (percent === null && amount === null)) return failed("Mã khuyến mãi phải có đúng một mức giảm.", 409);
      if (percent !== null) {
        discountPercent = Number(percent);
        if (!Number.isFinite(discountPercent) || discountPercent <= 0 || discountPercent > 100) return failed("Mã khuyến mãi có cấu hình không hợp lệ.", 409);
      } else {
        configuredDiscountAmount = Number(amount); fixedDiscount = true;
        if (!Number.isSafeInteger(configuredDiscountAmount) || configuredDiscountAmount <= 0) return failed("Mã khuyến mãi có cấu hình không hợp lệ.", 409);
      }
    } else if (personalVoucherName) {
      const voucher = docs.get(personalVoucherName);
      if (!voucher || read(voucher, "status") !== "available") return failed("Voucher đã được sử dụng hoặc không còn hiệu lực.", 409);
      const expiresAt = read(voucher, "expiresAt"), startsAt = read(voucher, "startsAt");
      if ((typeof startsAt === "number" && startsAt > Date.now()) || (typeof expiresAt === "number" && expiresAt < Date.now())) return failed("Voucher đã hết hạn.", 409);
      const minimum = Number(read(voucher, "minSubtotal") || 0);
      if (!Number.isSafeInteger(minimum) || minimum < 0 || subtotal < minimum) return failed("Đơn hàng chưa đạt giá trị tối thiểu của voucher.", 409);
      const percent = read(voucher, "percent"), amount = read(voucher, "discountAmount");
      if ((percent !== null && amount !== null) || (percent === null && amount === null)) return failed("Voucher phải có đúng một mức giảm.", 409);
      if (percent !== null) {
        discountPercent = Number(percent);
        if (!Number.isFinite(discountPercent) || discountPercent <= 0 || discountPercent > 100) return failed("Voucher có cấu hình không hợp lệ.", 409);
      } else {
        configuredDiscountAmount = Number(amount); fixedDiscount = true;
        if (!Number.isSafeInteger(configuredDiscountAmount) || configuredDiscountAmount <= 0) return failed("Voucher có cấu hình không hợp lệ.", 409);
      }
      usedVoucherPath = personalVoucherName;
    }
    const discount = fixedDiscount ? Math.min(subtotal, configuredDiscountAmount) : Math.round(subtotal * discountPercent / 100);
    const total = subtotal - discount;
    const orderCode = `CPB${requestId.replace(/[^A-Za-z0-9]/g, "").substring(0, 10).toUpperCase()}`;
    // The bank-transfer code and stock reservation are valid for 15 minutes.
    // Cash orders hold stock for one day so the shop can confirm in-person payment.
    const reservationMinutes = payment === "bank" ? 15 : 24 * 60;
    const writes: Record<string, unknown>[] = [];
    for (let index = 0; index < productIds.length; index++) {
      const product = docs.get(productNames[index])!;
      writes.push({
        update: { name: product.name, fields: fields({ stock: Number(read(product, "stock")) - quantities.get(productIds[index])! }) },
        updateMask: { fieldPaths: ["stock"] },
        currentDocument: { updateTime: product.updateTime },
      });
    }
    writes.push({
      update: { name: orderName, fields: fields({
        userId: uid, items: orderItems, address: address.trim(), payment, paymentStatus: "unpaid", orderCode,
        promoCode: promo, subtotal, discount, discountPercent, discountAmount: fixedDiscount ? configuredDiscountAmount : 0, total, status: "pending", createdAt: new Date(),
        reservationExpiresAt: new Date(Date.now() + reservationMinutes * 60_000),
      }) },
      currentDocument: { exists: false },
    });
    if (voucherUsageName) {
      writes.push({
        update: { name: voucherUsageName, fields: fields({ code: promo, orderId: requestId, usedAt: new Date() }) },
        currentDocument: { exists: false },
      });
    }
    if (usedVoucherPath) {
      const voucher = docs.get(usedVoucherPath)!;
      writes.push({
        update: { name: usedVoucherPath, fields: fields({ status: "used", orderId: requestId, usedAt: new Date() }) },
        updateMask: { fieldPaths: ["status", "orderId", "usedAt"] },
        currentDocument: { updateTime: voucher.updateTime },
      });
    }
    stage = "commit_firestore_order";
    const commit = await fs(":commit", { method: "POST", body: JSON.stringify({ transaction, writes }) });
    if (!commit.ok) {
      if (commit.status === 409 && voucherUsagePath) {
        const usage = await fs(`/${voucherUsagePath}`);
        if (usage.ok) return failed("Mỗi tài khoản chỉ được sử dụng mã voucher này một lần.", 409);
      }
      if (commit.status === 409 || commit.status === 412) return failed("Đơn hàng hoặc voucher vừa được cập nhật. Vui lòng kiểm tra lại và thử lại.", 409);
      throw new Error(`Firestore order commit failed (${await responseProblem(commit)}).`);
    }
    return json({ orderId: requestId, orderCode, total, subtotal, discount });
  } catch (error) {
    console.error("Order creation failed", { stage, error: error instanceof Error ? error.message : String(error) });
    return json({ error: `Không thể tạo đơn lúc này (${stage}). Vui lòng thử lại.` }, 500);
  }
});
