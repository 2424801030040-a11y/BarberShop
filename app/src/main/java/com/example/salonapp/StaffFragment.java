package com.example.salonapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Staff-only queue for appointment and order operations. */
public class StaffFragment extends Fragment {
    private final List<Map<String, Object>> appointments = new ArrayList<>();
    private final List<Map<String, Object>> orders = new ArrayList<>();
    private TextCardAdapter adapter;
    private RecyclerView list;
    private TextView empty;
    private MaterialButtonToggleGroup tabs;
    private boolean showingOrders;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_staff, parent, false);
        list = view.findViewById(R.id.rvStaff);
        empty = view.findViewById(R.id.tvStaffEmpty);
        tabs = view.findViewById(R.id.staffTabs);
        adapter = new TextCardAdapter();
        adapter.setOnClick(this::openSelected);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        tabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) { showingOrders = checkedId == R.id.tabOrders; render(); }
        });
        tabs.check(R.id.tabAppointments);
        view.findViewById(R.id.btnRefreshStaff).setOnClickListener(v -> load());
        load();
        return view;
    }

    private void load() {
        empty.setVisibility(View.VISIBLE);
        empty.setText("Đang tải dữ liệu…");
        list.setVisibility(View.GONE);
        SupabaseFunctions.post(requireContext(), "list-staff-work", new JSONObject(), (result, error) -> {
            if (!isAdded()) return;
            if (error != null || result == null) {
                empty.setVisibility(View.VISIBLE);
                empty.setText(error == null ? "Không tải được danh sách. Hãy thử làm mới." : error);
                list.setVisibility(View.GONE);
                return;
            }
            copyRows(result.optJSONArray("appointments"), appointments);
            copyRows(result.optJSONArray("orders"), orders);
            render();
        });
    }

    private void copyRows(JSONArray value, List<Map<String, Object>> target) {
        target.clear();
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) try {
            JSONObject row = value.getJSONObject(i);
            Map<String, Object> copy = new HashMap<>();
            java.util.Iterator<String> keys = row.keys();
            while (keys.hasNext()) { String key = keys.next(); Object item = row.opt(key); copy.put(key, item == JSONObject.NULL ? null : item); }
            target.add(copy);
        } catch (org.json.JSONException ignored) { }
    }

    private void render() {
        List<Map<String, Object>> rows = showingOrders ? orders : appointments;
        List<String> labels = new ArrayList<>();
        SimpleDateFormat date = new SimpleDateFormat("dd/MM · HH:mm", new Locale("vi", "VN"));
        date.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        for (Map<String, Object> row : rows) {
            if (showingOrders) {
                labels.add(String.valueOf(row.get("orderCode")) + "  ·  " + String.format(Vi.VN, "%,.0f đ", number(row.get("total")))
                        + "\n" + row.get("itemCount") + " sản phẩm · " + Vi.t(String.valueOf(row.get("status")))
                        + " · " + Vi.t(String.valueOf(row.get("paymentStatus"))));
            } else {
                labels.add(String.valueOf(row.get("serviceName")) + " · " + String.valueOf(row.get("stylistName"))
                        + "\n" + date.format(new java.util.Date((long) number(row.get("startAt"))))
                        + " · " + String.valueOf(row.get("branchName")) + "\n" + Vi.t(String.valueOf(row.get("status"))));
            }
        }
        adapter.setItems(labels);
        boolean isEmpty = rows.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (isEmpty) empty.setText(showingOrders ? "Chưa có đơn hàng." : "Chưa có lịch hẹn.");
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void openSelected(int position) {
        List<Map<String, Object>> rows = showingOrders ? orders : appointments;
        if (position < 0 || position >= rows.size()) return;
        Map<String, Object> row = rows.get(position);
        if (showingOrders) showOrderActions(row);
        else showAppointmentActions(row);
    }

    private void showAppointmentActions(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        List<String> labels = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        if ("pending".equals(status)) { labels.add("Xác nhận lịch hẹn"); statuses.add("confirmed"); }
        if ("confirmed".equals(status)) { labels.add("Bắt đầu cắt tóc"); statuses.add("in_service"); }
        if ("in_service".equals(status)) { labels.add("Đánh dấu hoàn thành"); statuses.add("completed"); }
        if ("pending".equals(status) || "confirmed".equals(status) || "in_service".equals(status)) { labels.add("Hủy lịch hẹn"); statuses.add("canceled"); }
        if (labels.isEmpty()) { toast("Lịch hẹn này đã kết thúc."); return; }
        new MaterialAlertDialogBuilder(requireContext()).setTitle(String.valueOf(row.get("serviceName")))
                .setItems(labels.toArray(new String[0]), (dialog, index) -> updateAppointment(String.valueOf(row.get("id")), statuses.get(index)))
                .setNegativeButton("Đóng", null).show();
    }

    private void showOrderActions(Map<String, Object> row) {
        String status = String.valueOf(row.get("status"));
        List<String> labels = new ArrayList<>();
        List<String> nextStatus = new ArrayList<>();
        List<Boolean> markPaid = new ArrayList<>();
        if ("pending".equals(status) && "paid".equals(String.valueOf(row.get("paymentStatus")))) { labels.add("Hoàn thành / giao khách"); nextStatus.add("completed"); markPaid.add(false); }
        if ("pending".equals(status) && !"paid".equals(String.valueOf(row.get("paymentStatus")))) { labels.add("Đã thu tiền mặt · hoàn thành"); nextStatus.add("completed"); markPaid.add(true); }
        if ("pending".equals(status) && !"paid".equals(String.valueOf(row.get("paymentStatus")))) { labels.add("Hủy đơn và hoàn tồn kho"); nextStatus.add("canceled"); markPaid.add(false); }
        if ("pending".equals(status) && "paid".equals(String.valueOf(row.get("paymentStatus")))) { labels.add("Xác nhận gửi hàng"); nextStatus.add("shipping"); markPaid.add(false); }
        if ("shipping".equals(status)) { labels.add("Đánh dấu đã giao"); nextStatus.add("delivered"); markPaid.add(false); }
        if ("delivered".equals(status)) { labels.add("Xác nhận hoàn tất đơn"); nextStatus.add("completed"); markPaid.add(false); }
        if ("unpaid".equals(String.valueOf(row.get("paymentStatus"))) && ("pending".equals(status) || "shipping".equals(status))) { labels.add("Xác nhận đã thanh toán"); nextStatus.add(null); markPaid.add(true); }
        if (labels.isEmpty()) { toast("Đơn hàng này đã hoàn tất."); return; }
        new MaterialAlertDialogBuilder(requireContext()).setTitle(String.valueOf(row.get("orderCode")))
                .setItems(labels.toArray(new String[0]), (dialog, index) -> updateOrder(String.valueOf(row.get("id")), nextStatus.get(index), markPaid.get(index)))
                .setNegativeButton("Đóng", null).show();
    }

    private void updateAppointment(String id, String status) {
        JSONObject payload = new JSONObject();
        try { payload.put("kind", "appointment"); payload.put("appointmentId", id); payload.put("status", status); }
        catch (org.json.JSONException error) { toast("Thông tin lịch hẹn không hợp lệ."); return; }
        SupabaseFunctions.post(requireContext(), "update-work-status", payload, (result, error) -> {
            if (!isAdded()) return;
            if (error != null) toast(error); else { toast("Đã cập nhật lịch hẹn."); load(); }
        });
    }

    private void updateOrder(String id, String status, boolean paid) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("kind", "order"); payload.put("orderId", id);
            if (status != null) payload.put("status", status);
            if (paid) payload.put("paymentStatus", "paid");
        } catch (org.json.JSONException error) { toast("Thông tin đơn hàng không hợp lệ."); return; }
        SupabaseFunctions.post(requireContext(), "update-work-status", payload, (result, error) -> {
            if (!isAdded()) return;
            if (error != null) toast(error); else { toast("Đã cập nhật đơn hàng."); load(); }
        });
    }

    private double number(Object value) { return value instanceof Number ? ((Number) value).doubleValue() : 0; }
    private void toast(String message) { if (getContext() != null) Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show(); }
}
