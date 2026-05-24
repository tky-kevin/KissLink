package com.nfctransfer.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room database singleton for the NFC Transfer app.
 *
 * Version history:
 *   1 — initial schema: transfer_records table
 *
 * Access via {@link #getInstance(Context)}; never construct directly.
 */
@Database(entities = {TransferRecord.class}, version = 1, exportSchema = false)
public abstract class TransferDatabase extends RoomDatabase {

    private static final String DB_NAME = "nfc_transfer.db";

    /** Returns the DAO for transfer records. */
    public abstract TransferDao transferDao();

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static volatile TransferDatabase INSTANCE;

    /**
     * Returns the application-scoped database instance, creating it on first call.
     * Thread-safe via double-checked locking.
     *
     * @param context any Context; the application context is used internally.
     */
    public static TransferDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (TransferDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    TransferDatabase.class,
                                    DB_NAME)
                            .fallbackToDestructiveMigration() // acceptable for a university project
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
