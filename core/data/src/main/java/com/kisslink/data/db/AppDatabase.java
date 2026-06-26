package com.kisslink.data.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * KissLink 的 Room 資料庫單例。
 *
 * <p>版本策略：每次 schema 變更即遞增 {@code version}，並提供對應的
 * {@link Migration} 以<b>保留</b>使用者歷史。{@code exportSchema = true} 讓每個版本的
 * schema JSON 落在 {@code app/schemas/}（路徑由 build.gradle 的 room.schemaLocation 指定），
 * 供日後 diff 與 MigrationTestHelper 驗證。僅在「降版」這種開發期才會遇到的情況退回破壞性重建。
 */
@Database(
        entities = {TransferRecordEntity.class},
        version  = 3,
        exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract TransferDao transferDao();

    /**
     * v1 → v2：新增 {@code mimeType}（決定開啟方式）與 {@code batchId}（burst 分組）兩欄。
     * 兩者皆為純新增欄位，舊資料原樣保留；{@code batchId} 為 primitive long → NOT NULL，
     * 既有列以 DEFAULT 0 回填（0 = 批次未知，與 entity 註解一致）。
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE transfer_records ADD COLUMN mimeType TEXT");
            db.execSQL("ALTER TABLE transfer_records ADD COLUMN batchId INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v2 → v3：新增 batchId 與 timestampMs 索引，加速批次查詢與時間排序。 */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_records_batchId ON transfer_records (batchId)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_records_timestampMs ON transfer_records (timestampMs)");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "kisslink.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                            // 升版一律走顯式 migration 保資料；只有降版（開發期換 branch）才破壞性重建。
                            .fallbackToDestructiveMigrationOnDowngrade()
                            .build();
                }
            }
        }
        return instance;
    }
}
