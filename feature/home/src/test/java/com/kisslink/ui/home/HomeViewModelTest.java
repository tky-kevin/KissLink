package com.kisslink.ui.home;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;

import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.SessionState;
import com.kisslink.transfer.TransferProgress;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

public class HomeViewModelTest {

    private HomeViewModel vm;

    @Before
    public void setUp() {
        // Swap ArchTaskExecutor to synchronous mode so LiveData.postValue() works without Looper.
        // Equivalent to InstantTaskExecutorRule internals, without the core-testing dependency.
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            @Override public void executeOnDiskIO(Runnable r) { r.run(); }
            @Override public void postToMainThread(Runnable r) { r.run(); }
            @Override public boolean isMainThread() { return true; }
        });
        vm = new HomeViewModel();
    }

    @After
    public void tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null);
    }

    // ── selection ─────────────────────────────────────────────

    @Test
    public void initialSelection_isEmpty() {
        assertTrue(vm.isSelectionEmpty());
        assertEquals(0, vm.selectionCount());
    }

    @Test
    public void addToSelection_incrementsCount() {
        vm.addToSelection(textItem("hello"));
        assertEquals(1, vm.selectionCount());
        assertFalse(vm.isSelectionEmpty());
    }

    @Test
    public void addAllToSelection_addsAll() {
        vm.addToSelection(textItem("a"));
        vm.addToSelection(textItem("b"));
        assertEquals(2, vm.selectionCount());
    }

    @Test
    public void removeSelection_removesCorrectItem() {
        vm.addToSelection(textItem("x"));
        vm.addToSelection(textItem("y"));
        vm.removeSelection(0);
        assertEquals(1, vm.selectionCount());
        assertEquals("y", vm.currentSelection().get(0).name);
    }

    @Test
    public void removeSelection_outOfBoundsIsIgnored() {
        vm.addToSelection(textItem("x"));
        vm.removeSelection(99);
        assertEquals(1, vm.selectionCount());
    }

    @Test
    public void clearSelection_emptiesSelection() {
        vm.addToSelection(textItem("a"));
        vm.addToSelection(textItem("b"));
        vm.clearSelection();
        assertTrue(vm.isSelectionEmpty());
    }

    // ── pending card send ─────────────────────────────────────

    @Test
    public void pendingCardSend_defaultFalse() {
        assertFalse(vm.isPendingCardSend());
    }

    @Test
    public void pendingCardSend_setAndGet() {
        vm.setPendingCardSend(true);
        assertTrue(vm.isPendingCardSend());
        vm.setPendingCardSend(false);
        assertFalse(vm.isPendingCardSend());
    }

    // ── sending flag ──────────────────────────────────────────

    @Test
    public void isSending_defaultFalse() {
        assertFalse(vm.isSending());
    }

    @Test
    public void markSendingStarted_setsSending() {
        vm.addToSelection(textItem("f1"));
        vm.markSendingStarted();
        assertTrue(vm.isSending());
    }

    @Test
    public void onBatchSent_clearsSendingAndSelection() {
        vm.addToSelection(textItem("f1"));
        vm.markSendingStarted();
        vm.onBatchSent();
        assertFalse(vm.isSending());
        assertTrue(vm.isSelectionEmpty());
    }

    // ── connection state ──────────────────────────────────────

    @Test
    public void isConnected_initiallyFalse() {
        assertFalse(vm.isConnected());
    }

    @Test
    public void isConnected_trueWhenConnected() {
        vm.onSession(SessionState.of(SessionState.Phase.CONNECTED));
        assertTrue(vm.isConnected());
    }

    @Test
    public void isConnected_trueWhenTransferring() {
        vm.onSession(SessionState.of(SessionState.Phase.TRANSFERRING));
        assertTrue(vm.isConnected());
    }

    @Test
    public void isConnected_falseWhenIdle() {
        vm.onSession(SessionState.of(SessionState.Phase.CONNECTED));
        vm.onSession(SessionState.of(SessionState.Phase.IDLE));
        assertFalse(vm.isConnected());
    }

    // ── shouldShowSendButton ──────────────────────────────────

    @Test
    public void shouldShowSendButton_falseWhenNotConnected() {
        vm.addToSelection(textItem("file"));
        assertFalse(vm.shouldShowSendButton());
    }

    @Test
    public void shouldShowSendButton_falseWhenSelectionEmpty() {
        vm.onSession(SessionState.of(SessionState.Phase.CONNECTED));
        assertFalse(vm.shouldShowSendButton());
    }

    @Test
    public void shouldShowSendButton_trueWhenConnectedWithItems() {
        vm.onSession(SessionState.of(SessionState.Phase.CONNECTED));
        vm.addToSelection(textItem("file"));
        assertTrue(vm.shouldShowSendButton());
    }

    @Test
    public void shouldShowSendButton_falseWhileSending() {
        vm.onSession(SessionState.of(SessionState.Phase.CONNECTED));
        vm.addToSelection(textItem("file"));
        vm.markSendingStarted();
        assertFalse(vm.shouldShowSendButton());
    }

    // ── celebrate gate ────────────────────────────────────────

    @Test
    public void shouldCelebrate_firstTimeTrueForBatch() {
        assertTrue(vm.shouldCelebrate(42L));
    }

    @Test
    public void shouldCelebrate_secondTimeFalseForSameBatch() {
        vm.shouldCelebrate(42L);
        assertFalse(vm.shouldCelebrate(42L));
    }

    @Test
    public void shouldCelebrate_trueForDifferentBatch() {
        vm.shouldCelebrate(1L);
        assertTrue(vm.shouldCelebrate(2L));
    }

    @Test
    public void resetCelebration_allowsReuseOfSameBatchId() {
        vm.shouldCelebrate(1L);
        vm.resetCelebration();
        assertTrue(vm.shouldCelebrate(1L));
    }

    // ── received list ─────────────────────────────────────────

    @Test
    public void receivedFiles_initiallyEmpty() {
        assertFalse(vm.hasReceivedList());
    }

    @Test
    public void upsertReceived_newFileReturnsTrue() {
        boolean isNew = vm.upsertReceived(1L, "photo.jpg", 1024L,
                (byte) 2, 50, false);
        assertTrue(isNew);
        assertTrue(vm.hasReceivedList());
    }

    @Test
    public void upsertReceived_sameFileReturnsFalse() {
        vm.upsertReceived(1L, "photo.jpg", 1024L, (byte) 2, 50, false);
        boolean isNew = vm.upsertReceived(1L, "photo.jpg", 1024L, (byte) 2, 80, false);
        assertFalse(isNew);
    }

    @Test
    public void upsertReceived_newBatchClearsPrevious() {
        vm.upsertReceived(1L, "old.jpg", 512L, (byte) 2, 100, true);
        vm.upsertReceived(2L, "new.jpg", 256L, (byte) 2, 0, false);
        java.util.Collection<HomeViewModel.RecvFile> files = vm.receivedFiles();
        assertEquals(1, files.size());
        assertEquals("new.jpg", files.iterator().next().name);
    }

    @Test
    public void clearReceivedList_emptiesList() {
        vm.upsertReceived(1L, "file.txt", 100L, (byte) 0, 100, true);
        vm.clearReceivedList();
        assertFalse(vm.hasReceivedList());
    }

    // ── batchProgress monotonicity ────────────────────────────

    @Test
    public void batchProgress_doesNotDecrease() {
        TransferProgress tp1 = new TransferProgress.Builder()
                .phase(TransferProgress.Phase.TRANSFERRING)
                .batchId(1L).fileName("f.txt").totalBytes(1000L)
                .doneBytes(800L).fileIndex(0).fileCount(1)
                .build();
        TransferProgress tp2 = new TransferProgress.Builder()
                .phase(TransferProgress.Phase.TRANSFERRING)
                .batchId(1L).fileName("f.txt").totalBytes(1000L)
                .doneBytes(400L).fileIndex(0).fileCount(1)
                .build();

        float p1 = vm.batchProgress(tp1);
        float p2 = vm.batchProgress(tp2);
        assertTrue("Progress must not decrease within a batch", p2 >= p1);
    }

    @Test
    public void batchProgress_newBatchResetsProgress() {
        TransferProgress tp1 = new TransferProgress.Builder()
                .phase(TransferProgress.Phase.TRANSFERRING)
                .batchId(1L).fileName("f.txt").totalBytes(100L)
                .doneBytes(100L).fileIndex(0).fileCount(1)
                .build();
        TransferProgress tp2 = new TransferProgress.Builder()
                .phase(TransferProgress.Phase.TRANSFERRING)
                .batchId(2L).fileName("new.txt").totalBytes(100L)
                .doneBytes(0L).fileIndex(0).fileCount(1)
                .build();

        float p1 = vm.batchProgress(tp1);
        float p2 = vm.batchProgress(tp2);
        assertEquals(1.0f, p1, 0.01f);
        assertTrue("New batch should start fresh", p2 < p1);
    }

    // ── helpers ───────────────────────────────────────────────

    private static SendItem textItem(String content) {
        return SendItem.text(content);
    }
}
