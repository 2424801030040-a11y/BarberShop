package com.example.salonapp;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
public class ReminderWorker extends Worker {
    public ReminderWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    @NonNull @Override public Result doWork() {
        Notify.show(getApplicationContext(), "Nhắc lịch hẹn", "1 giờ nữa: " + getInputData().getString("label"));
        return Result.success();
    }
}
