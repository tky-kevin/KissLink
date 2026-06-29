package com.kisslink.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kisslink.data.db.TransferRecordEntity;
import org.junit.Test;

/**
 * Pure-JVM coverage for {@link TransferRepository#buildRecord}, the record factory that maps a
 * completed transfer into a {@link TransferRecordEntity}. Guards the field mapping (e.g. a refactor
 * swapping direction/fileName) and the short-overload defaults — no Android/Room runtime needed.
 */
public class TransferRecordFactoryTest {

    @Test
    public void buildRecord_mapsEveryFieldAndStampsTime() {
        long before = System.currentTimeMillis();
        TransferRecordEntity e =
                TransferRepository.buildRecord(
                        "SEND",
                        "photo.jpg",
                        1234L,
                        true,
                        5000L,
                        "content://x",
                        "Alice",
                        "image/jpeg",
                        42L);
        long after = System.currentTimeMillis();

        assertEquals("SEND", e.direction);
        assertEquals("photo.jpg", e.fileName);
        assertEquals(1234L, e.fileSizeBytes);
        assertTrue(e.success);
        assertEquals(5000L, e.avgSpeedBps);
        assertEquals("content://x", e.filePath);
        assertEquals("Alice", e.peerDeviceName);
        assertEquals("image/jpeg", e.mimeType);
        assertEquals(42L, e.batchId);
        assertTrue(
                "timestamp should be stamped at build time",
                e.timestampMs >= before && e.timestampMs <= after);
    }

    @Test
    public void buildRecord_shortOverload_defaultsPeerMimeNullAndBatchZero() {
        TransferRecordEntity e =
                TransferRepository.buildRecord("RECEIVE", "doc.pdf", 9L, false, 0L, null);

        assertEquals("RECEIVE", e.direction);
        assertEquals("doc.pdf", e.fileName);
        assertFalse(e.success);
        assertNull(e.filePath);
        assertNull(e.peerDeviceName);
        assertNull(e.mimeType);
        assertEquals(0L, e.batchId);
    }
}
