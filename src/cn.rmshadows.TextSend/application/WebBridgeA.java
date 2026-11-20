package application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import protocol.B64;
import protocol.FileNames;
import protocol.PasteUtil;
import protocol.Protocol;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 网页桥 Way A：本机 HTTP + 访问令牌，正文明文。
 * 与 Way B（{@link WebBridge}）共用端口、互斥：同时只开一档。
 * URL：{@code http://host:54301/?s=…&t=…&m=A}（无 #k）。
 */
public final class WebBridgeA {
    public static final int DEFAULT_PORT = WebBridge.DEFAULT_PORT;
    static final long IDLE_MS = 60L * 60L * 1000L;
    private static final int MAX_PLAIN = 64 * 1024;
    private static final int MAX_JSON = MAX_PLAIN + 4096;
    /** 整包进内存；不做 32MB 产品限制。更大文件要等分片续传。 */
    private static final int MAX_FILE = 1024 * 1024 * 1024;
    private static final int MAX_FILE_BODY = MAX_FILE + 64 * 1024;
    private static final int MAX_CLIENTS = 8;
    private static final long PING_SEC = 15;
    private static final String WARN =
            "消息以明文在局域网传输，同一网络中的其他人可能看到内容。请仅在可信网络使用；不要发送密码、验证码等敏感信息。";

    private static final Object LOCK = new Object();
    private static final SecureRandom RNG = new SecureRandom();
    private static final ScheduledExecutorService TICK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "web-bridge-a-idle");
        t.setDaemon(true);
        return t;
    });

    private static HttpServer server;
    private static boolean shutdownHooked;
    private static ExecutorService workers;
    private static int boundPort;
    private static String sid;
    private static String token;
    private static long touched;
    private static boolean tickStarted;
    private static final List<SseClient> clients = new ArrayList<>();
    private static final List<byte[]> outbound = new ArrayList<>();
    private static final int MAX_OUTBOUND = 20;

    private WebBridgeA() {
    }

    public static boolean isRunning() {
        synchronized (LOCK) {
            return server != null;
        }
    }

    public static boolean hasSession() {
        synchronized (LOCK) {
            return token != null;
        }
    }

    public static int port() {
        synchronized (LOCK) {
            return boundPort;
        }
    }

    public static int listenerCount() {
        synchronized (LOCK) {
            return clients.size();
        }
    }

    /** 换网卡只改 host。会话过期或桥没开时返回 null。 */
    public static String pageUrl(String host) {
        synchronized (LOCK) {
            if (server == null || token == null || sid == null) {
                return null;
            }
            String h = host == null ? "" : host.trim();
            if (h.isEmpty()) {
                h = "127.0.0.1";
            }
            if (h.indexOf(':') >= 0 && !h.startsWith("[")) {
                h = "[" + h + "]";
            }
            return "http://" + h + ":" + boundPort + "/?s=" + sid + "&t=" + token + "&m=A";
        }
    }

    public static void start(int port) throws IOException {
        synchronized (LOCK) {
            if (server != null && boundPort == port) {
                return;
            }
        }
        if (isRunning()) {
            rebind(port);
            return;
        }
        bind(port);
        mint();
    }

    public static void rebind(int port) throws IOException {
        if (!isRunning()) {
            start(port);
            return;
        }
        synchronized (LOCK) {
            if (server != null && boundPort == port) {
                return;
            }
        }
        shutdown(false);
        bind(port);
    }

    public static void stop() {
        shutdown(true);
    }

    public static void refreshSession() {
        if (!isRunning()) {
            return;
        }
        dropClients("refreshed");
        WebBridgeOutbox.shared().discardAll();
        mint();
    }

    /**
     * @return 1 已写入下行队列；0 会话无效；-1 文字超过 64KB
     */
    public static int pushText(String text) {
        if (text == null) {
            return 0;
        }
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_PLAIN) {
            return -1;
        }
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("type", "text");
        o.addProperty("id", UUID.randomUUID().toString());
        o.addProperty("ts", System.currentTimeMillis());
        o.addProperty("body", text);
        byte[] ev;
        List<SseClient> copy;
        synchronized (LOCK) {
            if (token == null) {
                return 0;
            }
            touched = System.currentTimeMillis();
            ev = sse("msg", o.toString());
            outbound.add(ev);
            while (outbound.size() > MAX_OUTBOUND) {
                outbound.remove(0);
            }
            copy = new ArrayList<>(clients);
        }
        for (SseClient c : copy) {
            if (!c.write(ev)) {
                drop(c);
            }
        }
        return 1;
    }

    /**
     * @return 1 已通知；0 会话无效；-1 超过 20GB；-2 列表已满
     */
    public static int pushFile(Path path, String tree, String rel,
                               BooleanSupplier cancelled, WebBridgeOutbox.Progress progress) throws IOException {
        if (!hasSession()) {
            return 0;
        }
        WebBridgeOutbox.Item item;
        try {
            item = WebBridgeOutbox.shared().offer(path, tree, rel, cancelled, progress);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if ("size".equals(m)) {
                return -1;
            }
            if ("full".equals(m)) {
                return -2;
            }
            throw e;
        }
        try {
            if (!publish(item.event("file"))) {
                WebBridgeOutbox.shared().drop(item.id);
                return 0;
            }
        } catch (RuntimeException e) {
            WebBridgeOutbox.shared().drop(item.id);
            throw e;
        }
        return 1;
    }

    /** 文件事件不进文字那 20 条补发。后连上的手机走 /api/out/list。 */
    private static boolean publish(JsonObject o) {
        byte[] ev;
        List<SseClient> copy;
        synchronized (LOCK) {
            if (token == null) {
                return false;
            }
            touched = System.currentTimeMillis();
            ev = sse("msg", o.toString());
            copy = new ArrayList<>(clients);
        }
        for (SseClient c : copy) {
            if (!c.write(ev)) {
                drop(c);
            }
        }
        return true;
    }

    private static void ensureHook() {
        if (shutdownHooked) {
            return;
        }
        shutdownHooked = true;
        Runtime.getRuntime().addShutdownHook(new Thread(WebBridgeA::stop, "web-bridge-a-stop"));
    }

    private static void bind(int port) throws IOException {
        if (port < 1 || port > 65535) {
            throw new IOException("端口无效");
        }
        HttpServer hs;
        try {
            hs = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IOException("端口 " + port + " 无法监听（可能被占用）", e);
        }
        hs.createContext("/", WebBridgeA::handle);
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "web-bridge-a");
            t.setDaemon(true);
            return t;
        });
        hs.setExecutor(pool);
        hs.start();
        ensureHook();
        synchronized (LOCK) {
            server = hs;
            workers = pool;
            boundPort = port;
            if (!tickStarted) {
                tickStarted = true;
                TICK.scheduleWithFixedDelay(WebBridgeA::tick, PING_SEC, PING_SEC, TimeUnit.SECONDS);
            }
        }
    }

    private static void mint() {
        byte[] rawSid = new byte[16];
        byte[] rawTok = new byte[16];
        RNG.nextBytes(rawSid);
        RNG.nextBytes(rawTok);
        synchronized (LOCK) {
            sid = B64.enc(rawSid);
            token = B64.enc(rawTok);
            touched = System.currentTimeMillis();
            outbound.clear();
        }
    }

    private static void tick() {
        List<SseClient> copy;
        boolean expire = false;
        synchronized (LOCK) {
            if (server == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (token != null && clients.isEmpty() && now - touched >= IDLE_MS) {
                expire = true;
            }
            copy = new ArrayList<>(clients);
        }
        if (expire) {
            expireSession();
            return;
        }
        byte[] ping = ": ping\n\n".getBytes(StandardCharsets.UTF_8);
        for (SseClient c : copy) {
            if (!c.write(ping)) {
                drop(c);
            }
        }
    }

    private static void expireSession() {
        dropClients("expired");
        WebBridgeOutbox.shared().discardAll();
        synchronized (LOCK) {
            token = null;
            sid = null;
            outbound.clear();
        }
    }

    private static void dropClients(String reason) {
        List<SseClient> copy;
        synchronized (LOCK) {
            copy = new ArrayList<>(clients);
            clients.clear();
        }
        byte[] bye = sse("bye", reason);
        for (SseClient c : copy) {
            c.write(bye);
            c.close();
        }
    }

    private static void shutdown(boolean clearSession) {
        HttpServer hs;
        ExecutorService pool;
        List<SseClient> copy;
        boolean dropBox = false;
        synchronized (LOCK) {
            hs = server;
            server = null;
            pool = workers;
            workers = null;
            copy = new ArrayList<>(clients);
            clients.clear();
            if (clearSession) {
                dropBox = true;
                token = null;
                sid = null;
                outbound.clear();
            }
        }
        if (dropBox) {
            WebBridgeOutbox.shared().discardAll();
        }
        for (SseClient c : copy) {
            c.close();
        }
        if (hs != null) {
            hs.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private static void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if ("/".equals(path) || "/index.html".equals(path)) {
                staticFile(ex, "index.html", "text/html; charset=utf-8");
            } else if ("/bridge.js".equals(path)) {
                staticFile(ex, "bridge.js", "text/javascript; charset=utf-8");
            } else if ("/api/meta".equals(path)) {
                meta(ex);
            } else if ("/api/send".equals(path)) {
                send(ex);
            } else if ("/api/file".equals(path)) {
                upload(ex);
            } else if ("/api/file/begin".equals(path)) {
                fileBegin(ex);
            } else if ("/api/file/chunk".equals(path)) {
                fileChunk(ex);
            } else if ("/api/file/finish".equals(path)) {
                fileFinish(ex);
            } else if ("/api/file/cancel".equals(path)) {
                fileCancel(ex);
            } else if ("/api/out/list".equals(path)) {
                outList(ex);
            } else if ("/api/out/chunk".equals(path)) {
                outChunk(ex);
            } else if ("/api/out/drop".equals(path)) {
                outDrop(ex);
            } else if ("/api/out/clear".equals(path)) {
                outClear(ex);
            } else if ("/api/events".equals(path)) {
                events(ex);
            } else {
                text(ex, 404, "not found");
            }
        } catch (Exception e) {
            System.err.println("Log: webbridge-a " + e.getMessage());
            try {
                text(ex, 500, "error");
            } catch (IOException ignored) {
                // 头已经发出去时不能再回一包
            }
        }
    }

    private static void meta(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("mode", "A");
        o.addProperty("encrypt", false);
        o.addProperty("pageVersion", "1");
        o.addProperty("app", TextSendMain.VERSION);
        o.addProperty("warning", WARN);
        byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void send(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        String lenHdr = ex.getRequestHeaders().getFirst("Content-Length");
        if (lenHdr != null) {
            try {
                if (Long.parseLong(lenHdr.trim()) > MAX_JSON) {
                    text(ex, 413, "too big");
                    return;
                }
            } catch (NumberFormatException e) {
                text(ex, 400, "length");
                return;
            }
        }
        byte[] buf;
        try (InputStream in = ex.getRequestBody()) {
            buf = in.readNBytes(MAX_JSON + 1);
        }
        if (buf.length == 0 || buf.length > MAX_JSON) {
            text(ex, buf.length > MAX_JSON ? 413 : 400, "body");
            return;
        }
        String body;
        try {
            JsonObject obj = JsonParser.parseString(new String(buf, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!obj.has("v") || obj.get("v").getAsInt() != 1
                    || !obj.has("type") || !"text".equals(obj.get("type").getAsString())
                    || !obj.has("body") || !obj.get("body").isJsonPrimitive()) {
                text(ex, 400, "type");
                return;
            }
            body = obj.get("body").getAsString();
        } catch (RuntimeException e) {
            text(ex, 400, "json");
            return;
        }
        if (body.isEmpty() || body.getBytes(StandardCharsets.UTF_8).length > MAX_PLAIN) {
            text(ex, body.isEmpty() ? 400 : 413, "text");
            return;
        }
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        String paste = body;
        SwingUtilities.invokeLater(() -> PasteUtil.pasteText(paste));
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    /** 明文文件包：2 字节头长 + JSON 头 + 原始字节。 */
    private static void upload(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        String lenHdr = ex.getRequestHeaders().getFirst("Content-Length");
        if (lenHdr != null) {
            try {
                if (Long.parseLong(lenHdr.trim()) > MAX_FILE_BODY) {
                    text(ex, 413, "too big");
                    return;
                }
            } catch (NumberFormatException e) {
                text(ex, 400, "length");
                return;
            }
        }
        byte[] plain;
        try (InputStream in = ex.getRequestBody()) {
            plain = in.readNBytes(MAX_FILE_BODY + 1);
        }
        if (plain.length < 2 || plain.length > MAX_FILE_BODY) {
            text(ex, plain.length > MAX_FILE_BODY ? 413 : 400, "body");
            return;
        }
        int headLen = ((plain[0] & 0xff) << 8) | (plain[1] & 0xff);
        if (headLen < 2 || headLen > 8192 || 2 + headLen > plain.length) {
            text(ex, 400, "head");
            return;
        }
        String name;
        String rel;
        boolean clip;
        try {
            JsonObject obj = JsonParser.parseString(new String(plain, 2, headLen, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!obj.has("v") || obj.get("v").getAsInt() != 1
                    || !obj.has("type") || !"file".equals(obj.get("type").getAsString())) {
                text(ex, 400, "type");
                return;
            }
            name = obj.has("name") && obj.get("name").isJsonPrimitive() ? obj.get("name").getAsString() : "";
            rel = obj.has("rel") && obj.get("rel").isJsonPrimitive() ? obj.get("rel").getAsString() : "";
            clip = obj.has("clip") && obj.get("clip").isJsonPrimitive() && obj.get("clip").getAsBoolean();
        } catch (RuntimeException e) {
            text(ex, 400, "json");
            return;
        }
        byte[] data = Arrays.copyOfRange(plain, 2 + headLen, plain.length);
        if (data.length > MAX_FILE) {
            text(ex, 413, "too big");
            return;
        }
        Path saved;
        try {
            saved = placeUpload(name, rel, data);
        } catch (IOException e) {
            text(ex, 400, "path");
            return;
        }
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        String shown = saved.getFileName().toString();
        boolean wantClip = clip && data.length > 0 && data.length <= Protocol.CLIPBOARD_IMAGE_MAX
                && FileNames.isImageName(shown);
        BufferedImage image = null;
        if (wantClip) {
            try {
                image = ImageIO.read(new ByteArrayInputStream(data));
            } catch (IOException ignored) {
                image = null;
            }
        }
        BufferedImage clipImage = image;
        SwingUtilities.invokeLater(() -> {
            boolean onClip = clipImage != null && PasteUtil.setClipboardImage(clipImage);
            TextSendMain.noteWeb(onClip ? "收到图片 " + shown + "，已放入剪贴板" : "收到文件 " + shown);
        });
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private static Path placeUpload(String name, String rel, byte[] data) throws IOException {
        Path root = FileNames.inboxDir();
        Path dest;
        if (rel != null && !rel.isBlank()) {
            String safe = FileNames.sanitizeRelPath(rel);
            if (safe == null) {
                throw new IOException("path");
            }
            dest = FileNames.resolveUnder(root, safe);
            Path parent = dest.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(dest)) {
                dest = FileNames.unique(parent == null ? root : parent, dest.getFileName().toString());
            }
        } else {
            String safeName = FileNames.sanitize(name == null || name.isBlank() ? "file" : name);
            dest = FileNames.unique(root, safeName);
        }
        Files.write(dest, data);
        return dest;
    }

    private static void fileBegin(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        byte[] buf;
        try (InputStream in = ex.getRequestBody()) {
            buf = in.readNBytes(16 * 1024 + 1);
        }
        if (buf.length == 0 || buf.length > 16 * 1024) {
            text(ex, buf.length > 16 * 1024 ? 413 : 400, "body");
            return;
        }
        String id;
        String name;
        String rel;
        long size;
        boolean clip;
        try {
            JsonObject obj = JsonParser.parseString(new String(buf, StandardCharsets.UTF_8)).getAsJsonObject();
            id = obj.has("id") ? obj.get("id").getAsString() : "";
            name = obj.has("name") ? obj.get("name").getAsString() : "";
            rel = obj.has("rel") ? obj.get("rel").getAsString() : "";
            size = obj.has("size") ? obj.get("size").getAsLong() : -1;
            clip = obj.has("clip") && obj.get("clip").getAsBoolean();
        } catch (RuntimeException e) {
            text(ex, 400, "json");
            return;
        }
        String body;
        try {
            body = WebBridgeInbox.shared().begin(id, name, rel, size, clip);
        } catch (IOException e) {
            text(ex, "size".equals(e.getMessage()) ? 413 : 400, e.getMessage() == null ? "begin" : e.getMessage());
            return;
        }
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, raw.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(raw);
        }
    }

    private static void fileChunk(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        String id = query(ex, "id");
        int index;
        try {
            index = Integer.parseInt(query(ex, "i"));
        } catch (RuntimeException e) {
            text(ex, 400, "index");
            return;
        }
        byte[] data;
        try (InputStream in = ex.getRequestBody()) {
            data = in.readNBytes(WebBridgeInbox.CHUNK + 1);
        }
        if (data.length > WebBridgeInbox.CHUNK) {
            text(ex, 413, "size");
            return;
        }
        try {
            WebBridgeInbox.shared().chunk(id, index, data);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            int code = "cancelled".equals(m) || "order".equals(m) ? 409
                    : ("size".equals(m) ? 413 : ("missing".equals(m) ? 404 : 400));
            text(ex, code, m.isEmpty() ? "chunk" : m);
            return;
        }
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private static void fileCancel(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        WebBridgeInbox.shared().pause(query(ex, "id"));
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private static void fileFinish(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        WebBridgeInbox.Saved saved;
        try {
            saved = WebBridgeInbox.shared().finish(query(ex, "id"));
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            int code = "missing".equals(m) ? 404 : ("cancelled".equals(m) || "incomplete".equals(m) ? 409 : 400);
            text(ex, code, m.isEmpty() ? "finish" : m);
            return;
        }
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        String shown = saved.path.getFileName().toString();
        boolean wantClip = saved.clip && saved.size > 0 && saved.size <= Protocol.CLIPBOARD_IMAGE_MAX
                && FileNames.isImageName(shown);
        BufferedImage image = null;
        if (wantClip) {
            try (InputStream in = Files.newInputStream(saved.path)) {
                image = ImageIO.read(in);
            } catch (IOException ignored) {
                image = null;
            }
        }
        BufferedImage clipImage = image;
        SwingUtilities.invokeLater(() -> {
            boolean onClip = clipImage != null && PasteUtil.setClipboardImage(clipImage);
            TextSendMain.noteWeb(onClip ? "收到图片 " + shown + "，已放入剪贴板" : "收到文件 " + shown);
        });
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private static void outList(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        byte[] body = WebBridgeOutbox.shared().listJson().getBytes(StandardCharsets.UTF_8);
        writeBytes(ex, "application/json; charset=utf-8", body);
    }

    private static void outChunk(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        int index;
        try {
            index = Integer.parseInt(query(ex, "i"));
        } catch (RuntimeException e) {
            text(ex, 400, "index");
            return;
        }
        byte[] body;
        try {
            body = WebBridgeOutbox.shared().readChunk(query(ex, "id"), index);
        } catch (IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            int code = "missing".equals(m) ? 404 : 400;
            text(ex, code, m.isEmpty() ? "chunk" : m);
            return;
        }
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        writeBytes(ex, "application/octet-stream", body);
    }

    private static void outDrop(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        String id = query(ex, "id");
        if (WebBridgeOutbox.shared().drop(id) != null) {
            JsonObject o = new JsonObject();
            o.addProperty("v", 1);
            o.addProperty("type", "file-gone");
            o.addProperty("id", id == null ? "" : id);
            publish(o);
        }
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private static void outClear(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        if (!authOk(ex)) {
            text(ex, 401, "session");
            return;
        }
        WebBridgeOutbox.shared().clear();
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("type", "file-clear");
        publish(o);
        synchronized (LOCK) {
            touched = System.currentTimeMillis();
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private static void writeBytes(HttpExchange ex, String type, byte[] body) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void events(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        String seen;
        synchronized (LOCK) {
            if (!authOkLocked(ex) || token == null) {
                text(ex, 401, "session");
                return;
            }
            if (clients.size() >= MAX_CLIENTS) {
                text(ex, 429, "busy");
                return;
            }
            seen = sid;
            touched = System.currentTimeMillis();
        }
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("Connection", "keep-alive");
        h.set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(ex);
        try {
            if (!client.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8))) {
                return;
            }
            List<byte[]> backlog;
            synchronized (LOCK) {
                if (server == null || token == null || !seen.equals(sid)) {
                    return;
                }
                clients.add(client);
                backlog = new ArrayList<>(outbound);
            }
            for (byte[] ev : backlog) {
                if (!client.write(ev)) {
                    return;
                }
            }
            client.awaitClose();
        } finally {
            drop(client);
        }
    }

    private static boolean authOk(HttpExchange ex) {
        synchronized (LOCK) {
            return authOkLocked(ex);
        }
    }

    private static boolean authOkLocked(HttpExchange ex) {
        if (!same(sid, query(ex, "s"))) {
            return false;
        }
        String got = query(ex, "t");
        if (got == null || got.isEmpty()) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                String a = auth.trim();
                if (a.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    got = a.substring(7).trim();
                }
            }
        }
        return same(token, got);
    }

    private static boolean same(String expect, String got) {
        if (expect == null || got == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expect.getBytes(StandardCharsets.UTF_8),
                got.getBytes(StandardCharsets.UTF_8));
    }

    private static void staticFile(HttpExchange ex, String name, String type) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
            text(ex, 405, "method");
            return;
        }
        byte[] body = resource(name);
        if (body == null) {
            text(ex, 500, "missing page");
            return;
        }
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, "HEAD".equals(ex.getRequestMethod()) ? -1 : body.length);
        if (!"HEAD".equals(ex.getRequestMethod())) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } else {
            ex.close();
        }
    }

    private static byte[] resource(String name) throws IOException {
        InputStream in = WebBridgeA.class.getResourceAsStream("/webbridge/" + name);
        if (in == null) {
            in = WebBridgeA.class.getResourceAsStream("/resources/webbridge/" + name);
        }
        if (in == null) {
            return null;
        }
        try (InputStream ok = in) {
            return ok.readAllBytes();
        }
    }

    private static void drop(SseClient c) {
        synchronized (LOCK) {
            clients.remove(c);
        }
        c.close();
    }

    private static byte[] sse(String event, String data) {
        return ("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) {
            return null;
        }
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            String rawKey = i < 0 ? part : part.substring(0, i);
            String rawVal = i < 0 ? "" : part.substring(i + 1);
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return URLDecoder.decode(rawVal, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void text(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static final class SseClient {
        final HttpExchange ex;
        final OutputStream out;
        final CountDownLatch done = new CountDownLatch(1);
        boolean closed;

        SseClient(HttpExchange ex) {
            this.ex = ex;
            this.out = ex.getResponseBody();
        }

        boolean write(byte[] b) {
            synchronized (this) {
                if (closed) {
                    return false;
                }
                try {
                    out.write(b);
                    out.flush();
                    return true;
                } catch (IOException e) {
                    closed = true;
                    done.countDown();
                    return false;
                }
            }
        }

        void awaitClose() {
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void close() {
            synchronized (this) {
                if (closed) {
                    done.countDown();
                    return;
                }
                closed = true;
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 对方已经断开
                }
                ex.close();
                done.countDown();
            }
        }
    }
}
