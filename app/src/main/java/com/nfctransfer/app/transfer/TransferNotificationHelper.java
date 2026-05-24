package com.nfctransfer.app.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.nfctransfer.app.R;

public class TransferNotificationHelper {

    public static final String CHANNEL_ID = "nfc_transfer_channel";
    private static final String CHANNEL_NAME = "NFC Transfer";
    private static final int NOTIFICATION_ID_PROGRESS = 1001;
    private static final int NOTIFICATION_ID_COMPLETE = 1002;
    private static final int NOTIFICATION_ID_ERROR = 1003;

    public static void createNotificationChannel(Context context) {
        NotificationChannel progressChannel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        progressChannel.setDescription("File transfer progress");

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(progressChannel);
        }
    }

    public static void showProgressNotification(Context context, String fileName, int percent) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("傳輸中")
                .setContentText(fileName)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setSilent(true)
                .build();

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.notify(NOTIFICATION_ID_PROGRESS, notification);
    }

    public static void showCompletionNotification(Context context, int fileCount, boolean isSend) {
        String title = isSend ? "傳送完成" : "接收完成";
        String text = fileCount + " 個檔案" + (isSend ? "已傳送" : "已接收");

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build();

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.cancel(NOTIFICATION_ID_PROGRESS);
        nm.notify(NOTIFICATION_ID_COMPLETE, notification);
    }

    public static void showErrorNotification(Context context, String fileName) {
        String text = (fileName != null) ? "傳輸失敗：" + fileName : "傳輸失敗";

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("傳輸錯誤")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build();

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.cancel(NOTIFICATION_ID_PROGRESS);
        nm.notify(NOTIFICATION_ID_ERROR, notification);
    }

    public static void cancelProgressNotification(Context context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PROGRESS);
    }
}
