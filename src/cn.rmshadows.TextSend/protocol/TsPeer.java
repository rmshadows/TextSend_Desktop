package protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.AEADBadTagException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 单条 TCP 连接上的 v1 会话（握手 + 加密收发）
 */
public final class TsPeer implements Runnable {
    private static final SecureRandom RNG = new SecureRandom();

    private final Socket socket;
    private final boolean asServer;
    private final PairingMaterial serverMaterial;
    private final TsUri clientUri;
    private final Consumer<String> onStatus;
    private final Runnable onReady;
    private final Runnable onClosed;
    private final AtomicBoolean alive = new AtomicBoolean(true);

    private InputStream in;
    private OutputStream out;
    private volatile byte[] aesKey;
    private String sessionId;
    private volatile boolean handshakeOk;
    private volatile boolean peerHasFile;
    private volatile FileIoCallback fileIo;
    private final Object ackLock = new Object();
    private boolean waitingAck;
    private boolean ackReceived;
    private JsonObject lastAckJson;
    private final Object fileLock = new Object();
    private JsonObject pendingControl;
    private volatile String outgoingFileId;
    private volatile int outgoingSeq;
    private volatile int outgoingOf;
    private final AtomicBoolean cancelOutgoing = new AtomicBoolean(false);
    private volatile Inbox inbox;
    private final java.util.Map<String, Path> treeRoots = new java.util.HashMap<>();

    /**
     * 服务端会话
     */
    public TsPeer(Socket socket, PairingMaterial material, Consumer<String> onStatus,
                  Runnable onReady, Runnable onClosed) {
        this.socket = socket;
        this.asServer = true;
        this.serverMaterial = material;
        this.clientUri = null;
        this.onStatus = onStatus;
        this.onReady = onReady;
        this.onClosed = onClosed;
    }

    /**
     * 客户端会话
     */
    public TsPeer(Socket socket, TsUri uri, Consumer<String> onStatus,
                  Runnable onReady, Runnable onClosed) {
        this.socket = socket;
        this.asServer = false;
        this.serverMaterial = null;
        this.clientUri = uri;
        this.onStatus = onStatus;
        this.onReady = onReady;
        this.onClosed = onClosed;
    }

    public boolean handshakeSucceeded() {
        return handshakeOk;
    }

    public boolean isAlive() {
        return alive.get() && aesKey != null;
    }

    public boolean supportsFile() {
        return peerHasFile;
    }

    public void setFileIo(FileIoCallback cb) {
        this.fileIo = cb;
    }

    public FileIoCallback getFileIo() {
        return fileIo;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(Protocol.HANDSHAKE_TIMEOUT_MS);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            if (asServer) {
                handshakeServer();
            } else {
                handshakeClient();
            }
            handshakeOk = true;
            socket.setSoTimeout(0);
            status("session ready: " + sessionId);
            if (onReady != null) {
                onReady.run();
            }
            loop();
        } catch (SocketTimeoutException e) {
            status("【握手】超时（" + (Protocol.HANDSHAKE_TIMEOUT_MS / 1000) + "s）");
        } catch (AEADBadTagException e) {
            status("【握手】AUTH_OK 解密失败 — PIN 或长密钥不匹配");
        } catch (Exception e) {
            status("【握手】失败: " + e.getMessage());
            if (!(e.getCause() instanceof AEADBadTagException)) {
                e.printStackTrace();
            }
        } finally {
            if (handshakeOk) {
                status("【会话】结束 sessionId=" + sessionId);
            }
            close();
        }
    }

    private void handshakeServer() throws Exception {
        String remote = remoteAddr();
        status("【握手】新连接 " + remote);

        JsonObject hello = baseHello("server");
        hello.addProperty("auth", "psk+pin");
        hello.addProperty("pinLen", Protocol.PIN_LEN);
        writePlain(Protocol.TYPE_HELLO, hello.toString());
        status("【握手】发送 HELLO  ver=1 auth=psk+pin pinLen=" + Protocol.PIN_LEN);

        Frame clientHello = readPlain(Protocol.TYPE_HELLO);
        JsonObject ch = parseJson(utf8(clientHello.payload));
        parseCaps(ch);
        status("【握手】收到 HELLO  role=" + str(ch, "role") + " caps=" + str(ch, "caps")
                + " auth=" + str(ch, "auth") + " file=" + peerHasFile);

        Frame kxIn = readPlain(Protocol.TYPE_KEY_EXCHANGE);
        JsonObject clientKx = parseJson(utf8(kxIn.payload));
        String mode = clientKx.get("mode").getAsString();
        status("【握手】收到 KEY_EXCHANGE  mode=" + mode);

        byte[] serverNonce = random(Protocol.NONCE_LEN);
        JsonObject serverKx = new JsonObject();
        serverKx.addProperty("mode", mode);

        KeyPair serverKp = null;
        if ("pin".equals(mode)) {
            status("【握手】PIN 模式：生成 ECDH " + Protocol.CURVE + " 临时密钥对");
            serverKp = EcdhP256.generate();
            serverKx.addProperty("curve", Protocol.CURVE);
            serverKx.addProperty("pub", B64.enc(EcdhP256.encodePublic((ECPublicKey) serverKp.getPublic())));
            serverKx.addProperty("nonce", B64.enc(serverNonce));
            writePlain(Protocol.TYPE_KEY_EXCHANGE, serverKx.toString());
            status("【握手】发送 KEY_EXCHANGE  pub+nonce  PIN=" + serverMaterial.pin);

            byte[] clientNonce = B64.dec(clientKx.get("nonce").getAsString());
            ECPublicKey clientPub = EcdhP256.decodePublic(B64.dec(clientKx.get("pub").getAsString()));
            byte[] shared = EcdhP256.sharedSecret(serverKp, clientPub);
            byte[] salt = concat(serverNonce, clientNonce);
            String info = Protocol.HKDF_INFO_PIN_PREFIX + serverMaterial.pin;
            status("【握手】ECDH 完成，HKDF-SHA256  info=\"" + info + "\"  派生 aesKey(不打印)");
            aesKey = Hkdf.derive(shared, salt, info, Protocol.PSK_LEN);
        } else if ("psk".equals(mode)) {
            status("【握手】PSK 模式：扫码长密钥，HKDF-SHA256  info=\"" + Protocol.HKDF_INFO_PSK + "\"");
            serverKx.addProperty("nonce", B64.enc(serverNonce));
            writePlain(Protocol.TYPE_KEY_EXCHANGE, serverKx.toString());
            status("【握手】发送 KEY_EXCHANGE  nonce");
            byte[] clientNonce = B64.dec(clientKx.get("nonce").getAsString());
            byte[] salt = concat(serverNonce, clientNonce);
            aesKey = Hkdf.derive(serverMaterial.psk, salt, Protocol.HKDF_INFO_PSK, Protocol.PSK_LEN);
            status("【握手】HKDF 派生 aesKey(不打印)");
        } else {
            throw new IOException("unsupported mode: " + mode);
        }

        status("【握手】等待 AUTH_OK（GCM）…");
        Frame auth = readCipher(Protocol.TYPE_AUTH_OK);
        JsonObject clientAuth = parseJson(utf8(auth.payload));
        if (!"client".equals(clientAuth.get("role").getAsString())) {
            throw new IOException("bad AUTH_OK role");
        }
        status("【握手】收到 AUTH_OK  对端已用同一把 aesKey");

        sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        JsonObject ok = new JsonObject();
        ok.addProperty("role", "server");
        ok.addProperty("sessionId", sessionId);
        ok.addProperty("maxFrame", Protocol.MAX_FRAME);
        writeCipher(Protocol.TYPE_AUTH_OK, ok.toString().getBytes(StandardCharsets.UTF_8));
        status("【握手】完成  mode=" + mode + "  sessionId=" + sessionId + "  peer=" + remote);
    }

    private void handshakeClient() throws Exception {
        String remote = remoteAddr();
        boolean pinMode = clientUri.pinMode;
        status("【握手】连接 " + remote + "  本地模式=" + (pinMode ? "PIN" : "PSK"));

        Frame serverHello = readPlain(Protocol.TYPE_HELLO);
        JsonObject sh = parseJson(utf8(serverHello.payload));
        parseCaps(sh);
        status("【握手】收到 HELLO  role=" + str(sh, "role") + " auth=" + str(sh, "auth")
                + " pinLen=" + str(sh, "pinLen") + " file=" + peerHasFile);

        JsonObject hello = baseHello("client");
        hello.addProperty("auth", pinMode ? "pin" : "psk");
        writePlain(Protocol.TYPE_HELLO, hello.toString());
        status("【握手】发送 HELLO  auth=" + (pinMode ? "pin" : "psk"));

        byte[] clientNonce = random(Protocol.NONCE_LEN);
        JsonObject kx = new JsonObject();
        KeyPair clientKp = null;
        if (pinMode) {
            status("【握手】PIN 模式：生成 ECDH " + Protocol.CURVE + " 临时密钥对  PIN=" + clientUri.pin());
            clientKp = EcdhP256.generate();
            kx.addProperty("mode", "pin");
            kx.addProperty("curve", Protocol.CURVE);
            kx.addProperty("pub", B64.enc(EcdhP256.encodePublic((ECPublicKey) clientKp.getPublic())));
            kx.addProperty("nonce", B64.enc(clientNonce));
        } else {
            status("【握手】PSK 模式：使用连接串中的长密钥");
            kx.addProperty("mode", "psk");
            kx.addProperty("nonce", B64.enc(clientNonce));
        }
        writePlain(Protocol.TYPE_KEY_EXCHANGE, kx.toString());
        status("【握手】发送 KEY_EXCHANGE  mode=" + (pinMode ? "pin" : "psk"));

        Frame skxFrame = readPlain(Protocol.TYPE_KEY_EXCHANGE);
        JsonObject skx = parseJson(utf8(skxFrame.payload));
        byte[] serverNonce = B64.dec(skx.get("nonce").getAsString());
        status("【握手】收到 KEY_EXCHANGE  mode=" + str(skx, "mode"));

        if (pinMode) {
            ECPublicKey serverPub = EcdhP256.decodePublic(B64.dec(skx.get("pub").getAsString()));
            byte[] shared = EcdhP256.sharedSecret(clientKp, serverPub);
            byte[] salt = concat(serverNonce, clientNonce);
            String info = Protocol.HKDF_INFO_PIN_PREFIX + clientUri.pin();
            status("【握手】ECDH 完成，HKDF-SHA256  info=\"" + info + "\"  派生 aesKey(不打印)");
            aesKey = Hkdf.derive(shared, salt, info, Protocol.PSK_LEN);
        } else {
            byte[] salt = concat(serverNonce, clientNonce);
            aesKey = Hkdf.derive(clientUri.psk, salt, Protocol.HKDF_INFO_PSK, Protocol.PSK_LEN);
            status("【握手】HKDF-SHA256  info=\"" + Protocol.HKDF_INFO_PSK + "\"  派生 aesKey(不打印)");
        }

        JsonObject auth = new JsonObject();
        auth.addProperty("role", "client");
        writeCipher(Protocol.TYPE_AUTH_OK, auth.toString().getBytes(StandardCharsets.UTF_8));
        status("【握手】发送 AUTH_OK（GCM）");

        Frame ok = readCipher(Protocol.TYPE_AUTH_OK);
        JsonObject serverOk = parseJson(utf8(ok.payload));
        sessionId = serverOk.get("sessionId").getAsString();
        status("【握手】完成  mode=" + (pinMode ? "pin" : "psk")
                + "  sessionId=" + sessionId + "  peer=" + remote);
    }

    private void loop() throws Exception {
        while (alive.get() && !socket.isClosed()) {
            Frame f = FrameIO.read(in);
            if (f.version != Protocol.VERSION) {
                throw new IOException("bad version");
            }
            byte[] plain = AesGcm.open(aesKey, f.payload);
            if (f.type == Protocol.TYPE_TEXT) {
                handleText(plain);
            } else if (f.type == Protocol.TYPE_ACK) {
                System.out.println("Log: 【接收反馈】ACK");
                JsonObject ackJson = tryJson(plain);
                synchronized (ackLock) {
                    lastAckJson = ackJson;
                    if (waitingAck) {
                        ackReceived = true;
                        ackLock.notifyAll();
                    }
                }
            } else if (f.type == Protocol.TYPE_ERROR) {
                status("peer ERROR: " + utf8(plain));
            } else if (f.type == Protocol.TYPE_FILE_META) {
                handleFileMeta(plain);
            } else if (f.type == Protocol.TYPE_FILE_CHUNK) {
                handleFileChunk(plain);
            } else if (f.type == Protocol.TYPE_FILE_DONE) {
                handleFileDone(plain);
            } else if (f.type == Protocol.TYPE_FILE_CONTROL) {
                handleFileControl(plain);
            } else {
                status("ignore type=" + (f.type & 0xff));
            }
        }
    }

    /** TEXT 明文 = UTF-8 字符串。JSON 只用于握手 / ACK 等控制帧。 */
    private void handleText(byte[] plain) throws Exception {
        String text = utf8(plain);
        System.out.println("Log: 【接收】TEXT <= " + text);
        PasteUtil.pasteText(text);
        JsonObject ack = new JsonObject();
        ack.addProperty("ok", true);
        writeCipher(Protocol.TYPE_ACK, ack.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void sendText(String text) throws Exception {
        if (aesKey == null) {
            throw new IllegalStateException("not ready");
        }
        String body = text == null ? "" : text;
        System.out.println("Log: 【发送】TEXT => " + body);
        synchronized (ackLock) {
            waitingAck = true;
            ackReceived = false;
        }
        try {
            writeCipher(Protocol.TYPE_TEXT, body.getBytes(StandardCharsets.UTF_8));
            synchronized (ackLock) {
                long deadline = System.currentTimeMillis() + Protocol.ACK_TIMEOUT_MS;
                while (waitingAck && !ackReceived && alive.get()) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    ackLock.wait(left);
                }
                if (!ackReceived) {
                    throw new IOException(alive.get() ? "ACK timeout" : "disconnected before ACK");
                }
            }
        } finally {
            synchronized (ackLock) {
                waitingAck = false;
                ackReceived = false;
            }
        }
    }

    public boolean sendFile(Path path, BooleanSupplier cancelled) throws Exception {
        return sendFile(path, cancelled, null, null);
    }

    public boolean sendFile(Path path, BooleanSupplier cancelled, String treeName, String relPath)
            throws Exception {
        return sendFile(path, cancelled, treeName, relPath, 0, 0);
    }

    public boolean sendFile(Path path, BooleanSupplier cancelled, String treeName, String relPath,
                            int seq, int of) throws Exception {
        if (aesKey == null) {
            throw new IllegalStateException("not ready");
        }
        if (!peerHasFile) {
            throw new IOException("对端不支持文件传输");
        }
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("不是文件");
        }
        long size = Files.size(path);
        if (size < 0 || size > Protocol.FILE_SIZE_MAX) {
            throw new IOException("文件过大");
        }
        String name = FileNames.sanitize(path.getFileName().toString());
        if (relPath != null && !relPath.isBlank()) {
            String safeRel = FileNames.sanitizeRelPath(relPath);
            if (safeRel == null) {
                throw new IOException("非法路径");
            }
            int slash = safeRel.lastIndexOf('/');
            name = slash >= 0 ? safeRel.substring(slash + 1) : safeRel;
            relPath = safeRel;
        }
        boolean image = FileNames.isImageName(name) && treeName == null;
        String kind = image ? "image" : "file";
        String disposition = (image && size > 0 && size <= Protocol.CLIPBOARD_IMAGE_MAX) ? "paste" : "save";
        String fileId = FileIds.random();
        JsonObject meta = new JsonObject();
        meta.addProperty("fileId", fileId);
        meta.addProperty("name", name);
        meta.addProperty("size", size);
        meta.addProperty("chunkSize", Protocol.FILE_CHUNK);
        meta.addProperty("kind", kind);
        meta.addProperty("disposition", disposition);
        if (treeName != null && !treeName.isBlank()) {
            meta.addProperty("tree", FileNames.sanitize(treeName));
        }
        if (relPath != null && !relPath.isBlank()) {
            meta.addProperty("path", relPath);
        }
        String headSha = ResumeFiles.sha256Head(path, size);
        String tailSha = ResumeFiles.sha256Tail(path, size);
        meta.addProperty("headSha256", headSha);
        meta.addProperty("tailSha256", tailSha);
        if (seq > 0 && of > 0) {
            meta.addProperty("seq", seq);
            meta.addProperty("of", of);
        }
        System.out.println("Log: 【发送】FILE_META " + name + " " + size + "B " + kind + "/" + disposition);

        cancelOutgoing.set(false);
        outgoingFileId = fileId;
        outgoingSeq = seq;
        outgoingOf = of;
        try {
            writeCipher(Protocol.TYPE_FILE_META, meta.toString().getBytes(StandardCharsets.UTF_8));
            JsonObject ctrl = waitFileControl(fileId, Protocol.FILE_ACCEPT_TIMEOUT_MS);
            String op = str(ctrl, "op");
            if ("REJECT".equalsIgnoreCase(op)) {
                throw new IOException("对端拒绝: " + str(ctrl, "reason"));
            }
            long offset = 0;
            int index = 0;
            if ("RESUME".equalsIgnoreCase(op)) {
                offset = ctrl.has("offset") ? ctrl.get("offset").getAsLong() : 0;
                index = ctrl.has("nextIndex") ? ctrl.get("nextIndex").getAsInt() : 0;
                if (offset < 0 || offset > size) {
                    throw new IOException("续传偏移无效");
                }
                String expectPrefix = str(ctrl, "prefixSha256");
                if (offset > 0 && ResumeFiles.isSha256Hex(expectPrefix)) {
                    String localPrefix = ResumeFiles.sha256Prefix(path, offset);
                    if (!expectPrefix.equalsIgnoreCase(localPrefix)) {
                        System.out.println("Log: 【发送】已收前缀不是本文件，从头发 " + name);
                        sendControl("RESET", fileId, "prefix");
                        JsonObject again = waitFileControl(fileId, Protocol.FILE_ACCEPT_TIMEOUT_MS);
                        if (!"ACCEPT".equalsIgnoreCase(str(again, "op"))) {
                            throw new IOException("未接受文件: " + str(again, "op"));
                        }
                        offset = 0;
                        index = 0;
                    }
                }
                if (offset > 0) {
                    System.out.println("Log: 【发送】RESUME offset=" + offset);
                }
            } else if (!"ACCEPT".equalsIgnoreCase(op)) {
                throw new IOException("未接受文件: " + op);
            }

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            int chunkIndex = index;
            long sent = offset;
            byte[] idBytes = FileIds.toBytes(fileId);
            byte[] buf = new byte[Protocol.FILE_CHUNK];
            try (InputStream fin = new BufferedInputStream(Files.newInputStream(path))) {
                long skip = offset;
                while (skip > 0) {
                    int n = fin.read(buf, 0, (int) Math.min(buf.length, skip));
                    if (n < 0) {
                        throw new IOException("续传跳过失败");
                    }
                    sha.update(buf, 0, n);
                    skip -= n;
                }
                FileIoCallback cb0 = fileIo;
                if (cb0 != null && offset > 0) {
                    cb0.onProgress(false, name, sent, size, outgoingSeq, outgoingOf);
                }
                while (true) {
                    if ((cancelled != null && cancelled.getAsBoolean()) || cancelOutgoing.get() || !alive.get()) {
                        sendControl("CANCEL", fileId, null);
                        throw new IOException("cancelled");
                    }
                    int n = fin.read(buf);
                    if (n < 0) {
                        break;
                    }
                    sha.update(buf, 0, n);
                    byte[] chunk = new byte[Protocol.FILE_ID_LEN + 4 + n];
                    System.arraycopy(idBytes, 0, chunk, 0, Protocol.FILE_ID_LEN);
                    ByteBuffer.wrap(chunk, Protocol.FILE_ID_LEN, 4).putInt(chunkIndex);
                    System.arraycopy(buf, 0, chunk, Protocol.FILE_ID_LEN + 4, n);
                    writeCipher(Protocol.TYPE_FILE_CHUNK, chunk);
                    chunkIndex++;
                    sent += n;
                    FileIoCallback cb = fileIo;
                    if (cb != null) {
                        cb.onProgress(false, name, sent, size, outgoingSeq, outgoingOf);
                    }
                }
            }

            JsonObject done = new JsonObject();
            done.addProperty("fileId", fileId);
            done.addProperty("sha256", FileIds.shaHex(sha.digest()));
            synchronized (ackLock) {
                waitingAck = true;
                ackReceived = false;
                lastAckJson = null;
            }
            try {
                writeCipher(Protocol.TYPE_FILE_DONE, done.toString().getBytes(StandardCharsets.UTF_8));
                synchronized (ackLock) {
                    long deadline = System.currentTimeMillis() + Protocol.ACK_TIMEOUT_MS;
                    while (waitingAck && !ackReceived && alive.get() && !cancelOutgoing.get()) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) {
                            break;
                        }
                        ackLock.wait(left);
                    }
                    if (!ackReceived) {
                        throw new IOException(alive.get() ? "ACK timeout" : "disconnected before ACK");
                    }
                    if (lastAckJson != null && lastAckJson.has("ok") && !lastAckJson.get("ok").getAsBoolean()) {
                        throw new IOException("对端校验失败: " + str(lastAckJson, "err"));
                    }
                }
            } finally {
                synchronized (ackLock) {
                    waitingAck = false;
                    ackReceived = false;
                }
            }
            System.out.println("Log: 【发送】FILE_DONE " + name);
            return true;
        } finally {
            outgoingFileId = null;
            outgoingSeq = 0;
            outgoingOf = 0;
            cancelOutgoing.set(false);
        }
    }

    public void cancelFile() {
        cancelOutgoing.set(true);
        String outId = outgoingFileId;
        if (outId != null) {
            try {
                sendControl("CANCEL", outId, null);
            } catch (Exception ignored) {
            }
        }
        Inbox box = inbox;
        if (box != null) {
            try {
                sendControl("CANCEL", box.fileId, null);
            } catch (Exception ignored) {
            }
            failInbox(box, "已取消", true);
        }
        synchronized (fileLock) {
            fileLock.notifyAll();
        }
        synchronized (ackLock) {
            ackLock.notifyAll();
        }
    }

    public void close() {
        if (!alive.compareAndSet(true, false)) {
            return;
        }
        Inbox box = inbox;
        if (box != null) {
            failInbox(box, "连接断开", false);
        }
        synchronized (ackLock) {
            ackLock.notifyAll();
        }
        synchronized (fileLock) {
            fileLock.notifyAll();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        for (Path dir : treeRoots.values()) {
            try {
                if (!ResumeFiles.hasIncomplete(dir)) {
                    Files.deleteIfExists(dir.resolve(".textsend.receiving"));
                }
            } catch (IOException ignored) {
            }
        }
        treeRoots.clear();
        if (onClosed != null) {
            try {
                onClosed.run();
            } catch (Exception ignored) {
            }
        }
    }

    private JsonObject baseHello(String role) {
        JsonObject o = new JsonObject();
        o.addProperty("ver", 1);
        o.addProperty("role", role);
        JsonArray caps = new JsonArray();
        caps.add("text");
        caps.add("file");
        o.add("caps", caps);
        return o;
    }

    private void parseCaps(JsonObject hello) {
        peerHasFile = false;
        if (hello != null && hello.has("caps") && hello.get("caps").isJsonArray()) {
            for (var el : hello.getAsJsonArray("caps")) {
                if ("file".equals(el.getAsString())) {
                    peerHasFile = true;
                    break;
                }
            }
        }
    }

    private void handleFileMeta(byte[] plain) throws Exception {
        JsonObject meta = parseJson(utf8(plain));
        String fileId = meta.has("fileId") ? meta.get("fileId").getAsString() : "";
        String name = FileNames.sanitize(meta.has("name") ? meta.get("name").getAsString() : "file");
        long size = meta.has("size") ? meta.get("size").getAsLong() : -1;
        String kind = meta.has("kind") ? meta.get("kind").getAsString() : "file";
        String disposition = meta.has("disposition") ? meta.get("disposition").getAsString() : "save";
        String tree = meta.has("tree") ? FileNames.sanitize(meta.get("tree").getAsString()) : null;
        if (tree != null && tree.isBlank()) {
            tree = null;
        }
        String rel = meta.has("path") ? FileNames.sanitizeRelPath(meta.get("path").getAsString()) : null;
        String headSha = meta.has("headSha256") ? meta.get("headSha256").getAsString() : "";
        String tailSha = meta.has("tailSha256") ? meta.get("tailSha256").getAsString() : "";
        int seq = 0;
        int of = 0;
        if (meta.has("seq") && meta.has("of")) {
            try {
                seq = meta.get("seq").getAsInt();
                of = meta.get("of").getAsInt();
            } catch (Exception ignored) {
            }
        }
        if (seq < 1 || of < 1) {
            seq = 0;
            of = 0;
        }
        System.out.println("Log: 【接收】FILE_META " + name + " " + size + "B");

        if (inbox != null) {
            sendControl("REJECT", fileId, "busy");
            return;
        }
        if (fileId.length() != Protocol.FILE_ID_LEN * 2 || size < 0 || size > Protocol.FILE_SIZE_MAX) {
            sendControl("REJECT", fileId, "meta");
            return;
        }
        if (meta.has("tree") && (tree == null || rel == null)) {
            sendControl("REJECT", fileId, "path");
            return;
        }
        try {
            Path destDir = FileNames.inboxDir();
            if (tree != null) {
                destDir = resolveTreeRoot(tree);
                Path dest = FileNames.resolveUnder(destDir, rel);
                destDir = dest.getParent() == null ? destDir : dest.getParent();
                Files.createDirectories(destDir);
                name = dest.getFileName().toString();
            }
            Inbox opened = Inbox.open(fileId, name, size, kind, disposition, destDir, headSha, tailSha, seq, of);
            inbox = opened;
            if (opened.resuming) {
                sendResume(fileId, opened.written, opened.nextIndex, opened.partPath);
                FileIoCallback cb = fileIo;
                if (cb != null) {
                    cb.onProgress(true, name, opened.written, size, opened.seq, opened.of);
                }
                return;
            }
        } catch (Exception e) {
            if (inbox != null) {
                failInbox(inbox, e.getMessage(), false);
            } else {
                notifyFail(name, e.getMessage());
            }
            String reason = e.getMessage() != null && e.getMessage().startsWith("disk") ? "disk" : "io";
            sendControl("REJECT", fileId, reason);
            return;
        }
        sendControl("ACCEPT", fileId, null);
        FileIoCallback cb = fileIo;
        if (cb != null) {
            cb.onProgress(true, name, 0, size, seq, of);
        }
    }

    private Path resolveTreeRoot(String tree) throws java.io.IOException {
        Path cached = treeRoots.get(tree);
        if (cached != null) {
            return cached;
        }
        Path inboxDir = FileNames.inboxDir();
        Path named = inboxDir.resolve(tree);
        Path mark = named.resolve(".textsend.receiving");
        Path dir;
        if (Files.isDirectory(named) && (Files.exists(mark) || ResumeFiles.hasIncomplete(named))) {
            dir = named;
        } else {
            dir = FileNames.uniqueDir(inboxDir, tree);
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".textsend.receiving"), "1");
        treeRoots.put(tree, dir);
        return dir;
    }

    private void handleFileChunk(byte[] plain) throws Exception {
        Inbox box = inbox;
        if (box == null || plain.length < Protocol.FILE_ID_LEN + 4) {
            return;
        }
        String id = FileIds.toHex(java.util.Arrays.copyOf(plain, Protocol.FILE_ID_LEN));
        if (!box.fileId.equals(id)) {
            return;
        }
        int index = ByteBuffer.wrap(plain, Protocol.FILE_ID_LEN, 4).getInt();
        int dataOff = Protocol.FILE_ID_LEN + 4;
        int n = plain.length - dataOff;
        if (index != box.nextIndex || n < 0 || n > Protocol.FILE_CHUNK_MAX) {
            failInbox(box, "分片异常", true);
            sendControl("CANCEL", box.fileId, null);
            return;
        }
        if (box.written + n > box.size) {
            failInbox(box, "超出声明大小", true);
            sendControl("CANCEL", box.fileId, null);
            return;
        }
        box.out.write(plain, dataOff, n);
        box.out.flush();
        box.written += n;
        box.nextIndex++;
        FileIoCallback cb = fileIo;
        if (cb != null) {
            cb.onProgress(true, box.name, box.written, box.size, box.seq, box.of);
        }
    }

    private void handleFileDone(byte[] plain) throws Exception {
        JsonObject done = parseJson(utf8(plain));
        String fileId = done.has("fileId") ? done.get("fileId").getAsString() : "";
        String expectSha = done.has("sha256") ? done.get("sha256").getAsString() : "";
        Inbox box = inbox;
        if (box == null || !box.fileId.equals(fileId)) {
            writeAck(false, fileId, "no-inbox");
            return;
        }
        try {
            box.closeOut();
            if (Files.size(box.partPath) != box.size) {
                throw new IOException("大小不符");
            }
            String got = ResumeFiles.sha256File(box.partPath);
            if (expectSha != null && !expectSha.isBlank() && !expectSha.equalsIgnoreCase(got)) {
                throw new IOException("sha256");
            }
            boolean wantPaste = "paste".equalsIgnoreCase(box.disposition) && "image".equalsIgnoreCase(box.kind)
                    && box.size > 0 && box.size <= Protocol.CLIPBOARD_IMAGE_MAX;
            if (wantPaste) {
                BufferedImage img = ImageIO.read(box.partPath.toFile());
                if (img != null && PasteUtil.setClipboardImage(img)) {
            Files.deleteIfExists(box.partPath);
            ResumeFiles.delete(box.dir, box.name);
                    inbox = null;
                    writeAck(true, fileId, null);
                    FileIoCallback cb = fileIo;
                    if (cb != null) {
                        cb.onReceived(box.name, "剪贴板");
                    }
                    System.out.println("Log: 【接收】图片进剪贴板 " + box.name);
                    return;
                }
            }
            Path dest = FileNames.unique(box.dir, box.name);
            Files.move(box.partPath, dest, StandardCopyOption.REPLACE_EXISTING);
            ResumeFiles.delete(box.dir, box.name);
            inbox = null;
            writeAck(true, fileId, null);
            FileIoCallback cb = fileIo;
            if (cb != null) {
                cb.onReceived(box.name, dest.toString());
            }
            System.out.println("Log: 【接收】已保存 " + dest);
        } catch (Exception e) {
            failInbox(box, e.getMessage(), true);
            writeAck(false, fileId, e.getMessage());
        }
    }

    private void handleFileControl(byte[] plain) {
        JsonObject o = parseJson(utf8(plain));
        String op = str(o, "op");
        String fileId = o.has("fileId") ? o.get("fileId").getAsString() : "";
        System.out.println("Log: 【接收】FILE_CONTROL " + op + " " + fileId);
        if ("RESET".equalsIgnoreCase(op)) {
            Inbox box = inbox;
            if (box != null && box.fileId.equals(fileId)) {
                try {
                    box.resetToStart();
                    sendControl("ACCEPT", fileId, null);
                    System.out.println("Log: 【接收】RESET 从头收 " + box.name);
                } catch (Exception e) {
                    failInbox(box, e.getMessage(), true);
                    try {
                        sendControl("REJECT", fileId, "io");
                    } catch (Exception ignored) {
                    }
                }
            }
        } else if ("CANCEL".equalsIgnoreCase(op)) {
            if (outgoingFileId != null && outgoingFileId.equals(fileId)) {
                cancelOutgoing.set(true);
            }
            Inbox box = inbox;
            if (box != null && box.fileId.equals(fileId)) {
                failInbox(box, "对端取消", true);
            }
        }
        synchronized (fileLock) {
            pendingControl = o;
            fileLock.notifyAll();
        }
    }

    private JsonObject waitFileControl(String fileId, long timeoutMs) throws Exception {
        synchronized (fileLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (alive.get() && !cancelOutgoing.get()) {
                if (pendingControl != null) {
                    JsonObject m = pendingControl;
                    pendingControl = null;
                    String id = m.has("fileId") ? m.get("fileId").getAsString() : "";
                    if (fileId.equals(id)) {
                        return m;
                    }
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new IOException("对端未接受（可能还不支持文件）");
                }
                fileLock.wait(left);
            }
            throw new IOException(cancelOutgoing.get() ? "cancelled" : "disconnected");
        }
    }

    private void sendControl(String op, String fileId, String reason) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        if (fileId != null) {
            o.addProperty("fileId", fileId);
        }
        if (reason != null) {
            o.addProperty("reason", reason);
        }
        writeCipher(Protocol.TYPE_FILE_CONTROL, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendResume(String fileId, long offset, int nextIndex, Path part) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("op", "RESUME");
        o.addProperty("fileId", fileId);
        o.addProperty("offset", offset);
        o.addProperty("nextIndex", nextIndex);
        if (offset > 0 && part != null) {
            o.addProperty("prefixSha256", ResumeFiles.sha256Prefix(part, offset));
        }
        writeCipher(Protocol.TYPE_FILE_CONTROL, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeAck(boolean ok, String fileId, String err) throws Exception {
        JsonObject ack = new JsonObject();
        ack.addProperty("ok", ok);
        if (fileId != null) {
            ack.addProperty("id", fileId);
        }
        if (err != null) {
            ack.addProperty("err", err);
        }
        writeCipher(Protocol.TYPE_ACK, ack.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void failInbox(Inbox box, String err, boolean delete) {
        if (box == null) {
            return;
        }
        if (inbox == box) {
            inbox = null;
        }
        box.closeQuiet();
        if (delete) {
            ResumeFiles.delete(box.dir, box.name);
        } else {
            ResumeFiles.save(box.dir, box.name, box.size, box.written, box.nextIndex, box.headSha, box.tailSha);
        }
        notifyFail(box.name, delete ? (err == null ? "失败" : err) : "传输中断，可续传");
    }

    private void notifyFail(String name, String err) {
        FileIoCallback cb = fileIo;
        if (cb != null) {
            cb.onReceiveFailed(name, err == null ? "失败" : err);
        }
    }

    private static JsonObject tryJson(byte[] plain) {
        try {
            return parseJson(utf8(plain));
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Inbox {
        final String fileId;
        final String name;
        final long size;
        final String kind;
        final String disposition;
        final String headSha;
        final String tailSha;
        final int seq;
        final int of;
        final Path dir;
        final Path partPath;
        final boolean resuming;
        OutputStream out;
        long written;
        int nextIndex;

        private Inbox(String fileId, String name, long size, String kind, String disposition,
                      String headSha, String tailSha, int seq, int of, Path dir, Path partPath,
                      OutputStream out, long written, int nextIndex, boolean resuming) {
            this.fileId = fileId;
            this.name = name;
            this.size = size;
            this.kind = kind;
            this.disposition = disposition;
            this.headSha = headSha;
            this.tailSha = tailSha;
            this.seq = seq;
            this.of = of;
            this.dir = dir;
            this.partPath = partPath;
            this.out = out;
            this.written = written;
            this.nextIndex = nextIndex;
            this.resuming = resuming;
        }

        static Inbox open(String fileId, String name, long size, String kind, String disposition, Path dir,
                          String headSha, String tailSha, int seq, int of) throws Exception {
            try {
                long usable = Files.getFileStore(dir).getUsableSpace();
                if (usable < size + 1024 * 1024) {
                    throw new IOException("disk");
                }
            } catch (IOException e) {
                if ("disk".equals(e.getMessage())) {
                    throw e;
                }
            }
            Path part = ResumeFiles.partPath(dir, name);
            JsonObject saved = ResumeFiles.load(dir, name, size);
            if (saved != null && Files.isRegularFile(part)
                    && ResumeFiles.identityMatches(saved, part, size, headSha, tailSha)) {
                long onDisk = Files.size(part);
                long written = Math.min(onDisk, saved.has("written") ? saved.get("written").getAsLong() : onDisk);
                written = ResumeFiles.align(written);
                if (written > 0 && written <= size) {
                    if (onDisk != written) {
                        try (var ch = Files.newByteChannel(part, StandardOpenOption.WRITE)) {
                            ch.truncate(written);
                        }
                    }
                    int nextIndex = (int) (written / Protocol.FILE_CHUNK);
                    OutputStream out = new BufferedOutputStream(Files.newOutputStream(part,
                            StandardOpenOption.WRITE, StandardOpenOption.APPEND));
                    ResumeFiles.save(dir, name, size, written, nextIndex, headSha, tailSha);
                    System.out.println("Log: 【接收】续传 " + name + " offset=" + written);
                    return new Inbox(fileId, name, size, kind, disposition, headSha, tailSha, seq, of,
                            dir, part, out, written, nextIndex, true);
                }
            }
            if (saved != null && Files.isRegularFile(part)
                    && !ResumeFiles.identityMatches(saved, part, size, headSha, tailSha)) {
                System.out.println("Log: 【接收】同名同大小但不是同一文件，不续传 " + name);
            }
            ResumeFiles.delete(dir, name);
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(part));
            ResumeFiles.save(dir, name, size, 0, 0, headSha, tailSha);
            return new Inbox(fileId, name, size, kind, disposition, headSha, tailSha, seq, of,
                    dir, part, out, 0, 0, false);
        }

        void resetToStart() throws IOException {
            closeOut();
            try (var ch = Files.newByteChannel(partPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                ch.truncate(0);
            }
            out = new BufferedOutputStream(Files.newOutputStream(partPath));
            written = 0;
            nextIndex = 0;
            ResumeFiles.save(dir, name, size, 0, 0, headSha, tailSha);
        }

        void closeOut() throws IOException {
            if (out != null) {
                out.close();
                out = null;
            }
        }

        void closeQuiet() {
            try {
                closeOut();
            } catch (IOException ignored) {
            }
        }
    }

    private void writePlain(byte type, String json) throws IOException {
        synchronized (this) {
            FrameIO.write(out, type, json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeCipher(byte type, byte[] plain) throws Exception {
        synchronized (this) {
            FrameIO.write(out, type, AesGcm.seal(aesKey, plain));
        }
    }

    private Frame readPlain(byte expectType) throws IOException {
        Frame f = FrameIO.read(in);
        if (f.type != expectType) {
            throw new IOException("expect type " + expectType + " got " + f.type);
        }
        return f;
    }

    private Frame readCipher(byte expectType) throws Exception {
        Frame f = FrameIO.read(in);
        if (f.type != expectType) {
            throw new IOException("expect type " + expectType + " got " + f.type);
        }
        byte[] plain = AesGcm.open(aesKey, f.payload);
        return new Frame(f.version, f.type, plain);
    }

    private void status(String s) {
        System.out.println("Log: " + s);
        if (onStatus != null) {
            onStatus.accept(s);
        }
    }

    private String remoteAddr() {
        try {
            return socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        } catch (Exception e) {
            return "?";
        }
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) ? o.get(key).toString().replace("\"", "") : "?";
    }

    private static JsonObject parseJson(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}
