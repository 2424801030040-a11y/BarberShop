package com.example.salonapp;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import com.google.firebase.firestore.*;
import java.util.*;
/** Shared list for services, products, branches, promotions and other catalog collections. */
public class ListFragment extends Fragment {
    public static ListFragment of(String collection) {
        ListFragment f = new ListFragment(); Bundle b = new Bundle(); b.putString("c", collection); f.setArguments(b); return f;
    }
    private void open(Fragment f) { getParentFragmentManager().beginTransaction().replace(R.id.container, f).addToBackStack(null).commit(); }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.fragment_list, c, false);
        String col = getArguments().getString("c");
        boolean products = col.equals("products");
        ItemAdapter adapter = new ItemAdapter();
        RecyclerView rv = v.findViewById(R.id.rv);
        ViewModeToggle.bind(requireContext(), rv, v.findViewById(R.id.viewModeToggle));
        rv.setAdapter(adapter);
        ((EditText) v.findViewById(R.id.etSearch)).addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) { adapter.setQuery(a.toString()); }
            public void afterTextChanged(Editable e) {}
        });
        Spinner spCat = v.findViewById(R.id.spCat), spSort = v.findViewById(R.id.spSort);
        List<String> cats = new ArrayList<>();
        if (products) {
            v.findViewById(R.id.filterBar).setVisibility(View.VISIBLE);
            spSort.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, new String[]{"Sắp xếp: mặc định", "Giá: thấp đến cao", "Giá: cao đến thấp", "Tên: A–Z"}));
            spSort.setOnItemSelectedListener(new OnSel() { public void on(int p) { adapter.setSort(p); } });
            spCat.setOnItemSelectedListener(new OnSel() { public void on(int p) { adapter.setCategory(p == 0 || p >= cats.size() ? null : cats.get(p)); } });
            adapter.setOnLong(i -> open(ReviewsFragment.of(i.id, i.name)));
        }
        adapter.setCommerceMode(products || col.equals("services"), products, col);
        adapter.setOnAddToCart(i -> {
            boolean added = Cart.add(i);
            Toast.makeText(getContext(), added ? "Đã thêm vào giỏ hàng" : "Hết hàng hoặc đã đạt giới hạn tồn kho", Toast.LENGTH_SHORT).show();
            if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).refreshCartBadge();
        });
        adapter.setOnClick(i -> {
            if (products) {
                // Keep the card itself focused on details; cart actions are explicit.
                open(ReviewsFragment.of(i.id, i.name));
            } else if (col.equals("services")) open(ReviewsFragment.of(i.id, i.name));
            else if (col.equals("branches")) {
                try {
                    String query = Uri.encode((i.name == null ? "" : i.name + " ") + (i.description == null ? "" : i.description));
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + query)));
                }
                catch (Exception e) { Toast.makeText(getContext(), "Không tìm thấy ứng dụng bản đồ", Toast.LENGTH_SHORT).show(); }
            }
        });
        FirebaseFirestore.getInstance().collection(col).get()
            .addOnSuccessListener(snap -> {
                List<Item> list = new ArrayList<>(); TreeSet<String> set = new TreeSet<>();
                for (DocumentSnapshot d : snap) { Item i = d.toObject(Item.class); if (i != null) { i.id = d.getId(); i.isBranch = col.equals("branches"); list.add(i); if (i.category != null) set.add(i.category); } }
                adapter.setItems(list);
                if (products && isAdded()) { cats.clear(); cats.add("Tất cả danh mục"); cats.addAll(set); spCat.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, cats)); }
            })
            .addOnFailureListener(e -> Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show());
        return v;
    }
}
