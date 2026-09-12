package art.arcane.wormholes.ops;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.rtp.BukkitRtpCandidateLoader;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpSafetyResult;
import art.arcane.wormholes.portal.rtp.RtpSafetyValidator;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpValidationRequest;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * Picks where {@code /wormholes admin portals tp} puts the operator: the cell in front of the
 * aperture when the RTP safety validator accepts it, the portal centre otherwise.
 */
public final class PortalSafeLanding {
    private static final double FRONT_OFFSET_BLOCKS = 1.5D;

    /** Where to land, and whether the validator vouched for it. */
    public record Landing(Location location, boolean safe) {
    }

    private PortalSafeLanding() {
    }

    public static CompletableFuture<Landing> choose(ILocalPortal portal) {
        Location center = portal.getCenter();
        Wormholes plugin = Wormholes.instance;
        if (center == null || center.getWorld() == null || plugin == null) {
            return CompletableFuture.completedFuture(new Landing(center, false));
        }
        Location front = front(portal, center);
        World world = center.getWorld();
        RtpDestination destination = new RtpDestination(WorldIdentity.serialize(world),
            front.getBlockX(), front.getBlockY(), front.getBlockZ(), 0L, 0);
        RtpService.SearchRequest request = new RtpService.SearchRequest(portal.getId(), 0L,
            RtpSettings.defaults(world), destination);

        BukkitRtpCandidateLoader loader = new BukkitRtpCandidateLoader(plugin);
        CompletableFuture<Landing> landing = new CompletableFuture<>();
        loader.exact(request, RtpValidationRequest.EntityEnvelope.baseline())
            .whenComplete((candidate, failure) -> {
                try {
                    if (failure != null || candidate == null) {
                        landing.complete(new Landing(center, false));
                        return;
                    }
                    try {
                        RtpSafetyResult result = new RtpSafetyValidator()
                            .validate(candidate.validationRequest()).join();
                        landing.complete(result.safe() ? new Landing(front, true) : new Landing(center, false));
                    } finally {
                        candidate.retention().close();
                    }
                } finally {
                    loader.close();
                }
            });
        return landing;
    }

    private static Location front(ILocalPortal portal, Location center) {
        Direction normal = portal.getFrame() == null ? Direction.N : portal.getFrame().getNormal();
        return center.clone().add(normal.x() * FRONT_OFFSET_BLOCKS, 0.0D, normal.z() * FRONT_OFFSET_BLOCKS);
    }
}
