package com.example.salonapp;

import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class NotificationDetailFragment extends Fragment {
    private static final String ARG_ID = "notificationId";

    static NotificationDetailFragment of(String id) {
        NotificationDetailFragment fragment = new NotificationDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ID, id);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_notification_detail, parent, false);
        String id = getArguments() == null ? null : getArguments().getString(ARG_ID);
        if (id == null) return view;
        FirebaseFirestore.getInstance().collection("notifications").document(id).get()
            .addOnSuccessListener(document -> {
                if (!isAdded() || !document.exists()) return;
                TextView subject = view.findViewById(R.id.tvNotificationSubject);
                TextView from = view.findViewById(R.id.tvNotificationFrom);
                TextView date = view.findViewById(R.id.tvNotificationDate);
                TextView body = view.findViewById(R.id.tvNotificationBody);
                subject.setText(value(document.getString("subject"), "Thông báo"));
                from.setText("Từ  ·  " + value(document.getString("from"), "Chi Phèo BarberShop"));
                body.setText(value(document.getString("body"), ""));
                Timestamp created = document.getTimestamp("createdAt");
                if (created != null) {
                    SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("vi", "VN"));
                    formatter.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
                    date.setText(formatter.format(created.toDate()));
                }
                if (!Boolean.TRUE.equals(document.getBoolean("read")))
                    document.getReference().update("read", true, "readAt", FieldValue.serverTimestamp());
            });
        return view;
    }

    private String value(String text, String fallback) { return text == null || text.trim().isEmpty() ? fallback : text; }
}
