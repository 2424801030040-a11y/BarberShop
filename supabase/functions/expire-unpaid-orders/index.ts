import { createInAppNotification, decodeFirestore, encodeFirestore, firestoreRequest, firestoreResource, sendPush } from "../_shared/push-notifications.ts";

type Doc = Record<string, any>;
const json = (body: Doc, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
function sameSecret(received: string, expected: string): boolean {
  const a = new TextEncoder().encode(received), b = new TextEncoder().encode(expected);
  if (a.length !== b.length) return false;
  let diff = 0; for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}
function field(doc: Doc, key: string): any { return decodeFirestore(doc.fields?.[key]); }
function write(path: string, doc: Doc, data: Doc, mask: string[]): Doc {
  return { update: { name: path, fields: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, encodeFirestore(v)])) }, updateMask: { fieldPaths: mask }, currentDocument: { updateTime: doc.updateTime } };
}
async function transactionRead(tx: string, names: string[]): Promise<Map<string, Doc | null>> {
  const prefix = "projects/" + Deno.env.get("FIREBASE_PROJECT_ID") + "/databases/(default)/documents/";
  const rows = await Promise.all(names.map(async name => {
    if (!name.startsWith(prefix)) throw new Error("Invalid Firestore resource name.");
    const r = await firestoreRequest("/" + name.slice(prefix.length) + "?transaction=" + encodeURIComponent(tx));
    if (r.status === 404) return [name, null] as const;
    if (!r.ok) throw new Error("Firestore transaction read failed (" + r.status + ").");
    return [name, await r.json()] as const;
  }));
  return new Map(rows);
}
async function expire(path: string, id: string): Promise<boolean> {
  const begin = await firestoreRequest(":beginTransaction", { method: "POST", body: JSON.stringify({ options: { readWrite: {} } }) });
  if (!begin.ok) throw new Error("Could not start Firestore transaction.");
  const tx = (await begin.json()).transaction, orderResponse = await firestoreRequest("/orders/" + encodeURIComponent(id) + "?transaction=" + encodeURIComponent(tx));
  if (orderResponse.status === 404) return false;
  if (!orderResponse.ok) throw new Error("Could not read order in transaction.");
  const order = await orderResponse.json();
  const expires = field(order, "reservationExpiresAt"), created = field(order, "createdAt"), payment = field(order, "payment");
  const legacyWindow = payment === "bank" ? 15 * 60_000 : payment === "cash" ? 24 * 60 * 60_000 : 0;
  const effectiveExpiry = typeof expires === "number" ? expires : typeof created === "number" ? created + legacyWindow : Infinity;
  if (field(order, "status") !== "pending" || field(order, "paymentStatus") !== "unpaid" || effectiveExpiry > Date.now()) return false;
  const items = field(order, "items"), quantities = new Map<string, number>();
  if (Array.isArray(items)) for (const item of items) {
    if (!item || typeof item.productId !== "string" || !item.productId || item.productId.includes("/")) continue;
    const qty = Number(item.quantity ?? item.qty);
    if (Number.isInteger(qty) && qty > 0 && qty <= 99) quantities.set(item.productId, (quantities.get(item.productId) || 0) + qty);
  }
  const productNames = [...quantities.keys()].map(productId => firestoreResource("products/" + productId));
  const docs = await transactionRead(tx, [path, ...productNames]), current = docs.get(path);
  if (!current) return false;
  const currentExpiry = typeof field(current, "reservationExpiresAt") === "number" ? field(current, "reservationExpiresAt") : Number(field(current, "createdAt")) + legacyWindow;
  if (field(current, "status") !== "pending" || field(current, "paymentStatus") !== "unpaid" || currentExpiry > Date.now()) return false;
  const writes: Doc[] = [write(path, current, { status: "canceled", cancelReason: "reservation_expired", canceledAt: new Date(), updatedAt: new Date() }, ["status", "cancelReason", "canceledAt", "updatedAt"])];
  for (const productPath of productNames) {
    const product = docs.get(productPath); if (!product) continue;
    const id = productPath.slice(productPath.lastIndexOf("/") + 1), oldStock = Number(field(product, "stock"));
    if (!Number.isInteger(oldStock) || oldStock < 0) continue;
    writes.push(write(productPath, product, { stock: oldStock + quantities.get(id)! }, ["stock"]));
  }
  const commit = await firestoreRequest(":commit", { method: "POST", body: JSON.stringify({ transaction: tx, writes }) });
  if (!commit.ok) throw new Error("Order expiration commit failed (" + commit.status + ").");
  const uid = field(current, "userId");
  if (typeof uid === "string" && uid) {
    try {
      const notificationId = await createInAppNotification({ recipientUid: uid, type: "status_update", from: "Chi Phèo BarberShop", subject: "Đơn hàng đã hết thời gian giữ hàng", body: "Đơn hàng " + (field(current, "orderCode") || id) + " đã được hủy vì chưa thanh toán đúng hạn.", read: false });
      await sendPush(uid, notificationId, "Đơn hàng đã hết thời gian giữ hàng", "Đơn hàng " + (field(current, "orderCode") || id) + " đã được hủy vì chưa thanh toán đúng hạn.");
    } catch (error) { console.error("Could not notify customer about expired order", error instanceof Error ? error.message : "Unknown error"); }
  }
  return true;
}
async function queryExpired(payment: string, cutoff: Date): Promise<Doc[]> {
  const filters = [
    { fieldFilter: { field: { fieldPath: "status" }, op: "EQUAL", value: { stringValue: "pending" } } },
    { fieldFilter: { field: { fieldPath: "paymentStatus" }, op: "EQUAL", value: { stringValue: "unpaid" } } },
    { fieldFilter: { field: { fieldPath: "payment" }, op: "EQUAL", value: { stringValue: payment } } },
    { fieldFilter: { field: { fieldPath: "createdAt" }, op: "LESS_THAN_OR_EQUAL", value: { timestampValue: cutoff.toISOString() } } },
  ];
  const response = await firestoreRequest(":runQuery", { method: "POST", body: JSON.stringify({ structuredQuery: {
    from: [{ collectionId: "orders" }], where: { compositeFilter: { op: "AND", filters } },
    orderBy: [{ field: { fieldPath: "createdAt" }, direction: "ASCENDING" }], limit: 100,
  } }) });
  if (!response.ok) throw new Error("Expired-order query failed (" + response.status + ").");
  return (await response.json()).flatMap((row: Doc) => row.document ? [row.document] : []);
}
Deno.serve(async req => {
  if (req.method !== "POST") return json({ error: "Method not allowed." }, 405);
  const expected = Deno.env.get("ORDER_EXPIRY_SECRET") || "", received = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "") || "";
  if (!expected || !sameSecret(received, expected)) return json({ error: "Unauthorized." }, 401);
  try {
    const now = Date.now();
    const [bankRows, cashRows] = await Promise.all([
      queryExpired("bank", new Date(now - 15 * 60_000)),
      queryExpired("cash", new Date(now - 24 * 60 * 60_000)),
    ]);
    const rows = [...new Map([...bankRows, ...cashRows].map(order => [order.name, order])).values()];
    let expired = 0;
    for (const order of rows) if (await expire(order.name, order.name.split("/").pop())) expired++;
    return json({ ok: true, checked: rows.length, expired });
  } catch (error) {
    console.error("expire-unpaid-orders failed", error instanceof Error ? error.message : "Unknown error");
    return json({ error: "Could not expire unpaid orders." }, 500);
  }
});
