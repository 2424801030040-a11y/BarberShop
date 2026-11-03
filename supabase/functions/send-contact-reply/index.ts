import { createInAppNotification, firestoreField, HttpError, readFirestoreDocument, sendPush, verifyStaff } from "../_shared/push-notifications.ts";

const ALLOWED_ORIGINS = new Set(["https://barbershop-e85e1.web.app", "https://barbershop-e85e1.firebaseapp.com", "http://localhost:5000", "http://127.0.0.1:5000", "http://localhost:8080", "http://127.0.0.1:8080"]);
type JsonRecord = Record<string, any>;

function headers(request: Request): Headers {
  const result = new Headers({ "content-type": "application/json; charset=utf-8", "access-control-allow-methods": "POST, OPTIONS", "access-control-allow-headers": "authorization, apikey, content-type, x-client-info", vary: "Origin" });
  const origin = request.headers.get("origin");
  if (origin && ALLOWED_ORIGINS.has(origin)) result.set("access-control-allow-origin", origin);
  return result;
}

function json(request: Request, body: JsonRecord, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: headers(request) });
}

Deno.serve(async request => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: headers(request) });
  if (request.method !== "POST") return json(request, { error: "Method not allowed." }, 405);
  try {
    await verifyStaff(request);
    const body = await request.json();
    const contactId = body?.contactId;
    const reply = typeof body?.body === "string" ? body.body.trim() : "";
    if (typeof contactId !== "string" || !contactId || contactId.includes("/")) throw new HttpError("Tin nhắn không hợp lệ.", 400);
    if (!reply || reply.length > 4000) throw new HttpError("Nội dung trả lời phải từ 1 đến 4.000 ký tự.", 400);

    const contact = await readFirestoreDocument(`contacts/${encodeURIComponent(contactId)}`);
    if (!contact) throw new HttpError("Không tìm thấy tin nhắn.", 404);
    const recipientUid = firestoreField(contact, "userId");
    if (typeof recipientUid !== "string" || !recipientUid) throw new HttpError("Tin nhắn không có tài khoản khách hàng để nhận phản hồi.", 409);
    const originalSubject = firestoreField(contact, "subject") || "Yêu cầu hỗ trợ";
    const subject = /^re:/i.test(originalSubject) ? originalSubject : `Re: ${originalSubject}`;
    const notification = {
      recipientUid, contactId, type: "admin_reply", from: "Chi Phèo BarberShop", subject, body: reply, read: false,
    };
    const notificationId = await createInAppNotification(notification);
    let pushSent = false;
    try { pushSent = await sendPush(recipientUid, notificationId, subject, reply); }
    catch (error) { console.error("FCM delivery failed", error instanceof Error ? error.message : "Unknown error"); }
    return json(request, { ok: true, notificationId, pushSent });
  } catch (error) {
    if (error instanceof HttpError) return json(request, { error: error.message }, error.status);
    console.error("send-contact-reply failed", error instanceof Error ? error.message : "Unknown error");
    return json(request, { error: "Không thể gửi phản hồi. Hãy kiểm tra cấu hình Supabase và thử lại." }, 500);
  }
});
