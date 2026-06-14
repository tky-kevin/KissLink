package com.kisslink.transfer;

import com.kisslink.wifidirect.ConnectionState;

import org.junit.Test;

import static org.junit.Assert.*;

public class SessionStateTest {

    @Test
    public void idle_hasIdlePhase() {
        assertEquals(SessionState.Phase.IDLE, SessionState.idle().phase);
    }

    @Test
    public void error_hasErrorPhaseAndMessage() {
        SessionState s = SessionState.error("boom");
        assertTrue(s.isError());
        assertEquals("boom", s.error);
    }

    @Test
    public void of_setsPhase() {
        SessionState s = SessionState.of(SessionState.Phase.CONNECTED);
        assertEquals(SessionState.Phase.CONNECTED, s.phase);
    }

    @Test
    public void fromTransfer_mapsTransferring() {
        TransferProgress tp = new TransferProgress.Builder()
                .phase(TransferProgress.Phase.TRANSFERRING)
                .fileName("test.txt")
                .build();
        SessionState s = SessionState.fromTransfer(tp);
        assertEquals(SessionState.Phase.TRANSFERRING, s.phase);
    }

    @Test
    public void fromTransfer_nullReturnsIdle() {
        assertEquals(SessionState.Phase.IDLE, SessionState.fromTransfer(null).phase);
    }

    @Test
    public void fromConnection_nullReturnsIdle() {
        assertEquals(SessionState.Phase.IDLE, SessionState.fromConnection(null).phase);
    }

    @Test
    public void fromConnection_connected() {
        SessionState s = SessionState.fromConnection(ConnectionState.CONNECTED);
        assertEquals(SessionState.Phase.CONNECTED, s.phase);
    }

    @Test
    public void fromConnection_disconnectedReturnsError() {
        SessionState s = SessionState.fromConnection(ConnectionState.DISCONNECTED);
        assertTrue(s.isError());
    }

    @Test
    public void mapPhase_latched() {
        SessionState s = SessionState.mapPhase(com.kisslink.pairing.PairingCoordinator.Phase.LATCHED);
        assertEquals(SessionState.Phase.PAIRING_LATCHED, s.phase);
    }

    @Test
    public void mapPhase_idle() {
        SessionState s = SessionState.mapPhase(com.kisslink.pairing.PairingCoordinator.Phase.IDLE);
        assertEquals(SessionState.Phase.IDLE, s.phase);
    }

    @Test
    public void isPairing_trueForResetting() {
        assertTrue(SessionState.of(SessionState.Phase.RESETTING).isPairing());
    }

    @Test
    public void isPairing_falseForConnected() {
        assertFalse(SessionState.of(SessionState.Phase.CONNECTED).isPairing());
    }

    @Test
    public void isTransferStartedOrConnected_trueForConnected() {
        assertTrue(SessionState.of(SessionState.Phase.CONNECTED).isTransferStartedOrConnected());
    }

    @Test
    public void isTransferStartedOrConnected_falseForIdle() {
        assertFalse(SessionState.idle().isTransferStartedOrConnected());
    }

    @Test
    public void equality_samePhaseAndError() {
        SessionState a = new SessionState(SessionState.Phase.CONNECTED, null, null);
        SessionState b = new SessionState(SessionState.Phase.CONNECTED, null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equality_differentPhase() {
        SessionState a = SessionState.of(SessionState.Phase.CONNECTED);
        SessionState b = SessionState.of(SessionState.Phase.IDLE);
        assertNotEquals(a, b);
    }
}
