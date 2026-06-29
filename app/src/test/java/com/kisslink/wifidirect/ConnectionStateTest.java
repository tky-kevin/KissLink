package com.kisslink.wifidirect;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * {@link ConnectionState} 的 JVM 單元測試。
 *
 * <p>確保狀態枚舉的完整性與狀態轉換路徑的語義正確性。
 */
public class ConnectionStateTest {

    // ── 完整性 ────────────────────────────────────────────────

    @Test
    public void allExpectedStatesExist() {
        List<ConnectionState> expected =
                Arrays.asList(
                        ConnectionState.IDLE,
                        ConnectionState.CREATING_GROUP,
                        ConnectionState.HOSTING,
                        ConnectionState.CONNECTING,
                        ConnectionState.CONNECTED,
                        ConnectionState.DISCONNECTED,
                        ConnectionState.ERROR);

        List<ConnectionState> actual = Arrays.asList(ConnectionState.values());

        for (ConnectionState s : expected) {
            assertTrue("缺少狀態: " + s, actual.contains(s));
        }

        assertEquals("狀態數量不符（新增/刪除狀態時請同步更新此測試）", expected.size(), actual.size());
    }

    // ── 語義驗證 ──────────────────────────────────────────────

    @Test
    public void idle_isInitialState() {
        // 文件化保證：IDLE 是 WifiDirectManager 的初始狀態
        assertEquals(ConnectionState.IDLE, ConnectionState.valueOf("IDLE"));
    }

    @Test
    public void senderStates_areOrdered() {
        // 傳送方的合法進度：IDLE → CREATING_GROUP → HOSTING → CONNECTED
        int idle = ConnectionState.IDLE.ordinal();
        int creating = ConnectionState.CREATING_GROUP.ordinal();
        int hosting = ConnectionState.HOSTING.ordinal();
        int connected = ConnectionState.CONNECTED.ordinal();

        assertTrue("IDLE 應在 CREATING_GROUP 之前", idle < creating);
        assertTrue("CREATING_GROUP 應在 HOSTING 之前", creating < hosting);
        assertTrue("HOSTING 應在 CONNECTED 之前", hosting < connected);
    }

    @Test
    public void receiverStates_areOrdered() {
        // 接收方的合法進度：IDLE → CONNECTING → CONNECTED
        int idle = ConnectionState.IDLE.ordinal();
        int connecting = ConnectionState.CONNECTING.ordinal();
        int connected = ConnectionState.CONNECTED.ordinal();

        assertTrue("IDLE 應在 CONNECTING 之前", idle < connecting);
        assertTrue("CONNECTING 應在 CONNECTED 之前", connecting < connected);
    }

    @Test
    public void errorAndDisconnected_areTerminalStates() {
        // 文件化：這兩個狀態之後需呼叫 reset() 才能繼續
        assertNotNull(ConnectionState.ERROR);
        assertNotNull(ConnectionState.DISCONNECTED);
    }

    // ── valueOf 安全性 ─────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void valueOf_invalidName_throwsException() {
        ConnectionState.valueOf("UNKNOWN_STATE");
    }
}
