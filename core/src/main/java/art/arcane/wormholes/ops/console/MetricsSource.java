package art.arcane.wormholes.ops.console;

import java.util.List;
import java.util.Map;

/** Everything the endpoint serves, read from live snapshots without touching the game thread. */
public interface MetricsSource {
    /** One peer link and the labels its series carry. */
    record Peer(String name, String transport, String compression, long rttMillis, boolean connected) {
    }

    /** Integration metric key to current value; unavailable metrics are absent. */
    Map<String, Double> metrics();

    List<Peer> peers();

    /** Failure id to lifetime count. */
    Map<String, Long> failures();
}
