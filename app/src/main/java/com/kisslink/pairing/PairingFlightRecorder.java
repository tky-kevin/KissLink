package com.kisslink.pairing;

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
 * 配對「飛行記錄器」(flight recorder)——偶發問題的事後黑盒子。
 *
 * <p>把配對序列(PAIRSEQ)與關鍵事件持續寫進一個 in-process 環形緩衝;一旦配對失敗
 * ({@link PairingCoordinator#fail})即把緩衝快照落檔。事後用 {@code adb pull} 或檔案管理器
 * 取出分析,<b>不必每次蹲守 adb 重現</b>偶發的「卡在 BLE」。
 *
 * <h3>為何用環形緩衝而非讀 logcat</h3>
 * 第三方 app 沒有 {@code READ_LOGS} 權限,在裝置上只讀得到殘缺的自身 log;自記緩衝完全
 * 不依賴權限,且 PAIRSEQ 即使 logcat tag 關閉(預設靜默)也照樣留存——失敗當下要的就是
 * 「平常不輸出」的那段序列。
 *
 * <h3>取出位置</h3>
 * {@code <外部檔案目錄>/diagnostics/pair-<時戳>.log},例如 debug build:
 * {@code /sdcard/Android/data/com.kisslink.debug/files/diagnostics/}。落檔路徑也會寫進 logcat(info)。
 */
public final class PairingFlightRecorder {

    private static final String TAG        = "PairFlightRec";
    private static final int    MAX_LINES  = 1200;          // 環形緩衝容量（約覆蓋數場配對）
    private static final int    KEEP_DUMPS = 15;            // 落檔保留份數，逾則刪最舊
    private static final String DUMP_DIR   = "diagnostics";

    private static final ArrayDeque<String> buffer = new ArrayDeque<>(MAX_LINES);
    private static final SimpleDateFormat LINE_TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_TS =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private PairingFlightRecorder() {}

    /** 記一筆配對序列;logcat tag 開啟時一併輸出。可從任意執行緒呼叫。 */
    public static void seq(@NonNull String tag, @NonNull String msg) {
        record(tag, msg);
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, "PAIRSEQ " + msg);
    }

    /** 記一筆重要事件並一律輸出 logcat(warn)。供逾時/失敗/133/nonce 不同源等關鍵節點使用。 */
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
     * 把目前緩衝快照落檔。Best-effort:任何 IO 例外都吞掉,絕不影響配對流程。
     * @return 檔案絕對路徑(失敗回 {@code null})。
     */
    @Nullable
    public static File dump(@NonNull Context ctx, @NonNull String reason) {
        try {
            File base = ctx.getExternalFilesDir(null);
            if (base == null) { Log.w(TAG, "no external files dir"); return null; }
            File dir = new File(base, DUMP_DIR);
            if (!dir.exists() && !dir.mkdirs()) { Log.w(TAG, "cannot mkdir " + dir); return null; }

            String[] snapshot;
            synchronized (buffer) { snapshot = buffer.toArray(new String[0]); }

            File out = new File(dir, "pair-" + FILE_TS.format(new Date()) + ".log");
            try (FileWriter w = new FileWriter(out)) {
                w.write("== KissLink pairing flight recorder ==\n");
                w.write("when   : " + new Date() + "\n");
                w.write("reason : " + reason + "\n");
                w.write("device : " + Build.MANUFACTURER + " " + Build.MODEL
                        + " (Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT + ")\n");
                w.write("app    : " + appVersion(ctx) + "\n");
                w.write("lines  : " + snapshot.length + "\n");
                w.write("=======================================\n");
                for (String line : snapshot) { w.write(line); w.write('\n'); }
            }
            rotate(dir);
            Log.i(TAG, "pairing diagnostics dumped → " + out.getAbsolutePath()
                    + " (" + snapshot.length + " lines, reason: " + reason + ")");
            return out;
        } catch (Exception e) {
            Log.w(TAG, "dump failed: " + e.getMessage());
            return null;
        }
    }

    private static void rotate(@NonNull File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("pair-") && name.endsWith(".log"));
        if (files == null || files.length <= KEEP_DUMPS) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified)); // 舊在前
        for (int i = 0; i < files.length - KEEP_DUMPS; i++) {
            if (!files[i].delete()) Log.w(TAG, "cannot delete old dump " + files[i]);
        }
    }

    private static String appVersion(@NonNull Context ctx) {
        try {
            return ctx.getPackageName() + " "
                    + ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return ctx.getPackageName() + " ?";
        }
    }
}
