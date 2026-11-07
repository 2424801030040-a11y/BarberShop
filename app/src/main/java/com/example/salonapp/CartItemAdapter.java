package com.example.salonapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/** Cart rows with a product image and direct quantity controls. */
public class CartItemAdapter extends RecyclerView.Adapter<CartItemAdapter.Holder> {
    public interface OnQuantityChangeListener {
        void onQuantityChange(Item item, int change);
    }

    private final List<Item> items = new ArrayList<>();
    private OnQuantityChangeListener quantityChangeListener;

    public void setItems(List<Item> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void setOnQuantityChange(OnQuantityChangeListener listener) {
        quantityChangeListener = listener;
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cart, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Item item = items.get(position);
        boolean gridMode = holder.itemView.getParent() instanceof RecyclerView
                && ((RecyclerView) holder.itemView.getParent()).getLayoutManager() instanceof GridLayoutManager;
        holder.content.setOrientation(gridMode ? android.widget.LinearLayout.VERTICAL : android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams imageParams = (android.widget.LinearLayout.LayoutParams) holder.image.getLayoutParams();
        imageParams.width = gridMode ? ViewGroup.LayoutParams.MATCH_PARENT : dp(holder.itemView, 76);
        imageParams.height = dp(holder.itemView, gridMode ? 112 : 76);
        holder.image.setLayoutParams(imageParams);
        android.widget.LinearLayout.LayoutParams detailsParams = (android.widget.LinearLayout.LayoutParams) holder.details.getLayoutParams();
        detailsParams.width = gridMode ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        detailsParams.weight = gridMode ? 0 : 1;
        detailsParams.leftMargin = gridMode ? 0 : dp(holder.itemView, 12);
        detailsParams.topMargin = gridMode ? dp(holder.itemView, 8) : 0;
        holder.details.setLayoutParams(detailsParams);
        holder.name.setText(item.name == null ? "Sản phẩm" : item.name);
        holder.price.setText(String.format(Vi.VN, "%,.0f đ / sản phẩm", item.price));
        holder.lineTotal.setText(String.format(Vi.VN, "Thành tiền: %,.0f đ", item.price * item.qty));
        holder.quantity.setText(String.valueOf(item.qty));
        holder.decrease.setContentDescription(item.qty <= 1 ? "Xóa sản phẩm khỏi giỏ hàng" : "Giảm số lượng");
        holder.increase.setContentDescription("Tăng số lượng");

        if (item.imageUrl != null && item.imageUrl.startsWith("https://")) {
            holder.image.setContentDescription("Ảnh " + (item.name == null ? "sản phẩm" : item.name));
            Glide.with(holder.image).load(item.imageUrl).centerCrop().into(holder.image);
        } else {
            Glide.with(holder.image).clear(holder.image);
            holder.image.setImageDrawable(null);
            holder.image.setContentDescription("Chưa có ảnh sản phẩm");
        }

        holder.decrease.setOnClickListener(view -> {
            if (quantityChangeListener != null) quantityChangeListener.onQuantityChange(item, -1);
        });
        holder.increase.setOnClickListener(view -> {
            if (quantityChangeListener != null) quantityChangeListener.onQuantityChange(item, 1);
        });
    }

    @Override public int getItemCount() { return items.size(); }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final android.widget.LinearLayout content, details;
        final ImageView image;
        final TextView name, price, lineTotal, quantity;
        final MaterialButton decrease, increase;

        Holder(View view) {
            super(view);
            content = view.findViewById(R.id.cartContent);
            details = view.findViewById(R.id.cartDetails);
            image = view.findViewById(R.id.ivCartImage);
            name = view.findViewById(R.id.tvCartName);
            price = view.findViewById(R.id.tvCartPrice);
            lineTotal = view.findViewById(R.id.tvCartLineTotal);
            quantity = view.findViewById(R.id.tvCartQuantity);
            decrease = view.findViewById(R.id.btnCartDecrease);
            increase = view.findViewById(R.id.btnCartIncrease);
        }
    }
}
