package application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 用户配置：UI 缩放等。
 * 优先写在程序目录 textsend.properties（绿色版 / jar）；
 * 程序目录不可写（如 /opt 安装包）时，退回用户主目录下仅一份 .textsend.properties。
 * 不建 ~/.config 目录。
 */
public final class UserConfig {
    private static final String NAME = "textsend.properties";
    private static final String FALLBACK_NAME = ".textsend.properties";
    private static final Path LEGACY = Path.of(
            System.getProperty("user.home", "."), ".config", "textsend", "desktop.properties");
    private static final Path FILE = resolveConfigFile();

    private static float uiScale = 1.0f;
    private static int listenPort = 54300;
    private static boolean preferMini = false;
    private static boolean followSymlinks = false;
    private static String listenIp = "";
    private static boolean autoStartServer = false;
    private static boolean customPinEnabled = false;
    private static String customPin = "";
    private static boolean rememberLastClient = false;
    private static boolean lanDiscover = false;
    /** 有连接时停止探测。默认开 */
    private static boolean lanDiscoverPause = true;
    /** 关了就不周期广播，只在收到客户端探测时回复 */
    private static boolean lanDiscoverAnnounce = true;
    private static int lanDiscoverPort = 54300;
    /** 默认开：探测 UDP 口跟连接 TCP 口（listenPort）相同，设置里探测端口框禁用 */
    private static boolean lanDiscoverPortFollowListen = true;
    /** 中键启动时的最大客户端数（左键仍为 1） */
    private static int maxClients = 7;
    /** 二维码失焦（点到别处/其它软件）时自动关闭 */
    private static boolean qrCloseOnFocusLost = false;
    /** 同一 IP 每分钟最多几条授权请求（超出静默拒绝） */
    private static int authRatePerMin = 3;
    /** 拒绝后该 IP 冷却秒数（冷却期内静默拒绝）。默认 10。 */
    private static int authCooldownSec = 10;
    /** 授权黑名单 IP（持久化） */
    private static final LinkedHashSet<String> authBlacklist = new LinkedHashSet<>();
    private static String clientHost = "";
    private static int clientPort = 54300;
    private static String clientPin = "";

    private UserConfig() {
    }

    /** jar 旁或 -Dtextsend.home（jpackage 的 $ROOTDIR）；IDE 用工作目录。 */
    private static Path resolveAppDir() {
        String home = System.getProperty("textsend.home");
        if (home != null && !home.isBlank()) {
            return Path.of(home.trim()).toAbsolutePath().normalize();
        }
        try {
            var src = UserConfig.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                Path loc = Path.of(src.getLocation().toURI()).toAbsolutePath().normalize();
                if (Files.isRegularFile(loc) && loc.getFileName().toString().endsWith(".jar")) {
                    Path parent = loc.getParent();
                    if (parent != null) {
                        return parent;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static Path resolveConfigFile() {
        Path dir = resolveAppDir();
        Path primary = dir.resolve(NAME);
        if (canWriteDir(dir)) {
            return primary;
        }
        Path fallback = Path.of(System.getProperty("user.home", "."), FALLBACK_NAME)
                .toAbsolutePath().normalize();
        System.err.println("程序目录不可写，配置使用主目录 " + FALLBACK_NAME);
        return fallback;
    }

    /** 只检查权限，不在系统目录里创建探测文件。 */
    private static boolean canWriteDir(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                return Files.isWritable(dir);
            }
            Path parent = dir.getParent();
            return parent != null && Files.isDirectory(parent) && Files.isWritable(parent);
        } catch (Exception e) {
            return false;
        }
    }

    public static void load() {
        migrateLegacyIfNeeded();
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
            uiScale = parseScale(p.getProperty("uiScale", "1.0"));
            listenPort = Integer.parseInt(p.getProperty("listenPort", "54300"));
            preferMini = Boolean.parseBoolean(p.getProperty("preferMini", "false"));
            followSymlinks = Boolean.parseBoolean(p.getProperty("followSymlinks", "false"));
            listenIp = p.getProperty("listenIp", "").trim();
            autoStartServer = Boolean.parseBoolean(p.getProperty("autoStartServer", "false"));
            customPinEnabled = Boolean.parseBoolean(p.getProperty("customPinEnabled", "false"));
            customPin = p.getProperty("customPin", "").trim();
            if (!isValidPin(customPin)) {
                customPin = "";
            }
            rememberLastClient = Boolean.parseBoolean(p.getProperty("rememberLastClient", "false"));
            lanDiscover = Boolean.parseBoolean(p.getProperty("lanDiscover", "false"));
            lanDiscoverPause = Boolean.parseBoolean(p.getProperty("lanDiscoverPause", "true"));
            lanDiscoverAnnounce = Boolean.parseBoolean(p.getProperty("lanDiscoverAnnounce", "true"));
            lanDiscoverPort = parsePort(54300, p.getProperty("lanDiscoverPort", "54300"));
            // 旧默认 54301 收成和连接相同的 54300。单独改过的端口不动。
            if (lanDiscoverPort == 54301) {
                lanDiscoverPort = 54300;
            }
            // 缺省 true；旧配置没有该键时跟默认一致
            lanDiscoverPortFollowListen = Boolean.parseBoolean(
                    p.getProperty("lanDiscoverPortFollowListen", "true"));
            maxClients = parseMaxClients(p.getProperty("maxClients", "7"));
            qrCloseOnFocusLost = Boolean.parseBoolean(p.getProperty("qrCloseOnFocusLost", "false"));
            authRatePerMin = parseBoundedInt(p.getProperty("authRatePerMin", "3"), 3, 1, 60);
            authCooldownSec = parseBoundedInt(p.getProperty("authCooldownSec", "10"), 10, 0, 86400);
            authBlacklist.clear();
            authBlacklist.addAll(parseBlacklist(p.getProperty("authBlacklist", "")));
            clientHost = p.getProperty("clientHost", "").trim();
            clientPort = parsePort(54300, p.getProperty("clientPort", "54300"));
            clientPin = p.getProperty("clientPin", "").trim();
            if (!isValidPin(clientPin)) {
                clientPin = "";
            }
        } catch (Exception e) {
            System.err.println("读取配置失败: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            Path dir = FILE.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Properties p = new Properties();
            p.setProperty("uiScale", Float.toString(uiScale));
            p.setProperty("listenPort", Integer.toString(listenPort));
            p.setProperty("preferMini", Boolean.toString(preferMini));
            p.setProperty("followSymlinks", Boolean.toString(followSymlinks));
            p.setProperty("listenIp", listenIp == null ? "" : listenIp);
            p.setProperty("autoStartServer", Boolean.toString(autoStartServer));
            p.setProperty("customPinEnabled", Boolean.toString(customPinEnabled));
            p.setProperty("customPin", customPin == null ? "" : customPin);
            p.setProperty("rememberLastClient", Boolean.toString(rememberLastClient));
            p.setProperty("lanDiscover", Boolean.toString(lanDiscover));
            p.setProperty("lanDiscoverPause", Boolean.toString(lanDiscoverPause));
            p.setProperty("lanDiscoverAnnounce", Boolean.toString(lanDiscoverAnnounce));
            p.setProperty("lanDiscoverPort", Integer.toString(lanDiscoverPort));
            p.setProperty("lanDiscoverPortFollowListen", Boolean.toString(lanDiscoverPortFollowListen));
            p.setProperty("maxClients", Integer.toString(maxClients));
            p.setProperty("qrCloseOnFocusLost", Boolean.toString(qrCloseOnFocusLost));
            p.setProperty("authRatePerMin", Integer.toString(authRatePerMin));
            p.setProperty("authCooldownSec", Integer.toString(authCooldownSec));
            p.setProperty("authBlacklist", String.join(",", authBlacklist));
            p.setProperty("clientHost", clientHost == null ? "" : clientHost);
            p.setProperty("clientPort", Integer.toString(clientPort));
            p.setProperty("clientPin", clientPin == null ? "" : clientPin);
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "TextSend Desktop");
            }
        } catch (IOException e) {
            System.err.println("保存配置失败: " + e.getMessage());
        }
    }

    /** 若新位置还没有文件，把以前写在 ~/.config 的配置搬过来，并清掉旧文件。 */
    private static void migrateLegacyIfNeeded() {
        if (Files.isRegularFile(FILE) || !Files.isRegularFile(LEGACY)) {
            return;
        }
        try {
            Path dir = FILE.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.copy(LEGACY, FILE);
            Files.deleteIfExists(LEGACY);
            Path oldDir = LEGACY.getParent();
            if (oldDir != null) {
                try (var stream = Files.list(oldDir)) {
                    if (stream.findAny().isEmpty()) {
                        Files.deleteIfExists(oldDir);
                    }
                }
            }
            System.err.println("已迁移旧配置");
        } catch (Exception e) {
            System.err.println("迁移旧配置失败: " + e.getMessage());
        }
    }

    public static Path getFile() {
        return FILE;
    }

    /** 全部偏好回到出厂值并写盘。探测若开着会停掉。不碰正在跑的服务端口。 */
    public static void resetToDefaults() {
        boolean wasDiscover = lanDiscover;
        uiScale = 1.0f;
        listenPort = 54300;
        preferMini = false;
        followSymlinks = false;
        listenIp = "";
        autoStartServer = false;
        customPinEnabled = false;
        customPin = "";
        rememberLastClient = false;
        lanDiscover = false;
        lanDiscoverPause = true;
        lanDiscoverAnnounce = true;
        lanDiscoverPort = 54300;
        lanDiscoverPortFollowListen = true;
        maxClients = 7;
        qrCloseOnFocusLost = false;
        authRatePerMin = 3;
        authCooldownSec = 10;
        authBlacklist.clear();
        clientHost = "";
        clientPort = 54300;
        clientPin = "";
        save();
        if (wasDiscover) {
            LanDiscover.halt();
        }
    }

    public static float getUiScale() {
        return uiScale;
    }

    public static void setUiScale(float scale) {
        uiScale = parseScale(Float.toString(scale));
        save();
    }

    public static int getListenPort() {
        return listenPort;
    }

    public static void setListenPort(int port) {
        int next = port >= 1 && port <= 65535 ? port : 54300;
        boolean changed = listenPort != next;
        listenPort = next;
        if (lanDiscoverPortFollowListen) {
            lanDiscoverPort = listenPort;
        }
        save();
        if (changed && lanDiscoverPortFollowListen && lanDiscover) {
            LanDiscover.boot();
        }
    }

    public static boolean isPreferMini() {
        return preferMini;
    }

    public static void setPreferMini(boolean mini) {
        preferMini = mini;
        save();
    }

    public static boolean isFollowSymlinks() {
        return followSymlinks;
    }

    public static void setFollowSymlinks(boolean v) {
        followSymlinks = v;
        save();
    }

    public static String getListenIp() {
        return listenIp == null ? "" : listenIp;
    }

    public static void setListenIp(String ip) {
        listenIp = ip == null ? "" : ip.trim();
        save();
    }

    public static boolean isAutoStartServer() {
        return autoStartServer;
    }

    public static void setAutoStartServer(boolean v) {
        autoStartServer = v;
        save();
    }

    public static boolean isCustomPinEnabled() {
        return customPinEnabled;
    }

    public static void setCustomPinEnabled(boolean v) {
        customPinEnabled = v;
        save();
    }

    public static String getCustomPin() {
        return customPin == null ? "" : customPin;
    }

    public static void setCustomPin(String pin) {
        String s = pin == null ? "" : pin.trim();
        if (!s.isEmpty() && !isValidPin(s)) {
            return;
        }
        customPin = s;
        save();
    }

    public static boolean isValidPin(String pin) {
        return pin != null && pin.matches("\\d{8}");
    }

    public static boolean isRememberLastClient() {
        return rememberLastClient;
    }

    public static void setRememberLastClient(boolean v) {
        rememberLastClient = v;
        save();
    }

    public static boolean isLanDiscover() {
        return lanDiscover;
    }

    public static void setLanDiscover(boolean v) {
        lanDiscover = v;
        save();
        if (v) {
            LanDiscover.boot();
        } else {
            LanDiscover.halt();
        }
    }

    public static boolean isLanDiscoverAnnounce() {
        return lanDiscoverAnnounce;
    }

    public static void setLanDiscoverAnnounce(boolean v) {
        lanDiscoverAnnounce = v;
        save();
    }

    public static boolean isLanDiscoverPause() {
        return lanDiscoverPause;
    }

    public static void setLanDiscoverPause(boolean v) {
        lanDiscoverPause = v;
        save();
    }

    public static boolean isLanDiscoverPortFollowListen() {
        return lanDiscoverPortFollowListen;
    }

    public static void setLanDiscoverPortFollowListen(boolean v) {
        if (lanDiscoverPortFollowListen == v) {
            return;
        }
        lanDiscoverPortFollowListen = v;
        if (v) {
            lanDiscoverPort = listenPort >= 1 && listenPort <= 65535 ? listenPort : 54300;
        }
        save();
        if (lanDiscover) {
            LanDiscover.boot();
        }
    }

    public static int getLanDiscoverPort() {
        if (lanDiscoverPortFollowListen) {
            return listenPort >= 1 && listenPort <= 65535 ? listenPort : 54300;
        }
        return lanDiscoverPort >= 1 && lanDiscoverPort <= 65535 ? lanDiscoverPort : 54300;
    }

    public static boolean setLanDiscoverPort(int port) {
        if (port < 1 || port > 65535) {
            return false;
        }
        if (lanDiscoverPortFollowListen) {
            // 跟随模式下改探测口无效；实际口始终等于连接口
            return true;
        }
        if (lanDiscoverPort == port) {
            return true;
        }
        lanDiscoverPort = port;
        save();
        if (lanDiscover) {
            LanDiscover.boot();
        }
        return true;
    }

    public static int getMaxClients() {
        return maxClients >= 1 && maxClients <= 64 ? maxClients : 7;
    }

    public static boolean setMaxClients(int n) {
        if (n < 1 || n > 64) {
            return false;
        }
        if (maxClients == n) {
            return true;
        }
        maxClients = n;
        save();
        return true;
    }

    public static boolean isQrCloseOnFocusLost() {
        return qrCloseOnFocusLost;
    }

    public static void setQrCloseOnFocusLost(boolean v) {
        qrCloseOnFocusLost = v;
        save();
    }

    public static int getAuthRatePerMin() {
        return authRatePerMin >= 1 && authRatePerMin <= 60 ? authRatePerMin : 3;
    }

    public static boolean setAuthRatePerMin(int n) {
        if (n < 1 || n > 60) {
            return false;
        }
        if (authRatePerMin == n) {
            return true;
        }
        authRatePerMin = n;
        save();
        return true;
    }

    public static int getAuthCooldownSec() {
        return Math.max(0, Math.min(86400, authCooldownSec));
    }

    public static boolean setAuthCooldownSec(int sec) {
        if (sec < 0 || sec > 86400) {
            return false;
        }
        if (authCooldownSec == sec) {
            return true;
        }
        authCooldownSec = sec;
        save();
        return true;
    }

    public static List<String> getAuthBlacklist() {
        return Collections.unmodifiableList(new ArrayList<>(authBlacklist));
    }

    public static String getAuthBlacklistText() {
        return String.join("\n", authBlacklist);
    }

    public static boolean isAuthBlacklisted(String host) {
        String h = normalizeHost(host);
        return !h.isEmpty() && authBlacklist.contains(h);
    }

    public static void addAuthBlacklist(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty() || !authBlacklist.add(h)) {
            return;
        }
        save();
    }

    public static void removeAuthBlacklist(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty() || !authBlacklist.remove(h)) {
            return;
        }
        save();
    }

    /** 用多行/逗号文本整体替换黑名单。 */
    public static void setAuthBlacklistText(String text) {
        LinkedHashSet<String> next = new LinkedHashSet<>(parseBlacklist(text == null ? "" : text));
        if (next.equals(authBlacklist)) {
            return;
        }
        authBlacklist.clear();
        authBlacklist.addAll(next);
        save();
    }

    private static int parseMaxClients(String raw) {
        return parseBoundedInt(raw, 7, 1, 64);
    }

    private static int parseBoundedInt(String raw, int fallback, int min, int max) {
        try {
            int n = Integer.parseInt(raw.trim());
            if (n >= min && n <= max) {
                return n;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static List<String> parseBlacklist(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(UserConfig::normalizeHost)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private static String normalizeHost(String host) {
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
        return h.toLowerCase(Locale.ROOT);
    }

    public static String getClientHost() {
        return clientHost == null ? "" : clientHost;
    }

    public static int getClientPort() {
        return clientPort;
    }

    public static String getClientPin() {
        return clientPin == null ? "" : clientPin;
    }

    public static void setClientConnection(String host, int port, String pin) {
        clientHost = host == null ? "" : host.trim();
        clientPort = port >= 1 && port <= 65535 ? port : 54300;
        String s = pin == null ? "" : pin.trim();
        clientPin = isValidPin(s) ? s : "";
        save();
    }

    public static void clearClientConnection() {
        clientHost = "";
        clientPort = 54300;
        clientPin = "";
        save();
    }

    private static int parsePort(int fallback, String raw) {
        try {
            int port = Integer.parseInt(raw.trim());
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /** 按缩放换算像素 */
    public static int s(int px) {
        return Math.max(1, Math.round(px * uiScale));
    }

    public static float sf(float px) {
        return px * uiScale;
    }

    private static float parseScale(String raw) {
        try {
            float v = Float.parseFloat(raw.trim());
            if (v < 0.5f) {
                return 0.5f;
            }
            if (v > 3.0f) {
                return 3.0f;
            }
            return Math.round(v * 1000f) / 1000f;
        } catch (Exception e) {
            return 1.0f;
        }
    }
}
