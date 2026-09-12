package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.wormholes.door.view.DoorApertureDestinations;
import art.arcane.wormholes.door.view.DoorProjectionAdapter;
import art.arcane.wormholes.door.view.DoorProjectionDestination;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

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

    DoorApertureDestinationService(
        DoorStateGuard guard,
        DoorRuntimeIndex runtimes,
        PocketStructureService pocketStructures,
        PocketWorldService pocketWorldService
    ) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.pocketStructures = Objects.requireNonNull(pocketStructures, "pocketStructures");
        this.pocketWorldService = Objects.requireNonNull(pocketWorldService, "pocketWorldService");
    }

    @Override
    public Optional<DoorProjectionDestination> destinationOf(DoorProjectionAdapter adapter, UUID observerId) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(observerId, "observerId");
        if (guard.closed()) {
            return Optional.empty();
        }
        PlacedDoorEndpoint endpoint = adapter.endpoint();
        return switch (endpoint.identity().kind()) {
            case PAIR -> mate(adapter, endpoint);
            case PERSONAL -> pocketEntry(PocketBinding.personal(observerId));
            case PUBLIC -> pocketEntry(PocketBinding.publicDoor(endpoint.identity().itemId()));
            case RETURN -> ticket(observerId);
        };
    }

    private Optional<DoorProjectionDestination> mate(DoorProjectionAdapter adapter, PlacedDoorEndpoint endpoint) {
        Optional<PlacedDoorEndpoint> mate = guard.state().findMate(endpoint.identity());
        if (mate.isEmpty()) {
            return Optional.empty();
        }
        PlacedDoorEndpoint placed = mate.get();
        RuntimeDoor runtime = runtimes.runtime(placed.identity().itemId());
        DoorwayPlane matePlane = runtime == null ? null : runtime.plane();
        World world = runtimes.world(placed.position());
        if (matePlane == null || world == null) {
            return Optional.empty();
        }
        DoorVec3 center = matePlane.center();
        return Optional.of(new DoorProjectionDestination(
            placed.identity().itemId(),
            WorldIdentity.serialize(world),
            new Vector(center.x(), center.y(), center.z()),
            DoorApertureFrames.destinationFrame(adapter.plane(), matePlane)));
    }

    private Optional<DoorProjectionDestination> pocketEntry(PocketBinding binding) {
        Optional<World> world = pocketWorldService.world();
        Optional<PocketSpace> space = guard.state().findPocket(binding);
        if (world.isEmpty() || space.isEmpty()) {
            return Optional.empty();
        }
        Location entry = pocketStructures.entryLocation(world.get(), space.get());
        return Optional.of(new DoorProjectionDestination(
            space.get().spaceId(),
            WorldIdentity.serialize(world.get()),
            entry.toVector(),
            PortalFrame.fromNormalUp(
                DoorApertureFrames.normalOf(PocketStructureService.RETURN_DOOR_FACING), Direction.U)));
    }

    /**
     * A return door shows where its own traveler will land, looking the way they will be facing, so
     * two players standing at one return door see their own way home.
     */
    private Optional<DoorProjectionDestination> ticket(UUID observerId) {
        Optional<ReturnTicket> ticket = guard.state().getReturnTicket(observerId);
        if (ticket.isEmpty()) {
            return Optional.empty();
        }
        ReturnTicket found = ticket.get();
        return Optional.of(new DoorProjectionDestination(
            found.sourceEndpointId(),
            found.sourceWorldKey(),
            new Vector(found.x(), found.y(), found.z()),
            PortalFrame.fromNormalUp(lookDirection(found.yaw()).reverse(), Direction.U)));
    }

    private static Direction lookDirection(float yaw) {
        double radians = Math.toRadians(yaw);
        return Direction.closest(-Math.sin(radians), 0.0D, Math.cos(radians));
    }
}
