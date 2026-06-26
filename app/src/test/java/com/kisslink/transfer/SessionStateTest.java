package com.kisslink.transfer;

import org.junit.Test;

import static org.junit.Assert.*;

/** SessionState 是純值物件：只測值建構與自身 phase 判斷。跨 enum 轉譯在 SessionManagerTest。 */
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
