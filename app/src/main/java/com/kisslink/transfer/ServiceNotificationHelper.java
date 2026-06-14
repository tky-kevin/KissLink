package com.kisslink.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.kisslink.ui.home.HomeActivity;

/**
 * Manages foreground service notifications for the transfer service.
 */
final class ServiceNotificationHelper {

    private static final String CHANNEL_ID = "kisslink_transfer";
    static final int NOTIF_ID = 1001;

    private final Service service;
    private final NotificationManager notificationManager;

    ServiceNotificationHelper(@NonNull Service service) {
        this.service = service;
        this.notificationManager = service.getSystemService(NotificationManager.class);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "KissLink 傳輸", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("顯示配對與檔案傳輸進度");
        notificationManager.createNotificationChannel(ch);
    }

    void update(@NonNull String text, int progress) {
        notificationManager.notify(NOTIF_ID, build(text, progress));
    }

    @NonNull
    Notification build(@NonNull String text, int progress) {
        Intent tap = new Intent(service, HomeActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(service, 0, tap, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("KissLink")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true);
        if (progress > 0 && progress < 100) b.setProgress(100, progress, false);
        return b.build();
    }
}
