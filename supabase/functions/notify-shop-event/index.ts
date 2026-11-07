import {
  createInAppNotification, firestoreField, firestoreRequest,
  readFirestoreDocument, sendPush, verifyAdmin, HttpError,
} from "../_shared/push-notifications.ts";

type JsonRecord = Record<string, any>;
const allowedOrigins = new Set([
  "https://barbershop-e85e1.web.app", "https://barbershop-e85e1.firebaseapp.com",
  "http://localhost:5000", "http://127.0.0.1:5000", "http://localhost:8080", "http://127.0.0.1:8080",
]);

function response(request: Request, body: JsonRecord, status = 200): Response {
  const headers = new Headers({
    "content-type": "application/json; charset=utf-8",
    "access-control-allow-methods": "POST, OPTIONS",
    "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
    "vary": "Origin",
  });
  const origin = request.headers.get("origin");
  if (origin && allowedOrigins.has(origin)) headers.set("access-control-allow-origin", origin);
  return new Response(status === 204 ? null : JSON.stringify(body), { status, headers });
}

async function listUsers(pageToken?: string): Promise<{ documents: JsonRecord[]; nextPageToken?: string }> {
  const suffix = pageToken ? `&pageToken=${encodeURIComponent(pageToken)}` : "";
  const result = await firestoreRequest(`/users?pageSize=1000${suffix}`);
  if (!result.ok) throw new Error(`Could not list customers (${result.status}).`);
  return await result.json();
}

async function markPushSent(notificationId: string): Promise<void> {
  const result = await firestoreRequest(`/notifications/${encodeURIComponent(notificationId)}?updateMask.fieldPaths=pushSent`, {
    method: "PATCH",
    body: JSON.stringify({ fields: { pushSent: { booleanValue: true } } }),
  });
  if (!result.ok) throw new Error(`Could not save push delivery status (${result.status}).`);
}

async function notifyOne(user: JsonRecord, eventId: string, title: string, body: string): Promise<{ created: boolean; pushed: boolean }> {
  const uid = String(user.name || "").split("/").pop() || "";
  if (!uid || uid.includes("/")) throw new Error("Invalid user record.");
  const notificationId = `event_${eventId}_${uid}`;
  let existing = await readFirestoreDocument(`notifications/${encodeURIComponent(notificationId)}`);
  let created = false;
  if (!existing) {
    try {
      await createInAppNotification({
        recipientUid: uid, type: "shop_event", from: "Chi Phèo BarberShop",
        subject: title, body, read: false, eventId, pushSent: false,
      }, notificationId);
      created = true;
    } catch (error) {
      // A repeated admin request may race with an earlier request. Reuse its
      // deterministic inbox item instead of creating a second notification.
      existing = await readFirestoreDocument(`notifications/${encodeURIComponent(notificationId)}`);
      if (!existing) throw error;
    }
  }
  if (firestoreField(existing, "pushSent") === true) return { created, pushed: true };
  const pushed = await sendPush(uid, notificationId, title, body);
  if (pushed) await markPushSent(notificationId);
  return { created, pushed };
}

Deno.serve(async request => {
  if (request.method === "OPTIONS") return response(request, {}, 204);
  if (request.method !== "POST") return response(request, { error: "Method not allowed." }, 405);
  try {
    await verifyAdmin(request);
    const body = await request.json();
    const eventId = body && typeof body.eventId === "string" ? body.eventId : "";
    if (!/^[A-Za-z0-9_-]{1,120}$/.test(eventId)) throw new HttpError("Sự kiện không hợp lệ.", 400);
    const event = await readFirestoreDocument(`shopEvents/${encodeURIComponent(eventId)}`);
    if (!event) throw new HttpError("Không tìm thấy sự kiện.", 404);
    const title = String(firestoreField(event, "title") || "Sự kiện mới từ Chi Phèo BarberShop").trim().slice(0, 200);
    const eventBody = String(firestoreField(event, "body") || "").trim().slice(0, 4000);
    if (!eventBody) throw new HttpError("Sự kiện chưa có nội dung thông báo.", 400);

    let pageToken: string | undefined;
    let recipients = 0, inboxCreated = 0, pushSent = 0, failures = 0;
    do {
      const page = await listUsers(pageToken);
      const users = Array.isArray(page.documents) ? page.documents : [];
      for (let start = 0; start < users.length; start += 20) {
        const batch = users.slice(start, start + 20);
        const results = await Promise.allSettled(batch.map(user => notifyOne(user, eventId, title, eventBody)));
        for (const result of results) {
          recipients++;
          if (result.status === "rejected") { failures++; console.error("Shop event notification failed", result.reason instanceof Error ? result.reason.message : "Unknown error"); continue; }
          if (result.value.created) inboxCreated++;
          if (result.value.pushed) pushSent++;
        }
      }
      pageToken = page.nextPageToken;
    } while (pageToken);

    return response(request, { ok: true, recipients, inboxCreated, pushSent, failures });
  } catch (error) {
    if (error instanceof HttpError) return response(request, { error: error.message }, error.status);
    console.error("Shop event notification broadcast failed", error instanceof Error ? error.message : "Unknown error");
    return response(request, { error: "Không thể gửi thông báo sự kiện. Vui lòng thử lại." }, 500);
  }
});
