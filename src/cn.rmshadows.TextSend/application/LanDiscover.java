package application;

import protocol.TsServer;

import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网 UDP 探测。默认关。
 * 包格式（三端一致）：TSD1 | version=1 | tcpPort uint16 BE | nameLen | name | 8 字节实例号。
 * 只广播正在监听的 TCP 端口和设备名，不带 PIN / 密钥。
 * 探测口默认跟随连接端口（UserConfig：lanDiscoverPortFollowListen）；关掉跟随后可单独改。
 * 协议仍是 UDP；绑不上时 lastBindError 带占用提示。
 * 「有连接时关闭」打开后，已经有会话就停广播、停监听。
 */
public final class LanDiscover {
    public static final int DEFAULT_PORT = 54300;
    private static final byte[] MAGIC = "TSD1".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final int NAME_MAX = 48;
    private static final long INTERVAL_MS = 1000L;
    /** 超过这个时间没再收到广播就从列表去掉。强杀来不及发下线包，只能靠超时。 */
    private static final long PEER_TTL_MS = 2500L;

    private static final byte[] INSTANCE_ID = new byte[8];
    private static final Map<String, PeerSeen> PEERS = new LinkedHashMap<>();
    private static volatile Thread thread;
    private static volatile DatagramSocket socket;
    private static volatile int boundPort = -1;
    private static volatile boolean stop;
    private static volatile String lastBindError = "";

    static {
        new SecureRandom().nextBytes(INSTANCE_ID);
    }

    private LanDiscover() {
    }

    public static final class Peer {
        public final String host;
        public final int tcpPort;
        public final String name;
        /** 实例号 hex；多条相同表示可能是同一进程多网卡 */
        public final String idKey;

        Peer(String host, int tcpPort, String name, String idKey) {
            this.host = host;
            this.tcpPort = tcpPort;
            this.name = name;
            this.idKey = idKey == null ? "" : idKey;
        }
    }

    public static void boot() {
        halt();
        if (!UserConfig.isLanDiscover()) {
            return;
        }
        stop = false;
        lastBindError = "";
        Thread next = new Thread(LanDiscover::loop, "ts-lan");
        next.setDaemon(true);
        thread = next;
        next.start();
    }

    public static void halt() {
        stop = true;
        closeSocket();
        synchronized (PEERS) {
            PEERS.clear();
        }
        lastBindError = "";
    }

    public static String lastBindError() {
        return lastBindError == null ? "" : lastBindError;
    }

    public static List<Peer> peers() {
        long now = System.currentTimeMillis();
        List<Peer> out = new ArrayList<>();
        synchronized (PEERS) {
            PEERS.entrySet().removeIf(e -> now - e.getValue().seenAt > PEER_TTL_MS);
            for (PeerSeen seen : PEERS.values()) {
                out.add(seen.peer);
            }
        }
        out.sort(Comparator.comparing(p -> p.name == null || p.name.isBlank() ? p.host : p.name));
        return out;
    }

    public static boolean pausedNow() {
        return UserConfig.isLanDiscover()
                && UserConfig.isLanDiscoverPause()
                && hasLink();
    }

    private static void loop() {
        long nextSend = 0L;
        long nextProbe = 0L;
        while (!stop && UserConfig.isLanDiscover()) {
            int udpPort = UserConfig.getLanDiscoverPort();
            if (UserConfig.isLanDiscoverPause() && hasLink()) {
                // 停广播，但留着套接字收授权。否则已有人连上时，下一次授权永远进不来。
                synchronized (PEERS) {
                    PEERS.clear();
                }
                lastBindError = "";
                try {
                    receiveOne(ensureSocket(udpPort));
                } catch (Exception e) {
                    closeSocket();
                    lastBindError = bindTip(udpPort, e);
                    sleep(1500);
                }
                continue;
            }
            try {
                DatagramSocket sock = ensureSocket(udpPort);
                lastBindError = "";
                long now = System.currentTimeMillis();
                int port = advertisePort();
                if (UserConfig.isLanDiscoverAnnounce() && port > 0 && now >= nextSend) {
                    sendBeacon(sock, port, udpPort);
                    nextSend = now + INTERVAL_MS;
                }
                if (now >= nextProbe) {
                    sendProbe(sock, udpPort);
                    nextProbe = now + INTERVAL_MS;
                }
                receiveOne(sock);
            } catch (Exception e) {
                closeSocket();
                lastBindError = bindTip(udpPort, e);
                sleep(1500);
            }
        }
        closeSocket();
        synchronized (PEERS) {
            PEERS.clear();
        }
    }

    private static String bindTip(int udpPort, Exception e) {
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (cur instanceof BindException
                    || (msg != null && (msg.contains("Address already in use") || msg.contains("EADDRINUSE")))) {
                return "探测端口 " + udpPort + " 已被占用";
            }
            cur = cur.getCause();
        }
        return "探测端口 " + udpPort + " 打不开";
    }

    private static boolean hasLink() {
        if (TextSendMain.isClientConnected) {
            return true;
        }
        return TsServer.aliveCountCurrent() > 0;
    }

    private static int advertisePort() {
        if (!TextSendMain.isServerRunning()) {
            return -1;
        }
        int port = TextSendMain.getServerListenPort();
        return port >= 1 && port <= 65535 ? port : -1;
    }

    private static DatagramSocket ensureSocket(int udpPort) throws SocketException {
        DatagramSocket existing = socket;
        if (existing != null && !existing.isClosed() && boundPort == udpPort) {
            return existing;
        }
        closeSocket();
        DatagramSocket created = new DatagramSocket(null);
        created.setReuseAddress(true);
        created.setBroadcast(true);
        created.setSoTimeout(800);
        try {
            // 明确绑 IPv4。绑 :: 时若系统 bindv6only=1，手机发来的授权包会直接丢。
            created.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{0, 0, 0, 0}), udpPort));
        } catch (java.net.UnknownHostException e) {
            created.bind(new InetSocketAddress(udpPort));
        }
        socket = created;
        boundPort = udpPort;
        return created;
    }

    private static void sendProbe(DatagramSocket sock, int udpPort) {
        byte[] packet = new byte[]{'T', 'S', 'Q', '1', 1, 0};
        for (InetAddress addr : broadcastTargets()) {
            try {
                sock.send(new DatagramPacket(packet, packet.length, addr, udpPort));
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isQuery(byte[] buf, int len) {
        return len >= 6 && buf[0] == 'T' && buf[1] == 'S' && buf[2] == 'Q' && buf[3] == '1' && buf[4] == 1;
    }

    private static void sendBeacon(DatagramSocket sock, int tcpPort, int udpPort) {
        byte[] packet = encode(tcpPort, deviceName(), INSTANCE_ID);
        for (InetAddress addr : broadcastTargets()) {
            try {
                sock.send(new DatagramPacket(packet, packet.length, addr, udpPort));
            } catch (Exception ignored) {
            }
        }
    }

    private static void receiveOne(DatagramSocket sock) throws Exception {
        byte[] buf = new byte[256];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        try {
            sock.receive(pkt);
        } catch (SocketTimeoutException e) {
            return;
        }
        String host = ipv4Host(pkt.getAddress());
        if (host == null || isOwnAddress(host)) {
            return;
        }
        int len = pkt.getLength();
        if (isQuery(buf, len)) {
            int tcp = advertisePort();
            if (tcp > 0) {
                try {
                    byte[] beacon = encode(tcp, deviceName(), INSTANCE_ID);
                    sock.send(new DatagramPacket(beacon, beacon.length, pkt.getAddress(), pkt.getPort()));
                } catch (Exception ignored) {
                }
            }
            return;
        }
        if (LanAuth.isAuthPacket(buf, len)) {
            byte type = LanAuth.typeOf(buf, len);
            if (type == LanAuth.TYPE_GRANT || type == LanAuth.TYPE_DENY || type == LanAuth.TYPE_PENDING) {
                LanAuth.onAuthReply(buf, len);
                return;
            }
            if (type == LanAuth.TYPE_REQUEST) {
                LanAuth.Request req = LanAuth.parseRequest(buf, len, host, pkt.getPort());
                if (req != null) {
                    TextSendMain.onLanAuthRequest(req);
                }
                return;
            }
            if (type == LanAuth.TYPE_CANCEL && LanAuth.isCancel(buf, len)) {
                byte[] nonce = Arrays.copyOfRange(buf, 6, 6 + LanAuth.NONCE_LEN);
                TextSendMain.onLanAuthCancel(host, nonce);
                return;
            }
            return;
        }
        Decoded decoded = decode(buf, len);
        if (decoded == null || Arrays.equals(decoded.id, INSTANCE_ID)) {
            return;
        }
        String idKey = toHex(decoded.id);
        Peer peer = new Peer(host, decoded.tcpPort, decoded.name, idKey);
        synchronized (PEERS) {
            // 同实例多网卡：两条都留，UI 用 idKey 提示可能是同一设备
            PEERS.put(host + ":" + peer.tcpPort, new PeerSeen(peer, System.currentTimeMillis(), idKey));
        }
    }

    /** 供客户端发起临时授权；探测未开或 socket 未就绪时抛错。 */
    public static LanAuth.Grant requestAuth(String host, int udpPort) throws Exception {
        return requestAuth(host, udpPort, null);
    }

    public static LanAuth.Grant requestAuth(String host, int udpPort, java.util.function.Consumer<String> onSas)
            throws Exception {
        DatagramSocket sock = socket;
        if (sock == null || sock.isClosed()) {
            throw new IllegalStateException("请先打开局域网探测");
        }
        return LanAuth.requestAuth(sock, host, udpPort, deviceName(), onSas);
    }

    public static LanAuth.Grant requestAuthTcp(String host, int tcpPort) throws Exception {
        return requestAuthTcp(host, tcpPort, null);
    }

    public static LanAuth.Grant requestAuthTcp(String host, int tcpPort, java.util.function.Consumer<String> onSas)
            throws Exception {
        return LanAuth.requestAuthTcp(host, tcpPort, deviceName(), onSas);
    }

    public static void sendAuthPacket(String host, int port, byte[] packet) {
        DatagramSocket sock = socket;
        if (sock == null || sock.isClosed() || packet == null) {
            return;
        }
        try {
            sock.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(host), port));
        } catch (Exception ignored) {
        }
    }

    public static String localDeviceName() {
        return deviceName();
    }

    /** IPv4，或 IPv6 映射的 IPv4。回环和 0.0.0.0 丢掉。 */
    private static String ipv4Host(InetAddress addr) {
        byte[] raw = null;
        if (addr instanceof Inet4Address ip4) {
            if (ip4.isAnyLocalAddress() || ip4.isLoopbackAddress()) {
                return null;
            }
            raw = ip4.getAddress();
        } else if (addr != null && addr.getAddress() != null && addr.getAddress().length == 16) {
            byte[] b = addr.getAddress();
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (!(mapped && b[10] == (byte) 0xff && b[11] == (byte) 0xff)) {
                return null;
            }
            raw = new byte[]{b[12], b[13], b[14], b[15]};
            if ((raw[0] & 0xFF) == 127 || (raw[0] == 0 && raw[1] == 0 && raw[2] == 0 && raw[3] == 0)) {
                return null;
            }
        }
        if (raw == null) {
            return null;
        }
        return (raw[0] & 0xFF) + "." + (raw[1] & 0xFF) + "." + (raw[2] & 0xFF) + "." + (raw[3] & 0xFF);
    }

    private static boolean isOwnAddress(String host) {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    String local = ipv4Host(addr);
                    if (host.equals(local)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void closeSocket() {
        DatagramSocket current = socket;
        socket = null;
        boundPort = -1;
        if (current != null) {
            current.close();
        }
    }

    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b instanceof Inet4Address) {
                        out.add(b);
                    }
                }
            }
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 本机设备名（探测广播 / 授权请求 / 已连接列表回填共用）。 */
    public static String deviceName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "";
        }
        if (host == null || host.isBlank()) {
            host = System.getProperty("user.name", "PC");
        }
        host = host.replaceAll("\\p{Cntrl}", "").trim();
        return host.isBlank() ? "PC" : host;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PeerSeen {
        final Peer peer;
        final long seenAt;
        final String idKey;

        PeerSeen(Peer peer, long seenAt, String idKey) {
            this.peer = peer;
            this.seenAt = seenAt;
            this.idKey = idKey == null ? "" : idKey;
        }
    }

    private static String toHex(byte[] id) {
        if (id == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(id.length * 2);
        for (byte b : id) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static final class Decoded {
        final int tcpPort;
        final String name;
        final byte[] id;

        Decoded(int tcpPort, String name, byte[] id) {
            this.tcpPort = tcpPort;
            this.name = name;
            this.id = id;
        }
    }

    static byte[] encode(int tcpPort, String name, byte[] id) {
        byte[] nameBytes = clipName(name);
        byte[] out = new byte[4 + 1 + 2 + 1 + nameBytes.length + 8];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = VERSION;
        out[5] = (byte) (tcpPort >> 8);
        out[6] = (byte) tcpPort;
        out[7] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, out, 8, nameBytes.length);
        System.arraycopy(id, 0, out, 8 + nameBytes.length, 8);
        return out;
    }

    private static Decoded decode(byte[] buf, int length) {
        if (length < 16 || buf[0] != 'T' || buf[1] != 'S' || buf[2] != 'D' || buf[3] != '1' || buf[4] != VERSION) {
            return null;
        }
        int port = ((buf[5] & 0xFF) << 8) | (buf[6] & 0xFF);
        int nameLen = buf[7] & 0xFF;
        if (port < 1 || port > 65535 || nameLen > NAME_MAX || length != 16 + nameLen) {
            return null;
        }
        String name = new String(buf, 8, nameLen, StandardCharsets.UTF_8);
        byte[] id = Arrays.copyOfRange(buf, 8 + nameLen, 16 + nameLen);
        return new Decoded(port, name, id);
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

    /** 固定向量，和 uTools / Android 对一下包字节。 */
    public static void main(String[] args) {
        byte[] id = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] pkt = encode(54300, "pc", id);
        StringBuilder hex = new StringBuilder();
        for (byte b : pkt) {
            hex.append(String.format("%02x", b));
        }
        String expect = "5453443101d41c0270630102030405060708";
        if (!expect.equals(hex.toString())) {
            throw new AssertionError(hex.toString());
        }
        System.out.println("ok");
    }
}
