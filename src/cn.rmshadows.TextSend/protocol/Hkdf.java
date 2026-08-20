package protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** HKDF-SHA256 */
public final class Hkdf {
    private Hkdf() {
    }

    public static byte[] derive(byte[] ikm, byte[] salt, String info, int length) throws Exception {
        byte[] prk = extract(salt, ikm);
        return expand(prk, info.getBytes(StandardCharsets.UTF_8), length);
    }

    private static byte[] extract(byte[] salt, byte[] ikm) throws Exception {
        if (salt == null || salt.length == 0) {
            salt = new byte[32];
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    private static byte[] expand(byte[] prk, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] result = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        byte counter = 1;
        while (pos < length) {
            mac.reset();
            mac.update(t);
            mac.update(info);
            mac.update(counter);
            t = mac.doFinal();
            int copy = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, result, pos, copy);
            pos += copy;
            counter++;
        }
        Arrays.fill(prk, (byte) 0);
        return result;
    }
}
