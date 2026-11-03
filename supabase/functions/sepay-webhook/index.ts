// SePay bank-transfer webhook. The endpoint is public by necessity; every request
// is authenticated with SePay's HMAC signature before it can read or change Firestore.
import { createInAppNotification, sendPush } from "../_shared/push-notifications.ts";
const encoder = new TextEncoder();
const OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";
const FIRESTORE_SCOPE = "https://www.googleapis.com/auth/datastore";
const MAX_SIGNATURE_AGE_SECONDS = 5 * 60;

type SePayTransfer = {
  id: number | string;
  accountNumber: string;
  subAccount?: string | null;
  code?: string | null;
  content: string;
  transferType: "in" | "out";
  transferAmount: number;
  referenceCode?: string;
  transactionDate?: string;
};

let cachedAccessToken: { value: string; expiresAt: number } | null = null;

function json(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function pemBytes(pem: string): Uint8Array {
  const base64 = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  return Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
}

async function verifySePaySignature(rawBody: string, request: Request): Promise<boolean> {
  const secret = Deno.env.get("SEPAY_HMAC_SECRET");
  const signature = request.headers.get("x-sepay-signature") || "";
  const timestamp = request.headers.get("x-sepay-timestamp") || "";
  if (!secret || !/^sha256=[0-9a-f]{64}$/i.test(signature) || !/^\d+$/.test(timestamp)) return false;
  const signedAt = Number(timestamp);
  if (!Number.isSafeInteger(signedAt) || Math.abs(Date.now() / 1000 - signedAt) > MAX_SIGNATURE_AGE_SECONDS) return false;
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const expected = Uint8Array.from(signature.slice(7).match(/.{2}/g) || [], (hex) => parseInt(hex, 16));
  return crypto.subtle.verify("HMAC", key, expected, encoder.encode(`${timestamp}.${rawBody}`));
}

function privateKeyPem(): string {
  return (Deno.env.get("FIREBASE_PRIVATE_KEY") || "").replace(/\\n/g, "\n");
}

async function firebaseAccessToken(): Promise<string> {
  if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 60_000) return cachedAccessToken.value;
  const email = Deno.env.get("FIREBASE_CLIENT_EMAIL");
  const privateKey = privateKeyPem();
  if (!email || !privateKey) throw new Error("Firebase service credentials are not configured.");
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${base64Url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })))}.${base64Url(encoder.encode(JSON.stringify({
    iss: email,
    scope: FIRESTORE_SCOPE,
    aud: OAUTH_TOKEN_URL,
    iat: now,
    exp: now + 3600,
  })))}`;
  const key = await crypto.subtle.importKey("pkcs8", pemBytes(privateKey), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signed = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
  const assertion = `${unsigned}.${base64Url(new Uint8Array(signed))}`;
  const response = await fetch(OAUTH_TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  const tokenResult = await response.json();
  if (!response.ok || typeof tokenResult.access_token !== "string") throw new Error("Could not obtain a Firestore access token.");
  cachedAccessToken = { value: tokenResult.access_token, expiresAt: Date.now() + Number(tokenResult.expires_in || 3600) * 1000 };
  return cachedAccessToken.value;
}

function firestoreBase(): string {
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

async function firestoreRequest(path: string, init: RequestInit = {}): Promise<Response> {
  const token = await firebaseAccessToken();
  const headers = new Headers(init.headers);
  headers.set("authorization", `Bearer ${token}`);
  headers.set("content-type", "application/json");
  return fetch(`${firestoreBase()}${path}`, { ...init, headers });
}

function fieldString(document: Record<string, any> | undefined, field: string): string | null {
  return document?.fields?.[field]?.stringValue ?? null;
}

function fieldNumber(document: Record<string, any> | undefined, field: string): number | null {
  const value = document?.fields?.[field];
  if (!value) return null;
  const number = Number(value.integerValue ?? value.doubleValue);
  return Number.isFinite(number) ? number : null;
}

function fieldTimestamp(document: Record<string, any> | undefined, field: string): number | null {
  const value = document?.fields?.[field]?.timestampValue;
  if (typeof value !== "string") return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function firestoreValue(value: unknown): Record<string, unknown> {
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "number" && Number.isInteger(value)) return { integerValue: String(value) };
  if (typeof value === "number") return { doubleValue: value };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (typeof value === "boolean") return { booleanValue: value };
  return { nullValue: null };
}

function firestoreFields(fields: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, firestoreValue(value)]));
}

async function findOrder(orderCode: string): Promise<Record<string, any> | null> {
  const response = await firestoreRequest(":runQuery", {
    method: "POST",
    body: JSON.stringify({ structuredQuery: {
      from: [{ collectionId: "orders" }],
      where: { fieldFilter: { field: { fieldPath: "orderCode" }, op: "EQUAL", value: { stringValue: orderCode } } },
      limit: 2,
    } }),
  });
  if (!response.ok) throw new Error(`Firestore order lookup failed (${response.status}).`);
  const results = await response.json();
  const documents = (results as Array<Record<string, any>>).flatMap((item) => item.document ? [item.document] : []);
  if (documents.length > 1) throw new Error("Duplicate order codes found; refusing automatic payment confirmation.");
  return documents[0] || null;
}

async function firestoreProblem(response: Response): Promise<string> {
  const raw = await response.text();
  let detail = "";
  try {
    const body = JSON.parse(raw);
    detail = body.error?.message || (typeof body.error === "string" ? body.error : "") || body.message || "";
  } catch { /* Keep unstructured response text as a diagnostic below. */ }
  if (!detail && raw) detail = raw;
  return `${response.status}${detail ? `: ${String(detail).replace(/\s+/g, " ").slice(0, 300)}` : ""}`;
}

async function readInTransaction(transaction: string, names: string[]): Promise<Map<string, Record<string, any> | null>> {
  const prefix = `${documentResourcePrefix()}/`;
  const results = await Promise.all(names.map(async (name) => {
    if (!name.startsWith(prefix)) throw new Error("Invalid Firestore document resource name.");
    const path = name.slice(prefix.length);
    const response = await firestoreRequest(`/${path}?transaction=${encodeURIComponent(transaction)}`, { method: "GET" });
    if (response.status === 404) return { name, document: null };
    if (!response.ok) throw new Error(`Firestore transaction read failed (${await firestoreProblem(response)}).`);
    return { name, document: await response.json() as Record<string, any> };
  }));
  return new Map(results.map(({ name, document }) => [name, document]));
}

function transferOrderCode(transfer: SePayTransfer): string | null {
  const source = `${transfer.code || ""} ${transfer.content || ""}`;
  return source.match(/\bCPB[A-Z0-9]{6,12}\b/i)?.[0]?.toUpperCase() || null;
}

Deno.serve(async (request: Request) => {
  if (request.method !== "POST") return json({ success: false, message: "Method not allowed" }, 405);
  const rawBody = await request.text();
  try {
    if (!(await verifySePaySignature(rawBody, request))) {
      // Do not log the body, HMAC secret, or signature. This makes rejected
      // deliveries visible in Supabase, where they previously looked like no call.
      console.warn("Rejected SePay webhook: missing, invalid, or expired HMAC signature. Check SePay auth mode, matching secret, and timestamp.");
      return json({ success: false, message: "Invalid webhook signature" }, 401);
    }
    const transfer = JSON.parse(rawBody) as SePayTransfer;
    const transferId = String(transfer.id ?? "");
    if (!/^[A-Za-z0-9_-]{1,120}$/.test(transferId) || typeof transfer.content !== "string" || !Number.isSafeInteger(transfer.transferAmount) || transfer.transferAmount <= 0) {
      return json({ success: false, message: "Invalid transaction payload" }, 400);
    }
    if (transfer.transferType !== "in") return json({ success: true });

    const expectedAccount = (Deno.env.get("EXPECTED_BANK_ACCOUNT") || "").replace(/\s/g, "");
    if (!expectedAccount) throw new Error("EXPECTED_BANK_ACCOUNT is not configured.");
    if ((transfer.accountNumber || "").replace(/\s/g, "") !== expectedAccount) {
      console.warn(`SePay transaction ${transferId} ignored: receiving account did not match EXPECTED_BANK_ACCOUNT.`);
      return json({ success: true });
    }
    const expectedVa = (Deno.env.get("EXPECTED_BANK_VA") || "").replace(/\s/g, "").toUpperCase();
    if (!expectedVa) throw new Error("EXPECTED_BANK_VA is not configured.");
    if ((transfer.subAccount || "").replace(/\s/g, "").toUpperCase() !== expectedVa) {
      console.warn(`SePay transaction ${transferId} ignored: virtual account did not match EXPECTED_BANK_VA.`);
      return json({ success: true });
    }

    const orderCode = transferOrderCode(transfer);
    const order = orderCode ? await findOrder(orderCode) : null;
    const eventId = `sepay-${transferId}`;
    const eventName = documentName(`bankTransferEvents/${eventId}`);
    const orderName = order?.name;
    const begin = await firestoreRequest(":beginTransaction", { method: "POST", body: JSON.stringify({ options: { readWrite: {} } }) });
    if (!begin.ok) throw new Error(`Could not start Firestore transaction (${begin.status}).`);
    const { transaction } = await begin.json();
    const reads = await readInTransaction(transaction, orderName ? [eventName, orderName] : [eventName]);
    const previousEvent = reads.get(eventName);
    // A prior delivery can be recorded as unmatched (for example, while an order
    // was still being created). Let SePay retries re-evaluate those events. Only
    // a successfully matched event is terminal, so a replay cannot credit twice.
    if (["matched", "late_payment_review"].includes(previousEvent?.fields?.result?.stringValue || "")) {
      return json({ success: true });
    }

    const currentOrder = orderName ? reads.get(orderName) : null;
    const reservationExpiry = currentOrder
      ? fieldTimestamp(currentOrder, "reservationExpiresAt") ??
        (fieldTimestamp(currentOrder, "createdAt") === null ? Infinity : fieldTimestamp(currentOrder, "createdAt")! + 15 * 60_000)
      : Infinity;
    const expiredReservation = !!currentOrder && (
      (fieldString(currentOrder, "status") === "canceled" && fieldString(currentOrder, "cancelReason") === "reservation_expired") ||
      reservationExpiry <= Date.now()
    );
    const eventResult = !orderName ? "unmatched" : !currentOrder ? "order_missing" :
      fieldString(currentOrder, "payment") !== "bank" ? "not_bank_order" :
      fieldString(currentOrder, "paymentStatus") === "paid" ? "already_paid" :
      fieldNumber(currentOrder, "total") !== transfer.transferAmount ? "amount_mismatch" :
      expiredReservation ? "late_payment_review" :
      fieldString(currentOrder, "status") === "canceled" ? "order_canceled" : "matched";

    const event: Record<string, unknown> = {
      provider: "sepay",
      providerTransactionId: transferId,
      accountNumber: transfer.accountNumber,
      transferAmount: transfer.transferAmount,
      content: transfer.content,
      referenceCode: transfer.referenceCode || "",
      orderCode: orderCode || "",
      result: eventResult,
      receivedAt: new Date(),
    };
    const transactionTime = typeof transfer.transactionDate === "string" ? Date.parse(transfer.transactionDate) : NaN;
    if (Number.isFinite(transactionTime)) event.transactionAt = new Date(transactionTime);
    if ((eventResult === "matched" || eventResult === "late_payment_review") && orderName) event.matchedOrderId = orderName.split("/").pop()!;
    const eventWrite: Record<string, unknown> = {
      update: { name: eventName, fields: firestoreFields(event) },
    };
    if (previousEvent) eventWrite.currentDocument = { updateTime: previousEvent.updateTime };
    else eventWrite.currentDocument = { exists: false };
    const writes: Record<string, unknown>[] = [eventWrite];
    if ((eventResult === "matched" || eventResult === "late_payment_review") && orderName && currentOrder) {
      const paidAt = Number.isFinite(transactionTime) ? new Date(transactionTime) : new Date();
      const orderUpdates: Record<string, unknown> = {
        paymentStatus: "paid",
        paidAmount: transfer.transferAmount,
        paidAt,
        paymentTransactionId: transferId,
      };
      const orderUpdateMask = ["paymentStatus", "paidAmount", "paidAt", "paymentTransactionId"];
      if (eventResult === "late_payment_review") {
        // The reservation deadline passed. Record the money, but never auto-fulfill:
        // the order may already be canceled and its stock may have been released.
        // If it is still pending, stock is still reserved for staff to review.
        orderUpdates.paymentReviewRequired = true;
        orderUpdates.paymentReviewReason = "reservation_expired";
        orderUpdates.updatedAt = new Date();
        orderUpdateMask.push("paymentReviewRequired", "paymentReviewReason", "updatedAt");
      } else if (fieldString(currentOrder, "status") === "pending") {
        // For an in-window transfer, receiving the matching payment completes the sale.
        orderUpdates.status = "completed";
        orderUpdates.completedAt = paidAt;
        orderUpdateMask.push("status", "completedAt");
      }
      writes.push({
        update: { name: orderName, fields: firestoreFields(orderUpdates) },
        updateMask: { fieldPaths: orderUpdateMask },
        currentDocument: { updateTime: currentOrder.updateTime },
      });
    } else {
      console.warn(`SePay transaction ${transferId} was not applied automatically: ${eventResult}`);
    }
    console.info(`SePay transaction ${transferId}: order=${orderCode || "none"}, amount=${transfer.transferAmount}, result=${eventResult}.`);
    const commit = await firestoreRequest(":commit", { method: "POST", body: JSON.stringify({ transaction, writes }) });
    if (!commit.ok) throw new Error(`Firestore payment update failed (${commit.status}).`);
    if (eventResult === "late_payment_review" && currentOrder) {
      const uid = fieldString(currentOrder, "userId");
      if (uid) {
        const code = fieldString(currentOrder, "orderCode") || orderCode || "đơn hàng";
        const message = `Cửa hàng đã nhận khoản chuyển cho ${code} sau khi đơn hết thời gian giữ hàng. Đơn vẫn đang bị hủy; cửa hàng cần đối soát và sẽ liên hệ bạn để xử lý.`;
        try {
          const notificationId = await createInAppNotification({ recipientUid: uid, type: "status_update", from: "Chi Phèo BarberShop", subject: "Đã nhận tiền sau khi đơn hết hạn", body: message, read: false, orderId: orderName!.split("/").pop()!, orderCode: code });
          await sendPush(uid, notificationId, "Đã nhận tiền sau khi đơn hết hạn", message);
        } catch (notificationError) {
          console.error("Could not notify customer about late payment", notificationError instanceof Error ? notificationError.message : "Unknown error");
        }
      }
    }
    // SePay only accepts this exact success response body.
    return json({ success: true });
  } catch (error) {
    console.error("SePay webhook failed", error);
    return json({ success: false, message: "Temporary payment processing error" }, 500);
  }
});
