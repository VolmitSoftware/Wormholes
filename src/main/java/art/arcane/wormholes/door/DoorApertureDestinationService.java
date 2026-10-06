package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.wormholes.door.view.DoorApertureDestinations;
import art.arcane.wormholes.door.view.DoorProjectionAdapter;
import art.arcane.wormholes.door.view.DoorProjectionDestination;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import art.arcane.optics.math.Face;
import org.bukkit.World;
import art.arcane.optics.math.Vec3d;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves where each kind of door aperture looks, using the same lookups a traveler goes through.
 *
 * <p>A pair door shows its mate, a personal or public door the pocket entry, and a return door the
 * spot the observer's own ticket will put them back down on.</p>
 */
final class DoorApertureDestinationService implements DoorApertureDestinations {
    private final DoorStateGuard guard;
    private final DoorRuntimeIndex runtimes;
    private final PocketStructureService pocketStructures;
    private final PocketWorldService pocketWorldService;
    private final DoorTransitCoordinator transits;

    DoorApertureDestinationService(
        DoorStateGuard guard,
        DoorRuntimeIndex runtimes,
        PocketStructureService pocketStructures,
        PocketWorldService pocketWorldService,
        DoorTransitCoordinator transits
    ) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.pocketStructures = Objects.requireNonNull(pocketStructures, "pocketStructures");
        this.pocketWorldService = Objects.requireNonNull(pocketWorldService, "pocketWorldService");
        this.transits = Objects.requireNonNull(transits, "transits");
    }

    @Override
    public Optional<DoorProjectionDestination> destinationOf(DoorProjectionAdapter adapter, UUID observerId, boolean bypass) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(observerId, "observerId");
        if (guard.closed()) {
            return Optional.empty();
        }
        PlacedDoorEndpoint endpoint = adapter.endpoint();
        if (endpoint.identity().kind() != DoorKind.RETURN
            && !DoorAccessPolicy.canUse(guard.state().accessRecord(endpoint.identity().itemId()).orElse(null), observerId, bypass)) {
            return Optional.empty();
        }
        return switch (endpoint.identity().kind()) {
            case PAIR -> mate(adapter, endpoint);
            case PERSONAL, PUBLIC -> pocketEntry(adapter, observerId);
            case RETURN -> ticket(adapter, observerId);
        };
    }

    private Optional<DoorProjectionDestination> mate(DoorProjectionAdapter adapter, PlacedDoorEndpoint endpoint) {
        Optional<PlacedDoorEndpoint> mate = guard.state().findMate(endpoint.identity());
        if (mate.isEmpty()) {
            return Optional.empty();
        }
        return endpoint(adapter, mate.get());
    }

    private Optional<DoorProjectionDestination> endpoint(DoorProjectionAdapter adapter, PlacedDoorEndpoint placed) {
        RuntimeDoor runtime = runtimes.runtime(placed.identity().itemId());
        DoorwayPlane plane = runtime == null ? null : runtime.projectionPlane();
        World world = runtimes.world(placed.position());
        if (plane == null || world == null) {
            return Optional.empty();
        }
        DoorVec3 center = plane.center();
        return Optional.of(new DoorProjectionDestination(placed.identity().itemId(), WorldIdentity.serialize(world),
            new Vec3d(center.x(), center.y(), center.z()), DoorApertureFrames.destinationFrame(adapter.plane(), plane)));
    }

    private Optional<DoorProjectionDestination> pocketEntry(DoorProjectionAdapter adapter, UUID observerId) {
        PocketDoorDestination destination = (PocketDoorDestination) guard.state().resolveDestination(adapter.endpoint().identity(), observerId);
        PocketBinding binding = destination.binding();
        PocketSpace space = guard.state().findPocket(binding).orElse(null);
        if (pocketWorldService.world().isEmpty() || !transits.allowsPocketProjection(adapter.endpoint(), space)
            || space != null && guard.pocketQuarantined(space.spaceId())) {
            return Optional.empty();
        }
        if (transits.preparingProjection(binding)) {
            return Optional.empty();
        }
        if (space != null) {
            UUID returnId = pocketStructures.layout(space).returnDoorIdentity().itemId();
            Optional<DoorProjectionDestination> resolved = guard.state().findEndpointByItem(returnId)
                .flatMap(placed -> endpoint(adapter, placed));
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        transits.prepareProjection(adapter.endpoint(), observerId);
        return Optional.empty();
    }

    /**
     * A return door shows where its own traveler will land, looking the way they will be facing, so
     * two players standing at one return door see their own way home.
     */
    private Optional<DoorProjectionDestination> ticket(DoorProjectionAdapter adapter, UUID observerId) {
        ReturnTicket found = guard.state().getReturnTicket(observerId).orElse(null);
        if (found == null) {
            return Optional.empty();
        }
        PlacedDoorEndpoint current = guard.state().findEndpointByItem(found.sourceEndpointId()).orElse(null);
        if (current != null && DoorTransitCoordinator.canRouteReturnToCurrentEndpoint(current, runtimes.world(current.position()))) {
            Optional<DoorProjectionDestination> resolved = endpoint(adapter, current);
            if (resolved.isEmpty()) {
                transits.prepareEndpointProjection(current);
            }
            return resolved;
        }
        return Optional.of(new DoorProjectionDestination(found.sourceEndpointId(), found.sourceWorldKey(),
            new Vec3d(found.x(), found.y() + DoorApertureFrames.height(adapter.plane()) * 0.5D, found.z()),
            Frame.fromNormalUp(lookDirection(found.yaw()).reverse(), Face.U)));
    }

    private static Face lookDirection(float yaw) {
        double radians = Math.toRadians(yaw);
        return Face.closest(-Math.sin(radians), 0.0D, Math.cos(radians));
    }
}
