package com.example.salonapp;
import android.app.*;
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
import org.json.JSONObject;
/** History + cancel/reschedule (2-hour policy) + rating of completed appointments. */
public class AppointmentsFragment extends Fragment {
    private static final long MIN_NOTICE_MS = 2 * 3600_000L;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private List<DocumentSnapshot> docs = new ArrayList<>();
    private RecyclerView list;
    private TextCardAdapter adapter;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.fragment_appts, c, false);
        ((TextView) v.findViewById(R.id.tvTitle)).setText("Lịch sử lịch hẹn");
        list = v.findViewById(R.id.rvList);
        adapter = new TextCardAdapter();
        adapter.setOnClick(pos -> { if (pos < docs.size()) actions(docs.get(pos)); });
        ViewModeToggle.bind(requireContext(), list, v.findViewById(R.id.viewModeToggle));
        list.setAdapter(adapter);
        load(); return v;
    }
    private void load() {
        db.collection("appointments").whereEqualTo("userId", FirebaseAuth.getInstance().getUid()).get().addOnSuccessListener(sn -> {
            docs = sn.getDocuments();
            docs.sort((a, b) -> Long.compare(millis(b, "start"), millis(a, "start")));
            List<String> rows = new ArrayList<>();
            SimpleDateFormat f = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
            for (DocumentSnapshot d : docs) {
                Timestamp start = d.getTimestamp("start");
                String date = start == null ? "Thời gian chưa xác định" : f.format(start.toDate());
                rows.add((d.getString("serviceName") == null ? "Dịch vụ" : d.getString("serviceName"))
                    + (d.getString("stylistName") == null ? "" : " · " + d.getString("stylistName"))
                    + "\n" + date + "  ·  " + Vi.t(d.getString("status")));
            }
            if (rows.isEmpty()) rows.add("Bạn chưa có lịch hẹn nào.");
            if (isAdded()) adapter.setItems(rows);
        }).addOnFailureListener(error -> {
            if (isAdded()) toast("Không tải được lịch sử lịch hẹn. Vui lòng thử lại.");
        });
    }
    private long millis(DocumentSnapshot document, String field) {
        Timestamp timestamp = document.getTimestamp(field);
        return timestamp == null ? 0L : timestamp.toDate().getTime();
    }
    private void actions(DocumentSnapshot d) {
        String st = d.getString("status");
        if ("completed".equals(st)) { rate(d); return; }
        if (!"pending".equals(st) && !"confirmed".equals(st)) { toast("Không có thao tác nào cho lịch hẹn này"); return; }
        new MaterialAlertDialogBuilder(requireContext()).setTitle(d.getString("serviceName")).setItems(new String[]{"Đổi lịch", "Hủy lịch hẹn"}, (dl, w) -> {
            if (d.getTimestamp("start").toDate().getTime() - System.currentTimeMillis() < MIN_NOTICE_MS) { toast("Chỉ được đổi hoặc hủy lịch trước giờ hẹn ít nhất 2 giờ"); return; }
            if (w == 0) pickNew(d); else cancel(d);
        }).show();
    }
    private void cancel(DocumentSnapshot d) {
        JSONObject payload = new JSONObject();
        try { payload.put("action", "cancel"); payload.put("appointmentId", d.getId()); }
        catch (org.json.JSONException error) { toast("Thông tin lịch hẹn không hợp lệ."); return; }
        SupabaseFunctions.post(requireContext(), "manage-appointment", payload, (result, error) -> {
            if (!isAdded()) return;
            if (error != null) { toast(error); return; }
                Notify.cancelReminder(requireContext(), d.getId());
                toast("Đã hủy lịch hẹn");
                load();
        });
    }
    private void pickNew(DocumentSnapshot d) {
        Calendar initial = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"), Locale.getDefault());
        DatePickerDialog picker = new DatePickerDialog(requireContext(), (dp, year, month, day) -> {
            Calendar date = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"), Locale.getDefault());
            date.set(year, month, day, 0, 0, 0);
            date.set(Calendar.MILLISECOND, 0);
            loadNewTimes(d, date);
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        picker.show();
    }
    private void loadNewTimes(DocumentSnapshot appointment, Calendar date) {
        SimpleDateFormat dayFormatter = new SimpleDateFormat("yyyyMMdd", Locale.US);
        dayFormatter.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        String prefix = appointment.getString("branchId") + "_" + appointment.getString("stylistId") + "_"
                + dayFormatter.format(date.getTime());
        int duration = appointment.getLong("durationMinutes") == null ? 60 : appointment.getLong("durationMinutes").intValue();
        List<String> currentSlots = (List<String>) appointment.get("slotIds");
        if (currentSlots == null) currentSlots = Collections.singletonList(appointment.getString("slotId"));
        Set<String> ownSlots = new HashSet<>(currentSlots);
        db.collection("slots").orderBy(FieldPath.documentId()).startAt(prefix).endAt(prefix + "\uf8ff").get()
            .addOnSuccessListener(snapshot -> {
                if (!isAdded()) return;
                Set<String> occupied = new HashSet<>();
                for (DocumentSnapshot slot : snapshot) if (!ownSlots.contains(slot.getId())) occupied.add(slot.getId());
                List<Integer> hours = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                Calendar candidate = (Calendar) date.clone();
                for (int hour = 9; hour < 19; hour++) {
                    if (hour * 60 + duration > 19 * 60) continue;
                    candidate.set(Calendar.HOUR_OF_DAY, hour); candidate.set(Calendar.MINUTE, 0); candidate.set(Calendar.SECOND, 0); candidate.set(Calendar.MILLISECOND, 0);
                    if (candidate.getTimeInMillis() <= System.currentTimeMillis()) continue;
                    boolean free = true;
                    for (int block = 0; block < (duration + 59) / 60; block++) {
                        if (occupied.contains(prefix + "_" + String.format(Locale.US, "%02d00", hour + block))) { free = false; break; }
                    }
                    if (free) { hours.add(hour); labels.add(String.format(Locale.US, "%02d:00", hour)); }
                }
                if (labels.isEmpty()) { toast("Không còn giờ phù hợp trong ngày này."); return; }
                new MaterialAlertDialogBuilder(requireContext()).setTitle("Chọn giờ mới")
                    .setItems(labels.toArray(new String[0]), (dialog, position) -> {
                        Calendar start = (Calendar) date.clone();
                        start.set(Calendar.HOUR_OF_DAY, hours.get(position)); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
                        reschedule(appointment, start);
                    }).setNegativeButton("Hủy", null).show();
            }).addOnFailureListener(error -> toast("Không thể kiểm tra giờ trống. Vui lòng thử lại."));
    }
    private void reschedule(DocumentSnapshot d, Calendar start) {
        JSONObject payload = new JSONObject();
        try { payload.put("action", "reschedule"); payload.put("appointmentId", d.getId()); payload.put("startAt", start.getTimeInMillis()); }
        catch (org.json.JSONException error) { toast("Thông tin đổi lịch không hợp lệ."); return; }
        SupabaseFunctions.post(requireContext(), "manage-appointment", payload, (result, error) -> {
            if (!isAdded()) return;
            if (error != null) { toast(error); return; }
                String label = d.getString("serviceName") + " với " + d.getString("stylistName");
                long actualStart = result == null ? start.getTimeInMillis() : result.optLong("startAt", start.getTimeInMillis());
                Notify.scheduleReminder(requireContext(), d.getId(), label, actualStart);
                toast("Đã đổi lịch hẹn.");
                load();
        });
    }
    private void rate(DocumentSnapshot d) {
        Map<String, Object> base = new HashMap<>();
        base.put("appointmentId", d.getId());
        base.put("targetType", "service");
        base.put("targetId", d.getString("serviceId"));
        base.put("targetName", d.getString("serviceName"));
        ReviewDialog.show(requireContext(), d.getId(), base, "Đánh giá " + d.getString("serviceName"));
    }
    private void toast(String m) { if (getContext() != null) Toast.makeText(getContext(), m, Toast.LENGTH_LONG).show(); }
}
