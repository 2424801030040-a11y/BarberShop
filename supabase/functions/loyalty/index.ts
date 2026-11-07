import { encodeFirestore, firestoreField, firestoreRequest, firestoreResource, HttpError, readFirestoreDocument, verifyUser } from "../_shared/push-notifications.ts";

type JsonRecord = Record<string, any>;
function json(body: JsonRecord, status = 200): Response { return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } }); }
function fields(value: JsonRecord): JsonRecord { return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, encodeFirestore(item)])); }
function field(document: JsonRecord | null, name: string): any { return firestoreField(document, name); }
function pathFor(path: string): string { return firestoreResource(path); }
async function begin(): Promise<string> {
  const response = await firestoreRequest(":beginTransaction", { method: "POST", body: JSON.stringify({ options: { readWrite: {} } }) });
  if (!response.ok) throw new Error(`Could not start loyalty transaction (${response.status}).`);
  return (await response.json()).transaction;
}
async function readInTransaction(transaction: string, paths: string[]): Promise<Map<string, JsonRecord | null>> {
  const results = await Promise.all(paths.map(async path => {
    const response = await firestoreRequest(`/${path}?transaction=${encodeURIComponent(transaction)}`);
    if (response.status === 404) return [path, null] as const;
    if (!response.ok) throw new Error(`Could not read loyalty record (${response.status}).`);
    return [path, await response.json() as JsonRecord] as const;
  }));
  return new Map(results);
}
async function commit(transaction: string, writes: JsonRecord[]): Promise<void> {
  const response = await firestoreRequest(":commit", { method: "POST", body: JSON.stringify({ transaction, writes }) });
  if (!response.ok) throw new Error(`Could not save loyalty transaction (${response.status}).`);
}
async function usageKey(value: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  return [...digest].map(byte => byte.toString(16).padStart(2, "0")).join("");
}
function randomCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(5));
  return "CP" + [...bytes].map(value => value.toString(16).padStart(2, "0")).join("").toUpperCase();
}
function millis(value: any): number | null {
  if (typeof value === "number") return value;
  if (typeof value === "string") { const parsed = Date.parse(value); return Number.isFinite(parsed) ? parsed : null; }
  return null;
}

async function redeem(uid: string, rewardId: string): Promise<JsonRecord> {
  if (!/^[A-Za-z0-9_-]{1,150}$/.test(rewardId)) throw new HttpError("Voucher không hợp lệ.", 400);
  const rewardPath = `rewardVouchers/${rewardId}`;
  const initialReward = await readFirestoreDocument(rewardPath);
  if (!initialReward) throw new HttpError("Không tìm thấy voucher này.", 404);
  if (field(initialReward, "active") === false) throw new HttpError("Voucher hiện không khả dụng.", 409);
  const startsAt = millis(field(initialReward, "startsAt")), endsAt = millis(field(initialReward, "endsAt")), now = Date.now();
  if ((startsAt !== null && startsAt > now) || (endsAt !== null && endsAt < now)) throw new HttpError("Voucher chưa bắt đầu hoặc đã hết hạn.", 409);
  const cost = Number(field(initialReward, "pointsCost")), rawPercent = field(initialReward, "percent"), rawAmount = field(initialReward, "discountAmount");
  const minimum = Number(field(initialReward, "minSubtotal") || 0);
  const percent = rawPercent === null ? null : Number(rawPercent), discountAmount = rawAmount === null ? null : Number(rawAmount);
  if (!Number.isSafeInteger(cost) || cost < 1 || cost > 1_000_000 || (percent === null) === (discountAmount === null)
    || (percent !== null && (!Number.isFinite(percent) || percent <= 0 || percent > 100))
    || (discountAmount !== null && (!Number.isSafeInteger(discountAmount) || discountAmount <= 0))
    || !Number.isSafeInteger(minimum) || minimum < 0) throw new HttpError("Voucher đang có cấu hình không hợp lệ.", 409);

  const code = randomCode(), voucherPath = `users/${uid}/vouchers/${code}`;
  const accountPath = `users/${uid}`;
  const usageId = await usageKey("reward:" + rewardId);
  const usagePath = `users/${uid}/voucherUsages/${usageId}`;
  const transaction = await begin();
  const docs = await readInTransaction(transaction, [accountPath, rewardPath, voucherPath, usagePath]);
  const account = docs.get(accountPath), reward = docs.get(rewardPath);
  if (!reward) throw new HttpError("Voucher không còn khả dụng.", 409);
  if (docs.get(voucherPath)) throw new HttpError("Không tạo được mã voucher. Vui lòng thử lại.", 409);
  if (docs.get(usagePath)) throw new HttpError("Mỗi tài khoản chỉ được đổi voucher này một lần.", 409);
  if (field(reward, "active") === false) throw new HttpError("Voucher hiện không khả dụng.", 409);
  const liveStart = millis(field(reward, "startsAt")), liveEnd = millis(field(reward, "endsAt"));
  if ((liveStart !== null && liveStart > Date.now()) || (liveEnd !== null && liveEnd < Date.now())) throw new HttpError("Voucher chưa bắt đầu hoặc đã hết hạn.", 409);
  const balance = Math.max(0, Number(field(account, "loyaltyPoints") || 0));
  const currentCost = Number(field(reward, "pointsCost")), currentMinimum = Number(field(reward, "minSubtotal") || 0);
  const currentPercent = field(reward, "percent"), currentAmount = field(reward, "discountAmount");
  if (!Number.isSafeInteger(currentCost) || currentCost !== cost || currentMinimum !== minimum
    || (currentPercent === null) === (currentAmount === null)
    || (percent !== null && Number(currentPercent) !== percent)
    || (discountAmount !== null && Number(currentAmount) !== discountAmount)) {
    throw new HttpError("Voucher vừa được cập nhật. Vui lòng tải lại.", 409);
  }
  if (balance < cost) throw new HttpError("Bạn chưa có đủ điểm để đổi voucher này.", 409);

  const currentEndsAt = millis(field(reward, "endsAt"));
  const expiry = currentEndsAt !== null ? new Date(currentEndsAt) : new Date(now + 90 * 24 * 60 * 60 * 1000);
  const livePercent = currentPercent, liveAmount = currentAmount;
  const voucher: JsonRecord = {
    code, rewardId, name: String(field(reward, "name") || "Voucher ưu đãi"),
    minSubtotal: minimum, status: "available", pointsSpent: cost, createdAt: new Date(), expiresAt: expiry,
  };
  if (livePercent !== null) voucher.percent = percent;
  else voucher.discountAmount = discountAmount;
  const accountWrite: JsonRecord = {
    update: { name: pathFor(accountPath), fields: fields({ loyaltyPoints: balance - cost }) },
    updateMask: { fieldPaths: ["loyaltyPoints"] },
    currentDocument: account ? { updateTime: account.updateTime } : { exists: false },
  };
  const writes: JsonRecord[] = [accountWrite, {
    update: { name: pathFor(voucherPath), fields: fields(voucher) },
    currentDocument: { exists: false },
  }, {
    update: { name: pathFor(usagePath), fields: fields({ rewardId, voucherCode: code, pointsSpent: cost, usedAt: new Date() }) },
    currentDocument: { exists: false },
  }];
  try { await commit(transaction, writes); }
  catch (error) {
    const used = await readFirestoreDocument(usagePath);
    if (used) throw new HttpError("Mỗi tài khoản chỉ được đổi voucher này một lần.", 409);
    throw error;
  }
  return { ok: true, code, voucher: voucher.name, pointsBalance: balance - cost, expiresAt: expiry.toISOString() };
}

Deno.serve(async request => {
  if (request.method !== "POST") return json({ error: "Method not allowed." }, 405);
  try {
    const uid = await verifyUser(request), body = await request.json();
    if (!body || typeof body !== "object" || Array.isArray(body) || body.action !== "redeem") throw new HttpError("Yêu cầu đổi voucher không hợp lệ.", 400);
    if (typeof body.voucherId !== "string") throw new HttpError("Voucher không hợp lệ.", 400);
    return json(await redeem(uid, body.voucherId));
  } catch (error) {
    if (error instanceof HttpError) return json({ error: error.message }, error.status);
    console.error("Loyalty request failed", error instanceof Error ? error.message : "Unknown error");
    return json({ error: "Không thể xử lý điểm thưởng lúc này. Vui lòng thử lại." }, 500);
  }
});
