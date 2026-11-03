package com.example.salonapp;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.*;
public class AddressFragment extends Fragment {
    private CollectionReference col; private RecyclerView list; private TextCardAdapter adapter;
    private List<DocumentSnapshot> docs = new ArrayList<>();
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
        LinearLayout box = new LinearLayout(getContext()); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(48, 16, 48, 0);
        EditText label = new EditText(getContext()), text = new EditText(getContext());
        label.setHint("Nhãn (Nhà, Cơ quan...)"); text.setHint("Địa chỉ đầy đủ");
        label.setBackgroundResource(R.drawable.bg_field); text.setBackgroundResource(R.drawable.bg_field);
        box.addView(label); box.addView(text);
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Địa chỉ mới").setView(box).setPositiveButton("Lưu", (d, w) -> {
            if (text.getText().toString().trim().isEmpty()) return;
            Map<String, Object> m = new HashMap<>(); m.put("label", label.getText().toString().trim()); m.put("text", text.getText().toString().trim()); m.put("isDefault", docs.isEmpty());
            col.add(m).addOnSuccessListener(r -> load());
        }).setNegativeButton("Hủy", null).show();
    }
}
