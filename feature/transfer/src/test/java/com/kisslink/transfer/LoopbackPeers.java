package com.kisslink.transfer;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 單一 JVM 內的「假 peer」傳輸框架：用本機 TCP socket 接出一對相連的端點，
 * 不需要兩台實機就能讓傳送/接收邏輯在<b>真實 stream</b>上互通。
 *
 * <p>為何用真 socket 而非記憶體 pipe：要驗的正是 framing 在 TCP 上的行為——
 * 連續多幀的排序、TCP 分段下 {@code readFully} 的重組、{@link BufferedOutputStream}
 * 緩衝 + flush 的時機。這些是 {@link TransferProtocol} 純編解碼單元測試覆蓋不到的。
 *
 * <p>幀 I/O 刻意對齊生產碼：寫端等同 {@code PeerConnection.writeFrame}
 * （header(64B) + 可選 payload，逐幀 flush）；讀端等同 {@code PeerReceiver.readFully}
 * + 依封包型別推導 payload 長度。如此跑過的幀序列與真實 Wi-Fi Direct 通道一致。
 *
 * <p>用法：
 * <pre>{@code
 * try (LoopbackPeers link = LoopbackPeers.open()) {
 *     link.a().sendFrame(TransferProtocol.makeHello(), null);
 *     LoopbackPeers.Frame f = link.b().receiveFrame();
 *     assertEquals(TransferProtocol.TYPE_HELLO, f.header.type);
 * }
 * }</pre>
 */
final class LoopbackPeers implements AutoCloseable {

    /** 一端：可送/收幀。執行緒安全性同 socket——單一讀執行緒 + 單一寫執行緒即可。 */
    static final class Peer {
        private final Socket socket;
        private final BufferedOutputStream out;
        private final InputStream in;

        private Peer(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            this.out = new BufferedOutputStream(socket.getOutputStream());
            this.in = socket.getInputStream();
        }

        /**
         * 送出一幀：header(64B) 後接 payload（可為 null）。對齊 {@code PeerConnection.writeFrame}。
         */
        void sendFrame(TransferProtocol.Header header, byte[] payload) throws IOException {
            out.write(TransferProtocol.encodeHeader(header));
            if (payload != null && payload.length > 0) out.write(payload);
            out.flush();
        }

        /**
         * 讀出一幀：先 readFully 64B header 解碼，再依型別讀對應 payload。
         * 對齊 {@code PeerReceiver} 的解析——payload 長度來源：
         * HELLO / DATA_CHUNK → {@code chunkLen}；FILE_META → {@code metaLen}；其餘無 payload。
         */
        Frame receiveFrame() throws IOException, TransferProtocol.InvalidPacketException {
            byte[] hb = new byte[TransferProtocol.HEADER_SIZE];
            readFully(in, hb, hb.length);
            TransferProtocol.Header h = TransferProtocol.decodeHeader(hb);
            int payloadLen = payloadLengthFor(h);
            byte[] payload = null;
            if (payloadLen > 0) {
                payload = new byte[payloadLen];
                readFully(in, payload, payloadLen);
            }
            return new Frame(h, payload);
        }

        private static int payloadLengthFor(TransferProtocol.Header h) {
            switch (h.type) {
                case TransferProtocol.TYPE_HELLO:
                case TransferProtocol.TYPE_DATA_CHUNK:
                    return h.chunkLen;
                case TransferProtocol.TYPE_FILE_META:
                    return h.metaLen;
                default:
                    return 0;
            }
        }
    }

    /** 一個收到的幀：header + 已讀出的 payload（無 payload 時為 null）。 */
    static final class Frame {
        final TransferProtocol.Header header;
        final byte[] payload;
        Frame(TransferProtocol.Header header, byte[] payload) {
            this.header = header;
            this.payload = payload;
        }
    }

    private final ServerSocket server;
    private final Peer a;
    private final Peer b;

    private LoopbackPeers(ServerSocket server, Peer a, Peer b) {
        this.server = server;
        this.a = a;
        this.b = b;
    }

    Peer a() { return a; }
    Peer b() { return b; }

    /** 接出一對相連端點（本機 loopback，OS 指派的暫時埠）。 */
    static LoopbackPeers open() throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));

        // accept 會阻塞，放到另一執行緒，主執行緒同步 connect。
        final Socket[] accepted = new Socket[1];
        final IOException[] acceptErr = new IOException[1];
        Thread acceptor = new Thread(() -> {
            try { accepted[0] = server.accept(); }
            catch (IOException e) { acceptErr[0] = e; }
        }, "loopback-accept");
        acceptor.start();

        Socket client = new Socket();
        client.connect(server.getLocalSocketAddress(), 2000);
        try { acceptor.join(2000); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acceptErr[0] != null) throw acceptErr[0];
        if (accepted[0] == null) throw new IOException("loopback accept timed out");

        return new LoopbackPeers(server, new Peer(accepted[0]), new Peer(client));
    }

    @Override
    public void close() {
        closeQuietly(a == null ? null : a.socket);
        closeQuietly(b == null ? null : b.socket);
        try { server.close(); } catch (IOException ignored) {}
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }

    /** 等同 {@code PeerReceiver.readFully}：補滿 len 個 byte，遇 EOF 拋例外。 */
    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }
}
