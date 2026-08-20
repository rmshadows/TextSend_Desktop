package protocol;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

public final class AesGcm {
    private static final SecureRandom RNG = new SecureRandom();

    private AesGcm() {
    }

    /** nonce(12) || ciphertext || tag(16) */
    public static byte[] seal(byte[] key32, byte[] plaintext) throws Exception {
        byte[] nonce = new byte[Protocol.GCM_NONCE_LEN];
        RNG.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey key = new SecretKeySpec(key32, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(Protocol.GCM_TAG_LEN * 8, nonce));
        byte[] ct = cipher.doFinal(plaintext);
        byte[] out = new byte[nonce.length + ct.length];
        System.arraycopy(nonce, 0, out, 0, nonce.length);
        System.arraycopy(ct, 0, out, nonce.length, ct.length);
        return out;
    }

    public static byte[] open(byte[] key32, byte[] sealed) throws Exception {
        if (sealed == null || sealed.length < Protocol.GCM_NONCE_LEN + Protocol.GCM_TAG_LEN) {
            throw new IllegalArgumentException("sealed too short");
        }
        byte[] nonce = Arrays.copyOfRange(sealed, 0, Protocol.GCM_NONCE_LEN);
        byte[] ct = Arrays.copyOfRange(sealed, Protocol.GCM_NONCE_LEN, sealed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey key = new SecretKeySpec(key32, "AES");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(Protocol.GCM_TAG_LEN * 8, nonce));
        return cipher.doFinal(ct);
    }
}
