package application;

import protocol.B64;
import protocol.EcdhP256;
import protocol.Hkdf;
import protocol.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 局域网临时授权（TSA1）。
 * 客户端在请求里带上临时公钥；服务端先 PENDING 回公钥（短核对码），点允许后再 GRANT。
 * 两边用 ECDH + HKDF 各自算出一次性密钥，密钥本身不进网络包。
 */
public final class LanAuth {
    public static final byte[] MAGIC = "TSA1".getBytes(StandardCharsets.US_ASCII);
    /** 2：GRANT 里是公钥，不再带明文 PSK。1 的明文票直接丢掉。 */
    public static final byte VERSION = 2;
    public static final int PUB_LEN = 65;
    public static final String HKDF_INFO = "textsend-v1|lan-auth";
    public static final byte TYPE_REQUEST = 1;
    public static final byte TYPE_GRANT = 2;
    public static final byte TYPE_DENY = 3;
    public static final byte TYPE_PENDING = 4;
    /** 客户端取消这次申请。服务端丢掉待授权，不记冷却。 */
    public static final byte TYPE_CANCEL = 5;
    public static final int NONCE_LEN = 16;
    public static final int NAME_MAX = 48;
    public static final long GRANT_TTL_MS = 60_000L;
    public static final long REQUEST_WAIT_MS = 60_000L;
    /** DENY 尾部两个字节：0xFFFF 表示拉黑或服务没开；其余是还能再请求的秒数。 */
    public static final int DENY_HARD = 0xFFFF;

    /** 对方拒绝了这次授权。冷却和拉黑用同一类，客户端据此不再改走别的通道。 */
    public static final class AuthDeniedException extends IllegalStateException {
        public AuthDeniedException(String message) {
            super(message);
        }
    }

    /** 本机再点同一台，主动取消等待。 */
    public static final class AuthCancelledException extends IllegalStateException {
        public AuthCancelledException() {
            super("已取消授权申请");
        }
    }

    private static final class Active {
        volatile Socket tcp;
        volatile DatagramSocket udp;
        volatile String host;
        volatile int udpPort;
        volatile byte[] nonce;
        volatile boolean cancelled;
    }

    private static volatile Active active;

    private static final SecureRandom RNG = new SecureRandom();

    private static final class Waiter {
        final KeyPair keys;
        final byte[] clientPub;
        final CompletableFuture<Grant> fut;
        volatile byte[] pendingServerPub;
        volatile Consumer<String> onSas;

        Waiter(KeyPair keys, byte[] clientPub, CompletableFuture<Grant> fut) {
            this.keys = keys;
            this.clientPub = clientPub;
            this.fut = fut;
        }
    }

    private static final ConcurrentHashMap<ByteBuffer, Waiter> WAITING = new ConcurrentHashMap<>();

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
        /** 客户端临时公钥，65 字节未压缩。没有它就不能发允许。 */
        public final byte[] clientPub;
        /** 非空表示这次请求走 TCP，最终 GRANT/DENY 写进这个 future。 */
        public volatile java.util.concurrent.CompletableFuture<byte[]> tcpReply;
        /** TCP 上先发的 PENDING（含服务端公钥），由 onLanAuthRequest 填好。 */
        public volatile byte[] earlyPending;

        public Request(String host, int replyPort, String name, byte[] nonce, byte[] clientPub) {
            this.host = host;
            this.replyPort = replyPort;
            this.name = name;
            this.nonce = nonce;
            this.clientPub = clientPub;
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
        int base = 7 + nameLen + NONCE_LEN;
        if (nameLen > NAME_MAX || length != base + PUB_LEN) {
            return null;
        }
        String name = new String(buf, 7, nameLen, StandardCharsets.UTF_8);
        byte[] nonce = Arrays.copyOfRange(buf, 7 + nameLen, base);
        byte[] pub = Arrays.copyOfRange(buf, base, base + PUB_LEN);
        if (pub[0] != 0x04) {
            return null;
        }
        return new Request(host, replyPort, name, nonce, pub);
    }

    public static byte[] encodeRequest(String name, byte[] nonce, byte[] clientPub) {
        byte[] nameBytes = clipName(name);
        byte[] out = new byte[7 + nameBytes.length + NONCE_LEN + PUB_LEN];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = TYPE_REQUEST;
        out[6] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, out, 7, nameBytes.length);
        System.arraycopy(nonce, 0, out, 7 + nameBytes.length, NONCE_LEN);
        System.arraycopy(clientPub, 0, out, 7 + nameBytes.length + NONCE_LEN, PUB_LEN);
        return out;
    }

    /** GRANT 里是服务端临时公钥，没有 PSK。 */
    public static byte[] encodeGrant(byte[] nonce, byte[] serverPub, int tcpPort, long expireAtMs) {
        byte[] out = new byte[6 + NONCE_LEN + PUB_LEN + 2 + 4];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = TYPE_GRANT;
        System.arraycopy(nonce, 0, out, 6, NONCE_LEN);
        System.arraycopy(serverPub, 0, out, 6 + NONCE_LEN, PUB_LEN);
        int o = 6 + NONCE_LEN + PUB_LEN;
        out[o] = (byte) (tcpPort >> 8);
        out[o + 1] = (byte) tcpPort;
        int expSec = (int) Math.min(Integer.MAX_VALUE, expireAtMs / 1000L);
        out[o + 2] = (byte) (expSec >> 24);
        out[o + 3] = (byte) (expSec >> 16);
        out[o + 4] = (byte) (expSec >> 8);
        out[o + 5] = (byte) expSec;
        return out;
    }

    public static byte[] encodeCancel(byte[] nonce) {
        byte[] out = new byte[6 + NONCE_LEN];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = TYPE_CANCEL;
        if (nonce != null && nonce.length >= NONCE_LEN) {
            System.arraycopy(nonce, 0, out, 6, NONCE_LEN);
        }
        return out;
    }

    public static boolean isCancel(byte[] buf, int length) {
        return isAuthPacket(buf, length) && length >= 6 + NONCE_LEN && buf[5] == TYPE_CANCEL;
    }

    public static boolean isCancelled(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof AuthCancelledException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 正在等这台的允许。再点同一台时用来取消。 */
    public static boolean isWaiting(String host) {
        Active a = active;
        return a != null && !a.cancelled && sameHost(a.host, host);
    }

    /** 取消当前申请：通知对方丢掉待授权，并放开本机等待。 */
    public static boolean cancelActive() {
        Active a = active;
        if (a == null || a.cancelled) {
            return false;
        }
        a.cancelled = true;
        byte[] pkt = a.nonce == null ? null : encodeCancel(a.nonce);
        if (pkt != null && a.tcp != null && a.tcp.isConnected()) {
            try {
                OutputStream out = a.tcp.getOutputStream();
                out.write(pkt);
                out.flush();
                a.tcp.shutdownOutput();
            } catch (Exception ignored) {
            }
        }
        if (pkt != null && a.udp != null && a.host != null && a.udpPort > 0) {
            try {
                InetAddress addr = InetAddress.getByName(a.host);
                a.udp.send(new DatagramPacket(pkt, pkt.length, addr, a.udpPort));
            } catch (Exception ignored) {
            }
        }
        if (a.nonce != null) {
            Waiter waiter = WAITING.get(ByteBuffer.wrap(a.nonce));
            if (waiter != null) {
                waiter.fut.completeExceptionally(new AuthCancelledException());
            }
        }
        if (a.tcp != null) {
            try {
                a.tcp.close();
            } catch (IOException ignored) {
            }
        }
        return true;
    }

    private static boolean sameHost(String a, String b) {
        return normHost(a).equals(normHost(b));
    }

    private static String normHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("::ffff:") && h.length() > 7) {
            h = h.substring(7);
        }
        return h;
    }

    public static byte[] encodeDeny(byte[] nonce) {
        return encodeDeny(nonce, DENY_HARD);
    }

    public static byte[] encodeDeny(byte[] nonce, int waitSec) {
        int sec = waitSec < 0 ? DENY_HARD : Math.min(DENY_HARD, waitSec);
        byte[] out = new byte[6 + NONCE_LEN + 2];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = TYPE_DENY;
        System.arraycopy(nonce, 0, out, 6, NONCE_LEN);
        int o = 6 + NONCE_LEN;
        out[o] = (byte) (sec >> 8);
        out[o + 1] = (byte) sec;
        return out;
    }

    /** 旧包没有尾部秒数时返回 -1。 */
    public static int denyWaitSec(byte[] buf, int length) {
        int base = 6 + NONCE_LEN;
        if (buf == null || length < base + 2) {
            return -1;
        }
        return ((buf[base] & 0xFF) << 8) | (buf[base + 1] & 0xFF);
    }

    public static String denyMessage(int waitSec) {
        if (waitSec < 0) {
            return "对方拒绝了这次授权。冷却结束后可以再请求，没有拉黑";
        }
        if (waitSec == DENY_HARD) {
            return "对方拒绝了授权";
        }
        if (waitSec == 0) {
            return "对方拒绝了这次授权，可以再请求";
        }
        return "对方暂时拒绝，约 " + waitSec + " 秒后可以再请求（没有拉黑）";
    }

    public static boolean isDenied(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof AuthDeniedException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null && (m.contains("拒绝") || m.contains("冷却"))) {
                return true;
            }
        }
        return false;
    }

    public static byte[] encodePending(byte[] nonce, byte[] serverPub) {
        if (serverPub == null || serverPub.length != PUB_LEN) {
            throw new IllegalArgumentException("PENDING 需要服务端公钥");
        }
        byte[] out = new byte[6 + NONCE_LEN + PUB_LEN];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = TYPE_PENDING;
        System.arraycopy(nonce, 0, out, 6, NONCE_LEN);
        System.arraycopy(serverPub, 0, out, 6 + NONCE_LEN, PUB_LEN);
        return out;
    }

    /** PENDING 里的服务端公钥；长度不对返回 null。 */
    public static byte[] pendingServerPub(byte[] buf, int length) {
        int need = 6 + NONCE_LEN + PUB_LEN;
        if (buf == null || length < need || buf[5] != TYPE_PENDING) {
            return null;
        }
        byte[] pub = Arrays.copyOfRange(buf, 6 + NONCE_LEN, need);
        return pub[0] == 0x04 ? pub : null;
    }

    /** 短核对码：SHA-256(clientPub‖serverPub‖nonce) 前 4 字节 → ABCD-EF01。 */
    public static String sasCode(byte[] clientPub, byte[] serverPub, byte[] nonce) {
        try {
            if (clientPub == null || serverPub == null || nonce == null
                    || clientPub.length != PUB_LEN || serverPub.length != PUB_LEN
                    || nonce.length != NONCE_LEN) {
                return "";
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(clientPub);
            md.update(serverPub);
            md.update(nonce);
            byte[] dig = md.digest();
            String hex = String.format("%02X%02X%02X%02X",
                    dig[0] & 0xFF, dig[1] & 0xFF, dig[2] & 0xFF, dig[3] & 0xFF);
            return hex.substring(0, 4) + "-" + hex.substring(4);
        } catch (Exception e) {
            return "";
        }
    }

    /** 客户端收到 GRANT/DENY/PENDING 时调用。 */
    public static void onAuthReply(byte[] buf, int length) {
        if (!isAuthPacket(buf, length) || length < 6 + NONCE_LEN) {
            return;
        }
        byte type = buf[5];
        byte[] nonce = Arrays.copyOfRange(buf, 6, 6 + NONCE_LEN);
        ByteBuffer key = ByteBuffer.wrap(nonce);
        Waiter waiter = WAITING.get(key);
        if (waiter == null) {
            return;
        }
        if (type == TYPE_DENY) {
            WAITING.remove(key);
            waiter.fut.completeExceptionally(new AuthDeniedException(denyMessage(denyWaitSec(buf, length))));
            return;
        }
        if (type == TYPE_PENDING) {
            notePending(waiter, buf, length, nonce);
            return;
        }
        if (type != TYPE_GRANT) {
            return;
        }
        try {
            Grant grant = grantFromPacket(buf, length, waiter, nonce);
            WAITING.remove(key);
            waiter.fut.complete(grant);
        } catch (Exception e) {
            WAITING.remove(key);
            waiter.fut.completeExceptionally(e);
        }
    }

    private static void notePending(Waiter waiter, byte[] buf, int length, byte[] nonce) {
        byte[] serverPub = pendingServerPub(buf, length);
        if (serverPub == null) {
            return;
        }
        waiter.pendingServerPub = serverPub;
        String sas = sasCode(waiter.clientPub, serverPub, nonce);
        Consumer<String> cb = waiter.onSas;
        if (cb != null && !sas.isEmpty()) {
            try {
                cb.accept(sas);
            } catch (Exception ignored) {
            }
        }
    }

    /** 用自己的临时私钥和对面公钥算出一次性 PSK。包里看不到这 32 字节。 */
    public static byte[] deriveGrantPsk(KeyPair local, byte[] remotePub, byte[] nonce) throws Exception {
        if (remotePub == null || remotePub.length != PUB_LEN || remotePub[0] != 0x04) {
            throw new IllegalArgumentException("授权公钥无效");
        }
        byte[] secret = EcdhP256.sharedSecret(local, EcdhP256.decodePublic(remotePub));
        return Hkdf.derive(secret, nonce, HKDF_INFO, Protocol.PSK_LEN);
    }

    private static Grant grantFromPacket(byte[] buf, int length, Waiter waiter, byte[] expectNonce) throws Exception {
        int need = 6 + NONCE_LEN + PUB_LEN + 2 + 4;
        if (length < need) {
            throw new IllegalStateException("授权回应太短");
        }
        byte[] nonce = Arrays.copyOfRange(buf, 6, 6 + NONCE_LEN);
        if (!Arrays.equals(nonce, expectNonce)) {
            throw new IllegalStateException("授权回应和这次请求对不上");
        }
        byte[] serverPub = Arrays.copyOfRange(buf, 6 + NONCE_LEN, 6 + NONCE_LEN + PUB_LEN);
        if (serverPub[0] != 0x04) {
            throw new IllegalStateException("授权公钥无效");
        }
        byte[] pending = waiter.pendingServerPub;
        if (pending != null && !Arrays.equals(pending, serverPub)) {
            throw new IllegalStateException("核对码对应的公钥变了，已中止");
        }
        int o = 6 + NONCE_LEN + PUB_LEN;
        int tcpPort = ((buf[o] & 0xFF) << 8) | (buf[o + 1] & 0xFF);
        return new Grant(deriveGrantPsk(waiter.keys, serverPub, nonce), tcpPort);
    }

    /**
     * 向 host:udpPort 请求授权，阻塞直到 GRANT/DENY/超时。
     * 须在探测 socket 已绑定且能收包时调用（由 LanDiscover 转发回复）。
     */
    public static Grant requestAuth(DatagramSocket sock, String host, int udpPort, String deviceName)
            throws Exception {
        return requestAuth(sock, host, udpPort, deviceName, null);
    }

    public static Grant requestAuth(DatagramSocket sock, String host, int udpPort, String deviceName,
                                    Consumer<String> onSas) throws Exception {
        byte[] nonce = new byte[NONCE_LEN];
        RNG.nextBytes(nonce);
        KeyPair keys = EcdhP256.generate();
        byte[] pub = EcdhP256.encodePublic((ECPublicKey) keys.getPublic());
        ByteBuffer key = ByteBuffer.wrap(nonce);
        CompletableFuture<Grant> fut = new CompletableFuture<>();
        Waiter waiter = new Waiter(keys, pub, fut);
        waiter.onSas = onSas;
        WAITING.put(key, waiter);
        Active mine = beginActive(host, udpPort, nonce);
        mine.udp = sock;
        try {
            byte[] packet = encodeRequest(deviceName, nonce, pub);
            InetAddress addr = InetAddress.getByName(host);
            sock.send(new DatagramPacket(packet, packet.length, addr, udpPort));
            return fut.get(REQUEST_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            WAITING.remove(key);
            if (mine.cancelled) {
                throw new AuthCancelledException();
            }
            throw e;
        } finally {
            endActive(mine);
        }
    }

    /**
     * 连上对方的 TCP 端口直接写 TSA1，回复走同一条连接。
     * 先可能收到 PENDING（核对码），再等 GRANT/DENY。
     */
    public static Grant requestAuthTcp(String host, int tcpPort, String deviceName) throws Exception {
        return requestAuthTcp(host, tcpPort, deviceName, null);
    }

    public static Grant requestAuthTcp(String host, int tcpPort, String deviceName, Consumer<String> onSas)
            throws Exception {
        byte[] nonce = new byte[NONCE_LEN];
        RNG.nextBytes(nonce);
        KeyPair keys = EcdhP256.generate();
        byte[] pub = EcdhP256.encodePublic((ECPublicKey) keys.getPublic());
        byte[] packet = encodeRequest(deviceName, nonce, pub);
        Waiter waiter = new Waiter(keys, pub, new CompletableFuture<>());
        waiter.onSas = onSas;
        Socket sock = new Socket();
        Active mine = beginActive(host, 0, nonce);
        mine.tcp = sock;
        try {
            sock.connect(new InetSocketAddress(host, tcpPort), 5000);
            sock.setSoTimeout((int) Math.min(Integer.MAX_VALUE, REQUEST_WAIT_MS));
            OutputStream out = sock.getOutputStream();
            out.write(packet);
            out.flush();
            InputStream in = sock.getInputStream();
            while (true) {
                byte[] head = readN(in, 6);
                if (!isAuthPacket(head, 6)) {
                    throw new IllegalStateException("服务端没有按授权回应");
                }
                byte type = head[5];
                if (type == TYPE_DENY) {
                    byte[] rest = readDenyRest(sock, in);
                    byte[] all = new byte[head.length + rest.length];
                    System.arraycopy(head, 0, all, 0, head.length);
                    System.arraycopy(rest, 0, all, head.length, rest.length);
                    throw new AuthDeniedException(denyMessage(denyWaitSec(all, all.length)));
                }
                if (type == TYPE_PENDING) {
                    byte[] rest = readN(in, NONCE_LEN + PUB_LEN);
                    byte[] all = new byte[head.length + rest.length];
                    System.arraycopy(head, 0, all, 0, head.length);
                    System.arraycopy(rest, 0, all, head.length, rest.length);
                    notePending(waiter, all, all.length, nonce);
                    continue;
                }
                if (type != TYPE_GRANT) {
                    throw new IllegalStateException("服务端没有按授权回应");
                }
                byte[] rest = readN(in, NONCE_LEN + PUB_LEN + 2 + 4);
                byte[] all = new byte[head.length + rest.length];
                System.arraycopy(head, 0, all, 0, head.length);
                System.arraycopy(rest, 0, all, head.length, rest.length);
                return grantFromPacket(all, all.length, waiter, nonce);
            }
        } catch (Exception e) {
            if (mine.cancelled) {
                throw new AuthCancelledException();
            }
            throw e;
        } finally {
            endActive(mine);
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Active beginActive(String host, int udpPort, byte[] nonce) {
        Active mine = new Active();
        mine.host = host;
        mine.udpPort = udpPort;
        mine.nonce = nonce;
        active = mine;
        return mine;
    }

    private static void endActive(Active mine) {
        if (active == mine) {
            active = null;
        }
    }

    private static byte[] readDenyRest(Socket sock, InputStream in) throws IOException {
        byte[] nonce = readN(in, NONCE_LEN);
        int prev = sock.getSoTimeout();
        sock.setSoTimeout(200);
        try {
            byte[] extra = new byte[2];
            int n = in.read(extra);
            if (n == 1) {
                int n2 = in.read(extra, 1, 1);
                n = n2 < 0 ? 1 : 2;
            }
            if (n == 2) {
                byte[] both = new byte[NONCE_LEN + 2];
                System.arraycopy(nonce, 0, both, 0, NONCE_LEN);
                System.arraycopy(extra, 0, both, NONCE_LEN, 2);
                return both;
            }
            return nonce;
        } catch (SocketTimeoutException e) {
            return nonce;
        } finally {
            try {
                sock.setSoTimeout(prev);
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new java.io.EOFException("short read");
            }
            off += r;
        }
        return buf;
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
