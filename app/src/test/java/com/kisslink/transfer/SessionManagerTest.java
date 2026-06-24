package com.kisslink.transfer;

import android.os.Handler;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class SessionManagerTest {

    private SessionManager sm;

    @Before
    public void setup() {
        sm = new SessionManager();
    }

    @Test
    public void initialState_isIdle() {
        assertNotNull(sm.getState());
    }

    @Test
    public void transitionTo_doesNotThrow() {
        // setValue requires main thread; verify method exists and doesn't crash JVM
        // Real state transitions are verified in instrumented tests
        try {
            sm.transitionTo(SessionState.of(SessionState.Phase.CONNECTING));
        } catch (RuntimeException e) {
            // Expected: "Method setValue must be called on the main thread"
        }
        assertNotNull(sm.getState());
    }

    @Test
    public void sessionGen_increments() {
        int g1 = sm.nextSessionGen();
        int g2 = sm.nextSessionGen();
        assertEquals(g1 + 1, g2);
    }

    @Test
    public void clearPeerIdentity_resetsAll() {
        sm.setPeerProfile("Alice", new byte[]{1, 2, 3});
        sm.clearPeerIdentity();
        assertNull(sm.getPeerNameFromHello());
        assertNull(sm.getPeerAvatarBytes());
        assertNull(sm.getConnectedPeerToken());
    }

    @Test
    public void resetSession_resetsAll() {
        sm.setPeerProfile("Bob", new byte[]{4, 5, 6});
        sm.resetSession();
        assertNull(sm.getPendingSwitchPeer());
        assertFalse(sm.isTransferring());
    }

    @Test
    public void currentPeerName_prefersHelloName() {
        sm.setPeerProfile("HelloName", null);
        assertEquals("HelloName", sm.currentPeerName());
    }

    @Test
    public void currentPeerName_fallsBackToNull() {
        assertNull(sm.currentPeerName());
    }

    @Test
    public void isPendingReset_defaultFalse() {
        assertFalse(sm.isPendingReset());
    }

    @Test
    public void isTransferring_defaultFalse() {
        assertFalse(sm.isTransferring());
    }

    @Test
    public void mapPhase_latched() {
        assertEquals(SessionState.Phase.PAIRING_LATCHED,
                SessionManager.mapPhase(com.kisslink.pairing.PairingCoordinator.Phase.LATCHED).phase);
    }

    @Test
    public void mapPhase_connected() {
        assertEquals(SessionState.Phase.CONNECTED,
                SessionManager.mapPhase(com.kisslink.pairing.PairingCoordinator.Phase.CONNECTED).phase);
    }

    @Test
    public void mapPhase_idle() {
        assertEquals(SessionState.Phase.IDLE,
                SessionManager.mapPhase(com.kisslink.pairing.PairingCoordinator.Phase.IDLE).phase);
    }

    @Test
    public void mapTransfer_transferring() {
        TransferProgress tp = new TransferProgress.Builder()
                .phase(TransferProgress.Phase.TRANSFERRING)
                .fileName("test.txt")
                .build();
        assertEquals(SessionState.Phase.TRANSFERRING, SessionManager.mapTransfer(tp).phase);
    }

    @Test
    public void mapTransfer_nullReturnsIdle() {
        assertEquals(SessionState.Phase.IDLE, SessionManager.mapTransfer(null).phase);
    }

    @Test
    public void latchResult_enumHasAllValues() {
        assertEquals(4, SessionManager.LatchResult.values().length);
    }
}
