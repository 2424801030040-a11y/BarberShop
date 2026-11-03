package com.example.salonapp;
import android.view.*;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.ImageButton;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.*;
import java.util.Locale;
import java.util.function.Consumer;
public class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.VH> {
    private final List<Item> all = new ArrayList<>(), shown = new ArrayList<>();
    private Consumer<Item> onClick, onLong;
    private Consumer<Item> onAddToCart;
    private String favoriteCollection = "products";
    private boolean commerceMode, productMode;
    private String query = "", category = null; private int sort = 0;
    public void setOnClick(Consumer<Item> c) { onClick = c; }
    public void setOnLong(Consumer<Item> c) { onLong = c; }
    public void setOnAddToCart(Consumer<Item> c) { onAddToCart = c; }
    public void setCommerceMode(boolean commerce, boolean products, String collection) {
        commerceMode = commerce; productMode = products; favoriteCollection = collection;
        notifyDataSetChanged();
    }
    public void setItems(List<Item> items) { all.clear(); all.addAll(items); apply(); }
    public void setQuery(String q) { query = q; apply(); }
    public void setCategory(String c) { category = c; apply(); }
    public void setSort(int s) { sort = s; apply(); }
    private void apply() {
        shown.clear();
        for (Item i : all) {
            if (i.name == null || !i.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
            if (category != null && !category.equals(i.category)) continue;
            shown.add(i);
        }
        if (sort == 1) shown.sort(Comparator.comparingDouble(i -> i.price));
        else if (sort == 2) shown.sort((a, b) -> Double.compare(b.price, a.price));
        else if (sort == 3) shown.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        notifyDataSetChanged();
    }
    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_row, p, false));
    }
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Item i = shown.get(pos);
        String d = i.description == null ? "" : i.description;
        boolean out = i.stock != null && i.stock <= 0;
        h.name.setText(i.name); h.desc.setText(d);
        h.meta.setVisibility(View.GONE);
        if (productMode && i.stock != null) {
            h.meta.setVisibility(View.VISIBLE);
            h.meta.setText(out ? "Hết hàng" : "Còn hàng · " + i.stock);
        } else if (favoriteCollection.equals("services") && i.durationMinutes != null && i.durationMinutes > 0) {
            h.meta.setVisibility(View.VISIBLE);
            h.meta.setText(i.durationMinutes + " phút");
        }
        h.favorite.setVisibility(commerceMode ? View.VISIBLE : View.GONE);
        h.addToCart.setVisibility(productMode ? View.VISIBLE : View.GONE);
        h.favorite.setImageResource(FavoriteStore.contains(h.itemView.getContext(), favoriteCollection, i.id)
                ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        h.favorite.setContentDescription(FavoriteStore.contains(h.itemView.getContext(), favoriteCollection, i.id)
                ? "Bỏ yêu thích" : "Thêm vào yêu thích");
        h.favorite.setOnClickListener(v -> {
            boolean saved = FavoriteStore.toggle(v.getContext(), favoriteCollection, i.id);
            h.favorite.setImageResource(saved ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            h.favorite.setContentDescription(saved ? "Bỏ yêu thích" : "Thêm vào yêu thích");
            android.widget.Toast.makeText(v.getContext(), saved ? "Đã thêm vào yêu thích" : "Đã bỏ yêu thích", android.widget.Toast.LENGTH_SHORT).show();
        });
        h.addToCart.setEnabled(!out);
        h.addToCart.setAlpha(out ? 0.55f : 1f);
        h.addToCart.setText(out ? "Tạm hết hàng" : "Thêm vào giỏ");
        h.addToCart.setOnClickListener(v -> { if (onAddToCart != null) onAddToCart.accept(i); });
        h.map.setVisibility(i.isBranch ? View.VISIBLE : View.GONE);
        h.map.setOnClickListener(v -> { if (onClick != null) onClick.accept(i); });
        if (i.imageUrl != null && i.imageUrl.startsWith("https://res.cloudinary.com/")) {
            h.image.setVisibility(View.VISIBLE);
            Glide.with(h.image).load(i.imageUrl).centerCrop().into(h.image);
        } else {
            h.image.setImageDrawable(null);
            h.image.setVisibility(View.GONE);
        }
        h.price.setText(String.format(Vi.VN, "%,.0f đ", i.price));
        h.price.setVisibility(i.price > 0 ? View.VISIBLE : View.GONE);
        h.itemView.setAlpha(1f);
        h.itemView.setOnClickListener(v -> { if (onClick != null) onClick.accept(i); });
        h.itemView.setOnLongClickListener(v -> { if (onLong != null) { onLong.accept(i); return true; } return false; });
    }
    @Override public int getItemCount() { return shown.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView name, desc, price, meta;
        ImageView image;
        Button map, addToCart;
        ImageButton favorite;
        VH(View v) { super(v); name = v.findViewById(R.id.tvName); desc = v.findViewById(R.id.tvDesc); price = v.findViewById(R.id.tvPrice); meta = v.findViewById(R.id.tvMeta); image = v.findViewById(R.id.ivImage); map = v.findViewById(R.id.btnMap); favorite = v.findViewById(R.id.btnFavorite); addToCart = v.findViewById(R.id.btnAddToCart); }
    }
}
