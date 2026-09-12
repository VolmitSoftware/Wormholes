package art.arcane.wormholes.access;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.adapters.ClaimAdapter;
import art.arcane.wormholes.access.adapters.GriefPreventionAdapter;
import art.arcane.wormholes.access.adapters.LandsAdapter;
import art.arcane.wormholes.access.adapters.PlotSquaredAdapter;
import art.arcane.wormholes.access.adapters.ReflectiveEnvironment;
import art.arcane.wormholes.access.adapters.TownyAdapter;
import art.arcane.wormholes.access.adapters.WorldGuardAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the configured claim adapters in order. A refusal from any of them answers immediately. A
 * present-but-broken plugin fails closed, because an operator who installed a protection plugin
 * wants a portal refused rather than silently allowed, but the rest of the chain still runs so one
 * broken adapter cannot mask a real refusal or hide its own siblings from the log.
 */
public final class ClaimAdapters implements PortalPlacementPolicy {
    private final Map<String, ClaimAdapter> registry = new LinkedHashMap<>();
    private final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();
    private volatile List<ClaimAdapter> enabled = List.of();

    public ClaimAdapters(ReflectiveEnvironment environment) {
        this(List.of(
            new WorldGuardAdapter(environment),
            new GriefPreventionAdapter(environment),
            new TownyAdapter(environment),
            new LandsAdapter(environment),
            new PlotSquaredAdapter(environment)));
    }

    ClaimAdapters(List<ClaimAdapter> adapters) {
        for (ClaimAdapter adapter : adapters) {
            registry.put(adapter.id(), adapter);
        }
    }

    /** Re-reads the comma-separated adapter list; unknown tokens are reported once and skipped. */
    public void configure(String adapterList, boolean worldGuardFlagsEnabled) {
        List<ClaimAdapter> selected = new ArrayList<>();
        for (String token : (adapterList == null ? "" : adapterList).split(",")) {
            String id = token.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            ClaimAdapter adapter = registry.get(id);
            if (adapter == null) {
                Wormholes.w("access: unknown claim adapter " + id + "; known adapters are " + registry.keySet());
                continue;
            }
            selected.add(adapter);
        }
        for (ClaimAdapter adapter : registry.values()) {
            if (adapter instanceof WorldGuardAdapter worldGuard) {
                worldGuard.setCustomFlagsEnabled(worldGuardFlagsEnabled);
            }
            adapter.invalidate();
        }
        reportedFailures.clear();
        enabled = List.copyOf(selected);
    }

    public List<String> enabledIds() {
        List<String> ids = new ArrayList<>(enabled.size());
        for (ClaimAdapter adapter : enabled) {
            ids.add(adapter.id());
        }
        return List.copyOf(ids);
    }

    /** Drops cached reflection for a plugin that just disabled, so a reload re-resolves it. */
    public void invalidate(String pluginName) {
        for (ClaimAdapter adapter : registry.values()) {
            if (adapter.pluginName().equals(pluginName)) {
                adapter.invalidate();
            }
        }
        reportedFailures.remove(pluginName);
    }

    @Override
    public PlacementDecision evaluate(PlacementRequest request) {
        Objects.requireNonNull(request, "request");
        List<ClaimAdapter> adapters = enabled;
        if (adapters.isEmpty() || request.cells().isEmpty()) {
            return PlacementDecision.allowedResult();
        }
        List<int[]> perChunk = null;
        PlacementDecision firstFailure = null;
        for (ClaimAdapter adapter : adapters) {
            PlacementRequest scoped = request;
            if (!adapter.perCell()) {
                if (perChunk == null) {
                    perChunk = oneCellPerChunk(request.cells());
                }
                scoped = request.withCells(perChunk);
            }
            PlacementDecision decision = adapter.evaluate(scoped);
            if (decision.status() == PlacementDecision.Status.DENIED) {
                return decision;
            }
            if (decision.status() == PlacementDecision.Status.FAILURE) {
                reportFailure(decision);
                if (firstFailure == null) {
                    firstFailure = decision;
                }
            }
        }
        return firstFailure == null ? PlacementDecision.allowedResult() : firstFailure;
    }

    /** A 4096-cell selection spans at most 32 chunks, so chunk-granular plugins answer 32 questions. */
    static List<int[]> oneCellPerChunk(List<int[]> cells) {
        Map<Long, int[]> representatives = new LinkedHashMap<>();
        for (int[] cell : cells) {
            long chunkKey = (((long) (cell[0] >> 4)) << 32) ^ (cell[2] >> 4) & 0xFFFFFFFFL;
            representatives.putIfAbsent(chunkKey, cell);
        }
        return List.copyOf(representatives.values());
    }

    private void reportFailure(PlacementDecision decision) {
        if (!reportedFailures.add(decision.plugin())) {
            return;
        }
        Throwable failure = decision.failure().orElse(null);
        Wormholes.w("access: " + decision.plugin() + " claim check failed closed: "
            + (failure == null ? "unknown" : failure.getClass().getSimpleName() + " " + failure.getMessage()));
    }
}
