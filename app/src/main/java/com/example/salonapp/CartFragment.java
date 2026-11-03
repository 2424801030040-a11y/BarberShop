package com.example.salonapp;
import android.content.Context;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AlertDialog;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.*;
import java.util.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
public class CartFragment extends Fragment {
    private RecyclerView list; private TextCardAdapter adapter; private TextView total; private RadioGroup rg; private Button btnOrder;
    private EditText promoInput;
    private final Map<String, PromotionOffer> promotionOffers = new HashMap<>();
    private String checkoutRequestId;
    private ListenerRegistration paymentListener;
    private AlertDialog paymentDialog;
    private PopupWindow promoPopup;
    private Bitmap paymentQrBitmap;
    private CustomTarget<Bitmap> paymentQrTarget;
    private String pendingPaymentOrderId;
    private String pendingPaymentCode;
    private int paymentRetryAttempt;
    private boolean paymentWarningShown;
    private final Handler paymentRetryHandler = new Handler(Looper.getMainLooper());
    private final Runnable paymentRetry = () -> attachPaymentListener();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.fragment_cart, c, false);
        list = v.findViewById(R.id.rvCart); total = v.findViewById(R.id.tvTotal);
        adapter = new TextCardAdapter();
        adapter.setOnClick(pos -> {
            Item it = Cart.items.get(pos);
            new MaterialAlertDialogBuilder(requireContext()).setTitle(it.name).setItems(new String[]{"Tăng số lượng", "Giảm số lượng", "Xóa khỏi giỏ"}, (d, w) -> {
                if (w == 0) { if (it.stock == null || it.qty < it.stock) it.qty++; else toast("Không đủ hàng trong kho"); } else if (w == 1 && it.qty > 1) it.qty--; else if (w == 2) Cart.items.remove(it);
                refresh();
            }).show();
        });
        ViewModeToggle.bind(requireContext(), list, v.findViewById(R.id.viewModeToggle));
        list.setAdapter(adapter);
        EditText addr = v.findViewById(R.id.etAddress);
        EditText promo = v.findViewById(R.id.etPromo);
        promoInput = promo;
        promotionOffers.clear();
        TextView promoArrow = v.findViewById(R.id.tvPromoArrow);
        View promoAnchor = v.findViewById(R.id.promoFieldContainer);
        List<DocumentSnapshot> savedAddresses = new ArrayList<>();
        List<String> addressRows = new ArrayList<>();
        List<String> promoCodes = new ArrayList<>();
        List<String> promoRows = new ArrayList<>();
        ArrayAdapter<String> promoAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, promoRows);
        ListView promoList = new ListView(requireContext());
        promoList.setAdapter(promoAdapter);
        promoList.setDivider(new android.graphics.drawable.ColorDrawable(Color.parseColor("#E7DFD6")));
        promoList.setDividerHeight(dp(1));
        promoList.setPadding(0, dp(6), 0, dp(6));
        promoList.setClipToPadding(false);
        promoPopup = new PopupWindow(promoList, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        GradientDrawable popupBackground = new GradientDrawable();
        popupBackground.setColor(Color.WHITE);
        popupBackground.setCornerRadius(dp(16));
        popupBackground.setStroke(dp(1), Color.parseColor("#D9D2C5"));
        promoPopup.setBackgroundDrawable(popupBackground);
        promoPopup.setOutsideTouchable(true);
        promoPopup.setElevation(dp(8));
        promoPopup.setOnDismissListener(() -> {
            promoArrow.setContentDescription("Hiện mã khuyến mãi");
            setPromoArrowExpanded(promoArrow, false);
        });
        promoArrow.setOnClickListener(x -> {
            if (promoPopup.isShowing()) {
                promoPopup.dismiss();
            } else if (promoAdapter.isEmpty()) {
                toast("Hiện chưa có mã khuyến mãi khả dụng");
            } else {
                promoPopup.setWidth(promoAnchor.getWidth());
                int popupHeight = Math.min(promoAdapter.getCount() * dp(56) + dp(12), dp(240));
                promoPopup.setHeight(popupHeight);
                promoPopup.showAsDropDown(promoAnchor);
                setPromoArrowExpanded(promoArrow, true);
            }
            promoArrow.setContentDescription(promoPopup.isShowing() ? "Ẩn mã khuyến mãi" : "Hiện mã khuyến mãi");
        });
        promoList.setOnItemClickListener((parent, itemView, position, id) -> {
            if (position >= 0 && position < promoCodes.size()) promo.setText(promoCodes.get(position));
            promoPopup.dismiss();
        });
        promo.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateTotal(); }
            @Override public void afterTextChanged(Editable editable) { }
        });
        rg = v.findViewById(R.id.rgPay);
        btnOrder = v.findViewById(R.id.btnOrder);
        btnOrder.setOnClickListener(x -> {
            if (Cart.items.isEmpty()) { toast("Giỏ hàng trống"); return; }
            String address = addr.getText().toString().trim();
            if (address.length() < 5) { addr.setError("Nhập địa chỉ giao hàng đầy đủ"); return; }
            String code = promo.getText().toString().trim().toUpperCase(Locale.ROOT);
            place(address, code);
        });
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid != null) {
            db.collection("users").document(uid).collection("addresses").get().addOnSuccessListener(sn -> {
                if (!isAdded()) return;
                savedAddresses.clear();
                addressRows.clear();
                String defaultAddress = null;
                for (DocumentSnapshot doc : sn.getDocuments()) {
                    String text = doc.getString("text");
                    if (text == null || text.trim().isEmpty()) continue;
                    String label = doc.getString("label");
                    savedAddresses.add(doc);
                    addressRows.add(label == null || label.trim().isEmpty() ? text : label + ": " + text);
                    if (defaultAddress == null && Boolean.TRUE.equals(doc.getBoolean("isDefault"))) defaultAddress = text;
                }
                if (addr.getText().length() == 0 && defaultAddress != null) addr.setText(defaultAddress);
            }).addOnFailureListener(error -> Log.w("CartFragment", "Could not load saved addresses", error));

            db.collection("promotions").get().addOnSuccessListener(sn -> {
                if (!isAdded()) return;
                promoCodes.clear();
                promoRows.clear();
                long now = System.currentTimeMillis();
                for (DocumentSnapshot doc : sn.getDocuments()) {
                    String code = doc.getString("code");
                    if (code == null || code.trim().isEmpty() || Boolean.FALSE.equals(doc.getBoolean("active"))) continue;
                    if (isPromotionOutsideDates(doc, now)) continue;
                    String name = doc.getString("name");
                    Number percentValue = (Number) doc.get("percent");
                    Number minimumValue = (Number) doc.get("minSubtotal");
                    String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
                    double percent = percentValue == null ? 0 : percentValue.doubleValue();
                    double minimum = minimumValue == null ? 0 : minimumValue.doubleValue();
                    promotionOffers.put(normalizedCode, new PromotionOffer(percent, minimum));
                    promoCodes.add(normalizedCode);
                    promoRows.add(normalizedCode
                            + (name == null || name.trim().isEmpty() ? "" : " · " + name)
                            + (percentValue == null ? "" : " · giảm " + String.format(Locale.getDefault(), "%.0f%%", percent)));
                }
                promoAdapter.notifyDataSetChanged();
                updateTotal();
            }).addOnFailureListener(error -> Log.w("CartFragment", "Could not load promotion codes", error));
        }
        v.findViewById(R.id.btnPick).setOnClickListener(x -> {
            if (savedAddresses.isEmpty()) { toast("Chưa có địa chỉ đã lưu"); return; }
            String[] rows = addressRows.toArray(new String[0]);
            new MaterialAlertDialogBuilder(requireContext()).setItems(rows, (dialog, position) -> {
                if (position >= 0 && position < savedAddresses.size()) addr.setText(savedAddresses.get(position).getString("text"));
            }).show();
        });
        refresh();
        return v;
    }
    private void refresh() {
        List<String> rows = new ArrayList<>();
        for (Item i : Cart.items) rows.add(i.name + "  ×" + i.qty + "  –  " + String.format(Vi.VN, "%,.0f", i.price * i.qty) + " đ");
        adapter.setItems(rows);
        updateTotal();
    }
    private void updateTotal() {
        if (total == null) return;
        double subtotal = Cart.subtotal();
        String code = promoInput == null ? "" : promoInput.getText().toString().trim().toUpperCase(Locale.ROOT);
        PromotionOffer offer = promotionOffers.get(code);
        double amountAfterVoucher = subtotal;
        if (offer != null && subtotal >= offer.minimum && offer.percent >= 0 && offer.percent <= 100) {
            amountAfterVoucher = subtotal - Math.round(subtotal * offer.percent / 100.0);
        }
        total.setText(String.format(Locale.US, "Tạm tính sau voucher: %,.0f đ", amountAfterVoucher));
    }
    private static class PromotionOffer {
        final double percent;
        final double minimum;
        PromotionOffer(double percent, double minimum) { this.percent = percent; this.minimum = minimum; }
    }
    private void setPromoArrowExpanded(View arrow, boolean expanded) {
        float rotation = expanded ? 90f : 0f;
        if (arrow.getRotation() != rotation) arrow.animate().rotation(rotation).setDuration(180).start();
    }
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    private boolean isPromotionOutsideDates(DocumentSnapshot promotion, long now) {
        Long startsAt = promotionDateMillis(promotion.get("startsAt"));
        Long endsAt = promotionDateMillis(promotion.get("endsAt"));
        return (startsAt != null && startsAt > now) || (endsAt != null && endsAt < now);
    }
    private Long promotionDateMillis(Object value) {
        if (value instanceof Timestamp) return ((Timestamp) value).toDate().getTime();
        if (value instanceof Date) return ((Date) value).getTime();
        if (!(value instanceof String)) return null;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX"};
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = format.parse((String) value);
                if (date != null) return date.getTime();
            } catch (java.text.ParseException ignored) { }
        }
        return null;
    }
    /** Supabase validates the Firebase user and rechecks prices, promotions, and stock atomically. */
    private void place(String address, String code) {
        int rb = rg.getCheckedRadioButtonId();
        String method = rb == R.id.rbBank ? "bank" : "cash";
        if (checkoutRequestId == null) checkoutRequestId = UUID.randomUUID().toString();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Item item : Cart.items) {
            Map<String, Object> line = new HashMap<>();
            line.put("productId", item.id);
            line.put("quantity", item.qty);
            items.add(line);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("items", items);
        payload.put("address", address);
        payload.put("payment", method);
        payload.put("promoCode", code);
        payload.put("requestId", checkoutRequestId);
        btnOrder.setEnabled(false);
        btnOrder.setText("Đang xác nhận đơn hàng…");
        if (!Config.isSupabaseConfigured()) {
            toast("Hãy cấu hình Supabase URL và publishable key trong Config.java trước khi đặt hàng.");
            resetOrderButton();
            return;
        }
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            toast("Vui lòng đăng nhập lại.");
            resetOrderButton();
            return;
        }
        FirebaseAuth.getInstance().getCurrentUser().getIdToken(true).addOnSuccessListener(token -> {
            new Thread(() -> {
                String errorMessage = null;
                JSONObject responseJson = null;
                boolean retryWithNewRequestId = false;
                try {
                    Request request = new Request.Builder()
                            .url(Config.SUPABASE_URL + "/functions/v1/create-order")
                            .addHeader("apikey", Config.SUPABASE_PUBLISHABLE_KEY)
                            .addHeader("Authorization", "Bearer " + token.getToken())
                            .post(RequestBody.create(new JSONObject(payload).toString(), MediaType.parse("application/json; charset=utf-8")))
                            .build();
                    try (Response response = new OkHttpClient().newCall(request).execute()) {
                        String body = response.body() == null ? "{}" : response.body().string();
                        responseJson = new JSONObject(body);
                        if (!response.isSuccessful()) {
                            errorMessage = responseJson.optString("error", responseJson.optString("message", "Không thể tạo đơn lúc này."));
                            retryWithNewRequestId = response.code() == 409;
                        }
                    }
                } catch (Exception error) {
                    errorMessage = "Không thể kết nối máy chủ đặt hàng. Vui lòng thử lại.";
                }
                final JSONObject result = responseJson;
                final String failure = errorMessage;
                final boolean newRequest = retryWithNewRequestId;
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (failure != null || result == null) {
                        if (newRequest) checkoutRequestId = null;
                        toast(failure == null ? "Không thể tạo đơn lúc này." : failure);
                    } else {
                        String orderCode = result.optString("orderCode", "");
                        String orderId = result.optString("orderId", "");
                        double serverTotal = result.optDouble("total", -1);
                        if (orderCode.isEmpty() || orderId.isEmpty() || serverTotal < 0) {
                            toast("Máy chủ trả về thông tin đơn hàng không hợp lệ.");
                        } else {
                            Cart.items.clear();
                            checkoutRequestId = null;
                            refresh();
                            done(method, orderId, orderCode, serverTotal);
                        }
                    }
                    resetOrderButton();
                });
            }).start();
        }).addOnFailureListener(error -> {
            toast("Không xác thực được tài khoản. Vui lòng đăng nhập lại.");
            resetOrderButton();
        });
    }
    private void resetOrderButton() {
        if (isAdded() && btnOrder != null) { btnOrder.setEnabled(true); btnOrder.setText("Đặt hàng"); }
    }
    /** Cash: confirm immediately. Bank transfer: show the QR; SePay marks the order paid after a verified transfer. */
    private void done(String method, String orderId, String code, double amount) {
        if (!isAdded()) return;
        Context ctx = requireContext(); FragmentManager fm = getParentFragmentManager();
        if (method.equals("cash")) { Toast.makeText(ctx, "Đặt hàng thành công! Vui lòng thanh toán bằng tiền mặt.", Toast.LENGTH_LONG).show(); fm.popBackStack(); return; }
        String amt = String.format(Vi.VN, "%,.0f đ", amount);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        if (method.equals("bank")) {
            ImageView qr = new ImageView(ctx);
            LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(220), dp(220));
            qrParams.gravity = Gravity.CENTER_HORIZONTAL;
            qrParams.bottomMargin = dp(12);
            qr.setLayoutParams(qrParams);
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            String qrUrl = buildQrUrl(code, amount);
            box.addView(qr);

            Button downloadQr = new Button(ctx);
            downloadQr.setText("Đang chuẩn bị mã QR…");
            downloadQr.setEnabled(false);
            downloadQr.setAllCaps(false);
            LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            downloadParams.bottomMargin = dp(12);
            downloadQr.setLayoutParams(downloadParams);
            downloadQr.setOnClickListener(view -> saveCachedQrInBackground(ctx, code));
            box.addView(downloadQr);

            paymentQrTarget = new CustomTarget<Bitmap>() {
                @Override public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                    qr.setImageBitmap(resource);
                    paymentQrBitmap = resource.copy(Bitmap.Config.ARGB_8888, false);
                    downloadQr.setEnabled(paymentQrBitmap != null);
                    downloadQr.setText(paymentQrBitmap == null ? "Không thể chuẩn bị mã QR" : "Tải mã QR");
                }
                @Override public void onLoadCleared(@Nullable Drawable placeholder) {
                    qr.setImageDrawable(placeholder);
                }
            };
            Glide.with(this).asBitmap().load(qrUrl).into(paymentQrTarget);

            LinearLayout infoCard = new LinearLayout(ctx);
            infoCard.setOrientation(LinearLayout.VERTICAL);
            infoCard.setPadding(dp(16), dp(6), dp(16), dp(6));
            GradientDrawable cardBackground = new GradientDrawable();
            cardBackground.setColor(Color.WHITE);
            cardBackground.setCornerRadius(dp(16));
            cardBackground.setStroke(dp(1), Color.parseColor("#E7DFD6"));
            infoCard.setBackground(cardBackground);
            addPaymentInfoRow(ctx, infoCard, "NGÂN HÀNG", Config.BANK_ID, false);
            addPaymentInfoRow(ctx, infoCard, "SỐ TÀI KHOẢN ẢO (VA)", Config.BANK_ACCOUNT, false);
            addPaymentInfoRow(ctx, infoCard, "CHỦ TÀI KHOẢN", Config.BANK_ACCOUNT_NAME, false);
            addPaymentInfoRow(ctx, infoCard, "SỐ TIỀN CẦN CHUYỂN", amt, true);
            addPaymentInfoRow(ctx, infoCard, "NỘI DUNG CHUYỂN KHOẢN", code, false);
            box.addView(infoCard);

            TextView note = new TextView(ctx);
            note.setText("Vui lòng chuyển đúng số tiền và hoàn tất trong 15 phút để giữ hàng. Màn hình tự đóng sau khi hệ thống xác nhận đã nhận tiền.");
            note.setTextSize(13);
            note.setTextColor(Color.parseColor("#766F69"));
            note.setPadding(dp(2), dp(12), dp(2), 0);
            box.addView(note);
        }
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(box, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new MaterialAlertDialogBuilder(ctx).setTitle("Hoàn tất thanh toán").setView(scroll).setPositiveButton("Đóng", null).create();
        paymentDialog = dialog;
        dialog.setOnDismissListener(d -> {
            stopPaymentMonitoring();
            clearPaymentQrCache();
            if (paymentDialog == dialog) paymentDialog = null;
            if (fm.getBackStackEntryCount() > 0) fm.popBackStack();
        });
        dialog.show();
        if (method.equals("bank")) {
            startPaymentMonitoring(orderId, code);
        }
    }
    private String buildQrUrl(String code, double amount) {
        return "https://vietqr.app/img?acc=" + Uri.encode(Config.BANK_ACCOUNT) + "&bank=" + Uri.encode(Config.BANK_ID)
                + "&amount=" + (long) amount + "&des=" + Uri.encode(code) + "&template=compact&showinfo=true&holder="
                + Uri.encode(Config.BANK_ACCOUNT_NAME);
    }
    private void saveCachedQrInBackground(Context context, String code) {
        if (paymentQrBitmap == null || paymentQrBitmap.isRecycled()) {
            Toast.makeText(context, "Mã QR chưa sẵn sàng. Vui lòng thử lại.", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bitmap = paymentQrBitmap.copy(Bitmap.Config.ARGB_8888, false);
        if (bitmap == null) {
            Toast.makeText(context, "Không thể chuẩn bị mã QR để lưu.", Toast.LENGTH_SHORT).show();
            return;
        }
        String safeCode = code.replaceAll("[^A-Za-z0-9_-]", "");
        String filename = "Ma_QR_" + (safeCode.isEmpty() ? "thanh_toan" : safeCode) + ".png";
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            boolean saved = false;
            Uri outputUri = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    outputUri = appContext.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (outputUri == null) throw new IllegalStateException("Could not create Downloads entry");
                    try (OutputStream output = appContext.getContentResolver().openOutputStream(outputUri)) {
                        saved = output != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    }
                    if (saved) {
                        ContentValues ready = new ContentValues();
                        ready.put(MediaStore.Downloads.IS_PENDING, 0);
                        appContext.getContentResolver().update(outputUri, ready, null, null);
                    }
                } else {
                    File downloads = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (downloads == null) throw new IllegalStateException("Downloads directory unavailable");
                    if (!downloads.exists() && !downloads.mkdirs()) throw new IllegalStateException("Could not create Downloads directory");
                    File target = new File(downloads, filename);
                    try (OutputStream output = new FileOutputStream(target)) {
                        saved = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    }
                }
                if (!saved && outputUri != null) appContext.getContentResolver().delete(outputUri, null, null);
            } catch (Exception error) {
                Log.e("CartFragment", "Could not save cached payment QR", error);
                if (outputUri != null) appContext.getContentResolver().delete(outputUri, null, null);
            } finally {
                bitmap.recycle();
            }
            boolean result = saved;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), result ? "Đã lưu mã QR vào thư mục Tệp đã tải xuống." : "Không thể lưu mã QR.", Toast.LENGTH_LONG).show();
            });
        }, "save-payment-qr").start();
    }
    private void clearPaymentQrCache() {
        if (paymentQrTarget != null) {
            Glide.with(this).clear(paymentQrTarget);
            paymentQrTarget = null;
        }
        if (paymentQrBitmap != null) {
            if (!paymentQrBitmap.isRecycled()) paymentQrBitmap.recycle();
            paymentQrBitmap = null;
        }
    }
    private void addPaymentInfoRow(Context context, LinearLayout card, String label, String value, boolean highlight) {
        if (card.getChildCount() > 0) {
            View divider = new View(context);
            divider.setBackgroundColor(Color.parseColor("#EEE8E1"));
            card.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        if (highlight) {
            GradientDrawable amountBackground = new GradientDrawable();
            amountBackground.setColor(Color.parseColor("#F8ECE9"));
            amountBackground.setCornerRadius(dp(12));
            row.setBackground(amountBackground);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
        }
        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(11);
        labelView.setTextColor(Color.parseColor("#837A73"));
        TextView valueView = new TextView(context);
        valueView.setText(value == null ? "—" : value);
        valueView.setTextSize(highlight ? 18 : 14);
        valueView.setTextColor(highlight ? Color.parseColor("#8D3C32") : Color.parseColor("#29211D"));
        valueView.setTypeface(null, android.graphics.Typeface.BOLD);
        if (label.startsWith("NỘI DUNG")) valueView.setTextIsSelectable(true);
        row.addView(labelView);
        row.addView(valueView);
        card.addView(row);
    }
    private void startPaymentMonitoring(String orderId, String code) {
        stopPaymentMonitoring();
        pendingPaymentOrderId = orderId;
        pendingPaymentCode = code;
        paymentRetryAttempt = 0;
        paymentWarningShown = false;
        attachPaymentListener();
    }
    private void attachPaymentListener() {
        if (!isAdded() || pendingPaymentOrderId == null || paymentDialog == null || !paymentDialog.isShowing()) return;
        stopPaymentListener();
        paymentListener = db.collection("orders").document(pendingPaymentOrderId).addSnapshotListener((snapshot, error) -> {
            if (!isAdded()) return;
            if (error != null) {
                Log.e("CartFragment", "Could not monitor payment status for order " + pendingPaymentCode, error);
                stopPaymentListener();
                if (!paymentWarningShown) {
                    Toast.makeText(requireContext(), "Đang kết nối lại để theo dõi thanh toán. Bạn có thể xem trạng thái trong Lịch sử đơn hàng.", Toast.LENGTH_LONG).show();
                    paymentWarningShown = true;
                }
                long delayMs = Math.min(1_000L << Math.min(paymentRetryAttempt++, 5), 30_000L);
                paymentRetryHandler.removeCallbacks(paymentRetry);
                paymentRetryHandler.postDelayed(paymentRetry, delayMs);
                return;
            }
            paymentRetryAttempt = 0;
            if (snapshot == null || !snapshot.exists()) {
                Log.w("CartFragment", "Payment listener received no order document for " + pendingPaymentOrderId);
                return;
            }
            if ("paid".equals(snapshot.getString("paymentStatus"))) {
                stopPaymentMonitoring();
                Toast.makeText(requireContext(), "Thanh toán thành công! Đơn hàng đã được xác nhận.", Toast.LENGTH_LONG).show();
                if (paymentDialog != null && paymentDialog.isShowing()) paymentDialog.dismiss();
            } else if ("canceled".equals(snapshot.getString("status"))) {
                stopPaymentMonitoring();
                Toast.makeText(requireContext(), "Đơn hàng đã hết hạn hoặc bị hủy. Nếu bạn đã chuyển tiền, vui lòng liên hệ cửa hàng.", Toast.LENGTH_LONG).show();
                if (paymentDialog != null && paymentDialog.isShowing()) paymentDialog.dismiss();
            }
        });
    }
    private void stopPaymentListener() {
        if (paymentListener != null) { paymentListener.remove(); paymentListener = null; }
    }
    private void stopPaymentMonitoring() {
        paymentRetryHandler.removeCallbacks(paymentRetry);
        stopPaymentListener();
        pendingPaymentOrderId = null;
        pendingPaymentCode = null;
    }
    @Override public void onDestroyView() {
        stopPaymentMonitoring();
        clearPaymentQrCache();
        if (promoPopup != null) {
            promoPopup.dismiss();
            promoPopup = null;
        }
        if (paymentDialog != null) {
            AlertDialog dialog = paymentDialog;
            paymentDialog = null;
            dialog.setOnDismissListener(null);
            if (dialog.isShowing()) dialog.dismiss();
        }
        super.onDestroyView();
    }
    private void toast(String m) { Toast.makeText(getContext(), m, Toast.LENGTH_LONG).show(); }
}
