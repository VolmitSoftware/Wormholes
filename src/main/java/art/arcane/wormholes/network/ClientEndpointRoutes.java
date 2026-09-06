package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ClientEndpointRoutes {
    private final List<Route> routes;

    ClientEndpointRoutes(List<NetworkConfig.ClientRoute> configured) {
        List<Route> compiled = new ArrayList<>(configured.size());
        for (NetworkConfig.ClientRoute entry : configured) {
            compiled.add(compile(entry));
        }
        compiled.sort(Comparator.comparingInt((Route route) -> route.network().prefix()).reversed());
        routes = List.copyOf(compiled);
    }

    public static void validate(List<NetworkConfig.ClientRoute> configured) {
        for (NetworkConfig.ClientRoute entry : configured) {
            compile(entry);
        }
    }

    GameEndpoint resolve(String server, InetSocketAddress client) {
        InetAddress address = client == null ? null : client.getAddress();
        if (address == null) {
            return null;
        }
        for (Route route : routes) {
            if (route.server().equals(server) && route.network().contains(address)) {
                return route.endpoint();
            }
        }
        return null;
    }

    private static Route compile(NetworkConfig.ClientRoute entry) {
        if (entry == null || entry.server == null || entry.server.isBlank()) {
            throw new IllegalArgumentException("Client route must name a destination server");
        }
        if (entry.host == null || entry.host.isBlank()) {
            throw new IllegalArgumentException("Client route must provide a destination game host");
        }
        return new Route(entry.server.trim(), Cidr.parse(entry.clientCidr), new GameEndpoint(entry.host, entry.port));
    }

    private record Route(String server, Cidr network, GameEndpoint endpoint) {
    }

    record Cidr(byte[] address, int prefix) {
        static Cidr parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Client route requires an IP CIDR");
            }
            String[] parts = value.trim().split("/", -1);
            InetAddress address = parts.length == 2 ? GameEndpoint.literal(parts[0]) : null;
            if (address == null) {
                throw new IllegalArgumentException("Client route requires an IP CIDR: " + value);
            }
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > address.getAddress().length * 8) {
                throw new IllegalArgumentException("Invalid client CIDR prefix: " + value);
            }
            return new Cidr(address.getAddress(), prefix);
        }

        boolean contains(InetAddress candidate) {
            byte[] other = candidate.getAddress();
            if (address.length != other.length) {
                return false;
            }
            int complete = prefix / 8;
            for (int index = 0; index < complete; index++) {
                if (address[index] != other[index]) {
                    return false;
                }
            }
            int remaining = prefix % 8;
            int mask = 0xFF << (8 - remaining);
            return remaining == 0 || (address[complete] & mask) == (other[complete] & mask);
        }
    }
}
