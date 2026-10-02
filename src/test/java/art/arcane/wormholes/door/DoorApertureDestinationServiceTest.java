package art.arcane.wormholes.door;

import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.wormholes.door.view.DoorProjectionAdapter;
import art.arcane.wormholes.door.view.DoorProjectionDestination;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import art.arcane.wormholes.util.Direction;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.type.Door;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class DoorApertureDestinationServiceTest {
    @Test
    void firstPersonalAndPublicViewsPrepareBeforePublishingActualReturnAperture() {
        for (DoorKind kind : new DoorKind[]{DoorKind.PERSONAL, DoorKind.PUBLIC}) {
            Harness harness = new Harness();
            UUID observer = UUID.randomUUID();
            DoorItemIdentity identity = kind == DoorKind.PERSONAL
                ? DoorItemIdentity.newPersonal() : DoorItemIdentity.newPublic();
            DoorProjectionAdapter source = harness.adapter(identity, new DoorwayPlane(12, 64, 8, Direction.W));
            PocketBinding binding = kind == DoorKind.PERSONAL ? PocketBinding.personal(observer) : PocketBinding.publicDoor(identity.itemId());
            when(harness.state.resolveDestination(identity, observer)).thenReturn(new PocketDoorDestination(binding));
            when(harness.state.findPocket(binding)).thenReturn(Optional.empty());

            assertTrue(harness.service.destinationOf(source, observer, false).isEmpty());
            verify(harness.transits).prepareProjection(source.endpoint(), observer);

            PocketSpace space = harness.space(binding, 8);
            when(harness.state.findPocket(binding)).thenReturn(Optional.of(space));
            PlacedDoorEndpoint target = harness.pocketEndpoint(space);
            when(harness.transits.preparingProjection(binding)).thenReturn(true);
            assertTrue(harness.service.destinationOf(source, observer, false).isEmpty());

            when(harness.transits.preparingProjection(binding)).thenReturn(false);
            DoorProjectionDestination destination = harness.service.destinationOf(source, observer, false).orElseThrow();
            DoorwayPlane targetPlane = harness.runtimes.runtime(target.identity().itemId()).plane();
            assertEquals(targetPlane.center().x(), destination.origin().getX());
            assertEquals(targetPlane.center().y(), destination.origin().getY());
            assertEquals(targetPlane.center().z(), destination.origin().getZ());
            assertEquals(DoorApertureFrames.destinationFrame(source.plane(), targetPlane), destination.frame());
            assertNotEquals(new PocketLayout(space).entry().y(), destination.origin().getY());
        }
    }

    @Test
    void publicInstancedViewsUseResolvedObserverBindingAndNeverShareThePublicRoom() {
        Harness harness = new Harness();
        DoorItemIdentity identity = DoorItemIdentity.newPublic();
        DoorProjectionAdapter source = harness.adapter(identity, new DoorwayPlane(12, 64, 8, Direction.W));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PocketBinding firstBinding = PocketBinding.instance("room", first);
        PocketBinding secondBinding = PocketBinding.instance("room", second);
        when(harness.state.resolveDestination(identity, first)).thenReturn(new PocketDoorDestination(firstBinding, "room"));
        when(harness.state.resolveDestination(identity, second)).thenReturn(new PocketDoorDestination(secondBinding, "room"));
        PocketSpace firstSpace = harness.space(firstBinding, 8);
        PocketSpace secondSpace = harness.space(secondBinding, 520);
        when(harness.state.findPocket(firstBinding)).thenReturn(Optional.of(firstSpace));
        when(harness.state.findPocket(secondBinding)).thenReturn(Optional.of(secondSpace));
        harness.pocketEndpoint(firstSpace);
        harness.pocketEndpoint(secondSpace);

        DoorProjectionDestination firstRoute = harness.service.destinationOf(source, first, false).orElseThrow();
        DoorProjectionDestination secondRoute = harness.service.destinationOf(source, second, false).orElseThrow();
        assertNotEquals(firstRoute.routeId(), secondRoute.routeId());
        assertNotEquals(firstRoute.origin(), secondRoute.origin());
        verify(harness.state, never()).findPocket(PocketBinding.publicDoor(identity.itemId()));
    }

    @Test
    void returnViewUsesEachObserversCurrentSourceApertureInsteadOfLandingPose() {
        Harness harness = new Harness();
        DoorProjectionAdapter inside = harness.adapter(DoorItemIdentity.newReturn(UUID.randomUUID()),
            new DoorwayPlane(7, 80, 15, Direction.S));
        for (Direction facing : new Direction[]{Direction.N, Direction.E}) {
            UUID observer = UUID.randomUUID();
            DoorwayPlane plane = new DoorwayPlane(50 + facing.x() * 20, 68, 30, facing);
            DoorProjectionAdapter outside = harness.adapter(DoorItemIdentity.newPersonal(), plane);
            harness.register(outside.endpoint(), plane, harness.overworld);
            ReturnTicket ticket = new ReturnTicket(observer, outside.getId(), harness.overworld.getUID(),
                "minecraft:overworld", 1.5, 64, 2.5, 73, 0);
            when(harness.state.getReturnTicket(observer)).thenReturn(Optional.of(ticket));

            DoorProjectionDestination destination = harness.service.destinationOf(inside, observer, false).orElseThrow();
            assertEquals(plane.center().x(), destination.origin().getX());
            assertEquals(plane.center().y(), destination.origin().getY());
            assertEquals(plane.center().z(), destination.origin().getZ());
            assertEquals(DoorApertureFrames.destinationFrame(inside.plane(), plane), destination.frame());
        }
    }

    @Test
    void disallowedNestedPocketDoesNotPublishAnAlreadyPreparedRoute() {
        Harness harness = new Harness();
        UUID observer = UUID.randomUUID();
        DoorItemIdentity identity = DoorItemIdentity.newPersonal();
        DoorProjectionAdapter source = harness.adapter(identity, new DoorwayPlane(12, 64, 8, Direction.W));
        PocketBinding binding = PocketBinding.personal(observer);
        PocketSpace space = harness.space(binding, 8);
        when(harness.state.resolveDestination(identity, observer)).thenReturn(new PocketDoorDestination(binding));
        when(harness.state.findPocket(binding)).thenReturn(Optional.of(space));
        harness.pocketEndpoint(space);
        when(harness.transits.allowsPocketProjection(source.endpoint(), space)).thenReturn(false);

        assertTrue(harness.service.destinationOf(source, observer, false).isEmpty());
        verify(harness.transits, never()).prepareProjection(source.endpoint(), observer);
    }

    @Test
    void deniedObserverCannotPreparePocketWhileExplicitBypassCan() {
        Harness harness = new Harness();
        UUID observer = UUID.randomUUID();
        DoorItemIdentity identity = DoorItemIdentity.newPublic();
        DoorProjectionAdapter source = harness.adapter(identity, new DoorwayPlane(12, 64, 8, Direction.W));
        when(harness.state.accessRecord(identity.itemId())).thenReturn(Optional.of(new DoorAccessRecord(
            identity.itemId(), UUID.randomUUID(), Map.of(observer, DoorAccessState.BLACKLIST))));
        PocketBinding binding = PocketBinding.publicDoor(identity.itemId());
        when(harness.state.resolveDestination(identity, observer)).thenReturn(new PocketDoorDestination(binding));

        assertTrue(harness.service.destinationOf(source, observer, false).isEmpty());
        verify(harness.transits, never()).prepareProjection(source.endpoint(), observer);
        assertTrue(harness.service.destinationOf(source, observer, true).isEmpty());
        verify(harness.transits).prepareProjection(source.endpoint(), observer);
    }

    @Test
    void unloadedReturnSourceRequestsReconcileWithoutPublishingTicketCamera() {
        Harness harness = new Harness();
        UUID observer = UUID.randomUUID();
        DoorProjectionAdapter inside = harness.adapter(DoorItemIdentity.newReturn(UUID.randomUUID()),
            new DoorwayPlane(7, 80, 15, Direction.S));
        PlacedDoorEndpoint outside = new PlacedDoorEndpoint(new DoorPosition(harness.overworld.getUID(),
            "minecraft:overworld", 40, 70, 22), DoorItemIdentity.newPersonal());
        when(harness.state.findEndpointByItem(outside.identity().itemId())).thenReturn(Optional.of(outside));
        when(harness.runtimes.world(outside.position())).thenReturn(harness.overworld);
        ReturnTicket ticket = new ReturnTicket(observer, outside.identity().itemId(), harness.overworld.getUID(),
            "minecraft:overworld", 1.5, 64, 2.5, 73, 0);
        when(harness.state.getReturnTicket(observer)).thenReturn(Optional.of(ticket));

        assertTrue(harness.service.destinationOf(inside, observer, false).isEmpty());
        verify(harness.transits).prepareEndpointProjection(outside);
    }

    private static final class Harness {
        private final DoorStateGuard guard = mock(DoorStateGuard.class);
        private final DoorStateService state = mock(DoorStateService.class);
        private final DoorRuntimeIndex runtimes = mock(DoorRuntimeIndex.class);
        private final DoorTransitCoordinator transits = mock(DoorTransitCoordinator.class);
        private final World overworld = world("minecraft", "overworld");
        private final World pocketWorld = world("wormholes", "pockets");
        private final DoorApertureDestinationService service;

        private Harness() {
            PocketWorldService worlds = mock(PocketWorldService.class);
            when(worlds.world()).thenReturn(Optional.of(pocketWorld));
            when(guard.state()).thenReturn(state);
            when(transits.allowsPocketProjection(any(), any())).thenReturn(true);
            service = new DoorApertureDestinationService(guard, runtimes, new PocketStructureService(), worlds, transits);
        }

        private DoorProjectionAdapter adapter(DoorItemIdentity identity, DoorwayPlane plane) {
            PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(overworld.getUID(),
                "minecraft:overworld", plane.blockX(), plane.blockY(), plane.blockZ()), identity);
            RuntimeDoor runtime = new RuntimeDoor(endpoint);
            runtime.update(new VanillaDoorSnapshot(overworld.getUID(), plane, Door.Hinge.LEFT, true, false));
            return new DoorProjectionAdapter(runtime, plane, overworld);
        }

        private PocketSpace space(PocketBinding binding, int centerX) {
            return new PocketSpace(UUID.randomUUID(), binding, 0, centerX, 80, 8, PocketShell.defaults());
        }

        private PlacedDoorEndpoint pocketEndpoint(PocketSpace space) {
            PocketLayout layout = new PocketLayout(space);
            PocketBlockPosition lower = layout.returnDoorLower();
            PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(pocketWorld.getUID(),
                "wormholes:pockets", lower.x(), lower.y(), lower.z()), layout.returnDoorIdentity());
            register(endpoint, new DoorwayPlane(lower.x(), lower.y(), lower.z(), Direction.S), pocketWorld);
            return endpoint;
        }

        private void register(PlacedDoorEndpoint endpoint, DoorwayPlane plane, World world) {
            RuntimeDoor runtime = new RuntimeDoor(endpoint);
            runtime.update(new VanillaDoorSnapshot(world.getUID(), plane, Door.Hinge.LEFT, false, false));
            when(state.findEndpointByItem(endpoint.identity().itemId())).thenReturn(Optional.of(endpoint));
            when(runtimes.runtime(endpoint.identity().itemId())).thenReturn(runtime);
            when(runtimes.world(endpoint.position())).thenReturn(world);
        }

        private static World world(String namespace, String name) {
            World world = mock(World.class);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getName()).thenReturn(name);
            when(world.getKey()).thenReturn(new NamespacedKey(namespace, name));
            return world;
        }
    }
}
