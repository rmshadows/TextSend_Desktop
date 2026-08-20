package application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
        listenPort = port;
        save();
    }

    public static boolean isPreferMini() {
        return preferMini;
    }

    public static void setPreferMini(boolean mini) {
        preferMini = mini;
        save();
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
