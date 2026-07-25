package art.arcane.wormholes.papi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PortalProximityIndex {
    public static final double FACING_COSINE = 0.94D;

    private final Map<UUID, Match> best = new HashMap<>();

    public void offer(UUID playerId, int portalIndex, double distanceSquared, double facingCosine) {
        if (playerId == null || portalIndex < 0 || distanceSquared < 0.0D) {
            return;
        }

        Match candidate = new Match(portalIndex, distanceSquared, facingCosine >= FACING_COSINE);
        Match incumbent = best.get(playerId);

        if (incumbent == null || better(candidate, incumbent)) {
            best.put(playerId, candidate);
        }
    }

    public Match match(UUID playerId) {
        return playerId == null ? null : best.get(playerId);
    }

    public int size() {
        return best.size();
    }

    public static boolean better(Match candidate, Match incumbent) {
        if (candidate.facing() != incumbent.facing()) {
            return candidate.facing();
        }

        if (candidate.distanceSquared() != incumbent.distanceSquared()) {
            return candidate.distanceSquared() < incumbent.distanceSquared();
        }

        return candidate.portalIndex() < incumbent.portalIndex();
    }

    public static double facingCosine(float yaw, float pitch, double toPortalX, double toPortalY, double toPortalZ, double distanceSquared) {
        if (distanceSquared <= 0.0D) {
            return 1.0D;
        }

        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRadians);
        double lookX = -cosPitch * Math.sin(yawRadians);
        double lookY = -Math.sin(pitchRadians);
        double lookZ = cosPitch * Math.cos(yawRadians);

        return ((lookX * toPortalX) + (lookY * toPortalY) + (lookZ * toPortalZ)) / Math.sqrt(distanceSquared);
    }

    public record Match(int portalIndex, double distanceSquared, boolean facing) {
    }
}
