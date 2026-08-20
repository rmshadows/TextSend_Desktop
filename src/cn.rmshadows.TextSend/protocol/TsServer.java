package protocol;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<TsPeer> peers = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AuthLimiter authLimiter = new AuthLimiter();
    private ServerSocket serverSocket;

    public TsServer(int port, int maxConnections, PairingMaterial material, IntConsumer onClientCount) {
        this.port = port;
        this.maxConnections = maxConnections;
        this.material = material;
        this.onClientCount = onClientCount;
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

    public void stop() {
        running.set(false);
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
            System.out.println("Log: v1 server listen " + port + " PIN=" + material.pin
                    + "  (psk+pin 两种连接都接受)");
            while (running.get()) {
                Socket sock = ss.accept();
                String ip = sock.getInetAddress() == null ? "?" : sock.getInetAddress().getHostAddress();
                System.out.println("Log: 【接入】TCP " + ip + ":" + sock.getPort());
                if (!authLimiter.allow(ip)) {
                    System.out.println("Log: 【拒绝】" + ip + " PIN 失败次数过多，已锁定");
                    sock.close();
                    continue;
                }
                synchronized (peers) {
                    peers.removeIf(p -> !p.isAlive());
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
                        if (self != null && !self.handshakeSucceeded() && running.get()) {
                            authLimiter.fail(ip);
                        }
                        synchronized (peers) {
                            peers.removeIf(p -> !p.isAlive());
                        }
                        notifyCount();
                    });
                    box[0] = peer;
                    peers.add(peer);
                    pool.execute(peer);
                    notifyCount();
                }
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
