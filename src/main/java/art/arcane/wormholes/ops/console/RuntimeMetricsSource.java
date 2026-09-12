package art.arcane.wormholes.ops.console;

import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.service.FailureRegistry;
import art.arcane.wormholes.service.WormholesIntegrationService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Live metric values read from the integration service, the peer table, and the failure registry. */
public final class RuntimeMetricsSource implements MetricsSource {
    private final WormholesIntegrationService integration = new WormholesIntegrationService();

    @Override
    public Map<String, Double> metrics() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, IntegrationMetricSample> sample : integration.sampleMetrics(null).entrySet()) {
            IntegrationMetricSample value = sample.getValue();
            if (value != null && value.available() && value.numericValue() != null) {
                values.put(sample.getKey(), value.numericValue());
            }
        }
        return values;
    }

    @Override
    public List<Peer> peers() {
        NetworkManager network = Wormholes.networkManager;
        if (network == null) {
            return List.of();
        }
        List<NetworkManager.PeerSnapshot> snapshots = network.peerSnapshots();
        List<Peer> peers = new ArrayList<>(snapshots.size());
        for (NetworkManager.PeerSnapshot snapshot : snapshots) {
            peers.add(new Peer(snapshot.name(), snapshot.transport(), snapshot.compressionMode(),
                snapshot.rttMillis(), snapshot.handshakeComplete() && !snapshot.disconnected()));
        }
        return peers;
    }

    @Override
    public Map<String, Long> failures() {
        return FailureRegistry.counts();
    }
}
