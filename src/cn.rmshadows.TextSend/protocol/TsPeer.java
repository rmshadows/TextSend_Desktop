package protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.AEADBadTagException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Object ackLock = new Object();
    private boolean waitingAck;
    private boolean ackReceived;

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
        status("【握手】收到 HELLO  role=" + str(ch, "role") + " caps=" + str(ch, "caps")
                + " auth=" + str(ch, "auth"));

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
        status("【握手】收到 HELLO  role=" + str(sh, "role") + " auth=" + str(sh, "auth")
                + " pinLen=" + str(sh, "pinLen"));

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
                synchronized (ackLock) {
                    if (waitingAck) {
                        ackReceived = true;
                        ackLock.notifyAll();
                    }
                }
            } else if (f.type == Protocol.TYPE_ERROR) {
                status("peer ERROR: " + utf8(plain));
            } else if (f.type == Protocol.TYPE_FILE_META
                    || f.type == Protocol.TYPE_FILE_DONE
                    || f.type == Protocol.TYPE_FILE_CONTROL) {
                System.out.println("Log: 【接收】FILE type=0x" + Integer.toHexString(f.type & 0xff)
                        + " <= " + utf8(plain));
            } else if (f.type == Protocol.TYPE_FILE_CHUNK) {
                System.out.println("Log: 【接收】FILE_CHUNK <= " + plain.length + " bytes");
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

    public void close() {
        if (!alive.compareAndSet(true, false)) {
            return;
        }
        synchronized (ackLock) {
            ackLock.notifyAll();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
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
        o.add("caps", caps);
        return o;
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
