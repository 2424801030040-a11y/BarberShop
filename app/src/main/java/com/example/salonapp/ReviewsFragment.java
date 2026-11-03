package com.example.salonapp;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.firestore.*;
import java.util.*;
public class ReviewsFragment extends Fragment {
    public static ReviewsFragment of(String id, String name) {
        ReviewsFragment f = new ReviewsFragment(); Bundle b = new Bundle(); b.putString("id", id); b.putString("name", name); f.setArguments(b); return f;
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inf.inflate(R.layout.fragment_appts, c, false);
        TextView title = v.findViewById(R.id.tvTitle);
        RecyclerView list = v.findViewById(R.id.rvList);
        TextCardAdapter adapter = new TextCardAdapter();
        ViewModeToggle.bind(requireContext(), list, v.findViewById(R.id.viewModeToggle));
        list.setAdapter(adapter);
        title.setText(getArguments().getString("name"));
        FirebaseFirestore.getInstance().collection("reviews").whereEqualTo("targetId", getArguments().getString("id")).get().addOnSuccessListener(sn -> {
            List<String> rows = new ArrayList<>(); double sum = 0;
            for (DocumentSnapshot d : sn) {
                Double r = d.getDouble("rating"); sum += r == null ? 0 : r;
                rows.add("★ " + (r == null ? 0 : r.intValue()) + "  " + (d.getString("comment") == null ? "" : d.getString("comment")));
            }
            if (rows.isEmpty()) rows.add("Chưa có đánh giá");
            else title.setText(String.format(Locale.US, "%s  ★ %.1f (%d)", getArguments().getString("name"), sum / sn.size(), sn.size()));
            if (isAdded()) adapter.setItems(rows);
        });
        return v;
    }
}
