package com.example.salonapp;
import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.view.View;
import android.widget.TextView;
public class MainActivity extends AppCompatActivity {
    static final String EXTRA_NOTIFICATION_ID = "notificationId";
    private BottomNavigationView bottomNav;
    private ListenerRegistration notificationsListener;
    private int unreadNotifications;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(Notify::saveToken);
        FirebaseMessaging.getInstance().subscribeToTopic("promotions");
        FragmentManager fragments = getSupportFragmentManager();
        android.widget.ImageButton back = findViewById(R.id.btnBack);
        Runnable updateHeaderActions = () -> {
            boolean nested = fragments.getBackStackEntryCount() > 0;
            back.setVisibility(nested ? View.VISIBLE : View.GONE);
            findViewById(R.id.cartAction).setVisibility(!nested ? View.VISIBLE : View.GONE);
            findViewById(R.id.notificationAction).setVisibility(!nested ? View.VISIBLE : View.GONE);
            refreshCartBadge();
        };
        fragments.addOnBackStackChangedListener(updateHeaderActions::run);
        updateHeaderActions.run();
        back.setOnClickListener(view -> { if (fragments.getBackStackEntryCount() > 0) fragments.popBackStack(); });
        bottomNav = findViewById(R.id.bottomNav);
        findViewById(R.id.btnHeaderNotifications).setOnClickListener(view -> fragments.beginTransaction().replace(R.id.container, new NotificationsFragment()).addToBackStack("notifications").commit());
        findViewById(R.id.btnHeaderCart).setOnClickListener(view -> fragments.beginTransaction().replace(R.id.container, new CartFragment()).addToBackStack("cart").commit());
        bottomNav.setOnItemSelectedListener(item -> {
            if (fragments.getBackStackEntryCount() > 0) fragments.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            int id = item.getItemId();
            Fragment f = id == R.id.nav_services ? ListFragment.of("services")
                       : id == R.id.nav_booking ? new BookingFragment()
                       : id == R.id.nav_shop ? ListFragment.of("products") : id == R.id.nav_more ? new MoreFragment() : new ProfileFragment();
            fragments.beginTransaction().replace(R.id.container, f).commit();
            updateHeaderActions.run();
            return true;
        });
        bottomNav.setSelectedItemId(R.id.nav_services);
        String notificationId = getIntent().getStringExtra(EXTRA_NOTIFICATION_ID);
        if (notificationId != null && !notificationId.isEmpty()) {
            fragments.beginTransaction().replace(R.id.container, NotificationDetailFragment.of(notificationId))
                .addToBackStack("notification-detail").commit();
        }
    }

    public void refreshCartBadge() {
        View action = findViewById(R.id.cartAction);
        TextView badge = findViewById(R.id.tvCartBadge);
        if (action == null || badge == null) return;
        int count = Cart.itemCount();
        badge.setText(count > 99 ? "99+" : String.valueOf(count));
        badge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    @Override protected void onStart() {
        super.onStart();
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;
        notificationsListener = FirebaseFirestore.getInstance().collection("notifications")
            .whereEqualTo("recipientUid", uid)
            .addSnapshotListener((snapshot, error) -> {
                if (error != null || snapshot == null) return;
                unreadNotifications = 0;
                for (com.google.firebase.firestore.DocumentSnapshot item : snapshot.getDocuments())
                    if (!Boolean.TRUE.equals(item.getBoolean("read"))) unreadNotifications++;
                refreshNotificationBadge();
            });
    }

    @Override protected void onStop() {
        if (notificationsListener != null) { notificationsListener.remove(); notificationsListener = null; }
        super.onStop();
    }

    private void refreshNotificationBadge() {
        TextView badge = findViewById(R.id.tvNotificationBadge);
        if (badge == null) return;
        badge.setText(unreadNotifications > 99 ? "99+" : String.valueOf(unreadNotifications));
        badge.setVisibility(unreadNotifications > 0 ? View.VISIBLE : View.GONE);
    }
}
