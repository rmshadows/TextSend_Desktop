package protocol;

import java.nio.ByteBuffer;
import java.util.UUID;

final class FileIds {
    private FileIds() {
    }

    static String random() {
        UUID u = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.allocate(Protocol.FILE_ID_LEN);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return toHex(bb.array());
    }

    static byte[] toBytes(String hex) {
        if (hex == null || hex.length() != Protocol.FILE_ID_LEN * 2) {
            throw new IllegalArgumentException("bad fileId");
        }
        byte[] out = new byte[Protocol.FILE_ID_LEN];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad fileId");
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }

    static String toHex(byte[] raw) {
        if (raw == null || raw.length != Protocol.FILE_ID_LEN) {
            throw new IllegalArgumentException("bad fileId bytes");
        }
        char[] c = new char[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int v = raw[i] & 0xff;
            c[i * 2] = "0123456789abcdef".charAt(v >>> 4);
            c[i * 2 + 1] = "0123456789abcdef".charAt(v & 0xf);
        }
        return new String(c);
    }

    static String shaHex(byte[] digest) {
        char[] c = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xff;
            c[i * 2] = "0123456789abcdef".charAt(v >>> 4);
            c[i * 2 + 1] = "0123456789abcdef".charAt(v & 0xf);
        }
        return new String(c);
    }
}
