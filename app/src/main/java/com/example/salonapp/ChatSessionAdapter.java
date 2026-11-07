package com.example.salonapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/** RecyclerView adapter used by the hair advisor's chat history drawer. */
public class ChatSessionAdapter extends RecyclerView.Adapter<ChatSessionAdapter.SessionHolder> {
    private final List<ChatSession> sessions = new ArrayList<>();
    private Consumer<ChatSession> onClick;
    private Consumer<ChatSession> onLongClick;

    public void setOnClick(Consumer<ChatSession> listener) { onClick = listener; }
    public void setOnLongClick(Consumer<ChatSession> listener) { onLongClick = listener; }

    public void submitList(List<ChatSession> updated) {
        sessions.clear();
        if (updated != null) sessions.addAll(updated);
        notifyDataSetChanged();
    }

    @NonNull @Override public SessionHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new SessionHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_session, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull SessionHolder holder, int position) {
        ChatSession session = sessions.get(position);
        holder.title.setText(session.getTitle() == null || session.getTitle().trim().isEmpty()
                ? "Cuộc trò chuyện mới" : session.getTitle());
        if (session.getUpdatedAt() == null) holder.time.setText("");
        else holder.time.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(session.getUpdatedAt().toDate().getTime())));
        holder.itemView.setOnClickListener(view -> {
            int current = holder.getBindingAdapterPosition();
            if (current != RecyclerView.NO_POSITION && onClick != null) onClick.accept(sessions.get(current));
        });
        holder.itemView.setOnLongClickListener(view -> {
            int current = holder.getBindingAdapterPosition();
            if (current == RecyclerView.NO_POSITION || onLongClick == null) return false;
            onLongClick.accept(sessions.get(current));
            return true;
        });
    }

    @Override public int getItemCount() { return sessions.size(); }

    static final class SessionHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView time;
        SessionHolder(View view) {
            super(view);
            title = view.findViewById(R.id.tvChatSessionTitle);
            time = view.findViewById(R.id.tvChatSessionTime);
        }
    }
}
