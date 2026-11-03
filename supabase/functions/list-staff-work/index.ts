import { decodeFirestore, firestoreField, firestoreRequest, HttpError, verifyStaff } from "../_shared/push-notifications.ts";

const ORIGINS = new Set(["https://barbershop-e85e1.web.app", "https://barbershop-e85e1.firebaseapp.com", "http://localhost:5000", "http://127.0.0.1:5000", "http://localhost:8080", "http://127.0.0.1:8080"]);
function response(req: Request, body: unknown, status = 200): Response {
  const headers = new Headers({ "content-type": "application/json; charset=utf-8", "access-control-allow-methods": "POST, OPTIONS", "access-control-allow-headers": "authorization, apikey, content-type, x-client-info", vary: "Origin" });
  const origin = req.headers.get("origin"); if (origin && ORIGINS.has(origin)) headers.set("access-control-allow-origin", origin);
  return new Response(JSON.stringify(body), { status, headers });
}
async function list(collection: string, orderField: string): Promise<Record<string, any>[]> {
  const result = await firestoreRequest(":runQuery", { method: "POST", body: JSON.stringify({ structuredQuery: { from: [{ collectionId: collection }], orderBy: [{ field: { fieldPath: orderField }, direction: "DESCENDING" }], limit: 50 } }) });
  if (!result.ok) throw new Error("Firestore work-list query failed (" + result.status + ").");
  return (await result.json()).flatMap((row: Record<string, any>) => row.document ? [row.document] : []);
}
function millis(doc: Record<string, any>, field: string): number {
  const value = doc.fields?.[field]?.timestampValue;
  return typeof value === "string" ? Date.parse(value) : 0;
}
Deno.serve(async req => {
  if (req.method === "OPTIONS") return response(req, {}, 204);
  if (req.method !== "POST") return response(req, { error: "Method not allowed." }, 405);
  try {
    await verifyStaff(req);
    const [appointments, orders] = await Promise.all([list("appointments", "start"), list("orders", "createdAt")]);
    return response(req, {
      appointments: appointments.map(doc => ({ id: doc.name.split("/").pop(), serviceName: firestoreField(doc, "serviceName") || "Dịch vụ", stylistName: firestoreField(doc, "stylistName") || "", branchName: firestoreField(doc, "branchName") || "", userId: firestoreField(doc, "userId") || "", status: firestoreField(doc, "status") || "pending", startAt: millis(doc, "start") })),
      orders: orders.map(doc => ({ id: doc.name.split("/").pop(), orderCode: firestoreField(doc, "orderCode") || doc.name.split("/").pop(), total: Number(firestoreField(doc, "total") || 0), status: firestoreField(doc, "status") || "pending", paymentStatus: firestoreField(doc, "paymentStatus") || "unpaid", payment: firestoreField(doc, "payment") || "", itemCount: Array.isArray(decodeFirestore(doc.fields?.items)) ? decodeFirestore(doc.fields?.items).length : 0, createdAt: millis(doc, "createdAt") })),
    });
  } catch (error) {
    if (error instanceof HttpError) return response(req, { error: error.message }, error.status);
    console.error("list-staff-work failed", error instanceof Error ? error.message : "Unknown error");
    return response(req, { error: "Không thể tải danh sách công việc." }, 500);
  }
});
