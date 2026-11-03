package com.example.salonapp;

import android.app.DatePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Branch → stylist → service → date → conflict-free time selection. */
public class BookingFragment extends Fragment {
    private final Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"), Locale.getDefault());
    private final List<Item> branches = new ArrayList<>();
    private final List<Item> allStylists = new ArrayList<>();
    private final List<Item> stylists = new ArrayList<>();
    private final List<Item> services = new ArrayList<>();
    private final List<Integer> hours = new ArrayList<>();
    private boolean dateSet;
    private int slotRequestVersion;
    private Spinner spBranch, spStylist, spService, spTime;
    private Button btnDate, btnConfirm;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private static abstract class SelectionListener implements android.widget.AdapterView.OnItemSelectedListener {
        abstract void onSelected();
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { onSelected(); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_booking, parent, false);
        spBranch = view.findViewById(R.id.spBranch);
        spStylist = view.findViewById(R.id.spStylist);
        spService = view.findViewById(R.id.spService);
        spTime = view.findViewById(R.id.spTime);
        btnDate = view.findViewById(R.id.btnDate);
        btnConfirm = view.findViewById(R.id.btnConfirm);

        spBranch.setOnItemSelectedListener(new SelectionListener() { @Override void onSelected() { filterStylists(); } });
        spStylist.setOnItemSelectedListener(new SelectionListener() { @Override void onSelected() { refreshSlots(); } });
        spService.setOnItemSelectedListener(new SelectionListener() { @Override void onSelected() { refreshSlots(); } });
        btnDate.setOnClickListener(v -> showDatePicker());
        btnConfirm.setOnClickListener(v -> submitBooking());

        load("branches", branches, spBranch, this::filterStylists);
        load("stylists", allStylists, null, this::filterStylists);
        load("services", services, spService, this::refreshSlots);
        return view;
    }

    private void showDatePicker() {
        DatePickerDialog picker = new DatePickerDialog(requireContext(), (dialog, year, month, day) -> {
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, day);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            dateSet = true;
            btnDate.setText(new SimpleDateFormat("EEE, dd MMM yyyy", new Locale("vi", "VN")).format(cal.getTime()));
            refreshSlots();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        picker.show();
    }

    private void load(String collection, List<Item> target, Spinner spinner, Runnable finished) {
        db.collection(collection).get().addOnSuccessListener(snapshot -> {
            target.clear();
            for (DocumentSnapshot document : snapshot) {
                Item item = document.toObject(Item.class);
                if (item != null) {
                    item.id = document.getId();
                    target.add(item);
                }
            }
            if (!isAdded()) return;
            if (spinner != null) fill(spinner, target);
            if (finished != null) finished.run();
        }).addOnFailureListener(error -> toast("Không tải được dữ liệu đặt lịch: " + error.getLocalizedMessage()));
    }

    private void fill(Spinner spinner, List<Item> items) {
        if (spinner == spStylist) {
            spinner.setAdapter(new BarberSpinnerAdapter(requireContext(), items));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (Item item : items) {
            String label = item.name == null ? "" : item.name;
            if (spinner == spService && item.durationMinutes != null) label += " · " + item.durationMinutes + " phút";
            labels.add(label);
        }
        spinner.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels));
    }

    private void filterStylists() {
        if (branches.isEmpty() || !isAdded() || spBranch.getSelectedItemPosition() < 0) return;
        Item branch = branches.get(spBranch.getSelectedItemPosition());
        stylists.clear();
        for (Item stylist : allStylists) if (branch.id.equals(stylist.branchId)) stylists.add(stylist);
        fill(spStylist, stylists);
        refreshSlots();
    }

    private String dayPrefix() {
        Item branch = branches.get(spBranch.getSelectedItemPosition());
        Item stylist = stylists.get(spStylist.getSelectedItemPosition());
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        String day = formatter.format(cal.getTime());
        return branch.id + "_" + stylist.id + "_" + day;
    }

    private int durationMinutes() {
        if (spService.getSelectedItemPosition() < 0 || spService.getSelectedItemPosition() >= services.size()) return 60;
        Integer duration = services.get(spService.getSelectedItemPosition()).durationMinutes;
        return duration != null && duration > 0 ? duration : 60;
    }

    private void refreshSlots() {
        int version = ++slotRequestVersion;
        hours.clear();
        if (!dateSet || branches.isEmpty() || stylists.isEmpty() || services.isEmpty() ||
                spBranch.getSelectedItemPosition() < 0 || spStylist.getSelectedItemPosition() < 0 || spService.getSelectedItemPosition() < 0) {
            setTimes(new ArrayList<>());
            return;
        }
        String prefix = dayPrefix();
        int duration = durationMinutes();
        db.collection("slots").orderBy(FieldPath.documentId()).startAt(prefix).endAt(prefix + "\uf8ff").get()
                .addOnSuccessListener(snapshot -> {
                    if (!isAdded() || version != slotRequestVersion) return;
                    Set<String> occupied = new HashSet<>();
                    for (DocumentSnapshot document : snapshot) occupied.add(document.getId());
                    List<String> labels = new ArrayList<>();
                    Calendar candidate = (Calendar) cal.clone();
                    for (int startHour = 9; startHour < 19; startHour++) {
                        if (startHour * 60 + duration > 19 * 60) continue;
                        candidate.set(Calendar.HOUR_OF_DAY, startHour);
                        candidate.set(Calendar.MINUTE, 0);
                        candidate.set(Calendar.SECOND, 0);
                        candidate.set(Calendar.MILLISECOND, 0);
                        if (candidate.getTimeInMillis() <= System.currentTimeMillis()) continue;
                        int blockCount = (duration + 59) / 60;
                        boolean available = true;
                        for (int block = 0; block < blockCount; block++) {
                            String id = prefix + "_" + String.format(Locale.US, "%02d00", startHour + block);
                            if (occupied.contains(id)) { available = false; break; }
                        }
                        if (available) {
                            hours.add(startHour);
                            labels.add(String.format(Locale.US, "%02d:00", startHour));
                        }
                    }
                    setTimes(labels);
                }).addOnFailureListener(error -> {
                    if (version == slotRequestVersion) toast("Không thể kiểm tra giờ trống. Kiểm tra kết nối rồi thử lại.");
                });
    }

    private void setTimes(List<String> labels) {
        if (!isAdded()) return;
        if (labels.isEmpty()) labels.add(dateSet ? "Không có giờ phù hợp" : "Vui lòng chọn ngày trước");
        spTime.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels));
    }

    private void submitBooking() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) { toast("Vui lòng đăng nhập lại."); return; }
        int position = spTime.getSelectedItemPosition();
        if (branches.isEmpty() || stylists.isEmpty() || services.isEmpty() || !dateSet || position < 0 || position >= hours.size()) {
            toast("Chọn chi nhánh, thợ, dịch vụ, ngày và giờ còn trống.");
            return;
        }
        Item branch = branches.get(spBranch.getSelectedItemPosition());
        Item stylist = stylists.get(spStylist.getSelectedItemPosition());
        Item service = services.get(spService.getSelectedItemPosition());
        Calendar start = (Calendar) cal.clone();
        start.set(Calendar.HOUR_OF_DAY, hours.get(position));
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("branchId", branch.id);
        payload.put("stylistId", stylist.id);
        payload.put("serviceId", service.id);
        payload.put("startAt", start.getTimeInMillis());
        btnConfirm.setEnabled(false);
        btnConfirm.setText("Đang xác nhận…");
        Context appContext = requireContext().getApplicationContext();
        if (!Config.isSupabaseConfigured()) {
            toast("Hãy cấu hình Supabase URL và publishable key trong Config.java trước khi đặt lịch.");
            resetBookingButton();
            return;
        }
        int selectedHour = hours.get(position);
        FirebaseAuth.getInstance().getCurrentUser().getIdToken(true).addOnSuccessListener(token -> {
            new Thread(() -> {
                String failure = null;
                JSONObject result = null;
                try {
                    Request request = new Request.Builder()
                            .url(Config.SUPABASE_URL + "/functions/v1/create-appointment")
                            .addHeader("apikey", Config.SUPABASE_PUBLISHABLE_KEY)
                            .addHeader("Authorization", "Bearer " + token.getToken())
                            .post(RequestBody.create(new JSONObject(payload).toString(), MediaType.parse("application/json; charset=utf-8")))
                            .build();
                    try (Response response = new OkHttpClient().newCall(request).execute()) {
                        String body = response.body() == null ? "{}" : response.body().string();
                        result = new JSONObject(body);
                        if (!response.isSuccessful()) failure = result.optString("error", "Đặt lịch chưa thành công. Vui lòng thử lại.");
                    }
                } catch (Exception error) {
                    failure = "Không thể kết nối máy chủ đặt lịch. Vui lòng thử lại.";
                }
                final JSONObject responseJson = result;
                final String errorMessage = failure;
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (errorMessage != null || responseJson == null || responseJson.optString("appointmentId").isEmpty()) {
                        toast(errorMessage == null ? "Máy chủ trả về thông tin lịch hẹn không hợp lệ." : errorMessage);
                    } else {
                        String appointmentId = responseJson.optString("appointmentId");
                        Notify.scheduleReminder(appContext, appointmentId,
                                service.name + " với " + stylist.name + " lúc " + String.format(Locale.US, "%02d:00", selectedHour), start.getTimeInMillis());
                        toast("Đã gửi yêu cầu đặt lịch. Cửa hàng sẽ xác nhận sớm.");
                    }
                    refreshSlots();
                    resetBookingButton();
                });
            }).start();
        }).addOnFailureListener(error -> {
            toast("Không xác thực được tài khoản. Vui lòng đăng nhập lại.");
            resetBookingButton();
        });
    }

    private void resetBookingButton() {
        if (isAdded() && btnConfirm != null) {
            btnConfirm.setEnabled(true);
            btnConfirm.setText("Xác nhận đặt lịch");
        }
    }

    private void toast(String message) {
        if (getContext() != null) Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
    }
}
