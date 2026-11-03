package com.example.salonapp;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.*;
public class MyFirebaseService extends FirebaseMessagingService {
    @Override public void onNewToken(@NonNull String t) { Notify.saveToken(t); }
    @Override public void onMessageReceived(@NonNull RemoteMessage m) {
        RemoteMessage.Notification n = m.getNotification();
        if (n != null) Notify.show(this, n.getTitle(), n.getBody(), m.getData().get("notificationId"));
    }
}
