package art.arcane.wormholes.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.util.Locale;
import java.util.List;
import java.util.Objects;

public record GameEndpoint(String host, int port) {
    public GameEndpoint {
        host = normalizeHost(host);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Game port must be between 1 and 65535");
        }
    }

    public static GameEndpoint optional(String host, int port) {
        return host == null || host.isBlank() || port == 0 ? null : new GameEndpoint(host, port);
    }

    static GameEndpoint read(DataInputStream input) throws IOException {
        String host = input.readUTF();
        int port = input.readUnsignedShort();
        if (host.isEmpty() && port == 0) {
            return null;
        }
        try {
            return new GameEndpoint(host, port);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid game endpoint", error);
        }
    }

    static void write(DataOutputStream output, GameEndpoint endpoint) throws IOException {
        output.writeUTF(endpoint == null ? "" : endpoint.host());
        output.writeShort(endpoint == null ? 0 : endpoint.port());
    }

    static void writeCode(DataOutputStream output, GameEndpoint endpoint, List<String> hosts) throws IOException {
        if (endpoint == null) {
            output.writeByte(0);
            return;
        }
        int index = hosts.indexOf(endpoint.host());
        output.writeByte(index < 0 ? 255 : index + 1);
        if (index < 0) {
            output.writeUTF(endpoint.host());
        }
        output.writeShort(endpoint.port());
    }

    static GameEndpoint readCode(DataInputStream input, List<String> hosts) throws IOException {
        int index = input.readUnsignedByte();
        if (index == 0) {
            return null;
        }
        if (index != 255 && index > hosts.size()) {
            throw new IOException("Invalid endpoint host reference");
        }
        String host = index == 255 ? input.readUTF() : hosts.get(index - 1);
        try {
            return new GameEndpoint(host, input.readUnsignedShort());
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid endpoint", error);
        }
    }

    static InetAddress literal(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        if (host.equalsIgnoreCase("localhost") || host.equalsIgnoreCase("localhost.")) {
            return InetAddress.getLoopbackAddress();
        }
        try {
            return InetAddress.ofLiteral(host);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public String display() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    private static String normalizeHost(String value) {
        String host = Objects.requireNonNull(value, "Game host").trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > 253 || host.indexOf('%') >= 0) {
            throw new IllegalArgumentException("Invalid game host");
        }
        InetAddress literal = literal(host);
        if (literal != null) {
            if (literal.isAnyLocalAddress() || literal.isMulticastAddress()) {
                throw new IllegalArgumentException("Game host must identify a unicast destination");
            }
            return host.toLowerCase(Locale.ROOT);
        }
        if (host.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Game host and port must be configured separately");
        }
        return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }
}
