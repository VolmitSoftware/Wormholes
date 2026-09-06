package art.arcane.wormholes.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public record ServerCode(
    String serverName,
    String advertiseHost,
    List<String> fallbackHosts,
    int wormholePort,
    GameEndpoint gameEndpoint,
    GameEndpoint privateGameEndpoint,
    String publicKey
) {
    public static final String PREFIX = "WHS2.";
    private static final int MAX_CODE_LENGTH = 2048;
    private static final int MAX_FALLBACK_HOSTS = 4;

    public String encode() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(160);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeUTF(serverName);
            out.writeUTF(advertiseHost);
            out.writeByte(Math.min(fallbackHosts.size(), MAX_FALLBACK_HOSTS));
            for (int i = 0; i < Math.min(fallbackHosts.size(), MAX_FALLBACK_HOSTS); i++) {
                out.writeUTF(fallbackHosts.get(i));
            }
            out.writeShort(wormholePort);
            List<String> hosts = new ArrayList<>(Math.min(fallbackHosts.size(), MAX_FALLBACK_HOSTS) + 1);
            hosts.add(advertiseHost);
            hosts.addAll(fallbackHosts.subList(0, Math.min(fallbackHosts.size(), MAX_FALLBACK_HOSTS)));
            GameEndpoint.writeCode(out, gameEndpoint, hosts);
            GameEndpoint.writeCode(out, privateGameEndpoint, hosts);
            out.writeUTF(publicKey);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode server code", e);
        }
    }

    public static ServerCode decode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        if (!trimmed.startsWith(PREFIX) || trimmed.length() > MAX_CODE_LENGTH) {
            return null;
        }
        try {
            byte[] data = Base64.getUrlDecoder().decode(trimmed.substring(PREFIX.length()));
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            String serverName = in.readUTF();
            String advertiseHost = in.readUTF();
            int fallbackCount = in.readUnsignedByte();
            if (fallbackCount > MAX_FALLBACK_HOSTS) {
                return null;
            }
            List<String> fallbackHosts = new ArrayList<>(fallbackCount);
            for (int i = 0; i < fallbackCount; i++) {
                fallbackHosts.add(in.readUTF());
            }
            int wormholePort = in.readUnsignedShort();
            List<String> hosts = new ArrayList<>(fallbackCount + 1);
            hosts.add(advertiseHost);
            hosts.addAll(fallbackHosts);
            GameEndpoint gameEndpoint = GameEndpoint.readCode(in, hosts);
            GameEndpoint privateGameEndpoint = GameEndpoint.readCode(in, hosts);
            String publicKey = in.readUTF();
            if (in.available() > 0) {
                return null;
            }
            if (serverName.isBlank() || advertiseHost.isBlank() || wormholePort <= 0 || gameEndpoint == null || Handshake.decodePublicKeyText(publicKey) == null) {
                return null;
            }
            new GameEndpoint(advertiseHost, wormholePort);
            for (String fallback : fallbackHosts) {
                new GameEndpoint(fallback, wormholePort);
            }
            return new ServerCode(serverName, advertiseHost, List.copyOf(fallbackHosts), wormholePort, gameEndpoint, privateGameEndpoint, publicKey);
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }
}
