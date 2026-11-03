package com.example.salonapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.function.IntConsumer;

/** Two-column action cards for the More screen. */
public class MoreActionAdapter extends RecyclerView.Adapter<MoreActionAdapter.Holder> {
    private static final int[] ICONS = {
            R.drawable.ic_action_branch, R.drawable.ic_action_promo, R.drawable.ic_action_faq,
            R.drawable.ic_action_call, R.drawable.ic_action_contact, R.drawable.ic_action_rating
    };
    private static final String[] TITLES = {
            "Chi nhánh & bản đồ", "Khuyến mãi", "Câu hỏi thường gặp",
            "Gọi cho cửa hàng", "Gửi yêu cầu liên hệ", "Đánh giá dịch vụ"
    };
    private static final String[] SUBTITLES = {
            "Tìm cửa hàng gần bạn", "Ưu đãi đang áp dụng", "Chính sách & hỗ trợ",
            "Liên hệ trực tiếp", "Chúng tôi luôn lắng nghe", "Chia sẻ trải nghiệm sau lịch hẹn"
    };
    private IntConsumer onClick;

    public void setOnClick(IntConsumer listener) { onClick = listener; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_more_action, parent, false);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.icon.setImageResource(ICONS[position]);
        holder.title.setText(TITLES[position]);
        holder.subtitle.setText(SUBTITLES[position]);
        holder.itemView.setOnClickListener(v -> {
            int current = holder.getBindingAdapterPosition();
            if (onClick != null && current != RecyclerView.NO_POSITION) onClick.accept(current);
        });
    }

    @Override public int getItemCount() { return TITLES.length; }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title, subtitle;
        Holder(View view) {
            super(view);
            icon = view.findViewById(R.id.ivActionIcon);
            title = view.findViewById(R.id.tvActionTitle);
            subtitle = view.findViewById(R.id.tvActionSubtitle);
        }
    }
}
