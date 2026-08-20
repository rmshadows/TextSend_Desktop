package protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class FrameIO {
    private FrameIO() {
    }

    public static void write(OutputStream out, byte type, byte[] payload) throws IOException {
        if (payload == null) {
            payload = new byte[0];
        }
        if (payload.length > Protocol.MAX_FRAME) {
            throw new IOException("payload too large: " + payload.length);
        }
        ByteBuffer header = ByteBuffer.allocate(Protocol.HEADER_LEN);
        header.put(Protocol.MAGIC);
        header.put(Protocol.VERSION);
        header.put(type);
        header.putInt(payload.length);
        out.write(header.array());
        if (payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }

    public static Frame read(InputStream in) throws IOException {
        byte[] header = readFully(in, Protocol.HEADER_LEN);
        if (header[0] != Protocol.MAGIC[0] || header[1] != Protocol.MAGIC[1]) {
            throw new IOException("bad magic");
        }
        byte version = header[2];
        byte type = header[3];
        int len = ByteBuffer.wrap(header, 4, 4).getInt();
        if (len < 0 || len > Protocol.MAX_FRAME) {
            throw new IOException("bad length: " + len);
        }
        byte[] payload = len == 0 ? new byte[0] : readFully(in, len);
        return new Frame(version, type, payload);
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new EOFException("unexpected EOF");
            }
            off += r;
        }
        return buf;
    }
}
