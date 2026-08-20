package protocol;

import java.security.SecureRandom;

/** 服务端一次启动的配对材料：PSK + PIN */
public final class PairingMaterial {
    private static final SecureRandom RNG = new SecureRandom();

    public final byte[] psk;
    public final String pskB64;
    public final String pin;

    public PairingMaterial(byte[] psk, String pin) {
        this.psk = psk;
        this.pskB64 = B64.enc(psk);
        this.pin = pin;
    }

    public static PairingMaterial generate() {
        byte[] psk = new byte[Protocol.PSK_LEN];
        RNG.nextBytes(psk);
        char[] digits = new char[Protocol.PIN_LEN];
        for (int i = 0; i < Protocol.PIN_LEN; i++) {
            digits[i] = (char) ('0' + RNG.nextInt(10));
        }
        return new PairingMaterial(psk, new String(digits));
    }

    public String toUri(String host, int port) {
        return toPskUri(host, port);
    }

    public String toPskUri(String host, int port) {
        return TsUri.format(host, port, pskB64);
    }

    public String toPinUri(String host, int port) {
        return TsUri.format(host, port, pin);
    }
}
