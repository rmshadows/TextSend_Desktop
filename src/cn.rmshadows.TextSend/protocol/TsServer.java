package protocol;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * v1 服务端：accept + 多会话
 */
public final class TsServer implements Runnable {
    private static volatile TsServer instance;

    private final int port;
    private final int maxConnections;
    private final PairingMaterial material;
    private final IntConsumer onClientCount;
    private final FileIoCallback fileIo;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<TsPeer> peers = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AuthLimiter authLimiter = new AuthLimiter();
    private final ConcurrentHashMap<String, GrantEntry> lanGrants = new ConcurrentHashMap<>();
    /** 授权时记下的设备名，供已连接列表展示（按 IP）。 */
    private final ConcurrentHashMap<String, String> clientNames = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;

    private static final class GrantEntry {
        final byte[] psk;
        final long expireAt;

        GrantEntry(byte[] psk, long expireAt) {
            this.psk = psk;
            this.expireAt = expireAt;
        }
    }

    /** 已握手的连接摘要，供踢下线 UI。 */
    public static final class AliveClient {
        public final String host;
        public final int port;
        public final String name;
        public final TsPeer peer;

        AliveClient(String host, int port, String name, TsPeer peer) {
            this.host = host;
            this.port = port;
            this.name = name == null ? "" : name;
            this.peer = peer;
        }

        /** 列表主文案：有设备名则「名 · IP」，否则仅 IP。 */
        public String displayLabel() {
            if (name != null && !name.isBlank()) {
                return name + "  ·  " + host;
            }
            return host == null ? "" : host;
        }
    }

    public TsServer(int port, int maxConnections, PairingMaterial material, IntConsumer onClientCount,
                   FileIoCallback fileIo) {
        this.port = port;
        this.maxConnections = maxConnections;
        this.material = material;
        this.onClientCount = onClientCount;
        this.fileIo = fileIo;
        instance = this;
    }

    public PairingMaterial getMaterial() {
        return material;
    }

    public static void stopCurrent() {
        TsServer s = instance;
        if (s != null) {
            s.stop();
        }
    }

    /** @return 实际发出的会话数；服务未运行返回 -1 */
    public static int sendToAllCurrent(String text) {
        TsServer s = instance;
        if (s == null) {
            return -1;
        }
        return s.sendToAll(text);
    }

    public static int aliveCountCurrent() {
        TsServer s = instance;
        return s == null ? -1 : s.aliveCount();
    }

    public static List<AliveClient> listAliveClientsCurrent() {
        TsServer s = instance;
        if (s == null) {
            return List.of();
        }
        return s.listAliveClients();
    }

    public static boolean kickHostCurrent(String host) {
        TsServer s = instance;
        return s != null && s.kickHost(host);
    }

    public static void putLanGrantCurrent(String host, byte[] psk, long ttlMs) {
        putLanGrantCurrent(host, psk, ttlMs, null);
    }

    public static void putLanGrantCurrent(String host, byte[] psk, long ttlMs, String deviceName) {
        TsServer s = instance;
        if (s != null) {
            s.putLanGrant(host, psk, ttlMs, deviceName);
        }
    }

    public static void revokeLanGrantCurrent(String host) {
        TsServer s = instance;
        if (s != null) {
            s.revokeLanGrant(host);
        }
    }

    /** 握手用：有未过期授权票则返回其 PSK，否则 null。不消费。 */
    public static byte[] peekLanGrantPsk(String host) {
        TsServer s = instance;
        return s == null ? null : s.peekGrant(host);
    }

    public static void consumeLanGrant(String host, byte[] psk) {
        TsServer s = instance;
        if (s != null) {
            s.consumeGrant(host, psk);
        }
    }

    /** 同一张票只让一路握手拿走。第二次返回 false。 */
    public static boolean claimLanGrantCurrent(String host, byte[] psk) {
        TsServer s = instance;
        return s != null && s.claimGrant(host, psk);
    }

    public static int sendFileToAllCurrent(Path path, BooleanSupplier cancelled) throws Exception {
        return sendFileToAllCurrent(path, cancelled, null, null);
    }

    public static int sendFileToAllCurrent(Path path, BooleanSupplier cancelled,
                                           String treeName, String relPath) throws Exception {
        return sendFileToAllCurrent(path, cancelled, treeName, relPath, 0, 0);
    }

    public static int sendFileToAllCurrent(Path path, BooleanSupplier cancelled,
                                           String treeName, String relPath, int seq, int of)
            throws Exception {
        TsServer s = instance;
        if (s == null) {
            return -1;
        }
        return s.sendFileToAll(path, cancelled, treeName, relPath, seq, of);
    }

    public static void cancelFilesCurrent() {
        TsServer s = instance;
        if (s != null) {
            s.cancelFiles();
        }
    }

    public static void prepareOutgoingCurrent() {
        TsServer s = instance;
        if (s != null) {
            s.prepareOutgoing();
        }
    }

    public void prepareOutgoing() {
        List<TsPeer> snap;
        synchronized (peers) {
            snap = new ArrayList<>(peers);
        }
        for (TsPeer p : snap) {
            p.prepareOutgoingSend();
        }
    }

    public void stop() {
        running.set(false);
        lanGrants.clear();
        clientNames.clear();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        List<TsPeer> toClose;
        synchronized (peers) {
            toClose = new ArrayList<>(peers);
            peers.clear();
        }
        for (TsPeer p : toClose) {
            p.close();
        }
        pool.shutdownNow();
        if (instance == this) {
            instance = null;
        }
        notifyCount();
    }

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket(port, 50)) {
            serverSocket = ss;
            System.out.println("Log: v1 server listen *:" + port + " PIN=" + material.pin
                    + "  (psk+pin 两种连接都接受)");
            while (running.get()) {
                Socket sock = ss.accept();
                try {
                    sock.setTcpNoDelay(true);
                    sock.setKeepAlive(true);
                } catch (IOException ignored) {
                }
                if (divertAuth(sock)) {
                    continue;
                }
                String ip = sock.getInetAddress() == null ? "?" : sock.getInetAddress().getHostAddress();
                System.out.println("Log: 【接入】TCP " + ip + ":" + sock.getPort()
                        + " 本机=" + sock.getLocalSocketAddress());
                if (!authLimiter.allow(ip)) {
                    System.out.println("Log: 【拒绝】" + ip + " PIN 失败次数过多，已锁定");
                    sock.close();
                    continue;
                }
                synchronized (peers) {
                    peers.removeIf(p -> !p.occupiesSlot());
                    if (peers.size() >= maxConnections) {
                        System.out.println("Log: 【拒绝】" + ip + " 已达最大连接 " + maxConnections);
                        sock.close();
                        continue;
                    }
                    TsPeer[] box = new TsPeer[1];
                    TsPeer peer = new TsPeer(sock, material, msg -> {
                    }, () -> {
                        authLimiter.ok(ip);
                        notifyCount();
                    }, () -> {
                        TsPeer self = box[0];
                        if (self != null && self.authRejected() && running.get()) {
                            authLimiter.fail(ip);
                        }
                        synchronized (peers) {
                            peers.removeIf(p -> !p.occupiesSlot());
                        }
                        notifyCount();
                    });
                    peer.setFileIo(fileIo);
                    box[0] = peer;
                    peers.add(peer);
                    pool.execute(peer);
                }
                notifyCount();
            }
        } catch (IOException e) {
            if (running.get()) {
                e.printStackTrace();
            }
        } finally {
            stop();
            application.TextSendMain.onServerListenEnded();
        }
    }

    /**
     * 开头是 TSA1 的连接是授权请求，不占会话名额。
     * 正常握手由服务端先发 HELLO，客户端不会先开口。只等很短一会儿，
     * 没字节就当普通连接，交给 TsPeer。
     */
    private boolean divertAuth(Socket sock) {
        try {
            sock.setSoTimeout(400);
            java.io.InputStream in = sock.getInputStream();
            byte[] head = readN(in, 4);
            if (head[0] == 'T' && head[1] == 'S' && head[2] == 'A' && head[3] == '1') {
                pool.execute(() -> finishAuthTcp(sock, in));
                return true;
            }
            TsPeer.prime(sock, head);
            return false;
        } catch (java.net.SocketTimeoutException e) {
            try {
                sock.setSoTimeout(0);
            } catch (IOException ignored) {
            }
            return false;
        } catch (Exception e) {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
            return true;
        }
    }

    private void finishAuthTcp(Socket sock, java.io.InputStream in) {
        try {
            byte[] meta = readN(in, 3);
            if (meta[0] != application.LanAuth.VERSION || meta[1] != application.LanAuth.TYPE_REQUEST) {
                return;
            }
            int nameLen = meta[2] & 0xFF;
            if (nameLen > application.LanAuth.NAME_MAX) {
                return;
            }
            byte[] tail = readN(in, nameLen + application.LanAuth.NONCE_LEN + application.LanAuth.PUB_LEN);
            byte[] full = new byte[7 + nameLen + application.LanAuth.NONCE_LEN + application.LanAuth.PUB_LEN];
            full[0] = 'T';
            full[1] = 'S';
            full[2] = 'A';
            full[3] = '1';
            System.arraycopy(meta, 0, full, 4, 3);
            System.arraycopy(tail, 0, full, 7, tail.length);
            String ip = sock.getInetAddress() == null ? "" : sock.getInetAddress().getHostAddress();
            application.LanAuth.Request req = application.LanAuth.parseRequest(
                    full, full.length, ip, sock.getPort());
            if (req == null) {
                return;
            }
            java.util.concurrent.CompletableFuture<byte[]> fut = new java.util.concurrent.CompletableFuture<>();
            req.tcpReply = fut;
            application.TextSendMain.onLanAuthRequest(req);
            java.io.OutputStream out = sock.getOutputStream();
            byte[] early = req.earlyPending;
            if (early != null) {
                out.write(early);
                out.flush();
            }
            byte[] resp = waitAuthReply(sock, in, fut, req);
            if (resp != null) {
                out.write(resp);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 等允许/拒绝，同时看客户端有没有关掉或发来取消。 */
    private byte[] waitAuthReply(Socket sock, java.io.InputStream in,
                                 java.util.concurrent.CompletableFuture<byte[]> fut,
                                 application.LanAuth.Request req) throws Exception {
        long end = System.currentTimeMillis() + application.LanAuth.REQUEST_WAIT_MS;
        byte[] buf = new byte[64];
        java.io.ByteArrayOutputStream extra = new java.io.ByteArrayOutputStream();
        sock.setSoTimeout(400);
        while (!fut.isDone() && System.currentTimeMillis() < end) {
            try {
                int n = in.read(buf);
                if (n < 0) {
                    application.TextSendMain.onLanAuthCancel(req);
                    return null;
                }
                extra.write(buf, 0, n);
                byte[] got = extra.toByteArray();
                if (application.LanAuth.isCancel(got, got.length)) {
                    application.TextSendMain.onLanAuthCancel(req);
                    return null;
                }
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (IOException closed) {
                application.TextSendMain.onLanAuthCancel(req);
                return null;
            }
        }
        if (!fut.isDone() || fut.isCompletedExceptionally() || fut.isCancelled()) {
            return null;
        }
        return fut.getNow(null);
    }

    private static byte[] readN(java.io.InputStream in, int n) throws IOException {
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

    public int sendToAll(String text) {
        List<TsPeer> snap;
        synchronized (peers) {
            snap = new ArrayList<>(peers);
        }
        int sent = 0;
        for (TsPeer p : snap) {
            if (!p.isAlive()) {
                continue;
            }
            try {
                p.sendText(text);
                sent++;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return sent;
    }

    public int aliveCount() {
        synchronized (peers) {
            return (int) peers.stream().filter(TsPeer::isAlive).count();
        }
    }

    public List<AliveClient> listAliveClients() {
        List<AliveClient> out = new ArrayList<>();
        synchronized (peers) {
            for (TsPeer p : peers) {
                if (!p.isAlive()) {
                    continue;
                }
                String host = normalizeHost(p.remoteHost());
                String name = clientNames.getOrDefault(host, "");
                out.add(new AliveClient(host, p.remotePort(), name, p));
            }
        }
        return out;
    }

    public boolean kickHost(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return false;
        }
        List<TsPeer> hit = new ArrayList<>();
        synchronized (peers) {
            for (TsPeer p : peers) {
                if (h.equals(normalizeHost(p.remoteHost()))) {
                    hit.add(p);
                }
            }
        }
        for (TsPeer p : hit) {
            p.close();
        }
        revokeLanGrant(h);
        clientNames.remove(h);
        if (!hit.isEmpty()) {
            notifyCount();
        }
        return !hit.isEmpty();
    }

    public void putLanGrant(String host, byte[] psk, long ttlMs) {
        putLanGrant(host, psk, ttlMs, null);
    }

    public void putLanGrant(String host, byte[] psk, long ttlMs, String deviceName) {
        String h = normalizeHost(host);
        if (h.isEmpty() || psk == null || psk.length != Protocol.PSK_LEN) {
            return;
        }
        pruneGrants();
        lanGrants.put(h, new GrantEntry(Arrays.copyOf(psk, psk.length),
                System.currentTimeMillis() + Math.max(5_000L, ttlMs)));
        if (deviceName != null && !deviceName.isBlank()) {
            clientNames.put(h, deviceName.trim());
        }
    }

    public void revokeLanGrant(String host) {
        String h = normalizeHost(host);
        if (!h.isEmpty()) {
            lanGrants.remove(h);
        }
    }

    public void rememberClientName(String host, String deviceName) {
        String h = normalizeHost(host);
        if (h.isEmpty() || deviceName == null || deviceName.isBlank()) {
            return;
        }
        clientNames.put(h, deviceName.trim());
    }

    byte[] peekGrant(String host) {
        pruneGrants();
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return null;
        }
        GrantEntry g = lanGrants.get(h);
        if (g == null || g.expireAt <= System.currentTimeMillis()) {
            return null;
        }
        return Arrays.copyOf(g.psk, g.psk.length);
    }

    /** 握手时按顺序试：先对上这个地址，再试同一张票记在别的网卡上的。 */
    public static final class GrantCandidate {
        public final String host;
        public final byte[] psk;
        public final boolean forHost;

        GrantCandidate(String host, byte[] psk, boolean forHost) {
            this.host = host;
            this.psk = psk;
            this.forHost = forHost;
        }
    }

    List<GrantCandidate> grantCandidates(String host) {
        pruneGrants();
        String h = normalizeHost(host);
        long now = System.currentTimeMillis();
        List<GrantCandidate> out = new ArrayList<>();
        GrantEntry exact = h.isEmpty() ? null : lanGrants.get(h);
        if (exact != null && exact.expireAt > now) {
            out.add(new GrantCandidate(h, Arrays.copyOf(exact.psk, exact.psk.length), true));
        }
        for (Map.Entry<String, GrantEntry> e : lanGrants.entrySet()) {
            if (e.getKey().equals(h) || e.getValue().expireAt <= now) {
                continue;
            }
            out.add(new GrantCandidate(e.getKey(),
                    Arrays.copyOf(e.getValue().psk, e.getValue().psk.length), false));
        }
        return out;
    }

    public static List<GrantCandidate> grantCandidatesCurrent(String host) {
        TsServer s = instance;
        return s == null ? List.of() : s.grantCandidates(host);
    }

    void consumeGrant(String host, byte[] psk) {
        claimGrant(host, psk);
    }

    boolean claimGrant(String host, byte[] psk) {
        String h = normalizeHost(host);
        if (h.isEmpty() || psk == null) {
            return false;
        }
        synchronized (lanGrants) {
            GrantEntry g = lanGrants.get(h);
            if (g == null || g.expireAt <= System.currentTimeMillis() || !Arrays.equals(g.psk, psk)) {
                return false;
            }
            lanGrants.remove(h, g);
            return true;
        }
    }

    /** 统一 IP 键：去括号、去 IPv4 映射前缀。 */
    public static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.length() > 7 && h.regionMatches(true, 0, "::ffff:", 0, 7)) {
            h = h.substring(7);
        }
        return h;
    }

    /** 是否连回本机（回环或本机网卡地址）。 */
    public static boolean isSelfHost(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty()) {
            return false;
        }
        if ("127.0.0.1".equals(h) || "::1".equals(h) || "localhost".equalsIgnoreCase(h)) {
            return true;
        }
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (h.equals(normalizeHost(addr.getHostAddress()))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void pruneGrants() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, GrantEntry>> it = lanGrants.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, GrantEntry> e = it.next();
            if (e.getValue().expireAt <= now) {
                it.remove();
            }
        }
    }

    public int sendFileToAll(Path path, BooleanSupplier cancelled) throws Exception {
        return sendFileToAll(path, cancelled, null, null);
    }

    public int sendFileToAll(Path path, BooleanSupplier cancelled, String treeName, String relPath)
            throws Exception {
        return sendFileToAll(path, cancelled, treeName, relPath, 0, 0);
    }

    public int sendFileToAll(Path path, BooleanSupplier cancelled, String treeName, String relPath,
                             int seq, int of) throws Exception {
        List<TsPeer> snap;
        synchronized (peers) {
            snap = new ArrayList<>(peers);
        }
        List<TsPeer> targets = new ArrayList<>();
        int unsupported = 0;
        for (TsPeer p : snap) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new IOException("cancelled");
            }
            if (!p.isAlive()) {
                continue;
            }
            if (!p.supportsFile()) {
                unsupported++;
                continue;
            }
            targets.add(p);
        }
        if (targets.isEmpty()) {
            if (unsupported > 0) {
                throw new IOException("对端还不支持文件传输");
            }
            return 0;
        }
        long size = Files.size(path);
        int n = targets.size();
        if (n == 1) {
            targets.get(0).sendFile(path, cancelled, treeName, relPath, seq, of);
            return 1;
        }
        final long[] peerBytes = new long[n];
        final Object progressLock = new Object();
        FileIoCallback[] prev = new FileIoCallback[n];
        for (int i = 0; i < n; i++) {
            TsPeer p = targets.get(i);
            prev[i] = p.getFileIo();
            final int idx = i;
            FileIoCallback orig = prev[i];
            p.setFileIo(new FileIoCallback() {
                @Override
                public void onProgress(boolean incoming, String name, long done, long total, int s, int o) {
                    if (incoming) {
                        if (orig != null) {
                            orig.onProgress(true, name, done, total, s, o);
                        }
                        return;
                    }
                    long sum;
                    synchronized (progressLock) {
                        peerBytes[idx] = done;
                        sum = 0;
                        for (long b : peerBytes) {
                            sum += b;
                        }
                    }
                    if (orig != null) {
                        orig.onProgress(false, name, sum, size * n, s, o);
                    }
                }

                @Override
                public void onReceived(String name, String where) {
                    if (orig != null) {
                        orig.onReceived(name, where);
                    }
                }

                @Override
                public void onReceiveFailed(String name, String err) {
                    if (orig != null) {
                        orig.onReceiveFailed(name, err);
                    }
                }
            });
        }
        ExecutorService ex = Executors.newFixedThreadPool(n);
        int sent = 0;
        Exception last = null;
        try {
            List<Future<?>> futs = new ArrayList<>();
            for (TsPeer p : targets) {
                futs.add(ex.submit(() -> {
                    p.sendFile(path, cancelled, treeName, relPath, seq, of);
                    return null;
                }));
            }
            for (Future<?> f : futs) {
                try {
                    f.get();
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("cancelled");
                } catch (ExecutionException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    if (c.getMessage() != null && c.getMessage().contains("cancelled")) {
                        cancelFiles();
                        throw (c instanceof Exception ex0) ? ex0 : new IOException("cancelled", c);
                    }
                    last = c instanceof Exception ex1 ? ex1 : new IOException(c);
                    c.printStackTrace();
                }
            }
        } finally {
            ex.shutdownNow();
            try {
                ex.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < n; i++) {
                targets.get(i).setFileIo(prev[i]);
            }
        }
        if (sent == 0 && last != null) {
            throw last;
        }
        return sent;
    }

    public void cancelFiles() {
        List<TsPeer> snap;
        synchronized (peers) {
            snap = new ArrayList<>(peers);
        }
        for (TsPeer p : snap) {
            p.cancelFile();
        }
    }

    private void notifyCount() {
        int n;
        synchronized (peers) {
            n = (int) peers.stream().filter(TsPeer::isAlive).count();
        }
        if (onClientCount != null) {
            onClientCount.accept(n);
        }
    }
}
