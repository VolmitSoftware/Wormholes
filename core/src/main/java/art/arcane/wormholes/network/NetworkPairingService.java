package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class NetworkPairingService {
    private final NetworkManager network;

    public NetworkPairingService(NetworkManager network) {
        this.network = network;
    }

    public PortalCode portalCode(UUID portalId, String portalName) {
        ServerCode server = serverCode();
        return new PortalCode(server.serverName(), server.advertiseHost(), server.fallbackHosts(),
            server.wormholePort(), server.gameEndpoint(), server.privateGameEndpoint(), server.publicKey(), portalId, portalName);
    }

    public ServerCode serverCode() {
        NetworkConfig config = network.activeConfig();
        String host = config.advertiseHostOverride != null && !config.advertiseHostOverride.isBlank()
            ? config.advertiseHostOverride : network.getAdvertiseHost();
        return new ServerCode(network.getLocalName(), host, alternateHosts(host), network.getBoundListenPort(),
            network.gameEndpoint(), network.localPrivateGameEndpoint(), network.getPublicKey());
    }

    public void importPortal(PortalCode code) {
        importServer(new ServerCode(code.serverName(), code.advertiseHost(), code.fallbackHosts(), code.wormholePort(),
            code.gameEndpoint(), code.privateGameEndpoint(), code.publicKey()));
    }

    public void importServer(ServerCode code) {
        if (code.serverName().equals(network.getLocalName())) {
            throw new IllegalArgumentException("Cannot pair a server with its own identity");
        }
        network.trustPeer(code.serverName(), code.publicKey());
        network.savePeer(routeEntry(code));
    }

    public boolean importProxyServerCode(ServerCode code) {
        if (code == null || code.serverName().equals(network.getLocalName())) {
            return false;
        }
        if (!network.trustIntroducedPeer(code.serverName(), code.publicKey())) {
            return false;
        }
        NetworkConfig.PeerEntry entry = routeEntry(code);
        entry.useProxy = true;
        network.savePeer(entry);
        return true;
    }

    private static NetworkConfig.PeerEntry routeEntry(ServerCode code) {
        NetworkConfig.PeerEntry entry = new NetworkConfig.PeerEntry();
        entry.name = code.serverName();
        entry.host = code.advertiseHost();
        entry.fallbackHosts = joinFallbacks(code.advertiseHost(), code.fallbackHosts());
        entry.port = code.wormholePort();
        entry.publicHost = code.gameEndpoint().host();
        entry.publicPort = code.gameEndpoint().port();
        entry.privateHost = code.privateGameEndpoint() == null ? "" : code.privateGameEndpoint().host();
        entry.privatePort = code.privateGameEndpoint() == null ? 0 : code.privateGameEndpoint().port();
        return entry;
    }

    private List<String> alternateHosts(String identityHost) {
        List<String> alternates = new ArrayList<>(2);
        String publicIp = network.getResolvedPublicHost();
        if (publicIp != null && !publicIp.equals(identityHost)) {
            alternates.add(publicIp);
        }
        String lan = LanAddressResolver.detectLanAddress();
        if (lan != null && !lan.equals(identityHost) && !alternates.contains(lan)) {
            alternates.add(lan);
        }
        return alternates;
    }

    private static String joinFallbacks(String primaryHost, List<String> fallbacks) {
        List<String> filtered = new ArrayList<>(fallbacks.size());
        for (String host : fallbacks) {
            if (host != null && !host.isBlank() && !host.equals(primaryHost) && !filtered.contains(host)) {
                filtered.add(host);
            }
        }
        return String.join(",", filtered);
    }
}
