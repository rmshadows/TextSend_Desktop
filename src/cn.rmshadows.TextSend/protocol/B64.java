package protocol;

import java.util.Base64;

public final class B64 {
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private B64() {
    }

    public static String enc(byte[] data) {
        return ENC.encodeToString(data);
    }

    public static byte[] dec(String s) {
        return DEC.decode(s);
    }
}
