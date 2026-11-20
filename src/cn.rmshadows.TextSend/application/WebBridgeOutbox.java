package application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import protocol.FileNames;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 电脑发到网页的文件。正文不进 SSE，手机按 id 分片来拉。
 * 同一文件系统上用硬链接，删掉原文件也不影响下载；跨盘才复制。
 */
public final class WebBridgeOutbox {
    public static final int CHUNK = 1024 * 1024;
    public static final long MAX_SIZE = 20L * 1024 * 1024 * 1024;
    public static final int MAX_ITEMS = 100;

    private static final WebBridgeOutbox SHARED = new WebBridgeOutbox();
    private static final SecureRandom RNG = new SecureRandom();

    private final Map<String, Item> items = new LinkedHashMap<>();

    /** 进度回调只许排队到界面线程，不要在这里做耗时的事。 */
    public interface Progress {
        void update(String name, long done, long total);
    }

    public static final class Item {
        public final String id;
        public final String name;
        public final String rel;
        public final long size;
        public final String kind;
        public final long ts;
        final Path file;

        Item(String id, String name, String rel, long size, String kind, long ts, Path file) {
            this.id = id;
            this.name = name;
            this.rel = rel;
            this.size = size;
            this.kind = kind;
            this.ts = ts;
            this.file = file;
        }

        public JsonObject event(String type) {
            JsonObject o = new JsonObject();
            o.addProperty("v", 1);
            o.addProperty("type", type);
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("rel", rel);
            o.addProperty("size", size);
            o.addProperty("kind", kind);
            o.addProperty("ts", ts);
            return o;
        }
    }

    public static WebBridgeOutbox shared() {
        return SHARED;
    }

    public Item offer(Path source, String tree, String rel,
                      BooleanSupplier cancelled, Progress progress) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("不是文件");
        }
        long size = Files.size(source);
        if (size < 0 || size > MAX_SIZE) {
            throw new IOException("size");
        }
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new IOException("cancelled");
        }
        String safeRel = "";
        if (rel != null && !rel.isBlank()) {
            safeRel = FileNames.sanitizeRelPath(rel);
            if (safeRel == null) {
                throw new IOException("path");
            }
        }
        if (tree != null && !tree.isBlank()) {
            String folder = FileNames.sanitize(tree);
            safeRel = safeRel.isEmpty() ? folder : folder + "/" + safeRel;
        }
        String name;
        if (!safeRel.isEmpty()) {
            int slash = safeRel.lastIndexOf('/');
            name = slash >= 0 ? safeRel.substring(slash + 1) : safeRel;
        } else {
            Path fn = source.getFileName();
            name = FileNames.sanitize(fn == null ? "file" : fn.toString());
        }
        String kind = FileNames.isImageName(name) ? "image" : "file";
        String id = newId();
        Path dir = FileNames.inboxDir().resolve(".webout");
        Files.createDirectories(dir);
        Path dest = dir.resolve(id);
        Path dirNorm = dir.toAbsolutePath().normalize();
        if (!dest.toAbsolutePath().normalize().startsWith(dirNorm)) {
            throw new IOException("path");
        }
        if (progress != null) {
            progress.update(name, 0, size);
        }
        boolean linked = false;
        try {
            Files.createLink(dest, source.toAbsolutePath());
            linked = true;
        } catch (IOException | UnsupportedOperationException ignored) {
            try {
                Files.deleteIfExists(dest);
            } catch (IOException ignored2) {
                // 链接没建成，接着复制
            }
        }
        if (linked) {
            if (progress != null) {
                progress.update(name, size, size);
            }
        } else {
            copyTo(source, dest, name, size, cancelled, progress);
        }
        Item item = new Item(id, name, safeRel, size, kind, System.currentTimeMillis(), dest);
        synchronized (this) {
            if (items.size() >= MAX_ITEMS) {
                Files.deleteIfExists(dest);
                throw new IOException("full");
            }
            items.put(id, item);
        }
        return item;
    }

    public synchronized String listJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        JsonArray arr = new JsonArray();
        for (Item it : items.values()) {
            arr.add(it.event("file"));
        }
        o.add("items", arr);
        return o.toString();
    }

    public byte[] readChunk(String id, int index) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{32}")) {
            throw new IOException("missing");
        }
        Item item;
        synchronized (this) {
            item = items.get(id);
        }
        if (item == null) {
            throw new IOException("missing");
        }
        if (index < 0) {
            throw new IOException("range");
        }
        long off = (long) index * CHUNK;
        if (off >= item.size) {
            throw new IOException("range");
        }
        int n = (int) Math.min(CHUNK, item.size - off);
        byte[] buf = new byte[n];
        try (FileChannel ch = FileChannel.open(item.file, StandardOpenOption.READ)) {
            ByteBuffer wrap = ByteBuffer.wrap(buf);
            int got = 0;
            while (got < n) {
                int r = ch.read(wrap, off + got);
                if (r <= 0) {
                    throw new IOException("changed");
                }
                got += r;
            }
        } catch (NoSuchFileException e) {
            throw new IOException("missing");
        }
        return buf;
    }

    public synchronized Item drop(String id) {
        Item it = items.remove(id);
        if (it == null) {
            return null;
        }
        try {
            Files.deleteIfExists(it.file);
        } catch (IOException ignored) {
            // 列表里已经拿掉
        }
        return it;
    }

    public synchronized void clear() {
        wipe();
    }

    /** 会话作废或桥关掉时清掉，手机不能再拉。 */
    public synchronized void discardAll() {
        wipe();
    }

    private void wipe() {
        for (Item it : items.values()) {
            try {
                Files.deleteIfExists(it.file);
            } catch (IOException ignored) {
                // 下一轮会话不再引用
            }
        }
        items.clear();
    }

    private static void copyTo(Path source, Path dest, String name, long size,
                               BooleanSupplier cancelled, Progress progress) throws IOException {
        byte[] buf = new byte[CHUNK];
        long done = 0;
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(dest)) {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new IOException("cancelled");
                }
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                done += n;
                if (progress != null) {
                    progress.update(name, Math.min(done, size), size);
                }
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(dest);
            } catch (IOException ignored) {
                // 半截不要留
            }
            throw e;
        }
    }

    private static String newId() {
        byte[] raw = new byte[16];
        RNG.nextBytes(raw);
        StringBuilder id = new StringBuilder(32);
        for (byte b : raw) {
            id.append(Character.forDigit((b >> 4) & 0xF, 16));
            id.append(Character.forDigit(b & 0xF, 16));
        }
        return id.toString();
    }
}
