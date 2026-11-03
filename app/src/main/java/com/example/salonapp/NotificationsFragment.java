package com.example.salonapp;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class NotificationsFragment extends Fragment {
    private ListenerRegistration listener;
    private TextCardAdapter adapter;
    private final List<DocumentSnapshot> notifications = new ArrayList<>();

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_appts, parent, false);
        ((TextView) view.findViewById(R.id.tvTitle)).setText("Thông báo");
        RecyclerView list = view.findViewById(R.id.rvList);
        adapter = new TextCardAdapter();
        ViewModeToggle.bind(requireContext(), list, view.findViewById(R.id.viewModeToggle));
        list.setAdapter(adapter);
        adapter.setOnClick(position -> {
            if (position < 0 || position >= notifications.size()) return;
            String id = notifications.get(position).getId();
            getParentFragmentManager().beginTransaction()
                .replace(R.id.container, NotificationDetailFragment.of(id))
                .addToBackStack("notification-detail")
                .commit();
        });
        return view;
    }

    @Override public void onStart() {
        super.onStart();
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;
        listener = FirebaseFirestore.getInstance().collection("notifications")
            .whereEqualTo("recipientUid", uid)
            .addSnapshotListener((snapshot, error) -> {
                if (!isAdded() || adapter == null) return;
                if (error != null || snapshot == null) {
                    adapter.setItems(Collections.singletonList("Không tải được thông báo. Vui lòng thử lại."));
                    return;
                }
                notifications.clear();
                notifications.addAll(snapshot.getDocuments());
                notifications.sort((first, second) -> Long.compare(millis(second), millis(first)));
                List<String> rows = new ArrayList<>();
                SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("vi", "VN"));
                formatter.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
                for (DocumentSnapshot notification : notifications) {
                    String subject = notification.getString("subject");
                    String body = notification.getString("body");
                    Timestamp created = notification.getTimestamp("createdAt");
                    String date = created == null ? "" : formatter.format(created.toDate());
                    String unread = Boolean.TRUE.equals(notification.getBoolean("read")) ? "" : "● ";
                    String preview = body == null ? "" : body.replace('\n', ' ').trim();
                    if (preview.length() > 90) preview = preview.substring(0, 87) + "…";
                    rows.add(unread + "Chi Phèo BarberShop · " + (subject == null ? "Thông báo" : subject)
                        + (date.isEmpty() ? "" : "\n" + date) + (preview.isEmpty() ? "" : "\n" + preview));
                }
                if (rows.isEmpty()) rows.add("Chưa có thông báo mới.");
                adapter.setItems(rows);
            });
    }

    private long millis(DocumentSnapshot document) {
        Timestamp created = document.getTimestamp("createdAt");
        return created == null ? 0L : created.toDate().getTime();
    }

    @Override public void onStop() {
        if (listener != null) { listener.remove(); listener = null; }
        super.onStop();
    }
}
