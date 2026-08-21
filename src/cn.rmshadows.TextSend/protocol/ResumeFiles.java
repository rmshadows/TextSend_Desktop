package protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.stream.Stream;

/** 未完成文件的 .part + sidecar，供断线后续传。 */
public final class ResumeFiles {
    public static final String PART_SUFFIX = ".textsend.part";
    public static final String META_SUFFIX = ".textsend.json";

    private ResumeFiles() {
    }

    public static Path partPath(Path dir, String name) {
        return dir.resolve(name + PART_SUFFIX);
    }

    public static Path metaPath(Path dir, String name) {
        return dir.resolve(name + META_SUFFIX);
    }

    public static long align(long written) {
        if (written <= 0) {
            return 0;
        }
        return (written / Protocol.FILE_CHUNK) * Protocol.FILE_CHUNK;
    }

    public static boolean hasIncomplete(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> w = Files.walk(dir)) {
            return w.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(PART_SUFFIX) || n.endsWith(META_SUFFIX);
            });
        } catch (IOException e) {
            return false;
        }
    }

    public static int headLen(long size) {
        if (size <= 0) {
            return 0;
        }
        return (int) Math.min(Protocol.FILE_CHUNK, size);
    }

    public static boolean isSha256Hex(String s) {
        if (s == null || s.length() != 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    public static String sha256Head(Path path, long size) throws Exception {
        return sha256Range(path, 0, headLen(size));
    }

    /** 文件尾 SHA-256：最后一 chunk（不足则整文件）。头相同、中间不同的文件用来拒绝误续。 */
    public static String sha256Tail(Path path, long size) throws Exception {
        int n = headLen(size);
        return sha256Range(path, Math.max(0, size - n), n);
    }

    public static String sha256Prefix(Path path, long length) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        if (length <= 0) {
            return FileIds.shaHex(d.digest());
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[64 * 1024];
            long left = length;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) {
                    throw new IOException("前缀不足");
                }
                d.update(buf, 0, r);
                left -= r;
            }
        }
        return FileIds.shaHex(d.digest());
    }

    private static String sha256Range(Path path, long offset, int length) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        if (length <= 0) {
            return FileIds.shaHex(d.digest());
        }
        try (var ch = Files.newByteChannel(path, StandardOpenOption.READ)) {
            ch.position(offset);
            byte[] buf = new byte[8192];
            int left = length;
            while (left > 0) {
                int r = ch.read(ByteBuffer.wrap(buf, 0, Math.min(buf.length, left)));
                if (r < 0) {
                    throw new IOException("读取不足");
                }
                d.update(buf, 0, r);
                left -= r;
            }
        }
        return FileIds.shaHex(d.digest());
    }

    public static void save(Path dir, String name, long size, long written, int nextIndex,
                            String headSha, String tailSha) {
        try {
            Files.createDirectories(dir);
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("size", size);
            o.addProperty("written", written);
            o.addProperty("nextIndex", nextIndex);
            if (isSha256Hex(headSha)) {
                o.addProperty("headSha256", headSha.toLowerCase());
            }
            if (isSha256Hex(tailSha)) {
                o.addProperty("tailSha256", tailSha.toLowerCase());
            }
            Files.writeString(metaPath(dir, name), o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("Log: resume save failed: " + e.getMessage());
        }
    }

    public static JsonObject load(Path dir, String name, long size) {
        Path meta = metaPath(dir, name);
        Path part = partPath(dir, name);
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(part)) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            long metaSize = o.has("size") ? o.get("size").getAsLong() : -1;
            if (metaSize != size) {
                return null;
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 续传身份：头 + 尾都要对上。sidecar 没有头哈希时，用磁盘已收前缀补验头；
     * 没有尾哈希则不续（半截文件里没有完整文件的尾巴）。
     */
    public static boolean identityMatches(JsonObject saved, Path part, long size,
                                          String headSha, String tailSha) {
        if (!isSha256Hex(headSha) || !isSha256Hex(tailSha)) {
            return false;
        }
        String storedHead = saved != null && saved.has("headSha256")
                ? saved.get("headSha256").getAsString() : null;
        if (isSha256Hex(storedHead)) {
            if (!storedHead.equalsIgnoreCase(headSha)) {
                return false;
            }
        } else {
            int n = headLen(size);
            if (n > 0) {
                try {
                    if (!Files.isRegularFile(part) || Files.size(part) < n) {
                        return false;
                    }
                    if (!headSha.equalsIgnoreCase(sha256Head(part, n))) {
                        return false;
                    }
                } catch (Exception e) {
                    return false;
                }
            }
        }
        String storedTail = saved != null && saved.has("tailSha256")
                ? saved.get("tailSha256").getAsString() : null;
        return isSha256Hex(storedTail) && storedTail.equalsIgnoreCase(tailSha);
    }

    public static void delete(Path dir, String name) {
        try {
            Files.deleteIfExists(partPath(dir, name));
            Files.deleteIfExists(metaPath(dir, name));
        } catch (IOException ignored) {
        }
    }

    public static String sha256File(Path path) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                d.update(buf, 0, n);
            }
        }
        return FileIds.shaHex(d.digest());
    }
}
