package protocol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 落盘：系统下载目录 / TextSend。不弹保存框。
 */
public final class FileNames {
    public static final String FOLDER = "TextSend";

    private static final Set<String> IMAGE_EXT = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff");

    private FileNames() {
    }

    public static Path inboxDir() throws java.io.IOException {
        Path dir = downloads().resolve(FOLDER);
        Files.createDirectories(dir);
        return dir;
    }

    public static Path downloads() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String up = System.getenv("USERPROFILE");
            Path base = Paths.get(up != null && !up.isBlank() ? up : home);
            Path dl = base.resolve("Downloads");
            return Files.isDirectory(dl) ? dl : base;
        }
        if (os.contains("mac")) {
            Path dl = Paths.get(home, "Downloads");
            return Files.isDirectory(dl) ? dl : Paths.get(home);
        }
        Path xdg = xdgDownload();
        if (xdg != null && Files.isDirectory(xdg)) {
            return xdg;
        }
        Path dl = Paths.get(home, "Downloads");
        if (Files.isDirectory(dl)) {
            return dl;
        }
        Path zh = Paths.get(home, "下载");
        return Files.isDirectory(zh) ? zh : Paths.get(home);
    }

    public static boolean isImageName(String name) {
        String ext = extOf(name);
        return ext != null && IMAGE_EXT.contains(ext);
    }

    public static String sanitize(String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        n = n.replaceAll("[\\x00-\\x1f<>:\"|?*]", "_").trim();
        if (n.equals(".") || n.equals("..") || n.isEmpty()) {
            n = "file";
        }
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.equals("con") || lower.equals("prn") || lower.equals("aux") || lower.equals("nul")
                || lower.matches("com[1-9]") || lower.matches("lpt[1-9]")) {
            n = "_" + n;
        }
        if (n.length() > 120) {
            String ext = "";
            int dot = n.lastIndexOf('.');
            if (dot > 0 && dot >= n.length() - 8) {
                ext = n.substring(dot);
                n = n.substring(0, dot);
            }
            n = n.substring(0, Math.max(1, 120 - ext.length())) + ext;
        }
        return n;
    }

    /**
     * 相对路径：去掉盘符、绝对路径和 {@code ..}。非法返回 null。
     */
    public static String sanitizeRelPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String n = raw.replace('\\', '/').trim();
        if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : n.split("/")) {
            if (p.isEmpty() || ".".equals(p)) {
                continue;
            }
            if ("..".equals(p)) {
                return null;
            }
            String s = sanitize(p);
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 把相对路径接到 root 下；越界则抛错。 */
    public static Path resolveUnder(Path root, String rel) throws java.io.IOException {
        Path r = root.toAbsolutePath().normalize();
        Path p = r;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            p = p.resolve(seg);
        }
        p = p.normalize();
        if (!p.startsWith(r)) {
            throw new java.io.IOException("path");
        }
        return p;
    }

    /** 重名则 name、name (1)、name (2) 目录，并创建。 */
    public static Path uniqueDir(Path parent, String sanitized) throws java.io.IOException {
        Path cand = parent.resolve(sanitized);
        if (!Files.exists(cand)) {
            Files.createDirectories(cand);
            return cand;
        }
        for (int i = 1; i < 10_000; i++) {
            cand = parent.resolve(sanitized + " (" + i + ")");
            if (!Files.exists(cand)) {
                Files.createDirectories(cand);
                return cand;
            }
        }
        throw new java.io.IOException("too many duplicates: " + sanitized);
    }

    /** 重名则 name (1).ext、name (2).ext，不覆盖。 */
    public static Path unique(Path dir, String sanitized) throws java.io.IOException {
        Path cand = dir.resolve(sanitized);
        if (!Files.exists(cand)) {
            return cand;
        }
        String stem = sanitized;
        String ext = "";
        int dot = sanitized.lastIndexOf('.');
        if (dot > 0) {
            stem = sanitized.substring(0, dot);
            ext = sanitized.substring(dot);
        }
        for (int i = 1; i < 10_000; i++) {
            cand = dir.resolve(stem + " (" + i + ")" + ext);
            if (!Files.exists(cand)) {
                return cand;
            }
        }
        throw new java.io.IOException("too many duplicates: " + sanitized);
    }

    private static String extOf(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Path xdgDownload() {
        try {
            Process p = new ProcessBuilder("xdg-user-dir", "DOWNLOAD")
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                if (line == null || line.isBlank()) {
                    return null;
                }
                return Paths.get(line.trim());
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
