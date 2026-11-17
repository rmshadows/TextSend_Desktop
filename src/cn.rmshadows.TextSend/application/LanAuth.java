package application;

import protocol.B64;
import protocol.Protocol;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 局域网临时授权（TSA1）。
 * 探测打开后，客户端点设备发 REQUEST；服务端弹窗允许后单播 GRANT（32 字节一次性 PSK）。
 * 不进广播，短 TTL，用过即废。
 */
public final class LanAuth {
    public static final byte[] MAGIC = "TSA1".getBytes(StandardCharsets.US_ASCII);
    public static final byte VERSION = 1;
    public static final byte TYPE_REQUEST = 1;
    public static final byte TYPE_GRANT = 2;
    public static final byte TYPE_DENY = 3;
    public static final byte TYPE_PENDING = 4;
    public static final int NONCE_LEN = 16;
    public static final int NAME_MAX = 48;
    public static final long GRANT_TTL_MS = 60_000L;
    public static final long REQUEST_WAIT_MS = 60_000L;

    private static final SecureRandom RNG = new SecureRandom();
    private static final ConcurrentHashMap<ByteBuffer, CompletableFuture<Grant>> WAITING = new ConcurrentHashMap<>();

    private LanAuth() {
    }

    public static final class Grant {
        public final byte[] psk;
        public final int tcpPort;

        public Grant(byte[] psk, int tcpPort) {
            this.psk = psk;
            this.tcpPort = tcpPort;
        }

        public String pskB64() {
            return B64.enc(psk);
        }
    }

    public static final class Request {
        public final String host;
        public final int replyPort;
        public final String name;
        public final byte[] nonce;
        /** 非空表示这次请求走 TCP，回复写进这个 future，不再发 UDP。 */
        public volatile java.util.concurrent.CompletableFuture<byte[]> tcpReply;

        public Request(String host, int replyPort, String name, byte[] nonce) {
            this.host = host;
            this.replyPort = replyPort;
            this.name = name;
            this.nonce = nonce;
        }
    }

    public static boolean isAuthPacket(byte[] buf, int length) {
        return length >= 6
                && buf[0] == 'T' && buf[1] == 'S' && buf[2] == 'A' && buf[3] == '1'
                && buf[4] == VERSION;
    }

    public static byte typeOf(byte[] buf, int length) {
        return isAuthPacket(buf, length) ? buf[5] : 0;
    }

    public static Request parseRequest(byte[] buf, int length, String host, int replyPort) {
        if (!isAuthPacket(buf, length) || buf[5] != TYPE_REQUEST || length < 7 + NONCE_LEN) {
            return null;
        }
        int nameLen = buf[6] & 0xFF;
        if (nameLen > NAME_MAX || length != 7 + nameLen + NONCE_LEN) {
            return null;
        }
        String name = new String(buf, 7, nameLen, StandardCharsets.UTF_8);
        byte[] nonce = Arrays.copyOfRange(buf, 7 + nameLen, 7 + nameLen + NONCE_LEN);
        return new Request(host, replyPort, name, nonce);
    }

    public static byte[] encodeRequest(String name, byte[] nonce) {
        byte[] nameBytes = clipName(name);
        byte[] out = new byte[7 + nameBytes.length + NONCE_LEN];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = TYPE_REQUEST;
        out[6] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, out, 7, nameBytes.length);
        System.arraycopy(nonce, 0, out, 7 + nameBytes.length, NONCE_LEN);
        return out;
    }

    public static byte[] encodeGrant(byte[] nonce, byte[] psk, int tcpPort, long expireAtMs) {
        byte[] out = new byte[6 + NONCE_LEN + Protocol.PSK_LEN + 2 + 4];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = TYPE_GRANT;
        System.arraycopy(nonce, 0, out, 6, NONCE_LEN);
        System.arraycopy(psk, 0, out, 6 + NONCE_LEN, Protocol.PSK_LEN);
        int o = 6 + NONCE_LEN + Protocol.PSK_LEN;
        out[o] = (byte) (tcpPort >> 8);
        out[o + 1] = (byte) tcpPort;
        int expSec = (int) Math.min(Integer.MAX_VALUE, expireAtMs / 1000L);
        out[o + 2] = (byte) (expSec >> 24);
        out[o + 3] = (byte) (expSec >> 16);
        out[o + 4] = (byte) (expSec >> 8);
        out[o + 5] = (byte) expSec;
        return out;
    }

    public static byte[] encodeDeny(byte[] nonce) {
        byte[] out = new byte[6 + NONCE_LEN];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = TYPE_DENY;
        System.arraycopy(nonce, 0, out, 6, NONCE_LEN);
        return out;
    }

    public static byte[] encodePending(byte[] nonce) {
        byte[] out = new byte[6 + NONCE_LEN];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = TYPE_PENDING;
        System.arraycopy(nonce, 0, out, 6, NONCE_LEN);
        return out;
    }

    /** 客户端收到 GRANT/DENY 时调用。 */
    public static void onAuthReply(byte[] buf, int length) {
        if (!isAuthPacket(buf, length) || length < 6 + NONCE_LEN) {
            return;
        }
        byte type = buf[5];
        byte[] nonce = Arrays.copyOfRange(buf, 6, 6 + NONCE_LEN);
        ByteBuffer key = ByteBuffer.wrap(nonce);
        CompletableFuture<Grant> fut = WAITING.get(key);
        if (fut == null) {
            return;
        }
        if (type == TYPE_DENY) {
            WAITING.remove(key);
            fut.completeExceptionally(new IllegalStateException("服务端拒绝了授权"));
            return;
        }
        if (type == TYPE_PENDING) {
            return;
        }
        if (type != TYPE_GRANT) {
            return;
        }
        int need = 6 + NONCE_LEN + Protocol.PSK_LEN + 2 + 4;
        if (length < need) {
            return;
        }
        byte[] psk = Arrays.copyOfRange(buf, 6 + NONCE_LEN, 6 + NONCE_LEN + Protocol.PSK_LEN);
        int o = 6 + NONCE_LEN + Protocol.PSK_LEN;
        int tcpPort = ((buf[o] & 0xFF) << 8) | (buf[o + 1] & 0xFF);
        WAITING.remove(key);
        fut.complete(new Grant(psk, tcpPort));
    }

    /**
     * 向 host:udpPort 请求授权，阻塞直到 GRANT/DENY/超时。
     * 须在探测 socket 已绑定且能收包时调用（由 LanDiscover 转发回复）。
     */
    public static Grant requestAuth(DatagramSocket sock, String host, int udpPort, String deviceName)
            throws Exception {
        byte[] nonce = new byte[NONCE_LEN];
        RNG.nextBytes(nonce);
        ByteBuffer key = ByteBuffer.wrap(nonce);
        CompletableFuture<Grant> fut = new CompletableFuture<>();
        WAITING.put(key, fut);
        try {
            byte[] packet = encodeRequest(deviceName, nonce);
            InetAddress addr = InetAddress.getByName(host);
            sock.send(new DatagramPacket(packet, packet.length, addr, udpPort));
            return fut.get(REQUEST_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            WAITING.remove(key);
            throw e;
        }
    }

    public static byte[] newPsk() {
        byte[] psk = new byte[Protocol.PSK_LEN];
        RNG.nextBytes(psk);
        return psk;
    }

    private static byte[] clipName(String name) {
        byte[] all = (name == null ? "" : name).getBytes(StandardCharsets.UTF_8);
        if (all.length <= NAME_MAX) {
            return all;
        }
        int n = NAME_MAX;
        while (n > 0 && (all[n] & 0xC0) == 0x80) {
            n--;
        }
        return Arrays.copyOf(all, n);
    }
}
