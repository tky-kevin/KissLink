package com.nfctransfer.app.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileTransferClient {

    private static final String TAG = "FileTransferClient";
    private static final int BUFFER_SIZE = 65536;

    public interface Callback {
        void onProgressUpdate(String fileName, int percent);
        void onFileSent(String fileName);
        void onAllFilesSent(int totalCount);
        void onError(String fileName, Exception e);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void sendFiles(Context context, String targetIp, List<Uri> fileUris, Callback callback) {
        executor.execute(() -> doSend(context.getApplicationContext(), targetIp, fileUris, callback));
    }

    private void doSend(Context context, String targetIp, List<Uri> fileUris, Callback callback) {
        ContentResolver cr = context.getContentResolver();

        try (Socket socket = new Socket(targetIp, FileTransferServer.PORT);
             OutputStream raw = socket.getOutputStream()) {

            DataOutputStream out = new DataOutputStream(raw);

            out.writeInt(fileUris.size());

            for (Uri uri : fileUris) {
                String fileName = resolveFileName(cr, uri);
                long fileSize = resolveFileSize(cr, uri);

                byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
                out.writeInt(nameBytes.length);
                out.write(nameBytes);
                out.writeLong(fileSize);

                Log.d(TAG, "Sending: " + fileName + " (" + fileSize + " bytes) to " + targetIp);

                try (InputStream input = cr.openInputStream(uri)) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    long sent = 0;
                    int read;

                    while ((read = input.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        sent += read;
                        if (fileSize > 0) {
                            final int percent = (int) (sent * 100L / fileSize);
                            final String fn = fileName;
                            mainHandler.post(() -> {
                                if (callback != null) callback.onProgressUpdate(fn, percent);
                            });
                        }
                    }
                }

                out.flush();
                Log.d(TAG, "Sent: " + fileName);
                final String fn = fileName;
                mainHandler.post(() -> {
                    if (callback != null) callback.onFileSent(fn);
                });
            }

            final int total = fileUris.size();
            mainHandler.post(() -> {
                if (callback != null) callback.onAllFilesSent(total);
            });

        } catch (IOException e) {
            Log.e(TAG, "Send error", e);
            mainHandler.post(() -> {
                if (callback != null) callback.onError(null, e);
            });
        }
    }

    private String resolveFileName(ContentResolver cr, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = cr.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file_" + System.currentTimeMillis();
    }

    private long resolveFileSize(ContentResolver cr, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = cr.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx);
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }
}
