package protocol;

public final class Frame {
    public final byte version;
    public final byte type;
    public final byte[] payload;

    public Frame(byte type, byte[] payload) {
        this(Protocol.VERSION, type, payload == null ? new byte[0] : payload);
    }

    public Frame(byte version, byte type, byte[] payload) {
        this.version = version;
        this.type = type;
        this.payload = payload == null ? new byte[0] : payload;
    }
}
