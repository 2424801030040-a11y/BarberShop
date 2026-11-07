package com.example.salonapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONObject;

/** Customer promotions, loyalty balance, redeemable vouchers and shop announcements. */
public class PromotionsFragment extends Fragment {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final List<ListenerRegistration> listeners = new ArrayList<>();
    private LinearLayout promotionsList, vouchersList, eventsList, redeemedList;
    private TextView pointsValue;
    private int pointsBalance;
    private String uid;

    @Nullable @Override public View onCreateView(@NonNull android.view.LayoutInflater inflater,
            @Nullable ViewGroup parent, @Nullable Bundle state) {
        Context context = requireContext();
        uid = FirebaseAuth.getInstance().getUid();
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 241, 233));
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(24));
        scroll.addView(page);

        TextView title = text("Khuyến mãi & điểm thưởng", 23, "#29211D", true);
        page.addView(title);
        TextView intro = text("Tích điểm sau mỗi hóa đơn và đổi điểm lấy voucher ưu đãi.", 14, "#766B62", false);
        intro.setPadding(0, dp(5), 0, dp(14));
        page.addView(intro);

        LinearLayout balance = card();
        balance.setBackground(roundRect("#8D3C32", "#8D3C32", 18));
        TextView balanceTitle = text("Điểm thưởng của bạn", 15, "#F7E9E2", true);
        balance.addView(balanceTitle);
        pointsValue = text("… điểm", 30, "#FFFFFF", true);
        pointsValue.setPadding(0, dp(4), 0, dp(2));
        balance.addView(pointsValue);
        balance.addView(text("Cứ mỗi 10.000 ₫ trên hóa đơn hoàn tất, bạn nhận 1 điểm.", 13, "#FFFFFF", false));
        addCard(page, balance);

        promotionsList = addSection(page, "Mã khuyến mãi của cửa hàng");
        eventsList = addSection(page, "Tin mới từ cửa hàng");
        vouchersList = addSection(page, "Đổi điểm lấy voucher");
        redeemedList = addSection(page, "Voucher của tôi");
        if (uid == null) {
            pointsValue.setText("Đăng nhập để xem điểm");
            addLine(promotionsList, "Vui lòng đăng nhập để xem ưu đãi.");
            addLine(vouchersList, "Vui lòng đăng nhập để đổi voucher.");
        }
        return scroll;
    }

    @Override public void onStart() {
        super.onStart();
        if (pointsValue == null) return;
        if (uid != null) listeners.add(db.collection("users").document(uid).addSnapshotListener((snapshot, error) -> {
            if (!isAdded() || pointsValue == null) return;
            if (error != null) { pointsValue.setText("Không tải được điểm"); return; }
            Long balance = snapshot == null ? null : snapshot.getLong("loyaltyPoints");
            pointsBalance = balance == null ? 0 : Math.max(0, balance.intValue());
            pointsValue.setText(String.format(Locale.getDefault(), "%d điểm", pointsBalance));
            renderVouchers();
        }));
        listeners.add(db.collection("promotions").addSnapshotListener((snapshot, error) -> {
            if (!isAdded() || promotionsList == null) return;
            promotionsList.removeAllViews();
            if (error != null || snapshot == null) { addLine(promotionsList, "Không tải được mã khuyến mãi."); return; }
            int count = 0;
            for (DocumentSnapshot item : snapshot.getDocuments()) {
                if (!isCurrent(item)) continue;
                count++;
                addOffer(promotionsList, item.getString("name"), item.getString("description"),
                        item.getString("code"), item.get("percent"), item.get("discountAmount"), null, item.get("minSubtotal"));
            }
            if (count == 0) addLine(promotionsList, "Hiện chưa có mã khuyến mãi đang áp dụng.");
        }));
        listeners.add(db.collection("shopEvents").addSnapshotListener((snapshot, error) -> {
            if (!isAdded() || eventsList == null) return;
            eventsList.removeAllViews();
            if (error != null || snapshot == null) { addLine(eventsList, "Không tải được tin từ cửa hàng."); return; }
            List<DocumentSnapshot> events = new ArrayList<>(snapshot.getDocuments());
            events.sort((a, b) -> Long.compare(eventStart(b), eventStart(a)));
            int count = 0;
            for (DocumentSnapshot event : events) {
                if (!Boolean.TRUE.equals(event.getBoolean("active")) || eventEnd(event) < System.currentTimeMillis()) continue;
                count++;
                LinearLayout notice = card();
                notice.setBackground(roundRect("#FFFEFC", "#E9DFD0", 16));
                String title = event.getString("title");
                notice.addView(text(title == null ? "Thông báo từ cửa hàng" : title, 16, "#8D3C32", true));
                TextView body = text(event.getString("body") == null ? "" : event.getString("body"), 14, "#29211D", false);
                body.setPadding(0, dp(5), 0, dp(6));
                notice.addView(body);
                long start = eventStart(event), end = eventEnd(event);
                String date = start > System.currentTimeMillis() ? "Bắt đầu " + date(start) : "Đang diễn ra";
                if (end > 0) date += " · đến " + date(end);
                notice.addView(text(date, 12, "#766B62", false));
                addCard(eventsList, notice);
            }
            if (count == 0) addLine(eventsList, "Chưa có sự kiện mới.");
        }));
        listeners.add(db.collection("rewardVouchers").addSnapshotListener((snapshot, error) -> {
            if (!isAdded() || vouchersList == null) return;
            vouchersList.removeAllViews();
            if (error != null || snapshot == null) { addLine(vouchersList, "Không tải được danh sách voucher."); return; }
            for (DocumentSnapshot voucher : snapshot.getDocuments()) {
                if (!isCurrent(voucher)) continue;
                addOffer(vouchersList, voucher.getString("name"), voucher.getString("description"), null,
                        voucher.get("percent"), voucher.get("discountAmount"), voucher.get("pointsCost"), voucher.get("minSubtotal"));
                View row = vouchersList.getChildAt(vouchersList.getChildCount() - 1);
                Button redeem = new Button(requireContext());
                int cost = number(voucher.get("pointsCost"));
                redeem.setText("Đổi " + cost + " điểm");
                redeem.setAllCaps(false);
                redeem.setEnabled(pointsBalance >= cost && uid != null);
                redeem.setOnClickListener(view -> redeem(voucher.getId(), redeem));
                ((LinearLayout) row).addView(redeem);
            }
            if (vouchersList.getChildCount() == 0) addLine(vouchersList, "Cửa hàng chưa có voucher đổi điểm.");
        }));
        if (uid != null) listeners.add(db.collection("users").document(uid).collection("vouchers").addSnapshotListener((snapshot, error) -> {
            if (!isAdded() || redeemedList == null) return;
            redeemedList.removeAllViews();
            if (error != null || snapshot == null) { addLine(redeemedList, "Không tải được voucher của bạn."); return; }
            int count = 0;
            for (DocumentSnapshot item : snapshot.getDocuments()) {
                count++;
                String code = item.getString("code"), name = item.getString("name");
                String status = "used".equals(item.getString("status")) ? "Đã sử dụng" : "available".equals(item.getString("status")) ? "Sẵn sàng sử dụng" : "Hết hạn";
                LinearLayout row = card(); row.setBackground(roundRect("#FFFEFC", "#E9DFD0", 16));
                row.addView(text(name == null ? "Voucher" : name, 15, "#29211D", true));
                row.addView(text(code == null ? "" : code + " · " + status, 14, "#766B62", false));
                if (code != null && "available".equals(item.getString("status"))) {
                    Button copy = new Button(requireContext()); copy.setText("Sao chép mã"); copy.setAllCaps(false);
                    copy.setOnClickListener(v -> copyCode(code)); row.addView(copy);
                }
                addCard(redeemedList, row);
            }
            if (count == 0) addLine(redeemedList, "Bạn chưa đổi voucher nào.");
        }));
    }

    private void renderVouchers() {
        if (vouchersList == null) return;
        for (int i = 0; i < vouchersList.getChildCount(); i++) {
            View child = vouchersList.getChildAt(i);
            if (child instanceof LinearLayout) for (int j = 0; j < ((LinearLayout) child).getChildCount(); j++) {
                View inner = ((LinearLayout) child).getChildAt(j);
                if (inner instanceof Button) {
                    Button button = (Button) inner;
                    try { button.setEnabled(uid != null && pointsBalance >= Integer.parseInt(button.getText().toString().replaceAll("\\D", ""))); }
                    catch (NumberFormatException ignored) { }
                }
            }
        }
    }

    private void redeem(String voucherId, Button button) {
        if (uid == null || !isAdded()) return;
        button.setEnabled(false);
        try {
            JSONObject payload = new JSONObject().put("action", "redeem").put("voucherId", voucherId);
            SupabaseFunctions.post(requireContext(), "loyalty", payload, (result, error) -> {
                if (!isAdded()) return;
                if (error != null || result == null) {
                    button.setEnabled(true);
                    toast(error == null ? "Chưa đổi được voucher. Vui lòng thử lại." : error);
                    return;
                }
                String code = result.optString("code", "");
                new MaterialAlertDialogBuilder(requireContext()).setTitle("Đổi voucher thành công")
                        .setMessage("Mã voucher của bạn: " + code + "\n\nHãy nhập mã này khi thanh toán đơn hàng.")
                        .setPositiveButton("Đã hiểu", (dialog, which) -> copyCode(code)).show();
            });
        } catch (org.json.JSONException error) {
            button.setEnabled(true); toast("Không tạo được yêu cầu đổi điểm.");
        }
    }

    private boolean isCurrent(DocumentSnapshot document) {
        if (Boolean.FALSE.equals(document.getBoolean("active"))) return false;
        long starts = dateMillis(document.get("startsAt")), ends = dateMillis(document.get("endsAt")), now = System.currentTimeMillis();
        return (starts == 0 || starts <= now) && (ends == 0 || ends >= now);
    }
    private long eventStart(DocumentSnapshot event) { return dateMillis(event.get("startsAt")); }
    private long eventEnd(DocumentSnapshot event) { return dateMillis(event.get("endsAt")); }
    private long dateMillis(Object value) {
        if (value instanceof Timestamp) return ((Timestamp) value).toDate().getTime();
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) try { return java.time.Instant.parse((String) value).toEpochMilli(); } catch (Exception ignored) { }
        return 0;
    }
    private String date(long millis) {
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("vi", "VN"));
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        return formatter.format(new Date(millis));
    }
    private void addOffer(LinearLayout target, String name, String description, String code,
                          Object percent, Object discountAmount, Object points, Object minimum) {
        LinearLayout row = card(); row.setBackground(roundRect("#FFFEFC", "#E9DFD0", 16));
        row.addView(text(name == null ? "Ưu đãi" : name, 16, "#29211D", true));
        String summary = "Giảm " + (number(discountAmount) > 0
                ? String.format(Locale.getDefault(), "%,d ₫", number(discountAmount))
                : number(percent) + "%");
        if (points != null) summary += " · " + number(points) + " điểm";
        if (number(minimum) > 0) summary += " · Đơn từ " + String.format(Locale.getDefault(), "%,d ₫", number(minimum));
        row.addView(text(summary, 14, "#8D3C32", true));
        if (description != null && !description.trim().isEmpty()) row.addView(text(description, 13, "#766B62", false));
        if (code != null && !code.isEmpty()) {
            Button copy = new Button(requireContext()); copy.setText("Mã: " + code + " · Sao chép"); copy.setAllCaps(false);
            copy.setOnClickListener(v -> copyCode(code)); row.addView(copy);
        }
        addCard(target, row);
    }
    private int number(Object value) { return value instanceof Number ? Math.max(0, ((Number) value).intValue()) : 0; }
    private void copyCode(String code) {
        if (code == null || code.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Mã voucher", code)); toast("Đã sao chép mã voucher.");
    }
    private LinearLayout addSection(LinearLayout parent, String title) {
        TextView heading = text(title, 18, "#29211D", true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2); headingParams.topMargin = dp(15); headingParams.bottomMargin = dp(5);
        parent.addView(heading, headingParams);
        LinearLayout section = new LinearLayout(requireContext()); section.setOrientation(LinearLayout.VERTICAL); parent.addView(section);
        return section;
    }
    private LinearLayout card() { LinearLayout layout = new LinearLayout(requireContext()); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(14), dp(12), dp(14), dp(12)); return layout; }
    private void addCard(LinearLayout parent, View child) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(7); parent.addView(child, params); }
    private void addLine(LinearLayout parent, String message) { TextView line = text(message, 14, "#766B62", false); line.setPadding(dp(4), dp(8), dp(4), dp(8)); parent.addView(line); }
    private TextView text(String value, int size, String color, boolean bold) { TextView v = new TextView(requireContext()); v.setText(value); v.setTextSize(size); v.setTextColor(Color.parseColor(color)); if (bold) v.setTypeface(null, Typeface.BOLD); return v; }
    private GradientDrawable roundRect(String fill, String stroke, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(Color.parseColor(fill)); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), Color.parseColor(stroke)); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { if (getContext() != null) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show(); }

    @Override public void onStop() { for (ListenerRegistration listener : listeners) listener.remove(); listeners.clear(); super.onStop(); }
    @Override public void onDestroyView() { promotionsList = null; vouchersList = null; eventsList = null; redeemedList = null; pointsValue = null; super.onDestroyView(); }
}
