package art.arcane.wormholes.network.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Latest {@link LoadBeacon} per peer with the local receive time, so staleness never trusts remote clocks. */
public final class PeerLoadTable {
    private record Sample(LoadBeacon beacon, long receivedAtMillis) {
    }

    private final Map<String, Sample> samples = new ConcurrentHashMap<>();

    public void record(String peerName, LoadBeacon beacon, long nowMillis) {
        if (peerName == null || peerName.isBlank() || beacon == null) {
            return;
        }
        samples.put(peerName, new Sample(beacon, nowMillis));
    }

    public LoadBeacon latest(String peerName) {
        Sample sample = peerName == null ? null : samples.get(peerName);
        return sample == null ? null : sample.beacon();
    }

    /** True when no beacon arrived within {@code staleMillis}; unknown peers are stale. */
    public boolean isStale(String peerName, long nowMillis, long staleMillis) {
        Sample sample = peerName == null ? null : samples.get(peerName);
        return sample == null || nowMillis - sample.receivedAtMillis() > staleMillis;
    }

    public List<String> peers() {
        return new ArrayList<>(samples.keySet());
    }

    public void forget(String peerName) {
        if (peerName != null) {
            samples.remove(peerName);
        }
    }
}
