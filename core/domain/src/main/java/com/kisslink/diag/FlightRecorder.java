package com.kisslink.diag;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 全 app「飛行記錄器」(flight recorder)——偶發問題的事後黑盒子。
 *
 * <p>把跨子系統(配對 / Wi-Fi Direct / 傳輸)的序列與關鍵事件持續寫進<b>單一</b> in-process
 * 環形緩衝;發生失敗時把緩衝快照落檔。<b>單一時間線</b>是重點:配對 → 選舉 → 建群 → socket → 傳輸是一條連續因果鏈,交錯在同一條 timeline
 * 才看得出跨層因果(例如「配對成功但 socket 建不起來」)。事後用 {@code adb pull} 或檔案管理器取出,不必每次蹲守 adb 重現。
 *
 * <h3>為何用環形緩衝而非讀 logcat</h3>
 *
 * 第三方 app 沒有 {@code READ_LOGS} 權限,在裝置上只讀得到殘缺的自身 log;自記緩衝完全 不依賴權限,且診斷序列即使 logcat tag 關閉(預設靜默)也照樣留存。
 *
 * <h3>取出位置</h3>
 *
 * {@code <外部檔案目錄>/diagnostics/session-<時戳>.log},例如 debug build: {@code
 * /sdcard/Android/data/com.kisslink.debug/files/diagnostics/}。落檔路徑也會寫進 logcat(info)。
 */
public final class FlightRecorder {

    private static final String TAG = "FlightRecorder";
    private static final int MAX_LINES = 1200; // 環形緩衝容量（約覆蓋數場 session）
    private static final int KEEP_DUMPS = 15; // 落檔保留份數，逾則刪最舊
    private static final String DUMP_DIR = "diagnostics";
    private static final String DUMP_PREFIX = "session-";

    private static final ArrayDeque<String> buffer = new ArrayDeque<>(MAX_LINES);
    private static final SimpleDateFormat LINE_TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_TS =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private FlightRecorder() {}

    /** 記一筆序列;logcat tag 開啟時一併輸出。可從任意執行緒呼叫。 */
    public static void seq(@NonNull String tag, @NonNull String msg) {
        record(tag, msg);
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, "PAIRSEQ " + msg);
    }

    /** 記一筆重要事件並一律輸出 logcat(warn)。供逾時/失敗/狀態錯誤等關鍵節點使用。 */
    public static void event(@NonNull String tag, @NonNull String msg) {
        record(tag, msg);
        Log.w(tag, msg);
    }

    private static void record(@NonNull String tag, @NonNull String msg) {
        synchronized (buffer) {
            // SimpleDateFormat 非執行緒安全：格式化收在 buffer 鎖內，與多執行緒寫入共用同一把鎖。
            String line = LINE_TS.format(new Date()) + " " + tag + ": " + msg;
            if (buffer.size() >= MAX_LINES) buffer.pollFirst();
            buffer.addLast(line);
        }
    }

    /**
     * 把目前緩衝快照落檔。Best-effort:任何 IO 例外都吞掉,絕不影響主流程。
     *
     * @return 檔案絕對路徑(失敗回 {@code null})。
     */
    @Nullable
    public static File dump(@NonNull Context ctx, @NonNull String reason) {
        try {
            File base = ctx.getExternalFilesDir(null);
            if (base == null) {
                Log.w(TAG, "no external files dir");
                return null;
            }
            File dir = new File(base, DUMP_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "cannot mkdir " + dir);
                return null;
            }

            String[] snapshot;
            synchronized (buffer) {
                snapshot = buffer.toArray(new String[0]);
            }

            File out = new File(dir, DUMP_PREFIX + FILE_TS.format(new Date()) + ".log");
            try (FileWriter w = new FileWriter(out)) {
                w.write("== KissLink flight recorder ==\n");
                w.write("when   : " + new Date() + "\n");
                w.write("reason : " + reason + "\n");
                w.write(
                        "device : "
                                + Build.MANUFACTURER
                                + " "
                                + Build.MODEL
                                + " (Android "
                                + Build.VERSION.RELEASE
                                + ", SDK "
                                + Build.VERSION.SDK_INT
                                + ")\n");
                w.write("app    : " + appVersion(ctx) + "\n");
                w.write("lines  : " + snapshot.length + "\n");
                w.write("===============================\n");
                for (String line : snapshot) {
                    w.write(line);
                    w.write('\n');
                }
            }
            rotate(dir);
            Log.i(
                    TAG,
                    "diagnostics dumped → "
                            + out.getAbsolutePath()
                            + " ("
                            + snapshot.length
                            + " lines, reason: "
                            + reason
                            + ")");
            return out;
        } catch (Exception e) {
            Log.w(TAG, "dump failed: " + e.getMessage());
            return null;
        }
    }

    private static void rotate(@NonNull File dir) {
        File[] files =
                dir.listFiles((d, name) -> name.startsWith(DUMP_PREFIX) && name.endsWith(".log"));
        if (files == null || files.length <= KEEP_DUMPS) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified)); // 舊在前
        for (int i = 0; i < files.length - KEEP_DUMPS; i++) {
            if (!files[i].delete()) Log.w(TAG, "cannot delete old dump " + files[i]);
        }
    }

    private static String appVersion(@NonNull Context ctx) {
        try {
            return ctx.getPackageName()
                    + " "
                    + ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return ctx.getPackageName() + " ?";
        }
    }
}
