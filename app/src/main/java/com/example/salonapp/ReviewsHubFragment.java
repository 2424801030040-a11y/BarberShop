package com.example.salonapp;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.*;

/** Unified review inbox for completed services and completed, paid product purchases. */
public class ReviewsHubFragment extends Fragment {
    private final List<ReviewTarget> targets = new ArrayList<>();
    private TextCardAdapter adapter;
    private String userId;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_appts, parent, false);
        ((TextView) view.findViewById(R.id.tvTitle)).setText("Đánh giá dịch vụ & sản phẩm");
        RecyclerView list = view.findViewById(R.id.rvList);
        adapter = new TextCardAdapter();
        ViewModeToggle.bind(requireContext(), list, view.findViewById(R.id.viewModeToggle));
        list.setAdapter(adapter);
        adapter.setOnClick(position -> {
            if (position < 0 || position >= targets.size()) return;
            ReviewTarget target = targets.get(position);
            if (!target.canReview) return;
            ReviewDialog.show(requireContext(), target.documentId, target.reviewData, "Đánh giá " + target.name);
        });

        userId = FirebaseAuth.getInstance().getUid();
        if (userId == null) {
            adapter.setItems(Collections.singletonList("Vui lòng đăng nhập để xem và gửi đánh giá."));
            return view;
        }
        loadReviewTargets();
        return view;
    }

    private void loadReviewTargets() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("reviews").whereEqualTo("userId", userId).get().addOnSuccessListener(reviewSnapshot -> {
            if (!isAdded()) return;
            Set<String> reviewedIds = new HashSet<>();
            Map<String, DocumentSnapshot> reviewById = new HashMap<>();
            for (DocumentSnapshot review : reviewSnapshot.getDocuments()) {
                reviewedIds.add(review.getId());
                reviewById.put(review.getId(), review);
            }
            loadAppointments(reviewedIds, reviewById);
        }).addOnFailureListener(error -> showLoadError());
    }

    private void loadAppointments(Set<String> reviewedIds, Map<String, DocumentSnapshot> reviewById) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("appointments").whereEqualTo("userId", userId).get().addOnSuccessListener(snapshot -> {
            if (!isAdded()) return;
            for (DocumentSnapshot appointment : snapshot.getDocuments()) {
                if (!"completed".equals(appointment.getString("status"))) continue;
                String serviceId = appointment.getString("serviceId");
                String serviceName = appointment.getString("serviceName");
                if (serviceId == null || serviceName == null) continue;
                String id = appointment.getId();
                Map<String, Object> data = new HashMap<>();
                data.put("appointmentId", id);
                data.put("targetType", "service");
                data.put("targetId", serviceId);
                data.put("targetName", serviceName);
                DocumentSnapshot oldReview = reviewById.get(id);
                targets.add(new ReviewTarget(id, serviceName, "Dịch vụ", data,
                        !reviewedIds.contains(id), oldReview, appointment.getTimestamp("startAt")));
            }
            loadOrders(reviewedIds, reviewById);
        }).addOnFailureListener(error -> showLoadError());
    }

    @SuppressWarnings("unchecked")
    private void loadOrders(Set<String> reviewedIds, Map<String, DocumentSnapshot> reviewById) {
        FirebaseFirestore.getInstance().collection("orders").whereEqualTo("userId", userId).get().addOnSuccessListener(snapshot -> {
            if (!isAdded()) return;
            for (DocumentSnapshot order : snapshot.getDocuments()) {
                if (!"completed".equals(order.getString("status"))
                        || !"paid".equals(order.getString("paymentStatus"))) continue;
                Object rawItems = order.get("items");
                if (!(rawItems instanceof List)) continue;
                for (Object rawItem : (List<?>) rawItems) {
                    if (!(rawItem instanceof Map)) continue;
                    Map<String, Object> item = (Map<String, Object>) rawItem;
                    String productId = item.get("productId") == null ? null : String.valueOf(item.get("productId"));
                    String productName = item.get("name") == null ? "Sản phẩm" : String.valueOf(item.get("name"));
                    if (productId == null || productId.isEmpty()) continue;
                    String id = order.getId() + "_" + productId;
                    Map<String, Object> data = new HashMap<>();
                    data.put("orderId", order.getId());
                    data.put("targetType", "product");
                    data.put("targetId", productId);
                    data.put("targetName", productName);
                    DocumentSnapshot oldReview = reviewById.get(id);
                    targets.add(new ReviewTarget(id, productName, "Sản phẩm", data,
                            !reviewedIds.contains(id), oldReview, order.getTimestamp("createdAt")));
                }
            }
            renderTargets();
        }).addOnFailureListener(error -> showLoadError());
    }

    private void renderTargets() {
        if (!isAdded()) return;
        targets.sort((a, b) -> {
            if (a.canReview != b.canReview) return a.canReview ? -1 : 1;
            return Long.compare(b.dateMillis, a.dateMillis);
        });
        List<String> rows = new ArrayList<>();
        for (ReviewTarget target : targets) {
            String state = target.canReview ? "CHỜ ĐÁNH GIÁ" : "ĐÃ ĐÁNH GIÁ";
            String detail = target.kind + " · " + target.name;
            if (target.oldReview != null) {
                Double rating = target.oldReview.getDouble("rating");
                String comment = target.oldReview.getString("comment");
                detail += "\n★ " + (rating == null ? "—" : rating.intValue() + "/5");
                if (comment != null && !comment.trim().isEmpty()) detail += " · " + comment.trim();
            } else {
                detail += target.dateMillis == 0 ? "" : "\n" + android.text.format.DateFormat.format("dd/MM/yyyy", new Date(target.dateMillis));
            }
            rows.add(state + "\n" + detail + (target.canReview ? "\nNhấn để viết đánh giá" : ""));
        }
        if (rows.isEmpty()) rows.add("Đánh giá dịch vụ sau khi lịch hẹn hoàn tất; đánh giá sản phẩm khi đơn hàng đã hoàn tất và thanh toán.");
        adapter.setItems(rows);
    }

    private void showLoadError() {
        if (isAdded()) Toast.makeText(requireContext(), "Không tải được danh sách đánh giá. Vui lòng thử lại.", Toast.LENGTH_LONG).show();
    }

    private static class ReviewTarget {
        final String documentId, name, kind;
        final Map<String, Object> reviewData;
        final boolean canReview;
        final DocumentSnapshot oldReview;
        final long dateMillis;

        ReviewTarget(String documentId, String name, String kind, Map<String, Object> reviewData,
                     boolean canReview, DocumentSnapshot oldReview, Timestamp date) {
            this.documentId = documentId;
            this.name = name;
            this.kind = kind;
            this.reviewData = reviewData;
            this.canReview = canReview;
            this.oldReview = oldReview;
            this.dateMillis = date == null ? 0 : date.toDate().getTime();
        }
    }
}
