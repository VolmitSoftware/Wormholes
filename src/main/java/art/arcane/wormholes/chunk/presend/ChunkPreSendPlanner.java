package art.arcane.wormholes.chunk.presend;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChunkPreSendPlanner {
    private ChunkPreSendPlanner() {
    }

    public static ChunkPreSendPlan plan(ChunkPreSendRequest request) {
        ChunkPreSendRequest active = Objects.requireNonNull(request, "request");
        ChunkPreSendOptions options = active.options();
        if (!options.enabled()) {
            return ChunkPreSendPlan.rejected(ChunkPreSendOutcome.SKIPPED_DISABLED);
        }
        if (!active.platformSupported()) {
            return ChunkPreSendPlan.rejected(ChunkPreSendOutcome.SKIPPED_UNSUPPORTED_PLATFORM);
        }
        if (!active.playerOnline()) {
            return ChunkPreSendPlan.rejected(ChunkPreSendOutcome.SKIPPED_PLAYER_OFFLINE);
        }
        if (!active.destinationLocal()) {
            return ChunkPreSendPlan.rejected(ChunkPreSendOutcome.SKIPPED_CROSS_SERVER);
        }
        if (!options.usable()) {
            return ChunkPreSendPlan.rejected(ChunkPreSendOutcome.SKIPPED_NO_BUDGET);
        }
        if (!active.destinationRegionOwned()) {
            return ChunkPreSendPlan.rejected(ChunkPreSendOutcome.SKIPPED_REGION_NOT_OWNED);
        }
        if (!active.destinationLoaded()) {
            return ChunkPreSendPlan.rejected(ChunkPreSendOutcome.SKIPPED_DESTINATION_UNLOADED);
        }
        int radius = effectiveRadius(options.radiusChunks(), active.clientViewDistance());
        List<ChunkCoordinate> ordered = ring(active.destinationCenterX(), active.destinationCenterZ(), radius);
        boolean truncated = ordered.size() > options.maxChunks();
        List<ChunkCoordinate> selected = truncated
            ? new ArrayList<>(ordered.subList(0, options.maxChunks()))
            : ordered;
        return new ChunkPreSendPlan(
            truncated ? ChunkPreSendOutcome.PRE_SENT_PARTIAL : ChunkPreSendOutcome.PRE_SENT,
            active.destinationCenterX(),
            active.destinationCenterZ(),
            radius,
            selected,
            truncated
        );
    }

    public static int effectiveRadius(int requestedRadius, int clientViewDistance) {
        return Math.max(0, Math.min(requestedRadius, ClientChunkWindow.chunkRadiusFor(clientViewDistance)));
    }

    public static List<ChunkCoordinate> ring(int centerX, int centerZ, int radius) {
        int safeRadius = Math.max(0, radius);
        int range = safeRadius * 2 + 1;
        List<ChunkCoordinate> ordered = new ArrayList<>(range * range);
        for (int offsetX = -safeRadius; offsetX <= safeRadius; offsetX++) {
            for (int offsetZ = -safeRadius; offsetZ <= safeRadius; offsetZ++) {
                ordered.add(new ChunkCoordinate(centerX + offsetX, centerZ + offsetZ));
            }
        }
        ordered.sort(ChunkCoordinate.nearestFirst(centerX, centerZ));
        return ordered;
    }
}
