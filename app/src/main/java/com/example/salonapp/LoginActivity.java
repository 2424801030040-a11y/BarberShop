package com.example.salonapp;
import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.*;
import android.view.View;
import androidx.core.content.ContextCompat;
import androidx.credentials.*;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.libraries.identity.googleid.*;
import com.google.firebase.auth.GoogleAuthProvider;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.HashMap;
import java.util.Map;
public class LoginActivity extends AppCompatActivity {
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private EditText etEmail, etPass, etName, etPhone, etConfirmPass;
    private View nameField, phoneField, confirmPassField, googleLabel;
    private TextView tvAuthTitle, tvAuthSubtitle;
    private Button btnLogin, btnGoogle, btnForgot, btnRegister;
    private boolean registerMode;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (auth.getCurrentUser() != null) { goHome(); return; }
        setContentView(R.layout.activity_login);
        etEmail = findViewById(R.id.etEmail); etPass = findViewById(R.id.etPassword);
        etName = findViewById(R.id.etName); etPhone = findViewById(R.id.etPhone);
        etConfirmPass = findViewById(R.id.etConfirmPassword);
        nameField = findViewById(R.id.nameField); phoneField = findViewById(R.id.phoneField);
        confirmPassField = findViewById(R.id.confirmPasswordField);
        googleLabel = findViewById(R.id.tvGoogleLabel);
        tvAuthTitle = findViewById(R.id.tvAuthTitle);
        tvAuthSubtitle = findViewById(R.id.tvAuthSubtitle);
        btnLogin = findViewById(R.id.btnLogin);
        btnGoogle = findViewById(R.id.btnGoogle);
        btnForgot = findViewById(R.id.btnForgot);
        btnRegister = findViewById(R.id.btnRegister);
        btnLogin.setOnClickListener(v -> {
            if (!valid()) return;
            btnLogin.setEnabled(false);
            if (registerMode) {
                auth.createUserWithEmailAndPassword(email(), pass())
                    .addOnSuccessListener(r -> saveNewUserProfile(r.getUser()))
                    .addOnFailureListener(e -> { btnLogin.setEnabled(true); toast(authErrorMessage(e)); });
            } else {
                auth.signInWithEmailAndPassword(email(), pass())
                    .addOnSuccessListener(r -> goHome())
                    .addOnFailureListener(e -> { btnLogin.setEnabled(true); toast(authErrorMessage(e)); });
            }
        });
        btnGoogle.setOnClickListener(v -> googleSignIn());
        btnRegister.setOnClickListener(v -> setRegisterMode(!registerMode));
        btnForgot.setOnClickListener(v -> startActivity(new Intent(this, ForgotPasswordActivity.class)));
        setRegisterMode(false);
    }
    private void setRegisterMode(boolean enabled) {
        registerMode = enabled;
        etEmail.setError(null);
        etPass.setError(null);
        etName.setError(null); etPhone.setError(null); etConfirmPass.setError(null);
        nameField.setVisibility(enabled ? View.VISIBLE : View.GONE);
        phoneField.setVisibility(enabled ? View.VISIBLE : View.GONE);
        confirmPassField.setVisibility(enabled ? View.VISIBLE : View.GONE);
        btnGoogle.setVisibility(enabled ? View.GONE : View.VISIBLE);
        googleLabel.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            tvAuthTitle.setText("Tạo tài khoản mới");
            tvAuthSubtitle.setText("Nhập thông tin để tạo tài khoản.");
            btnLogin.setText("Tạo tài khoản bằng email");
            btnForgot.setVisibility(android.view.View.GONE);
            btnRegister.setText("Đã có tài khoản?  Đăng nhập");
        } else {
            tvAuthTitle.setText("Chào mừng trở lại");
            tvAuthSubtitle.setText("Đăng nhập để đặt lịch và chăm sóc phong cách của bạn.");
            btnLogin.setText("Đăng nhập");
            btnGoogle.setText("Tiếp tục với Google");
            btnForgot.setVisibility(android.view.View.VISIBLE);
            btnRegister.setText("Chưa có tài khoản?  Tạo tài khoản");
        }
    }
    private String authErrorMessage(Exception error) {
        if (error instanceof FirebaseAuthException) {
            switch (((FirebaseAuthException) error).getErrorCode()) {
                case "ERROR_EMAIL_ALREADY_IN_USE": return "Email này đã có tài khoản. Hãy đăng nhập hoặc đặt lại mật khẩu.";
                case "ERROR_INVALID_EMAIL": return "Địa chỉ email không hợp lệ.";
                case "ERROR_WEAK_PASSWORD": return "Mật khẩu quá yếu. Hãy dùng ít nhất 6 ký tự.";
                case "ERROR_OPERATION_NOT_ALLOWED": return "Phương thức này chưa được bật trong Firebase Authentication.";
                case "ERROR_NETWORK_REQUEST_FAILED": return "Không kết nối được Firebase. Hãy kiểm tra mạng rồi thử lại.";
                case "ERROR_TOO_MANY_REQUESTS": return "Bạn đã thử quá nhiều lần. Vui lòng đợi rồi thử lại.";
                case "ERROR_USER_NOT_FOUND":
                case "ERROR_WRONG_PASSWORD": return "Email hoặc mật khẩu chưa chính xác.";
            }
        }
        return "Không hoàn tất được yêu cầu tài khoản. Hãy kiểm tra email, mật khẩu và kết nối mạng.";
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
    private boolean valid() {
        if (registerMode && etName.getText().toString().trim().isEmpty()) { etName.setError("Vui lòng nhập họ tên"); etName.requestFocus(); return false; }
        String digits = etPhone.getText().toString().replaceAll("\\D", "");
        if (registerMode && (digits.length() < 9 || digits.length() > 11)) { etPhone.setError("Số điện thoại cần có 9–11 chữ số"); etPhone.requestFocus(); return false; }
        if (!Patterns.EMAIL_ADDRESS.matcher(email()).matches()) { etEmail.setError("Email không hợp lệ"); etEmail.requestFocus(); return false; }
        if (pass().length() < 6) { etPass.setError("Tối thiểu 6 ký tự"); etPass.requestFocus(); return false; }
        if (registerMode && !pass().equals(etConfirmPass.getText().toString())) { etConfirmPass.setError("Mật khẩu nhập lại chưa khớp"); etConfirmPass.requestFocus(); return false; }
        return true;
    }
    private void saveNewUserProfile(FirebaseUser user) {
        if (user == null) { btnLogin.setEnabled(true); toast("Tạo tài khoản không thành công. Vui lòng thử lại."); return; }
        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        UserProfileChangeRequest update = new UserProfileChangeRequest.Builder().setDisplayName(name).build();
        user.updateProfile(update).addOnCompleteListener(profileTask -> {
            Map<String, Object> data = new HashMap<>();
            data.put("name", name); data.put("phone", phone); data.put("email", email());
            FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                .set(data, SetOptions.merge())
                .addOnSuccessListener(done -> goHome())
                .addOnFailureListener(error -> {
                    btnLogin.setEnabled(true);
                    toast("Tài khoản đã tạo nhưng chưa lưu được hồ sơ. Hãy kiểm tra mạng rồi lưu lại trong mục Cá nhân.");
                    goHome();
                });
        });
    }
    private void goHome() { startActivity(new Intent(this, MainActivity.class)); finish(); }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
}
