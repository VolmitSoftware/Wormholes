package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.Handshake;
import art.arcane.wormholes.network.WireCodec;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.PrivateKey;

/**
 * Network-wide removal of a peer. {@link #publicKey()} is the removed peer's key as the issuer knew
 * it, so a receiver only forgets a peer whose trusted key matches; the signature is the issuer's
 * (the removed peer itself or any trusted member) over the "wormholes:peer-tombstone:v1" domain and
 * is checked against the issuer's trusted key by the receiver. Announces from the removed name at
 * or below {@link #epoch()} are ignored until the tombstone expires.
 */
public record PeerTombstone(String name, long epoch, byte[] publicKey, long issuedAtMillis, byte[] signature) {
    private static final String SIGNATURE_DOMAIN = "wormholes:peer-tombstone:v1";
    private static final int MAX_NAME_CHARS = 64;

    public void write(DataOutputStream out) throws IOException {
        writeUnsigned(out);
        WireCodec.writeByteArray(out, signature, Handshake.SIGNATURE_MAX_LENGTH);
    }

    public static PeerTombstone read(DataInputStream in) throws IOException {
        String name = in.readUTF();
        if (name.isBlank() || name.length() > MAX_NAME_CHARS) {
            throw new IOException("Invalid peer tombstone name");
        }
        long epoch = in.readLong();
        byte[] publicKey = WireCodec.readByteArray(in, Handshake.PUBLIC_KEY_MAX_LENGTH);
        long issuedAtMillis = in.readLong();
        byte[] signature = WireCodec.readByteArray(in, Handshake.SIGNATURE_MAX_LENGTH);
        return new PeerTombstone(name, epoch, publicKey, issuedAtMillis, signature);
    }

    public PeerTombstone signWith(PrivateKey issuerKey) {
        return new PeerTombstone(name, epoch, publicKey, issuedAtMillis, Handshake.sign(issuerKey, unsignedBytes()));
    }

    public boolean verify(byte[] issuerPublicKey) {
        if (signature == null || signature.length == 0) {
            return false;
        }
        return Handshake.verify(issuerPublicKey, signature, unsignedBytes());
    }

    private void writeUnsigned(DataOutputStream out) throws IOException {
        out.writeUTF(name);
        out.writeLong(epoch);
        WireCodec.writeByteArray(out, publicKey == null ? new byte[0] : publicKey, Handshake.PUBLIC_KEY_MAX_LENGTH);
        out.writeLong(issuedAtMillis);
    }

    private byte[] unsignedBytes() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF(SIGNATURE_DOMAIN);
            writeUnsigned(out);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode peer tombstone", e);
        }
    }
}
