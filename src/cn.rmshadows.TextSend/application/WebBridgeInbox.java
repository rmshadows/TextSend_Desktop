package application;

import com.google.gson.JsonObject;
import protocol.FileNames;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * 网页桥分片落盘。同一 id 再次 begin 时返回已写入偏移，供断点续传。
 */
public final class WebBridgeInbox {
    public static final int CHUNK = 512 * 1024;
    public static final long MAX_SIZE = 8L * 1024 * 1024 * 1024;

    private final Map<String, Part> parts = new HashMap<>();

    public static final class Part {
        public final String id;
        public final String name;
        public final String rel;
        public final long size;
        public final boolean clip;
        public long written;
        public final Path file;

        Part(String id, String name, String rel, long size, boolean clip, long written, Path file) {
            this.id = id;
            this.name = name;
            this.rel = rel;
            this.size = size;
            this.clip = clip;
            this.written = written;
            this.file = file;
        }
    }

    public synchronized String begin(String id, String name, String rel, long size, boolean clip) throws IOException {
        if (id == null || !id.matches("[0-9a-fA-F]{16,64}")) {
            throw new IOException("id");
        }
        if (size < 0 || size > MAX_SIZE) {
            throw new IOException("size");
        }
        Part existing = parts.get(id);
        if (existing != null && existing.size == size && Files.isRegularFile(existing.file)) {
            return json(existing);
        }
        if (existing != null) {
            Files.deleteIfExists(existing.file);
            parts.remove(id);
        }
        Path dir = FileNames.inboxDir().resolve(".webpart");
        Files.createDirectories(dir);
        Path file = dir.resolve(id + ".part");
        if (!Files.exists(file)) {
            Files.write(file, new byte[0]);
        }
        long written = Files.size(file);
        if (written > size) {
            Files.delete(file);
            Files.write(file, new byte[0]);
            written = 0;
        }
        long aligned = (written / CHUNK) * CHUNK;
        if (aligned != written) {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(aligned);
            }
            written = aligned;
        }
        Part part = new Part(id, name == null ? "" : name, rel == null ? "" : rel, size, clip, written, file);
        parts.put(id, part);
        return json(part);
    }

    /** 必须按序；已经写过的块直接忽略，便于重试。 */
    public synchronized void chunk(String id, int index, byte[] data) throws IOException {
        Part part = parts.get(id);
        if (part == null) {
            throw new IOException("missing");
        }
        if (index < 0 || data == null) {
            throw new IOException("index");
        }
        long off = (long) index * CHUNK;
        if (data.length > CHUNK || off + data.length > part.size) {
            throw new IOException("size");
        }
        if (off + data.length <= part.written) {
            return;
        }
        if (off != part.written) {
            throw new IOException("order");
        }
        boolean last = off + data.length == part.size;
        if (!last && data.length != CHUNK) {
            throw new IOException("short");
        }
        try (FileChannel ch = FileChannel.open(part.file, StandardOpenOption.WRITE)) {
            ch.position(off);
            ch.write(ByteBuffer.wrap(data));
        }
        part.written = off + data.length;
    }

    public static final class Saved {
        public final Path path;
        public final boolean clip;
        public final long size;

        Saved(Path path, boolean clip, long size) {
            this.path = path;
            this.clip = clip;
            this.size = size;
        }
    }

    public synchronized Saved finish(String id) throws IOException {
        Part part = parts.get(id);
        if (part == null) {
            throw new IOException("missing");
        }
        if (part.written != part.size) {
            throw new IOException("incomplete");
        }
        Path dest = destFor(part.name, part.rel);
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.move(part.file, dest);
        parts.remove(id);
        return new Saved(dest, part.clip, part.size);
    }

    public synchronized void discardAll() {
        for (Part part : parts.values()) {
            try {
                Files.deleteIfExists(part.file);
            } catch (IOException ignored) {
                // 停桥时清不掉也无所谓
            }
        }
        parts.clear();
    }

    private static String json(Part part) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("id", part.id);
        o.addProperty("next", part.written);
        o.addProperty("chunk", CHUNK);
        return o.toString();
    }

    private static Path destFor(String name, String rel) throws IOException {
        Path root = FileNames.inboxDir();
        if (rel != null && !rel.isBlank()) {
            String safe = FileNames.sanitizeRelPath(rel);
            if (safe == null) {
                throw new IOException("path");
            }
            Path dest = FileNames.resolveUnder(root, safe);
            Path parent = dest.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(dest)) {
                dest = FileNames.unique(parent == null ? root : parent, dest.getFileName().toString());
            }
            return dest;
        }
        String safeName = FileNames.sanitize(name == null || name.isBlank() ? "file" : name);
        return FileNames.unique(root, safeName);
    }
}
