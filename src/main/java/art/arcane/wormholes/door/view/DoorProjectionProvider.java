package art.arcane.wormholes.door.view;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.rtp.RtpProjectionView;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.util.Direction;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feeds door apertures through the RTP projection shape, which is the one path that already lets a
 * portal resolve a different destination per observer without a tunnel.
 *
 * <p>The rim is an RTP-only decoration, so it is switched off and never dispatched.</p>
 */
public final class DoorProjectionProvider implements ProjectionManager.RtpProjectionProvider {
    private static final double APERTURE_WIDTH = 1.0D;

    private final DoorApertureDestinations destinations;
    private final ConcurrentHashMap<RouteKey, Route> routes;
    private final AtomicLong revisions;

    public DoorProjectionProvider(DoorApertureDestinations destinations) {
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        routes = new ConcurrentHashMap<>();
        revisions = new AtomicLong();
    }

    @Override
    public boolean supports(ILocalPortal portal) {
        return portal instanceof DoorProjectionAdapter;
    }

    @Override
    public ProjectionManager.RtpProjectionResult touch(ILocalPortal portal, Player observer) {
        Objects.requireNonNull(portal, "portal");
        UUID observerId = Objects.requireNonNull(observer, "observer").getUniqueId();
        if (!(portal instanceof DoorProjectionAdapter adapter)) {
            return suppressed(observerId);
        }
        Optional<DoorProjectionDestination> resolved = destinations.destinationOf(adapter, observerId);
        if (resolved.isEmpty()) {
            forget(adapter.getId(), observerId);
            return suppressed(observerId);
        }
        DoorProjectionDestination destination = resolved.get();
        long revision = revisionOf(adapter.getId(), observerId, destination);
        RtpProjectionView view = RtpProjectionView.ready(observerId, revision, new RtpProjectionView.ReadyData(
            destination.routeId(), revision, sourceFrame(adapter, revision), target(destination)));
        return new ProjectionManager.RtpProjectionResult(
            view, true, false, true, RtpRotationMode.STATIC, RtpRimRenderer.Phase.READY, 0L, 0L);
    }

    @Override
    public World resolveTargetWorld(String worldKey) {
        return WorldIdentity.resolve(Objects.requireNonNull(worldKey, "worldKey")).orElse(null);
    }

    @Override
    public void dispatchRim(ILocalPortal portal, Player observer, RtpRimRenderer.Sample sample) {
    }

    /** Drops every remembered route so a reload starts from a fresh revision. */
    public void clear() {
        routes.clear();
    }

    public void forget(UUID doorItemId) {
        Objects.requireNonNull(doorItemId, "doorItemId");
        routes.keySet().removeIf(key -> key.doorItemId().equals(doorItemId));
    }

    private void forget(UUID doorItemId, UUID observerId) {
        routes.remove(new RouteKey(doorItemId, observerId));
    }

    /** A route keeps its revision until its destination actually moves or turns. */
    private long revisionOf(UUID doorItemId, UUID observerId, DoorProjectionDestination destination) {
        String signature = destination.signature();
        Route route = routes.compute(new RouteKey(doorItemId, observerId), (ignored, current) ->
            current != null && current.signature().equals(signature)
                ? current
                : new Route(signature, revisions.incrementAndGet()));
        return route.revision();
    }

    private static RtpProjectionView.SourceFrame sourceFrame(DoorProjectionAdapter adapter, long revision) {
        Vector origin = adapter.getOrigin();
        PortalFrame frame = adapter.getFrame();
        return new RtpProjectionView.SourceFrame(
            WorldIdentity.serialize(adapter.getWorld()),
            new RtpProjectionView.Point3(origin.getX(), origin.getY(), origin.getZ()),
            vector(frame.getRight()),
            vector(frame.getUp()),
            vector(frame.getNormal().reverse()),
            APERTURE_WIDTH,
            DoorApertureFrames.height(adapter.plane()),
            revision);
    }

    private static RtpProjectionView.Target target(DoorProjectionDestination destination) {
        Vector origin = destination.origin();
        PortalFrame frame = destination.frame();
        return new RtpProjectionView.Target(
            destination.worldKey(),
            new RtpProjectionView.Point3(origin.getX(), origin.getY(), origin.getZ()),
            vector(frame.getRight()),
            vector(frame.getUp()),
            vector(frame.getNormal().reverse()));
    }

    private static RtpProjectionView.Vector3 vector(Direction direction) {
        return new RtpProjectionView.Vector3(direction.x(), direction.y(), direction.z());
    }

    private static ProjectionManager.RtpProjectionResult suppressed(UUID observerId) {
        return new ProjectionManager.RtpProjectionResult(
            RtpProjectionView.none(observerId, 0L), false, false, false,
            RtpRotationMode.STATIC, RtpRimRenderer.Phase.PREPARING, 0L, 0L);
    }

    private record RouteKey(UUID doorItemId, UUID observerId) {
    }

    private record Route(String signature, long revision) {
    }
}
