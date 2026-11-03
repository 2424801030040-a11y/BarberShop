package com.example.salonapp;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.*;
public class ReviewDialog {
    /** Writes reviews/{docId}; security rules check the appointment/order is completed/delivered and allow one review per doc. */
    public static void show(Context ctx, String docId, Map<String, Object> base, String title) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 8));
        final int[] rating = {5};
        TextView[] stars = new TextView[5];
        Runnable refreshStars = () -> {
            for (int i = 0; i < stars.length; i++) {
                stars[i].setText(i < rating[0] ? "★" : "☆");
                stars[i].setTextColor(i < rating[0] ? Color.rgb(205, 157, 66) : Color.rgb(184, 177, 169));
                stars[i].setContentDescription((i + 1) + " sao");
                stars[i].setSelected(i < rating[0]);
            }
        };
        for (int i = 0; i < stars.length; i++) {
            final int selectedRating = i + 1;
            TextView star = new TextView(ctx);
            stars[i] = star;
            star.setTextSize(36);
            star.setGravity(Gravity.CENTER);
            star.setMinWidth(dp(ctx, 42));
            star.setClickable(true);
            star.setFocusable(true);
            star.setOnClickListener(view -> { rating[0] = selectedRating; refreshStars.run(); });
            box.addView(star, new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 52)));
        }
        refreshStars.run();
        new MaterialAlertDialogBuilder(ctx).setTitle(title).setView(box).setPositiveButton("Gửi", (d, w) -> {
            Map<String, Object> m = new HashMap<>(base);
            m.put("userId", FirebaseAuth.getInstance().getUid()); m.put("rating", (double) rating[0]);
            m.put("createdAt", FieldValue.serverTimestamp());
            FirebaseFirestore.getInstance().collection("reviews").document(docId).set(m)
                .addOnSuccessListener(r -> Toast.makeText(ctx, "Cảm ơn bạn đã đánh giá!", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(ctx, "Bạn đã đánh giá rồi hoặc chưa thể đánh giá", Toast.LENGTH_LONG).show());
        }).setNegativeButton("Hủy", null).show();
    }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
