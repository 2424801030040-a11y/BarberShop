package com.example.salonapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Compact card rows used by appointment, order, review, and address histories. */
public class TextCardAdapter extends RecyclerView.Adapter<TextCardAdapter.Holder> {
    private final List<String> rows = new ArrayList<>();
    private IntConsumer onClick;

    public void setItems(List<String> items) {
        rows.clear();
        rows.addAll(items);
        notifyDataSetChanged();
    }

    public void setOnClick(IntConsumer listener) { onClick = listener; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_text_card, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.text.setText(rows.get(position));
        holder.itemView.setOnClickListener(v -> {
            int current = holder.getBindingAdapterPosition();
            if (onClick != null && current != RecyclerView.NO_POSITION) onClick.accept(current);
        });
    }

    @Override public int getItemCount() { return rows.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView text;
        Holder(View view) { super(view); text = view.findViewById(R.id.tvItemText); }
    }
}
