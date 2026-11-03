package com.example.salonapp;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.*;
import java.text.SimpleDateFormat;
import java.util.*;
/** Order history + status tracking; delivered orders can be rated per product. */
public class OrdersFragment extends Fragment {
    private List<DocumentSnapshot> docs = new ArrayList<>();
    private ListenerRegistration ordersListener;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.fragment_appts, c, false);
        ((TextView) v.findViewById(R.id.tvTitle)).setText("Lịch sử đơn hàng");
        RecyclerView list = v.findViewById(R.id.rvList);
        TextCardAdapter adapter = new TextCardAdapter();
        adapter.setOnClick(pos -> { if (pos < docs.size()) details(docs.get(pos)); });
        ViewModeToggle.bind(requireContext(), list, v.findViewById(R.id.viewModeToggle));
        list.setAdapter(adapter);
        ordersListener = FirebaseFirestore.getInstance().collection("orders").whereEqualTo("userId", FirebaseAuth.getInstance().getUid()).addSnapshotListener((sn, error) -> {
            if (error != null || sn == null) {
                if (isAdded()) Toast.makeText(requireContext(), "Không tải được lịch sử đơn hàng. Vui lòng thử lại.", Toast.LENGTH_LONG).show();
                return;
            }
            docs = sn.getDocuments();
            docs.sort((a, b) -> Long.compare(millis(b, "createdAt"), millis(a, "createdAt")));
            List<String> rows = new ArrayList<>();
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("vi", "VN"));
            dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
            for (DocumentSnapshot d : docs) {
                List<?> it = (List<?>) d.get("items");
                Number total = (Number) d.get("total");
                String code = d.getString("orderCode");
                Timestamp created = d.getTimestamp("createdAt");
                String date = created == null ? "" : dateFormat.format(created.toDate());
                rows.add((code == null ? d.getId() : code) + (date.isEmpty() ? "" : " · " + date)
                    + "\n" + String.format(Vi.VN, "%,.0f đ", total == null ? 0d : total.doubleValue())
                    + "  ·  " + Vi.t(d.getString("status")) + "  ·  " + Vi.t(d.getString("paymentStatus"))
                    + (Boolean.TRUE.equals(d.getBoolean("paymentReviewRequired")) ? " · Cần cửa hàng đối soát thanh toán" : "")
                    + "  ·  " + (it == null ? 0 : it.size()) + " sản phẩm");
            }
            if (rows.isEmpty()) rows.add("Chưa có đơn hàng");
            if (isAdded()) adapter.setItems(rows);
        });
        return v;
    }
    @Override public void onDestroyView() {
        if (ordersListener != null) { ordersListener.remove(); ordersListener = null; }
        super.onDestroyView();
    }
    private long millis(DocumentSnapshot document, String field) {
        Timestamp timestamp = document.getTimestamp(field);
        return timestamp == null ? 0L : timestamp.toDate().getTime();
    }
    @SuppressWarnings("unchecked")
    private void details(DocumentSnapshot d) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) d.get("items");
        if (items == null) return;
        StringBuilder sb = new StringBuilder("Trạng thái: " + Vi.t(d.getString("status")) + "\nThanh toán: " + Vi.t(d.getString("payment")) + " (" + Vi.t(d.getString("paymentStatus")) + ")\nMã đơn: " + d.getString("orderCode") + "\nĐịa chỉ: " + d.getString("address") + "\n");
        if (Boolean.TRUE.equals(d.getBoolean("paymentReviewRequired")))
            sb.append("\nCửa hàng đã nhận khoản chuyển sau khi đơn hết thời gian giữ hàng. Đơn chưa được xác nhận giao; vui lòng liên hệ cửa hàng để đối soát và sắp xếp xử lý.\n");
        String[] names = new String[items.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = String.valueOf(items.get(i).get("name"));
            Object quantity = items.get(i).get("quantity");
            if (quantity == null) quantity = items.get(i).get("qty");
            sb.append("• ").append(names[i]).append(" ×").append(quantity == null ? "?" : quantity).append("\n");
        }
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(requireContext()).setTitle("Chi tiết đơn hàng").setMessage(sb).setNegativeButton("Đóng", null);
        if ("delivered".equals(d.getString("status")))
            b.setPositiveButton("Đánh giá sản phẩm", (dl, w) -> new MaterialAlertDialogBuilder(requireContext()).setTitle("Sản phẩm nào?").setItems(names, (d2, k) -> {
                String pid = String.valueOf(items.get(k).get("productId"));
                Map<String, Object> base = new HashMap<>(); base.put("targetType", "product"); base.put("targetId", pid); base.put("targetName", names[k]); base.put("orderId", d.getId());
                ReviewDialog.show(requireContext(), d.getId() + "_" + pid, base, "Đánh giá " + names[k]);
            }).show());
        b.show();
    }
}
