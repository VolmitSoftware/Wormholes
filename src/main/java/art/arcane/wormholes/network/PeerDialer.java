package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PeerDialer {
    static final long SCAN_INTERVAL_MS = 2_000L;

    private static final long BACKOFF_BASE_MS = 1_000L;
    private static final long BACKOFF_MAX_MS = 30_000L;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final NetworkManager network;
    private final Path dataDirectory;
    private final Map<String, Long> nextDialAttempt = new ConcurrentHashMap<>();
    private final Map<String, Integer> dialFailures = new ConcurrentHashMap<>();
    private final Map<String, Integer> dialCandidateIndex = new ConcurrentHashMap<>();
    private final Map<String, String> lastDialError = new ConcurrentHashMap<>();

    PeerDialer(NetworkManager network, Path dataDirectory) {
        this.network = network;
        this.dataDirectory = dataDirectory;
    }

    String lastError(String peerName) {
        return lastDialError.get(peerName);
    }

    void setLastError(String peerName, String message) {
        lastDialError.put(peerName, message);
    }

    void clearLastError(String peerName) {
        lastDialError.remove(peerName);
    }

    void clearFailures(String peerName) {
        dialFailures.remove(peerName);
        lastDialError.remove(peerName);
    }

    void resetDialState(String peerName) {
        nextDialAttempt.remove(peerName);
        dialFailures.remove(peerName);
        lastDialError.remove(peerName);
    }

    void registerFailure(String peerName) {
        int failures = dialFailures.merge(peerName, 1, Integer::sum);
        long backoff = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << Math.min(10, failures - 1));
        nextDialAttempt.put(peerName, System.currentTimeMillis() + backoff);
    }

    void scan() {
        NetworkConfig active = network.activeConfig();
        long now = System.currentTimeMillis();
        String localName = network.getLocalName();
        for (NetworkConfig.PeerEntry peer : network.directory().all()) {
            if (peer.name == null || peer.name.isBlank() || peer.name.equals(localName) || !PeerDirectory.isDialable(peer)) {
                continue;
            }
            if (network.links().isRawReady(peer.name) || network.links().hasPendingDial(peer.name)) {
                continue;
            }
            long notBefore = nextDialAttempt.getOrDefault(peer.name, 0L);
            if (now < notBefore) {
                continue;
            }
            dial(active, peer);
        }
    }

    private void dial(NetworkConfig active, NetworkConfig.PeerEntry peer) {
        List<String> candidates = dialCandidates(peer);
        if (candidates.isEmpty()) {
            lastDialError.put(peer.name, "no route host available");
            registerFailure(peer.name);
            return;
        }
        int index = dialCandidateIndex.getOrDefault(peer.name, 0) % candidates.size();
        String host = candidates.get(index);
        if (active.transport.udsEnabled && hostResolvesToLoopback(host)) {
            PeerTransport.PeerChannel udsChannel = tryDialUds(active, peer);
            if (udsChannel != null) {
                network.startDialedConnection(udsChannel, peer.name);
                return;
            }
        }
        try {
            PeerTransport.PeerChannel channel = TcpPeerTransport.dialDirect(host, peer.port, CONNECT_TIMEOUT_MS);
            network.startDialedConnection(channel, peer.name);
        } catch (IOException e) {
            lastDialError.put(peer.name, host + ":" + peer.port + " - " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            dialCandidateIndex.put(peer.name, index + 1);
            registerFailure(peer.name);
        }
    }

    private PeerTransport.PeerChannel tryDialUds(NetworkConfig active, NetworkConfig.PeerEntry peer) {
        Path socketPath = peerSocketPath(active, peer);
        if (socketPath == null || !Files.exists(socketPath)) {
            return null;
        }
        try {
            return UnixDomainPeerTransport.dial(socketPath);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private Path udsDirectory(NetworkConfig active) {
        String configured = active.transport.udsDir;
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return dataDirectory.resolve("uds");
    }

    Path localSocketPath(NetworkConfig active) {
        return udsDirectory(active).resolve("peer-" + sanitizeForFile(network.getLocalName()) + ".sock");
    }

    private Path peerSocketPath(NetworkConfig active, NetworkConfig.PeerEntry peer) {
        if (peer == null || peer.name == null || peer.name.isBlank()) {
            return null;
        }
        return udsDirectory(active).resolve("peer-" + sanitizeForFile(peer.name) + ".sock");
    }

    private static String sanitizeForFile(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean safe = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.';
            builder.append(safe ? ch : '_');
        }
        return builder.toString();
    }

    private static boolean hostResolvesToLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static List<String> dialCandidates(NetworkConfig.PeerEntry peer) {
        List<String> candidates = new ArrayList<>(3);
        if (peer.host != null && !peer.host.isBlank()) {
            candidates.add(peer.host);
        }
        if (peer.fallbackHosts != null && !peer.fallbackHosts.isBlank()) {
            for (String host : peer.fallbackHosts.split("\\s*,\\s*")) {
                if (!host.isBlank() && !candidates.contains(host)) {
                    candidates.add(host);
                }
            }
        }
        return candidates;
    }
}
