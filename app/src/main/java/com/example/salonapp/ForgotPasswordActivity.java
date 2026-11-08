package com.example.salonapp;

import android.os.Bundle;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;

public class ForgotPasswordActivity extends AppCompatActivity {
    private EditText emailInput;
    private Button sendButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);
        emailInput = findViewById(R.id.etResetEmail);
        sendButton = findViewById(R.id.btnSendReset);
        sendButton.setOnClickListener(v -> sendResetEmail());
        findViewById(R.id.btnBackToLogin).setOnClickListener(v -> finish());
    }

    private void sendResetEmail() {
        String email = emailInput.getText().toString().trim();
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError("Nhập địa chỉ email hợp lệ");
            emailInput.requestFocus();
            return;
        }
        sendButton.setEnabled(false);
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
            .addOnSuccessListener(unused -> {
                Toast.makeText(this, "Nếu email có tài khoản, liên kết đặt lại mật khẩu sẽ được gửi đến hộp thư.", Toast.LENGTH_LONG).show();
                finish();
            })
            .addOnFailureListener(error -> {
                sendButton.setEnabled(true);
                Toast.makeText(this, "Chưa gửi được email. Hãy kiểm tra kết nối và thử lại.", Toast.LENGTH_LONG).show();
            });
    }
}
