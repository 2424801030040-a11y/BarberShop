import { cert, getApps, initializeApp } from "npm:firebase-admin@13.4.0/app";
import { getAuth, type UserRecord } from "npm:firebase-admin@13.4.0/auth";

type Json = Record<string, unknown>;
const allowedOrigins = new Set([
  "https://barbershop-e85e1.web.app",
  "https://barbershop-e85e1.firebaseapp.com",
  "http://localhost:5000",
  "http://127.0.0.1:5000",
  "http://localhost:8080",
  "http://127.0.0.1:8080",
]);

function response(request: Request, body: Json, status = 200): Response {
  const headers = new Headers({
    "content-type": "application/json; charset=utf-8",
    "access-control-allow-methods": "POST, OPTIONS",
    "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
    "vary": "Origin",
  });
  const origin = request.headers.get("origin");
  if (origin && allowedOrigins.has(origin)) headers.set("access-control-allow-origin", origin);
  return new Response(JSON.stringify(body), { status, headers });
}

function firebaseAuth() {
  const raw = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
  let serviceAccount: Json;
  let invalidJson = false;
  if (raw) {
    try {
      serviceAccount = JSON.parse(raw);
    } catch {
      invalidJson = true;
    }
  }
  if (!raw || invalidJson) {
    // Reuse the Firebase service-account secrets already configured for the other Edge Functions.
    const projectId = Deno.env.get("FIREBASE_PROJECT_ID");
    const clientEmail = Deno.env.get("FIREBASE_CLIENT_EMAIL");
    const privateKey = Deno.env.get("FIREBASE_PRIVATE_KEY");
    if (!projectId || !clientEmail || !privateKey) {
      const message = invalidJson
        ? "Supabase secret FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON, and the split Firebase secrets are incomplete. Correct that JSON or set FIREBASE_PROJECT_ID, FIREBASE_CLIENT_EMAIL, and FIREBASE_PRIVATE_KEY."
        : "Configure FIREBASE_SERVICE_ACCOUNT_JSON or all three secrets FIREBASE_PROJECT_ID, FIREBASE_CLIENT_EMAIL, and FIREBASE_PRIVATE_KEY in Supabase.";
      throw Object.assign(new Error(message), { status: 503 });
    }
    serviceAccount = { project_id: projectId, client_email: clientEmail, private_key: privateKey };
  }
  if (typeof serviceAccount.project_id !== "string"
      || typeof serviceAccount.client_email !== "string"
      || typeof serviceAccount.private_key !== "string") {
    throw Object.assign(new Error("Firebase service account configuration is missing project_id, client_email, or private_key."), { status: 503 });
  }
  serviceAccount.private_key = serviceAccount.private_key.replace(/\\n/g, "\n");
  const appName = "manage-staff-admin";
  const app = getApps().find(candidate => candidate.name === appName)
    ?? initializeApp({ credential: cert(serviceAccount as Parameters<typeof cert>[0]) }, appName);
  return getAuth(app);
}

function publicUser(user: UserRecord) {
  const claims = user.customClaims ?? {};
  const roles = [
    ...(claims.admin === true ? ["admin"] : []),
    ...(claims.staff === true ? ["staff"] : []),
  ];
  return {
    uid: user.uid,
    email: user.email ?? "",
    displayName: user.displayName ?? "",
    createdAt: user.metadata.creationTime,
    roles: roles.length ? roles : ["user"],
    disabled: user.disabled,
  };
}

async function requireAdmin(request: Request) {
  const token = request.headers.get("authorization")?.match(/^Bearer\s+(.+)$/i)?.[1];
  if (!token) throw Object.assign(new Error("Vui lòng đăng nhập lại."), { status: 401 });
  const auth = firebaseAuth();
  let decoded;
  try {
    decoded = await auth.verifyIdToken(token, true);
  } catch {
    throw Object.assign(new Error("Phiên đăng nhập không hợp lệ hoặc đã hết hạn."), { status: 401 });
  }
  if (decoded.admin !== true) throw Object.assign(new Error("Tài khoản chưa được cấp quyền quản trị."), { status: 403 });
  return { auth, uid: decoded.uid };
}

async function allUsers(auth: ReturnType<typeof getAuth>): Promise<UserRecord[]> {
  const users: UserRecord[] = [];
  let pageToken: string | undefined;
  do {
    const page = await auth.listUsers(1000, pageToken);
    users.push(...page.users);
    pageToken = page.pageToken;
  } while (pageToken);
  return users;
}

async function handle(request: Request): Promise<Response> {
  try {
    const { auth, uid: actorUid } = await requireAdmin(request);
    const body = await request.json().catch(() => null) as Json | null;
    if (!body || typeof body !== "object" || Array.isArray(body)) return response(request, { error: "Yêu cầu không hợp lệ." }, 400);

    if (body.action === "list") {
      const users = await allUsers(auth);
      return response(request, { users: users.map(publicUser) });
    }

    const targetUid = body.uid;
    if (typeof targetUid !== "string" || !targetUid.trim() || targetUid.includes("/")) {
      return response(request, { error: "UID người dùng không hợp lệ." }, 400);
    }

    if (body.action === "setRole") {
      const role = body.role;
      if (role !== "staff" && role !== "admin") return response(request, { error: "Vai trò không hợp lệ." }, 400);
      if (typeof body.enabled !== "boolean") return response(request, { error: "Thiếu trạng thái bật/tắt vai trò." }, 400);
      if (targetUid === actorUid && role === "admin" && body.enabled === false) {
        return response(request, { error: "Bạn không thể tự thu hồi quyền quản trị của mình." }, 409);
      }

      const target = await auth.getUser(targetUid);
      const claims = { ...(target.customClaims ?? {}) };
      if (body.enabled) claims[role] = true;
      else delete claims[role];

      if (role === "admin" && body.enabled === false && claims.admin !== true) {
        const admins = (await allUsers(auth)).filter(user => user.customClaims?.admin === true);
        if (admins.length <= 1) return response(request, { error: "Không thể thu hồi quyền quản trị cuối cùng." }, 409);
      }
      await auth.setCustomUserClaims(targetUid, claims);
      return response(request, { ok: true, user: publicUser(await auth.getUser(targetUid)) });
    }

    if (body.action === "toggleDisabled") {
      if (typeof body.disabled !== "boolean") return response(request, { error: "Thiếu trạng thái khóa tài khoản." }, 400);
      if (targetUid === actorUid && body.disabled) return response(request, { error: "Bạn không thể tự khóa tài khoản đang sử dụng." }, 409);
      const target = await auth.getUser(targetUid);
      if (target.customClaims?.admin === true && body.disabled) {
        const activeAdmins = (await allUsers(auth)).filter(user => user.customClaims?.admin === true && !user.disabled);
        if (activeAdmins.length <= 1) return response(request, { error: "Không thể khóa tài khoản quản trị đang hoạt động cuối cùng." }, 409);
      }
      await auth.updateUser(targetUid, { disabled: body.disabled });
      return response(request, { ok: true, user: publicUser(await auth.getUser(targetUid)) });
    }

    return response(request, { error: "Thao tác không được hỗ trợ." }, 400);
  } catch (error) {
    let status = Number((error as { status?: number })?.status) || 500;
    if (status >= 500) console.error("manage-staff failed", error instanceof Error ? error.message : "Unknown error");
    const code = String((error as { code?: unknown })?.code ?? "");
    let message = status < 500 && error instanceof Error ? error.message : "Không thể quản lý tài khoản. Hãy thử lại hoặc kiểm tra cấu hình backend.";
    if (status === 503 && error instanceof Error) message = error.message;
    else if (code.includes("insufficient-permission") || code.includes("permission-denied")) {
      message = "Service account của Firebase cần quyền Firebase Authentication Admin (roles/firebaseauth.admin) trong dự án barbershop-e85e1.";
    } else if (code.includes("user-not-found")) {
      status = 404;
      message = "Không tìm thấy tài khoản Firebase Auth này.";
    }
    return response(request, { error: message }, status);
  }
}

Deno.serve(request => {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: {
    "access-control-allow-methods": "POST, OPTIONS",
    "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
    "access-control-allow-origin": request.headers.get("origin") && allowedOrigins.has(request.headers.get("origin")!) ? request.headers.get("origin")! : "https://barbershop-e85e1.web.app",
    "vary": "Origin",
  } });
  if (request.method !== "POST") return response(request, { error: "Method not allowed." }, 405);
  return handle(request);
});
