package com.example.salonapp;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.*;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.*;
public class AddressFragment extends Fragment {
    private CollectionReference col; private RecyclerView list; private TextCardAdapter adapter;
    private List<DocumentSnapshot> docs = new ArrayList<>();
    private EditText currentAddressInput;
    private TextView locationStatus;
    private LocationListener locationListener;
    private int locationRequestId;
    private final Handler locationHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String[]> locationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                        || Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (granted && currentAddressInput != null) findCurrentLocation();
                else showLocationStatus("Cần quyền vị trí để tự điền địa chỉ.");
            });
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.fragment_address, c, false);
        list = v.findViewById(R.id.rvAddr);
        adapter = new TextCardAdapter();
        adapter.setOnClick(pos -> {
            DocumentSnapshot d = docs.get(pos);
            new MaterialAlertDialogBuilder(requireContext()).setTitle(d.getString("label")).setItems(new String[]{"Đặt làm mặc định", "Xóa"}, (dl, w) -> {
                if (w == 1) d.getReference().delete().addOnSuccessListener(r -> load());
                else {
                    WriteBatch b = FirebaseFirestore.getInstance().batch();
                    for (DocumentSnapshot o : docs) b.update(o.getReference(), "isDefault", o.getId().equals(d.getId()));
                    b.commit().addOnSuccessListener(r -> load());
                }
            }).show();
        });
        ViewModeToggle.bind(requireContext(), list, v.findViewById(R.id.viewModeToggle));
        list.setAdapter(adapter);
        col = FirebaseFirestore.getInstance().collection("users").document(FirebaseAuth.getInstance().getUid()).collection("addresses");
        v.findViewById(R.id.btnAdd).setOnClickListener(x -> addDialog());
        load();
        return v;
    }
    private void load() {
        col.get().addOnSuccessListener(sn -> {
            docs = sn.getDocuments(); List<String> rows = new ArrayList<>();
            for (DocumentSnapshot d : docs) rows.add(d.getString("label") + (Boolean.TRUE.equals(d.getBoolean("isDefault")) ? "  ★ mặc định" : "") + "\n" + d.getString("text"));
            if (isAdded()) adapter.setItems(rows);
        });
    }
    private void addDialog() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(22), vertical = dp(8);
        box.setPadding(horizontal, vertical, horizontal, 0);

        TextInputLayout labelLayout = addressField("Tên gợi nhớ", "Ví dụ: Nhà, Cơ quan");
        TextInputEditText label = new TextInputEditText(labelLayout.getContext());
        label.setSingleLine(true);
        label.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        labelLayout.addView(label);
        box.addView(labelLayout, fullWidth());

        TextInputLayout addressLayout = addressField("Địa chỉ giao hàng", "Số nhà, đường, phường/xã, quận/huyện...");
        TextInputEditText address = new TextInputEditText(addressLayout.getContext());
        address.setMinLines(2);
        address.setMaxLines(4);
        address.setGravity(Gravity.TOP | Gravity.START);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        addressLayout.addView(address);
        LinearLayout.LayoutParams addressParams = fullWidth();
        addressParams.topMargin = dp(12);
        box.addView(addressLayout, addressParams);

        MaterialButton locate = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        locate.setText("Lấy vị trí hiện tại");
        locate.setIconResource(R.drawable.ic_action_branch);
        locate.setIconPadding(dp(8));
        LinearLayout.LayoutParams locateParams = fullWidth();
        locateParams.topMargin = dp(10);
        box.addView(locate, locateParams);

        locationStatus = new TextView(requireContext());
        locationStatus.setText("Hoặc nhập địa chỉ giao hàng bên trên.");
        locationStatus.setTextSize(12);
        locationStatus.setTextColor(0xFF766B62);
        LinearLayout.LayoutParams statusParams = fullWidth();
        statusParams.topMargin = dp(3);
        box.addView(locationStatus, statusParams);
        currentAddressInput = address;

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Thêm địa chỉ giao hàng")
                .setView(box)
                .setPositiveButton("Lưu", null)
                .setNegativeButton("Hủy", null)
                .create();
        locate.setOnClickListener(view -> requestCurrentLocation());
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String addressText = address.getText() == null ? "" : address.getText().toString().trim();
            if (addressText.isEmpty()) {
                addressLayout.setError("Vui lòng nhập địa chỉ hoặc lấy vị trí hiện tại.");
                address.requestFocus();
                return;
            }
            addressLayout.setError(null);
            String labelText = label.getText() == null ? "" : label.getText().toString().trim();
            Map<String, Object> data = new HashMap<>();
            data.put("label", labelText.isEmpty() ? "Địa chỉ của tôi" : labelText);
            data.put("text", addressText);
            data.put("isDefault", docs.isEmpty());
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            col.add(data).addOnSuccessListener(result -> {
                if (!isAdded()) return;
                currentAddressInput = null;
                locationStatus = null;
                if (dialog.isShowing()) dialog.dismiss();
                load();
            }).addOnFailureListener(error -> {
                if (!isAdded() || !dialog.isShowing()) return;
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                android.content.Context context = getContext();
                if (context != null) Toast.makeText(context, "Không lưu được địa chỉ. Vui lòng thử lại.", Toast.LENGTH_LONG).show();
            });
        }));
        dialog.setOnDismissListener(ignored -> {
            locationRequestId++;
            stopLocationUpdates();
            currentAddressInput = null;
            locationStatus = null;
        });
        dialog.show();
    }

    private TextInputLayout addressField(String label, String placeholder) {
        TextInputLayout layout = new TextInputLayout(requireContext());
        layout.setHint(label);
        layout.setPlaceholderText(placeholder);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxCornerRadii(dp(14), dp(14), dp(14), dp(14));
        layout.setBoxStrokeColor(0xFF9B4033);
        return layout;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestCurrentLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
            return;
        }
        findCurrentLocation();
    }

    @SuppressWarnings("MissingPermission")
    private void findCurrentLocation() {
        if (!isAdded() || currentAddressInput == null) return;
        LocationManager manager = (LocationManager) requireContext().getSystemService(android.content.Context.LOCATION_SERVICE);
        List<String> providers = new ArrayList<>();
        try {
            for (String provider : manager.getProviders(true)) {
                if (LocationManager.GPS_PROVIDER.equals(provider) || LocationManager.NETWORK_PROVIDER.equals(provider)) providers.add(provider);
            }
        } catch (Exception ignored) { }
        if (providers.isEmpty()) {
            showLocationStatus("Bật dịch vụ vị trí trên điện thoại rồi thử lại.");
            return;
        }
        showLocationStatus("Đang xác định vị trí...");
        stopLocationUpdates();
        final int requestId = ++locationRequestId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                for (String provider : providers) {
                    manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(requireContext()), location -> {
                        if (requestId != locationRequestId || location == null || currentAddressInput == null) return;
                        stopLocationUpdates();
                        fillAddressFromLocation(location, requestId);
                    });
                }
            } catch (SecurityException error) {
                showLocationStatus("Không có quyền truy cập vị trí.");
                return;
            } catch (RuntimeException error) {
                showLocationStatus("Không thể lấy vị trí lúc này. Hãy nhập địa chỉ thủ công.");
                return;
            }
            locationHandler.postDelayed(() -> {
                if (requestId == locationRequestId && currentAddressInput != null && locationStatus != null
                        && "Đang xác định vị trí...".contentEquals(locationStatus.getText()))
                    showLocationStatus("Chưa lấy được vị trí. Hãy thử lại hoặc nhập địa chỉ thủ công.");
            }, 15000);
        } else {
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(@NonNull Location location) {
                    stopLocationUpdates();
                    fillAddressFromLocation(location, requestId);
                }
            };
            try {
                for (String provider : providers) manager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper());
                locationHandler.postDelayed(() -> {
                    if (locationListener != null) {
                        stopLocationUpdates();
                        showLocationStatus("Chưa lấy được vị trí. Hãy thử lại hoặc nhập địa chỉ thủ công.");
                    }
                }, 15000);
            } catch (SecurityException error) {
                showLocationStatus("Không có quyền truy cập vị trí.");
            } catch (RuntimeException error) {
                showLocationStatus("Không thể lấy vị trí lúc này. Hãy nhập địa chỉ thủ công.");
            }
        }
    }

    private void fillAddressFromLocation(Location location, int requestId) {
        if (!isAdded() || currentAddressInput == null) return;
        showLocationStatus("Đã lấy vị trí. Đang tìm địa chỉ...");
        android.content.Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            String result = String.format(Locale.getDefault(), "%.6f, %.6f", location.getLatitude(), location.getLongitude());
            try {
                Geocoder geocoder = new Geocoder(appContext, Locale.getDefault());
                List<Address> matches = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (matches != null && !matches.isEmpty()) {
                    String formatted = matches.get(0).getAddressLine(0);
                    if (formatted != null && !formatted.trim().isEmpty()) result = formatted;
                }
            } catch (Exception ignored) { /* Coordinates are still a usable location fallback. */ }
            String finalAddress = result;
            android.app.Activity activity = getActivity();
            if (activity != null) activity.runOnUiThread(() -> {
                if (requestId != locationRequestId || !isAdded() || currentAddressInput == null) return;
                currentAddressInput.setText(finalAddress);
                currentAddressInput.setSelection(finalAddress.length());
                showLocationStatus("Đã điền vị trí hiện tại. Vui lòng kiểm tra lại địa chỉ.");
            });
        }).start();
    }

    private void showLocationStatus(String message) {
        if (locationStatus != null) locationStatus.setText(message);
    }

    @SuppressWarnings("MissingPermission")
    private void stopLocationUpdates() {
        locationHandler.removeCallbacksAndMessages(null);
        if (locationListener != null && isAdded()) {
            try {
                LocationManager manager = (LocationManager) requireContext().getSystemService(android.content.Context.LOCATION_SERVICE);
                manager.removeUpdates(locationListener);
            } catch (Exception ignored) { }
            locationListener = null;
        }
    }
}
