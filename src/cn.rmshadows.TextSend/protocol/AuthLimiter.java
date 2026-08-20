package protocol;

import java.util.concurrent.ConcurrentHashMap;

/** PIN 路径：同一 IP 连续失败后锁定一段时间。 */
final class AuthLimiter {
    private static final class Window {
        int fails;
        long lockUntil;
    }

    private final ConcurrentHashMap<String, Window> map = new ConcurrentHashMap<>();

    boolean allow(String ip) {
        if (ip == null) {
            return true;
        }
        Window w = map.get(ip);
        if (w == null) {
            return true;
        }
        synchronized (w) {
            return w.lockUntil <= System.currentTimeMillis();
        }
    }

    void fail(String ip) {
        if (ip == null) {
            return;
        }
        Window w = map.computeIfAbsent(ip, k -> new Window());
        synchronized (w) {
            if (w.lockUntil > System.currentTimeMillis()) {
                return;
            }
            w.fails++;
            if (w.fails >= Protocol.PIN_FAIL_MAX) {
                w.lockUntil = System.currentTimeMillis() + Protocol.PIN_LOCK_MS;
                w.fails = 0;
                System.out.println("Log: auth lock " + ip + " for " + (Protocol.PIN_LOCK_MS / 1000) + "s");
            }
        }
    }

    void ok(String ip) {
        if (ip != null) {
            map.remove(ip);
        }
    }
}
