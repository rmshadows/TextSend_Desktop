package protocol;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 ts://host:port/k... 或 host/k...（端口可省略，默认 54300）
 */
public final class TsUri {
    private static final Pattern URI = Pattern.compile(
            "^(?:ts://)?(?:\\[([^\\]]+)]|([^:/\\s]+))(?::(\\d{1,5}))?/k(.+)$",
            Pattern.CASE_INSENSITIVE
    );

    public final String host;
    public final int port;
    /** 原始 k 字符串 */
    public final String k;
    public final boolean pinMode;
    /** PIN 模式为 null；PSK 模式为 32 字节 */
    public final byte[] psk;

    private TsUri(String host, int port, String k, boolean pinMode, byte[] psk) {
        this.host = host;
        this.port = port;
        this.k = k;
        this.pinMode = pinMode;
        this.psk = psk;
    }

    public static TsUri parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("empty uri");
        }
        String s = raw.trim().split("\\R")[0].trim();
        Matcher m = URI.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("bad connection string: " + s);
        }
        String host = m.group(1) != null ? m.group(1) : m.group(2);
        int port = parsePort(m.group(3));
        String k = m.group(4);
        if (k.matches("\\d{" + Protocol.PIN_LEN + "}")) {
            return new TsUri(host, port, k, true, null);
        }
        byte[] psk;
        try {
            psk = B64.dec(k);
        } catch (Exception e) {
            throw new IllegalArgumentException("k is neither PIN nor Base64URL PSK");
        }
        if (psk.length != Protocol.PSK_LEN) {
            throw new IllegalArgumentException("PSK must be 32 bytes");
        }
        return new TsUri(host, port, k, false, psk);
    }

    private static int parsePort(String s) {
        if (s == null || s.isEmpty()) {
            return Protocol.DEFAULT_PORT;
        }
        int port = Integer.parseInt(s);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("bad port: " + s);
        }
        return port;
    }

    public static String format(String host, int port, String kMaterial) {
        Objects.requireNonNull(kMaterial);
        boolean v6 = host.contains(":");
        String h = v6 ? "[" + host + "]" : host;
        return String.format(Locale.ROOT, "ts://%s:%d/k%s", h, port, kMaterial);
    }

    public String pin() {
        return pinMode ? k : null;
    }
}
