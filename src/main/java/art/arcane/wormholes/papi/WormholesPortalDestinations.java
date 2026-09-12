package art.arcane.wormholes.papi;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpPortalRuntime;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpRuntimeSnapshot;
import art.arcane.wormholes.portal.rtp.RtpService;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class WormholesPortalDestinations {
    private static final WormholesPortalDestinations EMPTY = new WormholesPortalDestinations(null, Map.of());

    private final Destination shared;
    private final Map<UUID, Destination> players;

    private WormholesPortalDestinations(Destination shared, Map<UUID, Destination> players) {
        this.shared = shared;
        this.players = Map.copyOf(players);
    }

    public static WormholesPortalDestinations capture(ILocalPortal portal, RtpService.Snapshot rtp) {
        Objects.requireNonNull(portal, "portal");
        if (portal.getType() == PortalType.RTP) {
            return rtp == null || !portal.getId().equals(rtp.portalId()) ? EMPTY : captureRtp(rtp);
        }
        ITunnel tunnel = portal.hasTunnel() ? portal.getTunnel() : null;
        IPortal destination = tunnel == null ? null : tunnel.getDestination();
        Vector origin = destination == null ? null : destination.getOrigin();
        if (origin == null || !Double.isFinite(origin.getX()) || !Double.isFinite(origin.getY()) || !Double.isFinite(origin.getZ())) {
            return EMPTY;
        }
        return new WormholesPortalDestinations(new Destination(
            Integer.toString((int) Math.floor(origin.getX())),
            Integer.toString((int) Math.floor(origin.getY())),
            Integer.toString((int) Math.floor(origin.getZ())),
            false,
            0L), Map.of());
    }

    public String resolve(UUID playerId, String field, long nowMillis) {
        Destination destination = shared == null && playerId != null ? players.get(playerId) : shared;
        if (field == null) {
            return null;
        }
        return switch (field) {
            case "x" -> destination == null ? PlaceholderValues.UNAVAILABLE : destination.x();
            case "y" -> destination == null ? PlaceholderValues.UNAVAILABLE : destination.y();
            case "z" -> destination == null ? PlaceholderValues.UNAVAILABLE : destination.z();
            case "time-remaining" -> destination == null ? PlaceholderValues.UNAVAILABLE : destination.timeRemaining(nowMillis);
            default -> null;
        };
    }

    private static WormholesPortalDestinations captureRtp(RtpService.Snapshot rtp) {
        RtpRuntimeSnapshot runtime = rtp.runtime();
        boolean timed = runtime.rotationMode() == RtpRotationMode.TIMED;
        if (runtime.allocationMode() == RtpAllocationMode.SHARED) {
            return runtime.active() == null ? EMPTY : new WormholesPortalDestinations(
                destination(runtime.active(), timed && (runtime.nextRotationAtMillis() > 0L || runtime.timedRotationPending()),
                    runtime.nextRotationAtMillis()), Map.of());
        }
        Map<UUID, Destination> players = new HashMap<>(rtp.playerDestinations().size());
        for (Map.Entry<UUID, RtpPortalRuntime.PlayerDestination> entry : rtp.playerDestinations().entrySet()) {
            RtpPortalRuntime.PlayerDestination current = entry.getValue();
            players.put(entry.getKey(), destination(current.destination(), timed && current.nextRotationAtMillis() > 0L,
                current.nextRotationAtMillis()));
        }
        return players.isEmpty() ? EMPTY : new WormholesPortalDestinations(null, players);
    }

    private static Destination destination(RtpDestination destination, boolean timed, long nextRotationAtMillis) {
        return new Destination(Integer.toString(destination.blockX()), Integer.toString(destination.feetY()),
            Integer.toString(destination.blockZ()), timed, nextRotationAtMillis);
    }

    private record Destination(String x, String y, String z, boolean timed, long nextRotationAtMillis) {
        private String timeRemaining(long nowMillis) {
            if (!timed) {
                return PlaceholderValues.UNAVAILABLE;
            }
            long remainingMillis = nextRotationAtMillis <= nowMillis ? 0L : nextRotationAtMillis - Math.max(0L, nowMillis);
            long seconds = remainingMillis / 1_000L + (remainingMillis % 1_000L == 0L ? 0L : 1L);
            long days = seconds / 86_400L;
            long hours = seconds / 3_600L % 24L;
            long minutes = seconds / 60L % 60L;
            StringBuilder remaining = new StringBuilder(32);
            if (days > 0L) {
                remaining.append(days).append("d ");
            }
            if (hours > 0L) {
                remaining.append(hours).append("h ");
            }
            if (minutes > 0L) {
                remaining.append(minutes).append("m ");
            }
            return remaining.append(seconds % 60L).append('s').toString();
        }
    }
}
