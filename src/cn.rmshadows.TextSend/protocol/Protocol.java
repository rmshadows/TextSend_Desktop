package protocol;

import java.nio.charset.StandardCharsets;

/**
 * TextSend v1 协议常量（与根目录 README 对齐）
 */
public final class Protocol {
    private Protocol() {
    }

    public static final byte[] MAGIC = "TS".getBytes(StandardCharsets.US_ASCII);
    public static final byte VERSION = 0x01;

    public static final byte TYPE_HELLO = 0x01;
    public static final byte TYPE_KEY_EXCHANGE = 0x02;
    public static final byte TYPE_AUTH_OK = 0x03;
    public static final byte TYPE_TEXT = 0x10;
    public static final byte TYPE_ACK = 0x11;
    public static final byte TYPE_FILE_META = 0x20;
    public static final byte TYPE_FILE_CHUNK = 0x21;
    public static final byte TYPE_FILE_DONE = 0x22;
    public static final byte TYPE_FILE_CONTROL = 0x23;
    public static final byte TYPE_ERROR = 0x7F;

    public static final int HEADER_LEN = 8;
    public static final int MAX_FRAME = 4 * 1024 * 1024;
    public static final int GCM_NONCE_LEN = 12;
    public static final int GCM_TAG_LEN = 16;
    public static final int PSK_LEN = 32;
    public static final int NONCE_LEN = 16;
    public static final int PIN_LEN = 8;
    public static final int DEFAULT_PORT = 54300;
    public static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    public static final int ACK_TIMEOUT_MS = 5_000;

    /** FILE_DONE 后对端可能还在落盘；按大小放宽，避免大文件误报 ACK timeout。 */
    public static long fileDoneAckMs(long size) {
        long mb = Math.max(0L, size / (1024L * 1024L));
        return Math.min(180_000L, ACK_TIMEOUT_MS + mb * 80L);
    }
    public static final int PIN_FAIL_MAX = 5;
    public static final int PIN_LOCK_MS = 60_000;

    public static final int FILE_ID_LEN = 16;
    public static final int FILE_CHUNK = 128 * 1024;
    public static final int FILE_CHUNK_MAX = 256 * 1024;
    public static final int FILE_BATCH_MAX = 50;
    public static final long CLIPBOARD_IMAGE_MAX = 20L * 1024 * 1024;
    public static final int FILE_ACCEPT_TIMEOUT_MS = 12_000;
    public static final long FILE_SIZE_MAX = 1024L * 1024 * 1024 * 1024; // 1 TiB  sanity

    public static final String HKDF_INFO_PIN_PREFIX = "textsend-v1|pin=";
    public static final String HKDF_INFO_PSK = "textsend-v1|psk";
    public static final String CURVE = "P-256";
}
