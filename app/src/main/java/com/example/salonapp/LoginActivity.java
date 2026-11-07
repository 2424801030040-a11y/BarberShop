package com.example.salonapp;
import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.credentials.*;
import androidx.credentials.exceptions.GetCredentialException;
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
        requestGoogleCredential(false);
    }

    /** First try the account picker, then use Google's explicit button flow if it has no entry. */
    private void requestGoogleCredential(boolean explicitButtonFlow) {
        CredentialOption option;
        if (explicitButtonFlow) {
            option = new GetSignInWithGoogleOption.Builder(getString(R.string.default_web_client_id)).build();
        } else {
            option = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(getString(R.string.default_web_client_id))
                .build();
        }
        GetCredentialRequest req = new GetCredentialRequest.Builder().addCredentialOption(option).build();
        CredentialManager.create(this).getCredentialAsync(this, req, null, ContextCompat.getMainExecutor(this),
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override public void onResult(GetCredentialResponse r) {
                    try {
                        Credential c = r.getCredential();
                        if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                            GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(((CustomCredential) c).getData());
                            auth.signInWithCredential(GoogleAuthProvider.getCredential(g.getIdToken(), null))
                                .addOnSuccessListener(x -> goHome()).addOnFailureListener(e -> toast(describeGoogleError(e)));
                        } else {
                            toast("Google did not return a sign-in token. Please try again.");
                        }
                    } catch (Exception e) { toast(e.getMessage()); }
                }
                @Override public void onError(GetCredentialException e) {
                    if (!explicitButtonFlow && e instanceof NoCredentialException) {
                        requestGoogleCredential(true);
                        return;
                    }
                    if (e instanceof NoCredentialException) {
                        toast("No Google account is available on this device. Add one in Android Settings > Passwords & accounts, then try again.");
                    } else {
                        toast(describeGoogleError(e));
                    }
                }
            });
    }
    private String describeGoogleError(Exception error) {
        String detail = error == null || error.getMessage() == null ? "" : error.getMessage();
        String normalized = detail.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("[16]") || normalized.contains("reauth")) {
            return "Google needs you to re-authenticate the account on this emulator. In Android Settings > Passwords & accounts, remove and add the Google account again, then retry. Also check internet access and automatic date/time.";
        }
        if (normalized.contains("[10]") || normalized.contains("developer_error")) {
            return "Google OAuth configuration was rejected. Check that Google sign-in is enabled in Firebase Authentication and that the Android and Web OAuth clients belong to this Firebase project.";
        }
        return "Google sign-in failed: " + (detail.isEmpty() ? "Please try again." : detail);
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
