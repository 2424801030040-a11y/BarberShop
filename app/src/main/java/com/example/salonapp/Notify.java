package com.example.salonapp;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.work.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
public class Notify {
    static final String CH = "barbershop";
    public static void show(Context c, String title, String body) {
        show(c, title, body, null);
    }
    public static void show(Context c, String title, String body, String notificationId) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Chí Phèo BarberShop", NotificationManager.IMPORTANCE_DEFAULT));
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(c, MainActivity.class);
        if (notificationId != null) intent.putExtra(MainActivity.EXTRA_NOTIFICATION_ID, notificationId);
        PendingIntent pi = PendingIntent.getActivity(c, notificationId == null ? (int) System.currentTimeMillis() : notificationId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify((int) System.currentTimeMillis(), new NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(c.getResources(), R.drawable.ic_brand_mark))
            .setColor(0xFF8D3C32)
            .setContentTitle(title).setContentText(body).setContentIntent(pi).setAutoCancel(true).build());
    }
    /** Local reminder 1 hour before the appointment (works without a server). */
    public static void scheduleReminder(Context c, String apptId, String label, long startMs) {
        long delay = startMs - 3600_000L - System.currentTimeMillis();
        if (delay <= 0) return;
        WorkManager.getInstance(c).enqueueUniqueWork(apptId, ExistingWorkPolicy.REPLACE, new OneTimeWorkRequest.Builder(ReminderWorker.class)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(new Data.Builder().putString("label", label).build()).build());
    }
    public static void cancelReminder(Context c, String apptId) { WorkManager.getInstance(c).cancelUniqueWork(apptId); }
    public static void saveToken(String token) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid != null) FirebaseFirestore.getInstance().collection("users").document(uid).set(Collections.singletonMap("fcmToken", token), SetOptions.merge());
    }
}
