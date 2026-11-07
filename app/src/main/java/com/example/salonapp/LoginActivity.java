package com.example.salonapp;
import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.credentials.*;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.libraries.identity.googleid.*;
import com.google.firebase.auth.GoogleAuthProvider;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
public class LoginActivity extends AppCompatActivity {
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private EditText etEmail, etPass;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (auth.getCurrentUser() != null) { goHome(); return; }
        setContentView(R.layout.activity_login);
        etEmail = findViewById(R.id.etEmail); etPass = findViewById(R.id.etPassword);
        findViewById(R.id.btnLogin).setOnClickListener(v -> {
            if (!valid(true)) return;
            auth.signInWithEmailAndPassword(email(), pass()).addOnSuccessListener(r -> goHome()).addOnFailureListener(e -> toast(e.getMessage()));
        });
        findViewById(R.id.btnGoogle).setOnClickListener(v -> googleSignIn());
        findViewById(R.id.btnRegister).setOnClickListener(v -> {
            if (!valid(true)) return;
            auth.createUserWithEmailAndPassword(email(), pass()).addOnSuccessListener(r -> goHome()).addOnFailureListener(e -> toast(e.getMessage()));
        });
        findViewById(R.id.btnForgot).setOnClickListener(v -> {
            if (!valid(false)) return;
            auth.sendPasswordResetEmail(email()).addOnSuccessListener(r -> toast("Đã gửi email đặt lại mật khẩu")).addOnFailureListener(e -> toast(e.getMessage()));
        });
    }
    private void googleSignIn() {
        CredentialOption option = new GetSignInWithGoogleOption.Builder(
            getString(R.string.default_web_client_id)
        ).build();
        GetCredentialRequest req = new GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build();
        CredentialManager.create(this).getCredentialAsync(this, req, null,
            ContextCompat.getMainExecutor(this),
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override public void onResult(GetCredentialResponse response) {
                    try {
                        Credential credential = response.getCredential();
                        if (!(credential instanceof CustomCredential)
                            || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                            toast("Google không trả về thông tin đăng nhập. Vui lòng thử lại.");
                            return;
                        }
                        GoogleIdTokenCredential google = GoogleIdTokenCredential.createFrom(
                            ((CustomCredential) credential).getData());
                        auth.signInWithCredential(GoogleAuthProvider.getCredential(google.getIdToken(), null))
                            .addOnSuccessListener(result -> goHome())
                            .addOnFailureListener(error -> toast(describeGoogleError(error)));
                    } catch (Exception error) {
                        toast("Không thể hoàn tất đăng nhập Google. Vui lòng thử lại.");
                    }
                }

                @Override public void onError(GetCredentialException error) {
                    if (error instanceof GetCredentialCancellationException) {
                        toast("Bạn đã đóng bước đăng nhập Google. Hãy bấm Tiếp tục với Google và chọn tài khoản để tiếp tục.");
                    } else if (error instanceof NoCredentialException) {
                        toast("Thiết bị chưa có tài khoản Google. Hãy thêm tài khoản trong Cài đặt rồi thử lại.");
                    } else {
                        toast(describeGoogleError(error));
                    }
                }
            });
    }
    private String describeGoogleError(Exception error) {
        String detail = error == null || error.getMessage() == null ? "" : error.getMessage();
        String normalized = detail.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("[16]") || normalized.contains("reauth")) {
            return "Tài khoản Google cần xác thực lại trên thiết bị. Hãy kiểm tra kết nối mạng, ngày giờ tự động và đăng nhập lại tài khoản Google trong Cài đặt.";
        }
        if (normalized.contains("[10]") || normalized.contains("developer_error")) {
            return "Firebase từ chối cấu hình Google Sign-In. Hãy kiểm tra nhà cung cấp Google và OAuth client trong Firebase.";
        }
        return "Đăng nhập Google thất bại. Vui lòng thử lại.";
    }
    private String email() { return etEmail.getText().toString().trim(); }
    private String pass() { return etPass.getText().toString(); }
    private boolean valid(boolean needPass) {
        if (!Patterns.EMAIL_ADDRESS.matcher(email()).matches()) { etEmail.setError("Email không hợp lệ"); return false; }
        if (needPass && pass().length() < 6) { etPass.setError("Tối thiểu 6 ký tự"); return false; }
        return true;
    }
    private void goHome() { startActivity(new Intent(this, MainActivity.class)); finish(); }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
}
