package com.kisslink.transfer;

import org.junit.Test;

import static org.junit.Assert.*;

public class PeerConnectorTest {

    @Test
    public void peerConnector_canBeInstantiated() {
        PeerConnector connector = new PeerConnector();
        assertNotNull(connector);
    }

    @Test
    public void callback_interfaceCanBeImplemented() {
        final boolean[] called = {false};
        PeerConnector.Callback cb = new PeerConnector.Callback() {
            @Override public void onSocketReady(java.net.Socket socket) {
                called[0] = true;
            }
            @Override public void onError(String message) {
                called[0] = true;
            }
        };
        cb.onError("test");
        assertTrue(called[0]);
    }
}
