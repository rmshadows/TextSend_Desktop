package protocol;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

public final class EcdhP256 {
    private EcdhP256() {
    }

    public static KeyPair generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    /** X9.63 uncompressed: 0x04 || X(32) || Y(32) */
    public static byte[] encodePublic(ECPublicKey pub) {
        ECPoint w = pub.getW();
        byte[] x = toFixed(w.getAffineX(), 32);
        byte[] y = toFixed(w.getAffineY(), 32);
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    public static ECPublicKey decodePublic(byte[] encoded) throws Exception {
        if (encoded == null || encoded.length != 65 || encoded[0] != 0x04) {
            throw new IllegalArgumentException("expect uncompressed P-256 public key");
        }
        byte[] x = Arrays.copyOfRange(encoded, 1, 33);
        byte[] y = Arrays.copyOfRange(encoded, 33, 65);
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecSpec));
    }

    public static byte[] sharedSecret(KeyPair local, ECPublicKey remote) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(local.getPrivate());
        ka.doPhase(remote, true);
        return ka.generateSecret();
    }

    private static byte[] toFixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        if (raw.length == len) {
            return raw;
        }
        if (raw.length == len + 1 && raw[0] == 0) {
            System.arraycopy(raw, 1, out, 0, len);
            return out;
        }
        if (raw.length > len) {
            System.arraycopy(raw, raw.length - len, out, 0, len);
            return out;
        }
        System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        return out;
    }
}
