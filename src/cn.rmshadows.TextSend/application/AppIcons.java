package application;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 窗口 / 任务栏图标：jar 内 resources/icon.png，开发时回退 other/icon.png。 */
final class AppIcons {
    private static Image cached;

    private AppIcons() {
    }

    static void applyTo(Window window) {
        Image img = load();
        if (img == null) {
            return;
        }
        if (window instanceof Frame frame) {
            // Linux 上 setIconImages(已解码图) 比 setIconImage 更可靠
            frame.setIconImages(List.of(img));
        }
    }

    /** 应用级任务栏 / Dock 图标（在首窗 show 前调一次） */
    static void applyToTaskbar() {
        Image img = load();
        if (img == null || !Taskbar.isTaskbarSupported()) {
            return;
        }
        Taskbar tb = Taskbar.getTaskbar();
        if (tb.isSupported(Taskbar.Feature.ICON_IMAGE)) {
            tb.setIconImage(img);
        }
    }

    private static Image load() {
        if (cached != null) {
            return cached;
        }
        Image cp = fromClasspath();
        cached = cp != null ? cp : fromDisk();
        return cached;
    }

    private static Image fromClasspath() {
        try (InputStream in = AppIcons.class.getResourceAsStream("/icon.png")) {
            if (in == null) {
                return null;
            }
            return decodeImage(in.readAllBytes());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Image fromDisk() {
        Path app = appDir();
        Path[] candidates = {
                Path.of("other", "icon.png"),
                app.resolve("other").resolve("icon.png"),
                app.resolve("icon.png"),
                app.resolve(".package").resolve("TextSend.png"),
                app.getParent() != null ? app.getParent().resolve("icon.png") : null
        };
        for (Path p : candidates) {
            if (p == null) {
                continue;
            }
            try {
                if (Files.isRegularFile(p)) {
                    return decodeImage(Files.readAllBytes(p));
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 用 ImageIO 解码，避免 Linux 上 ToolkitImage 宽高为 -1 导致任务栏无图标 */
    private static Image decodeImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                return img;
            }
        } catch (Exception ignored) {
        }
        return new ImageIcon(bytes).getImage();
    }

    private static Path appDir() {
        String home = System.getProperty("textsend.home");
        if (home != null && !home.isBlank()) {
            return Path.of(home.trim()).toAbsolutePath().normalize();
        }
        try {
            var src = AppIcons.class.getProtectionDomain().getCodeSource();
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
}
