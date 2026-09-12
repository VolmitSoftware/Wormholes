package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.GameEndpoint;
import art.arcane.wormholes.network.Handshake;
import art.arcane.wormholes.network.WireCodec;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.PrivateKey;

/**
 * Self-signed peer advertisement flooded through the relay layer. The signature covers every field
 * under the "wormholes:peer-announce:v1" domain and is produced by the announced peer's identity key,
 * so any server can check that the name, endpoints, epoch and capability set were published by the
 * holder of {@link #publicKey()} regardless of which introducer forwarded the frame.
 */
public record PeerAnnounce(
    String name,
    int protocolVersion,
    String pluginVersion,
    String advertiseHost,
    int wormholePort,
    GameEndpoint gameEndpoint,
    GameEndpoint privateGameEndpoint,
    byte[] publicKey,
    long epoch,
    long capabilities,
    long issuedAtMillis,
    byte[] signature
) {
    private static final String SIGNATURE_DOMAIN = "wormholes:peer-announce:v1";
    private static final int MAX_NAME_CHARS = 64;
    private static final int MAX_HOST_CHARS = 255;
    private static final int MAX_VERSION_CHARS = 64;

    public void write(DataOutputStream out) throws IOException {
        writeUnsigned(out);
        WireCodec.writeByteArray(out, signature, Handshake.SIGNATURE_MAX_LENGTH);
    }

    public static PeerAnnounce read(DataInputStream in) throws IOException {
        String name = in.readUTF();
        int protocolVersion = in.readInt();
        String pluginVersion = in.readUTF();
        String advertiseHost = in.readUTF();
        if (name.isBlank() || name.length() > MAX_NAME_CHARS || advertiseHost.length() > MAX_HOST_CHARS || pluginVersion.length() > MAX_VERSION_CHARS) {
            throw new IOException("Invalid peer announce identity");
        }
        int wormholePort = in.readUnsignedShort();
        GameEndpoint gameEndpoint = GameEndpoint.read(in);
        GameEndpoint privateGameEndpoint = GameEndpoint.read(in);
        if (gameEndpoint == null) {
            throw new IOException("Peer announce game endpoint is required");
        }
        byte[] publicKey = WireCodec.readByteArray(in, Handshake.PUBLIC_KEY_MAX_LENGTH);
        long epoch = in.readLong();
        long capabilities = in.readLong();
        long issuedAtMillis = in.readLong();
        byte[] signature = WireCodec.readByteArray(in, Handshake.SIGNATURE_MAX_LENGTH);
        return new PeerAnnounce(name, protocolVersion, pluginVersion, advertiseHost, wormholePort, gameEndpoint, privateGameEndpoint, publicKey, epoch, capabilities, issuedAtMillis, signature);
    }

    public PeerAnnounce signWith(PrivateKey privateKey) {
        return new PeerAnnounce(name, protocolVersion, pluginVersion, advertiseHost, wormholePort, gameEndpoint, privateGameEndpoint, publicKey, epoch, capabilities, issuedAtMillis,
            Handshake.sign(privateKey, unsignedBytes()));
    }

    /** True when the signature was produced by the private half of {@link #publicKey()} over every other field. */
    public boolean verify() {
        if (signature == null || signature.length == 0 || publicKey == null || publicKey.length == 0) {
            return false;
        }
        return Handshake.verify(publicKey, signature, unsignedBytes());
    }

    public String fingerprint() {
        return Handshake.fingerprint(publicKey);
    }

    public NetworkConfig.PeerEntry toRoute() {
        NetworkConfig.PeerEntry route = new NetworkConfig.PeerEntry();
        route.name = name;
        route.host = advertiseHost;
        route.fallbackHosts = "";
        route.port = wormholePort;
        route.publicHost = gameEndpoint == null ? "" : gameEndpoint.host();
        route.publicPort = gameEndpoint == null ? 25565 : gameEndpoint.port();
        route.privateHost = privateGameEndpoint == null ? "" : privateGameEndpoint.host();
        route.privatePort = privateGameEndpoint == null ? 0 : privateGameEndpoint.port();
        return route;
    }

    private void writeUnsigned(DataOutputStream out) throws IOException {
        out.writeUTF(name);
        out.writeInt(protocolVersion);
        out.writeUTF(pluginVersion == null ? "" : pluginVersion);
        out.writeUTF(advertiseHost == null ? "" : advertiseHost);
        out.writeShort(wormholePort);
        GameEndpoint.write(out, gameEndpoint);
        GameEndpoint.write(out, privateGameEndpoint);
        WireCodec.writeByteArray(out, publicKey, Handshake.PUBLIC_KEY_MAX_LENGTH);
        out.writeLong(epoch);
        out.writeLong(capabilities);
        out.writeLong(issuedAtMillis);
    }

    private byte[] unsignedBytes() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF(SIGNATURE_DOMAIN);
            writeUnsigned(out);
            out.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode peer announce", e);
        }
    }
}
