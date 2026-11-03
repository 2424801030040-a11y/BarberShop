package com.example.salonapp;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.bumptech.glide.Glide;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;
import java.io.*;
import java.util.*;
import okhttp3.*;
import org.json.JSONObject;
public class ProfileFragment extends Fragment {
    private ImageView avatar; private DocumentReference ref;
    private final ActivityResultLauncher<String> picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> { if (uri != null) upload(uri); });
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.fragment_profile, c, false);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        avatar = v.findViewById(R.id.ivAvatar);
        EditText name = v.findViewById(R.id.etName), phone = v.findViewById(R.id.etPhone);
        ((TextView) v.findViewById(R.id.tvEmail)).setText(u.getEmail() != null && !u.getEmail().isEmpty() ? u.getEmail() : u.getPhoneNumber());
        ref = db.collection("users").document(u.getUid());
        ref.get().addOnSuccessListener(d -> {
            name.setText(d.getString("name") != null ? d.getString("name") : u.getDisplayName()); phone.setText(d.getString("phone"));
            Map<String, Object> identity = new HashMap<>();
            if (u.getEmail() != null && !u.getEmail().isEmpty()) identity.put("email", u.getEmail());
            if ((d.getString("name") == null || d.getString("name").trim().isEmpty())
                    && u.getDisplayName() != null && !u.getDisplayName().trim().isEmpty())
                identity.put("name", u.getDisplayName());
            if (!identity.isEmpty()) ref.set(identity, SetOptions.merge());
            String url = d.getString("avatarUrl"); if (url == null && u.getPhotoUrl() != null) url = u.getPhotoUrl().toString();
            if (url != null && isAdded()) Glide.with(this).load(url).circleCrop().into(avatar);
        });
        v.findViewById(R.id.btnPhoto).setOnClickListener(x -> picker.launch("image/*"));
        v.findViewById(R.id.btnAddresses).setOnClickListener(x -> getParentFragmentManager().beginTransaction().replace(R.id.container, new AddressFragment()).addToBackStack(null).commit());
        v.findViewById(R.id.btnAppts).setOnClickListener(x -> getParentFragmentManager().beginTransaction().replace(R.id.container, new AppointmentsFragment()).addToBackStack(null).commit());
        v.findViewById(R.id.btnSave).setOnClickListener(x -> {
            Map<String, Object> m = new HashMap<>(); m.put("name", name.getText().toString().trim()); m.put("phone", phone.getText().toString().trim());
            ref.set(m, SetOptions.merge()).addOnSuccessListener(r -> toast("Đã lưu hồ sơ"));
        });
        v.findViewById(R.id.btnOrders).setOnClickListener(x -> getParentFragmentManager().beginTransaction().replace(R.id.container, new OrdersFragment()).addToBackStack(null).commit());
        View staffButton = v.findViewById(R.id.btnStaff);
        staffButton.setVisibility(View.GONE);
        u.getIdToken(false).addOnSuccessListener(token -> {
            if (isAdded() && Boolean.TRUE.equals(token.getClaims().get("staff"))) staffButton.setVisibility(View.VISIBLE);
        });
        staffButton.setOnClickListener(x -> getParentFragmentManager().beginTransaction().replace(R.id.container, new StaffFragment()).addToBackStack(null).commit());
        v.findViewById(R.id.btnLogout).setOnClickListener(x -> {
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(getContext(), LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        return v;
    }
    /** Unsigned Cloudinary upload: images only, max 5 MB (enforce the same limits in the preset). */
    private void upload(Uri uri) {
        String mime = requireContext().getContentResolver().getType(uri);
        if (mime == null || !mime.startsWith("image/")) { toast("Vui lòng chọn một ảnh"); return; }
        toast("Đang tải ảnh lên...");
        new Thread(() -> {
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > Config.MAX_IMAGE_BYTES) throw new IOException("Ảnh lớn hơn 5 MB"); }
                RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("upload_preset", Config.UPLOAD_PRESET)
                    .addFormDataPart("file", "avatar", RequestBody.create(bo.toByteArray(), MediaType.parse(mime))).build();
                Request req = new Request.Builder().url("https://api.cloudinary.com/v1_1/" + Config.CLOUD_NAME + "/image/upload").post(body).build();
                try (Response r = new OkHttpClient().newCall(req).execute()) {
                    String url = new JSONObject(r.body().string()).getString("secure_url");
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        Map<String, Object> m = new HashMap<>(); m.put("avatarUrl", url);
                        ref.set(m, SetOptions.merge());
                        Glide.with(this).load(url).circleCrop().into(avatar); toast("Đã cập nhật ảnh");
                    });
                }
            } catch (Exception e) { if (isAdded()) requireActivity().runOnUiThread(() -> toast("Tải ảnh thất bại: " + e.getMessage())); }
        }).start();
    }
    private void toast(String m) { if (getContext() != null) Toast.makeText(getContext(), m, Toast.LENGTH_LONG).show(); }
}
