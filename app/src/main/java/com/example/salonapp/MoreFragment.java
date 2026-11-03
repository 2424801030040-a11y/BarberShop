package com.example.salonapp;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.*;
public class MoreFragment extends Fragment {
    static final String SHOP_PHONE = "0900000000";
    static final String FAQ = "Hủy lịch: vui lòng hủy hoặc đổi lịch trước giờ hẹn ít nhất 2 giờ.\n\n"
        + "Trả hàng: sản phẩm chưa mở có thể trả trong 7 ngày kèm hóa đơn.\n\n"
        + "Đổi hàng: sản phẩm lỗi hoặc giao sai được đổi miễn phí trong 7 ngày.\n\n"
        + "Thanh toán: tiền mặt khi nhận hàng hoặc tại cửa hàng, hoặc chuyển khoản.";
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.fragment_more, c, false);
        RecyclerView actions = v.findViewById(R.id.rvMoreActions);
        GridLayoutManager grid = new GridLayoutManager(requireContext(), 2);
        grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return position >= 4 ? 2 : 1; }
        });
        actions.setLayoutManager(grid);
        MoreActionAdapter adapter = new MoreActionAdapter();
        adapter.setOnClick(this::onActionClick);
        actions.setAdapter(adapter);
        return v;
    }
    private void onActionClick(int position) {
        if (position == 0) open(ListFragment.of("branches"));
        else if (position == 1) open(ListFragment.of("promotions"));
        else if (position == 2) new MaterialAlertDialogBuilder(requireContext()).setTitle("Câu hỏi thường gặp & chính sách").setMessage(FAQ).setPositiveButton("OK", null).show();
        else if (position == 3) startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + SHOP_PHONE)));
        else if (position == 4) contact();
        else if (position == 5) open(new AppointmentsFragment());
    }
    private void contact() {
        Context context = requireContext();
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        FirebaseAuth auth = FirebaseAuth.getInstance();
        String fromValue = auth.getCurrentUser() == null ? "" : auth.getCurrentUser().getEmail();
        if (fromValue == null || fromValue.isEmpty())
            fromValue = auth.getCurrentUser() == null ? "" : auth.getCurrentUser().getPhoneNumber();
        EditText from = composeField(form, "From", "Email hoặc số điện thoại", fromValue, 220, false);
        EditText to = composeField(form, "To", "Cửa hàng", "Chi Phèo BarberShop", 120, true);
        EditText subject = composeField(form, "Subject", "Tiêu đề yêu cầu", "", 150, false);
        EditText content = composeField(form, "Content", "Nội dung tin nhắn", "", 1400, false);
        content.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.setGravity(Gravity.TOP | Gravity.START);
        content.setMinLines(5);
        content.setMaxLines(8);
        content.setPadding(dp(12), dp(12), dp(12), dp(12));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.addView(form);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
            .setTitle("Soạn yêu cầu liên hệ")
            .setView(scroll)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Gửi", null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String sender = from.getText().toString().trim();
            String recipient = to.getText().toString().trim();
            String topic = subject.getText().toString().trim();
            String body = content.getText().toString().trim();
            if (sender.isEmpty()) { from.setError("Vui lòng nhập email hoặc số điện thoại"); from.requestFocus(); return; }
            if (topic.isEmpty()) { subject.setError("Vui lòng nhập tiêu đề"); subject.requestFocus(); return; }
            if (body.isEmpty()) { content.setError("Vui lòng nhập nội dung"); content.requestFocus(); return; }
            String message = "From: " + sender + "\nTo: " + recipient + "\nSubject: " + topic + "\n\n" + body;
            if (message.length() > 2000) { content.setError("Nội dung quá dài"); return; }
            Map<String, Object> data = new HashMap<>();
            data.put("userId", auth.getUid());
            data.put("from", sender);
            data.put("to", recipient);
            data.put("subject", topic);
            data.put("content", body);
            data.put("message", message);
            data.put("status", "open");
            data.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
            button.setEnabled(false);
            FirebaseFirestore.getInstance().collection("contacts").add(data)
                .addOnSuccessListener(result -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), "Đã gửi yêu cầu liên hệ", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .addOnFailureListener(error -> {
                    if (!isAdded()) return;
                    button.setEnabled(true);
                    Toast.makeText(requireContext(), "Không gửi được yêu cầu. Vui lòng thử lại.", Toast.LENGTH_LONG).show();
                });
        }));
        dialog.show();
    }
    private EditText composeField(LinearLayout form, String label, String hint, String value, int maxLength, boolean readOnly) {
        TextView labelView = new TextView(requireContext());
        labelView.setText(label);
        labelView.setTextColor(Color.parseColor("#766B62"));
        labelView.setTextSize(12);
        labelView.setPadding(dp(2), dp(8), dp(2), dp(4));
        form.addView(labelView);
        EditText input = new EditText(requireContext());
        input.setSingleLine(!label.equals("Content"));
        input.setTextSize(15);
        input.setTextColor(Color.parseColor("#29211D"));
        input.setHintTextColor(Color.parseColor("#9A918A"));
        input.setHint(hint);
        input.setText(value);
        input.setBackgroundResource(R.drawable.bg_field);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        if (readOnly) { input.setFocusable(false); input.setClickable(false); }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(4);
        form.addView(input, params);
        return input;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void open(Fragment f) { getParentFragmentManager().beginTransaction().replace(R.id.container, f).addToBackStack(null).commit(); }
}
