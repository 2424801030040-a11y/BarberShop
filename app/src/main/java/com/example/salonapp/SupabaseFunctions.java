package com.example.salonapp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GetTokenResult;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/** Small authenticated client for the project's Firebase-token protected Supabase functions. */
final class SupabaseFunctions {
    interface Callback { void onComplete(JSONObject result, String error); }
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().build();
    private static final OkHttpClient HAIR_AI_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(155, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(160, java.util.concurrent.TimeUnit.SECONDS)
        .build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private SupabaseFunctions() { }

    static void post(Context context, String function, JSONObject payload, Callback callback) {
        if (!Config.isSupabaseConfigured()) { callback.onComplete(null, "Hãy cấu hình Supabase trong Config.java."); return; }
        if (FirebaseAuth.getInstance().getCurrentUser() == null) { callback.onComplete(null, "Vui lòng đăng nhập lại."); return; }
        FirebaseAuth.getInstance().getCurrentUser().getIdToken(true)
            .addOnSuccessListener(token -> new Thread(() -> request(function, payload, token, callback)).start())
            .addOnFailureListener(error -> callback.onComplete(null, "Không xác thực được tài khoản. Vui lòng đăng nhập lại."));
    }

    private static void request(String function, JSONObject payload, GetTokenResult token, Callback callback) {
        JSONObject result = null;
        String error = null;
        try {
            Request request = new Request.Builder()
                .url(Config.SUPABASE_URL + "/functions/v1/" + function)
                .addHeader("apikey", Config.SUPABASE_PUBLISHABLE_KEY)
                .addHeader("Authorization", "Bearer " + token.getToken())
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
            OkHttpClient client = "hair-advisor".equals(function) ? HAIR_AI_CLIENT : CLIENT;
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() == null ? "{}" : response.body().string();
                result = new JSONObject(body);
                if (!response.isSuccessful()) error = result.optString("error", "Yêu cầu thất bại (HTTP " + response.code() + ").");
            }
        } catch (IOException | org.json.JSONException exception) {
            error = "Không thể kết nối máy chủ. Vui lòng thử lại.";
        }
        JSONObject answer = result;
        String failure = error;
        MAIN.post(() -> callback.onComplete(answer, failure));
    }
}
