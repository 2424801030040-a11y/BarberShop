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
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Displays locally saved service and product favorites. */
public class FavoritesFragment extends Fragment {
    private ItemAdapter adapter;
    private TextView empty;
    private String collection = "services";

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_favorites, parent, false);
        RecyclerView list = view.findViewById(R.id.rvFavorites);
        empty = view.findViewById(R.id.tvFavoritesEmpty);
        adapter = new ItemAdapter();
        adapter.setCommerceMode(true, false, collection);
        list.setAdapter(adapter);
        ViewModeToggle.bind(requireContext(), list, view.findViewById(R.id.viewModeToggle));

        adapter.setOnClick(item -> open(ReviewsFragment.of(item.id, item.name)));
        adapter.setOnAddToCart(item -> {
            boolean added = Cart.add(item);
            Toast.makeText(requireContext(), added ? "Đã thêm vào giỏ hàng" : "Hết hàng hoặc đã đạt giới hạn tồn kho", Toast.LENGTH_SHORT).show();
            if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).refreshCartBadge();
        });
        adapter.setOnFavoriteChanged(item -> loadFavorites());

        MaterialButtonToggleGroup tabs = view.findViewById(R.id.favoritesTypeToggle);
        tabs.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) return;
            collection = checkedId == R.id.btnFavoriteProducts ? "products" : "services";
            adapter.setCommerceMode(true, collection.equals("products"), collection);
            loadFavorites();
        });
        tabs.check(R.id.btnFavoriteServices);
        return view;
    }

    private void loadFavorites() {
        if (!isAdded() || adapter == null) return;
        String selectedCollection = collection;
        Set<String> savedIds = FavoriteStore.getSavedIds(requireContext(), selectedCollection);
        empty.setText(savedIds.isEmpty() ? emptyMessage(selectedCollection) : "Đang tải mục yêu thích…");
        empty.setVisibility(savedIds.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.setItems(new ArrayList<>());
        if (savedIds.isEmpty()) return;

        FirebaseFirestore.getInstance().collection(selectedCollection).get()
                .addOnSuccessListener(snapshot -> {
                    if (!isAdded() || !selectedCollection.equals(collection)) return;
                    List<Item> favorites = new ArrayList<>();
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        if (!savedIds.contains(document.getId())) continue;
                        Item item = document.toObject(Item.class);
                        if (item == null) continue;
                        item.id = document.getId();
                        favorites.add(item);
                    }
                    adapter.setItems(favorites);
                    empty.setText(emptyMessage(selectedCollection));
                    empty.setVisibility(favorites.isEmpty() ? View.VISIBLE : View.GONE);
                })
                .addOnFailureListener(error -> {
                    if (!isAdded() || !selectedCollection.equals(collection)) return;
                    empty.setText("Không thể tải mục yêu thích. Vui lòng thử lại.");
                    empty.setVisibility(View.VISIBLE);
                    Toast.makeText(requireContext(), "Không tải được danh sách yêu thích", Toast.LENGTH_SHORT).show();
                });
    }

    private String emptyMessage(String selectedCollection) {
        return selectedCollection.equals("products")
                ? "Bạn chưa lưu sản phẩm yêu thích nào."
                : "Bạn chưa lưu dịch vụ yêu thích nào.";
    }

    private void open(Fragment fragment) {
        getParentFragmentManager().beginTransaction()
                .replace(R.id.container, fragment)
                .addToBackStack(null)
                .commit();
    }
}
