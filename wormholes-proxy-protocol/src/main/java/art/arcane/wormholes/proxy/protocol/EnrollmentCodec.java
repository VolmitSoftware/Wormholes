package art.arcane.wormholes.proxy.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Plugin-message frames exchanged on the {@value #CHANNEL} channel between a Wormholes backend and the
 * WormholesProxy module. Layout: int protocol, byte frame type, then length-prefixed UTF fields,
 * then a 32-byte HMAC-SHA256 tag over everything before it.
 *
 * <p>The channel is not an authenticated transport: Bukkit hands a backend every payload a client
 * sends on it, so the tag is what separates a proxy frame from a forged one. Every frame is tagged
 * with the shared secret ({@code [network.proxy] secret} on the backend, {@code secret.txt} on the
 * proxy) and a frame that does not verify is refused. Enroll carries a nonce the proxy echoes in its
 * roster so a backend only accepts an answer to an enroll it sent.</p>
 *
 * <p>Shared by both artifacts so neither needs the other on its classpath at runtime.</p>
 */
public final class EnrollmentCodec {
    public static final String CHANNEL = "wormholes:proxy";
    public static final int PROTOCOL = 2;
    public static final int MAX_LIST = 256;
    public static final int TAG_BYTES = 32;
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final byte TYPE_ENROLL = 1;
    private static final byte TYPE_ROSTER = 2;
    private static final byte TYPE_HANDOFF = 3;
    private static final byte TYPE_RECEIPT = 4;

    public sealed interface Frame permits Enroll, Roster, Handoff, Receipt {
    }

    /** Backend to proxy: this server's identity code, negotiated capability set, shareable portal names and a fresh nonce. */
    public record Enroll(String serverName, String serverCode, long capabilities, List<String> portals, UUID nonce) implements Frame {
        public Enroll {
            portals = List.copyOf(portals);
        }
    }

    /** Proxy to backend: every other enrolled backend's server code, echoing the nonce of the enroll it answers. */
    public record Roster(UUID nonce, List<String> serverCodes) implements Frame {
        public Roster {
            serverCodes = List.copyOf(serverCodes);
        }
    }

    /** Backend to proxy: move the sending player to the backend that enrolled as {@code targetServer}. */
    public record Handoff(UUID transferId, String targetServer) implements Frame {
    }

    /** Proxy to the source backend: the connect result for a handoff. */
    public record Receipt(UUID transferId, boolean ok, String detail) implements Frame {
    }

    private EnrollmentCodec() {
    }

    public static byte[] encode(Frame frame, byte[] secret) {
        requireSecret(secret);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(PROTOCOL);
            switch (frame) {
                case Enroll enroll -> {
                    out.writeByte(TYPE_ENROLL);
                    out.writeUTF(enroll.serverName());
                    out.writeUTF(enroll.serverCode());
                    out.writeLong(enroll.capabilities());
                    writeList(out, enroll.portals());
                    writeUuid(out, enroll.nonce());
                }
                case Roster roster -> {
                    out.writeByte(TYPE_ROSTER);
                    writeUuid(out, roster.nonce());
                    writeList(out, roster.serverCodes());
                }
                case Handoff handoff -> {
                    out.writeByte(TYPE_HANDOFF);
                    writeUuid(out, handoff.transferId());
                    out.writeUTF(handoff.targetServer());
                }
                case Receipt receipt -> {
                    out.writeByte(TYPE_RECEIPT);
                    writeUuid(out, receipt.transferId());
                    out.writeBoolean(receipt.ok());
                    out.writeUTF(receipt.detail() == null ? "" : receipt.detail());
                }
            }
            out.flush();
            byte[] body = buffer.toByteArray();
            byte[] tag = tag(body, secret);
            byte[] payload = Arrays.copyOf(body, body.length + TAG_BYTES);
            System.arraycopy(tag, 0, payload, body.length, TAG_BYTES);
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode proxy frame", e);
        }
    }

    /** Decodes a frame whose tag verifies against {@code secret}; anything else is refused. */
    public static Frame decode(byte[] payload, byte[] secret) throws IOException {
        requireSecret(secret);
        if (payload == null || payload.length < 5 + TAG_BYTES) {
            throw new IOException("proxy frame too short");
        }
        int bodyLength = payload.length - TAG_BYTES;
        byte[] expected = tag(Arrays.copyOf(payload, bodyLength), secret);
        if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(payload, bodyLength, payload.length))) {
            throw new IOException("proxy frame is not signed by the configured proxy secret");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload, 0, bodyLength));
        int protocol = in.readInt();
        if (protocol != PROTOCOL) {
            throw new IOException("unsupported proxy frame protocol " + protocol);
        }
        byte type = in.readByte();
        Frame frame = switch (type) {
            case TYPE_ENROLL -> new Enroll(in.readUTF(), in.readUTF(), in.readLong(), readList(in), readUuid(in));
            case TYPE_ROSTER -> new Roster(readUuid(in), readList(in));
            case TYPE_HANDOFF -> new Handoff(readUuid(in), in.readUTF());
            case TYPE_RECEIPT -> new Receipt(readUuid(in), in.readBoolean(), in.readUTF());
            default -> throw new IOException("unknown proxy frame type " + type);
        };
        if (in.available() != 0) {
            throw new IOException("proxy frame has trailing bytes");
        }
        return frame;
    }

    private static byte[] tag(byte[] body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, MAC_ALGORITHM));
            return mac.doFinal(body);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static void requireSecret(byte[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("The proxy channel needs a shared secret");
        }
    }

    private static void writeList(DataOutputStream out, List<String> values) throws IOException {
        if (values.size() > MAX_LIST) {
            throw new IOException("proxy frame list too long: " + values.size());
        }
        out.writeInt(values.size());
        for (String value : values) {
            out.writeUTF(value);
        }
    }

    private static List<String> readList(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_LIST) {
            throw new IOException("invalid proxy frame list length " + count);
        }
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(in.readUTF());
        }
        return values;
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
