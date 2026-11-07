package com.example.salonapp;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.*;
import java.util.*;
public class ReviewsFragment extends Fragment {
    private String targetId, targetName, userId;
    private TextCardAdapter adapter;
    private TextView titleView;
    private MaterialButton reviewButton;
    private List<DocumentSnapshot> reviews = new ArrayList<>();
    private List<DocumentSnapshot> eligibleOrders = new ArrayList<>();
    public static ReviewsFragment of(String id, String name) { return of(id, name, "service"); }
    public static ReviewsFragment of(String id, String name, String type) {
        ReviewsFragment f = new ReviewsFragment(); Bundle b = new Bundle(); b.putString("id", id); b.putString("name", name); b.putString("type", type); f.setArguments(b); return f;
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.fragment_appts, c, false);
        titleView = v.findViewById(R.id.tvTitle);
        RecyclerView list = v.findViewById(R.id.rvList);
        adapter = new TextCardAdapter();
        ViewModeToggle.bind(requireContext(), list, v.findViewById(R.id.viewModeToggle));
        list.setAdapter(adapter);
        targetId = getArguments().getString("id");
        targetName = getArguments().getString("name");
        userId = FirebaseAuth.getInstance().getUid();
        titleView.setText(targetName);

        // The screen is shared by service and product reviews. Only products get a purchase-verified CTA.
        ViewGroup root = (ViewGroup) v;
        reviewButton = new MaterialButton(requireContext());
        reviewButton.setText("Đánh giá sản phẩm đã mua");
        reviewButton.setVisibility(View.GONE);
        int margin = Math.round(8 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams reviewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reviewParams.setMargins(0, margin, 0, margin);
        int toggleIndex = root.indexOfChild(v.findViewById(R.id.viewModeToggle));
        root.addView(reviewButton, Math.max(1, toggleIndex), reviewParams);
        reviewButton.setOnClickListener(view -> chooseOrderToReview());

        FirebaseFirestore.getInstance().collection("reviews").whereEqualTo("targetId", targetId).get().addOnSuccessListener(sn -> {
            reviews = sn.getDocuments();
            List<String> rows = new ArrayList<>(); double sum = 0;
            for (DocumentSnapshot d : reviews) {
                Double r = d.getDouble("rating"); sum += r == null ? 0 : r;
                String reviewer = d.getString("userName");
                String comment = d.getString("comment");
                rows.add("★ " + (r == null ? 0 : r.intValue()) + (reviewer == null || reviewer.isEmpty() ? "" : "  ·  " + reviewer)
                        + (comment == null || comment.trim().isEmpty() ? "" : "\n" + comment.trim()));
            }
            if (rows.isEmpty()) rows.add("Chưa có đánh giá");
            else titleView.setText(String.format(Locale.US, "%s  ★ %.1f (%d)", targetName, sum / reviews.size(), reviews.size()));
            if (isAdded()) { adapter.setItems(rows); updateReviewButton(); }
        });
        if ("product".equals(getArguments().getString("type")) && userId != null) loadEligibleOrders();
        else if ("product".equals(getArguments().getString("type"))) {
            reviewButton.setVisibility(View.VISIBLE);
            reviewButton.setText("Đăng nhập để đánh giá sản phẩm đã mua");
            reviewButton.setEnabled(false);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private void loadEligibleOrders() {
        FirebaseFirestore.getInstance().collection("orders").whereEqualTo("userId", userId).get()
                .addOnSuccessListener(snapshot -> {
                    if (!isAdded()) return;
                    eligibleOrders.clear();
                    for (DocumentSnapshot order : snapshot.getDocuments()) {
                        if (!"completed".equals(order.getString("status"))
                                || !"paid".equals(order.getString("paymentStatus"))) continue;
                        Object value = order.get("items");
                        if (!(value instanceof List)) continue;
                        for (Object itemValue : (List<?>) value) {
                            if (!(itemValue instanceof Map)) continue;
                            Map<String, Object> item = (Map<String, Object>) itemValue;
                            if (targetId.equals(String.valueOf(item.get("productId")))) {
                                eligibleOrders.add(order);
                                break;
                            }
                        }
                    }
                    updateReviewButton();
                })
                .addOnFailureListener(error -> {
                    if (isAdded()) Toast.makeText(requireContext(), "Không tải được lịch sử mua hàng.", Toast.LENGTH_LONG).show();
                });
    }

    private void updateReviewButton() {
        if (reviewButton == null || !isAdded() || !"product".equals(getArguments().getString("type"))) return;
        List<DocumentSnapshot> unreviewed = new ArrayList<>();
        for (DocumentSnapshot order : eligibleOrders) {
            boolean alreadyReviewed = false;
            for (DocumentSnapshot review : reviews) {
                if (order.getId().equals(review.getString("orderId")) && userId.equals(review.getString("userId"))) {
                    alreadyReviewed = true;
                    break;
                }
            }
            if (!alreadyReviewed) unreviewed.add(order);
        }
        eligibleOrders = unreviewed;
        reviewButton.setVisibility(View.VISIBLE);
        reviewButton.setEnabled(!eligibleOrders.isEmpty());
        reviewButton.setText(eligibleOrders.isEmpty()
                ? "Chỉ đơn hàng đã hoàn tất và thanh toán mới được đánh giá"
                : "Đánh giá sản phẩm đã mua (" + eligibleOrders.size() + ")");
    }

    private void chooseOrderToReview() {
        if (eligibleOrders.isEmpty()) return;
        String[] options = new String[eligibleOrders.size()];
        for (int i = 0; i < eligibleOrders.size(); i++) {
            DocumentSnapshot order = eligibleOrders.get(i);
            String code = order.getString("orderCode");
            Timestamp createdAt = order.getTimestamp("createdAt");
            String date = createdAt == null ? "" : android.text.format.DateFormat.format("dd/MM/yyyy", createdAt.toDate()).toString();
            options[i] = (code == null ? order.getId() : code) + (date.isEmpty() ? "" : " · " + date);
        }
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Chọn đơn hàng đã nhận")
                .setItems(options, (dialog, index) -> {
                    if (index < 0 || index >= eligibleOrders.size()) return;
                    DocumentSnapshot order = eligibleOrders.get(index);
                    Map<String, Object> base = new HashMap<>();
                    base.put("targetType", "product");
                    base.put("targetId", targetId);
                    base.put("targetName", targetName);
                    base.put("orderId", order.getId());
                    ReviewDialog.show(requireContext(), order.getId() + "_" + targetId, base, "Đánh giá " + targetName);
                }).setNegativeButton("Hủy", null).show();
    }
}
