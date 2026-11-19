package application;

import com.google.gson.JsonObject;
import protocol.FileNames;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 网页桥分片落盘。同一 id 再次 begin 时返回已写入偏移，供断点续传。
 */
public final class WebBridgeInbox {
    public static final int CHUNK = 1024 * 1024;
    /** 每写满这么多再 force 一次。每块都刷盘会把速度压得很低。 */
    private static final long FORCE_EVERY = 4L * 1024 * 1024;
    public static final long MAX_SIZE = 20L * 1024 * 1024 * 1024;
    private static final long KEEP_MS = 72L * 60L * 60L * 1000L;
    private static final WebBridgeInbox SHARED = new WebBridgeInbox();

    private final Map<String, Part> parts = new HashMap<>();
    private static volatile Watch watch;

    /** 进度回调只许排队到界面线程，不要在这里做耗时的事。 */
    public interface Watch {
        void progress(String name, long done, long total);

        void finished();

        void aborted(String name);

        void paused(String name);
    }

    public static void setWatch(Watch next) {
        watch = next;
    }

    public static WebBridgeInbox shared() {
        return SHARED;
    }

    public static final class Part {
        public final String id;
        public final String name;
        public final String rel;
        public final long size;
        public final boolean clip;
        public long written;
        public final Path file;
        FileChannel channel;
        long forced;
        boolean aborted;
        /** 手机主动停了。半截还在，再 begin 会接着传，这期间不再刷进度。 */
        boolean paused;

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
        sweep();
        Part existing = parts.get(id);
        if (existing != null && existing.size == size && Files.isRegularFile(existing.file)) {
            existing.aborted = false;
            existing.paused = false;
            notifyProgress(existing);
            return json(existing);
        }
        if (existing != null) {
            closeChannel(existing);
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
        notifyProgress(part);
        return json(part);
    }

    /** 必须按序；已经写过的块直接忽略，便于重试。 */
    public synchronized void chunk(String id, int index, byte[] data) throws IOException {
        Part part = parts.get(id);
        if (part == null) {
            throw new IOException("missing");
        }
        if (part.aborted) {
            throw new IOException("cancelled");
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
        if (part.channel == null || !part.channel.isOpen()) {
            part.channel = FileChannel.open(part.file, StandardOpenOption.WRITE);
        }
        FileChannel ch = part.channel;
        ch.position(off);
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
        part.written = off + data.length;
        if (last || part.written - part.forced >= FORCE_EVERY) {
            ch.force(false);
            part.forced = part.written;
        }
        notifyProgress(part);
    }

    /** 停掉正在收的网页文件。半截留在磁盘，手机再发会从断点继续。 */
    public synchronized int abortActive() {
        String name = "";
        int n = 0;
        for (Part part : parts.values()) {
            if (part.aborted) {
                continue;
            }
            part.aborted = true;
            closeChannel(part);
            name = label(part);
            n++;
        }
        if (n > 0) {
            Watch current = watch;
            if (current != null) {
                current.aborted(name);
            }
        }
        return n;
    }

    /** 手机点了停止。不删半截，只让电脑收起进度。 */
    public synchronized void pause(String id) {
        String name = "";
        boolean hit = false;
        for (Part part : parts.values()) {
            if (id != null && !id.isEmpty() && !id.equals(part.id)) {
                continue;
            }
            if (part.aborted) {
                continue;
            }
            part.paused = true;
            name = label(part);
            hit = true;
        }
        if (!hit) {
            return;
        }
        Watch current = watch;
        if (current != null) {
            current.paused(name);
        }
    }

    private static void closeChannel(Part part) {
        if (part == null || part.channel == null) {
            return;
        }
        try {
            if (part.channel.isOpen()) {
                part.channel.force(false);
                part.channel.close();
            }
        } catch (IOException ignored) {
            // 关掉失败不影响半截文件还在
        }
        part.channel = null;
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
        if (part.aborted) {
            throw new IOException("cancelled");
        }
        if (part.written != part.size) {
            throw new IOException("incomplete");
        }
        closeChannel(part);
        Path dest = destFor(part.name, part.rel);
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.move(part.file, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part.file, dest);
        }
        parts.remove(id);
        Watch current = watch;
        if (current != null) {
            current.finished();
        }
        return new Saved(dest, part.clip, part.size);
    }

    /** 只清内存登记。半截留在磁盘，同一 id 再 begin 会从文件长度接着写。 */
    public synchronized void discardAll() {
        boolean live = false;
        for (Part part : parts.values()) {
            if (!part.aborted) {
                live = true;
            }
            closeChannel(part);
        }
        parts.clear();
        if (live) {
            Watch current = watch;
            if (current != null) {
                current.finished();
            }
        }
    }

    private static String label(Part part) {
        if (part.rel != null && !part.rel.isBlank()) {
            return part.rel;
        }
        if (part.name != null && !part.name.isBlank()) {
            return part.name;
        }
        return "文件";
    }

    private static void notifyProgress(Part part) {
        Watch current = watch;
        if (current == null || part.aborted || part.paused) {
            return;
        }
        current.progress(label(part), part.written, part.size);
    }

    private void sweep() {
        try {
            Path dir = FileNames.inboxDir().resolve(".webpart");
            if (!Files.isDirectory(dir)) {
                return;
            }
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            try (Stream<Path> list = Files.list(dir)) {
                for (Path p : list.toList()) {
                    String fn = p.getFileName().toString();
                    if (!fn.endsWith(".part")) {
                        continue;
                    }
                    String id = fn.substring(0, fn.length() - 5);
                    if (parts.containsKey(id)) {
                        continue;
                    }
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException ignored) {
            // 清不掉旧半截不影响这次传输
        }
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
