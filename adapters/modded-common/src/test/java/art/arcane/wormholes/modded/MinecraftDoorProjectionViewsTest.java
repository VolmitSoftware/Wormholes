package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorHalf;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.optics.math.Vec3;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.optics.math.Face;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftDoorProjectionViewsTest extends MinecraftTestBase {
    @Test
    public void observerRoutesKeepStableDescriptorsAndInvalidateOnlyWhenDestinationMoves() {
        Fixture fixture = new Fixture();
        MinecraftDoorProjectionViews views = new MinecraftDoorProjectionViews(fixture.runtime);
        MinecraftPortal source = views.update(fixture.player, List.of(fixture.door), false).getFirst();
        MinecraftPortal destination = views.destination(source);
        assertEquals(PortalType.PORTAL, source.getType());
        assertEquals(PortalType.PORTAL, destination.getType());
        long identity = views.routeIdentity(source);
        assertNotEquals(0L, identity);
        assertSame(source, views.source(source.getId()));
        assertTrue(views.current(source));
        assertTrue(source.getGeometry().containsBlock(2, 64, 3));
        assertTrue(source.getGeometry().containsBlock(2, 65, 3));
        assertEquals(4, source.getNetworkViewLateralPad());
        fixture.route(new Vec3(20.5D, 65.0D, 30.5D));
        assertSame(source, views.update(fixture.player, List.of(fixture.door), false).getFirst());
        assertSame(destination, views.destination(source));
        assertEquals(identity, views.routeIdentity(source));
        fixture.route(new Vec3(30.5D, 65.0D, 30.5D));
        MinecraftPortal moved = views.update(fixture.player, List.of(fixture.door), false).getFirst();
        assertNotSame(source, moved);
        assertFalse(views.current(source));
        assertNotEquals(identity, views.routeIdentity(moved));
        assertEquals(0L, views.routeIdentity(source));
        assertEquals(30.5D, views.destination(moved).getOrigin().x(), 0.0D);
    }

    @Test
    public void independentObserversShareOnlyAnIdenticalDestinationRoute() {
        Fixture fixture = new Fixture();
        MinecraftDoorProjectionViews first = new MinecraftDoorProjectionViews(fixture.runtime);
        MinecraftDoorProjectionViews second = new MinecraftDoorProjectionViews(fixture.runtime);
        MinecraftPortal source = first.update(fixture.player, List.of(fixture.door), false).getFirst();
        MinecraftPortal matching = second.update(fixture.player, List.of(fixture.door), false).getFirst();
        assertEquals(first.routeIdentity(source), second.routeIdentity(matching));
        fixture.route(new Vec3(120.5D, 65.0D, 30.5D));
        MinecraftPortal moved = second.update(fixture.player, List.of(fixture.door), false).getFirst();
        assertNotEquals(first.routeIdentity(source), second.routeIdentity(moved));
    }

    @Test
    public void globalAndPerDoorSuppressionRemoveTransientApertures() {
        Fixture fixture = new Fixture();
        MinecraftDoorProjectionViews views = new MinecraftDoorProjectionViews(fixture.runtime);
        assertEquals(1, views.update(fixture.player, List.of(fixture.door), false).size());
        fixture.settings.getDoors().projectionEnabled = false;
        assertTrue(views.update(fixture.player, List.of(fixture.door), false).isEmpty());
        assertFalse(views.contains(fixture.door.endpoint().identity().itemId()));
        fixture.settings.getDoors().projectionEnabled = true;
        MinecraftDoorService.DoorView disabled = new MinecraftDoorService.DoorView(fixture.door.endpoint().withProjection(DoorProjectionState.OFF),
            fixture.level, fixture.door.plane(), true);
        assertTrue(views.update(fixture.player, List.of(disabled), false).isEmpty());
    }

    @Test
    public void nativeDoorAndTrapdoorIgnoreProjectionTogglesButKeepTheirResolvedApertures() {
        Fixture fixture = new Fixture();
        fixture.settings.getDoors().projectionEnabled = false;
        Optional<MinecraftDoorService.ProjectionDestination> route = fixture.doors.projectionDestination(fixture.door, fixture.observerId);
        for (boolean trapdoor : new boolean[] {false, true}) {
            DoorwayPlane plane = trapdoor ? DoorwayPlane.trapdoor(2, 64, 3, Face.N, DoorHalf.TOP, DoorOpenState.OPEN)
                : fixture.door.plane();
            MinecraftDoorService.DoorView disabled = new MinecraftDoorService.DoorView(fixture.door.endpoint().withProjection(DoorProjectionState.OFF),
                fixture.level, plane, true);
            when(fixture.doors.projectionDestination(disabled, fixture.observerId)).thenReturn(route);
            MinecraftDoorProjectionViews views = new MinecraftDoorProjectionViews(fixture.runtime);
            assertTrue(views.update(fixture.player, List.of(disabled), false).isEmpty());
            MinecraftPortal source = views.update(fixture.player, List.of(disabled), true).getFirst();
            assertEquals(trapdoor ? Face.U : Face.N, source.getFrame().getNormal());
            assertTrue(source.getGeometry().containsBlock(2, 64, 3));
            assertEquals(!trapdoor, source.getGeometry().containsBlock(2, 65, 3));
            assertTrue(views.update(fixture.player, List.of(disabled), false).isEmpty());
            assertFalse(views.contains(source.getId()));
        }
    }

    @Test
    public void nativeTrapdoorDescriptorsRetainTheirFacingAndPhysicalHorizontalPlane() {
        Fixture fixture = new Fixture();
        fixture.settings.getDoors().projectionEnabled = false;
        Optional<MinecraftDoorService.ProjectionDestination> route = fixture.doors.projectionDestination(fixture.door, fixture.observerId);
        for (Face facing : new Face[]{Face.N, Face.E, Face.S, Face.W}) {
            for (DoorHalf half : DoorHalf.values()) {
                DoorwayPlane plane = DoorwayPlane.trapdoor(2, 64, 3, facing, half, DoorOpenState.OPEN);
                MinecraftDoorService.DoorView disabled = new MinecraftDoorService.DoorView(
                    fixture.door.endpoint().withProjection(DoorProjectionState.OFF), fixture.level, plane, true);
                when(fixture.doors.projectionDestination(disabled, fixture.observerId)).thenReturn(route);
                MinecraftDoorProjectionViews views = new MinecraftDoorProjectionViews(fixture.runtime);
                assertTrue(views.update(fixture.player, List.of(disabled), false).isEmpty());
                MinecraftPortal source = views.update(fixture.player, List.of(disabled), true).getFirst();
                assertEquals(DoorApertureFrames.of(plane), source.getFrame());
                assertEquals(plane.center().x(), source.getOrigin().x(), 0.0D);
                assertEquals(plane.planeY(), source.getOrigin().y(), 0.0D);
                assertEquals(plane.center().z(), source.getOrigin().z(), 0.0D);
                assertTrue(source.getGeometry().containsBlock(2, 64, 3));
                assertFalse(source.getGeometry().containsBlock(2, 65, 3));
            }
        }
    }

    @Test
    public void nativeDoorIncludesOtherDimensionsButRequiresActiveResolvedObserverDestination() {
        Fixture fixture = new Fixture();
        fixture.settings.getDoors().projectionEnabled = false;
        MinecraftDoorProjectionViews views = new MinecraftDoorProjectionViews(fixture.runtime);
        assertEquals(1, views.update(fixture.player, List.of(fixture.door), true).size());
        MinecraftDoorService.DoorView inactive = new MinecraftDoorService.DoorView(fixture.door.endpoint(), fixture.level, fixture.door.plane(), false);
        assertTrue(views.update(fixture.player, List.of(inactive), true).isEmpty());
        when(fixture.player.level()).thenReturn(mock(ServerLevel.class));
        assertEquals(1, views.update(fixture.player, List.of(fixture.door), true).size());
        fixture.settings.getDoors().projectionEnabled = true;
        assertTrue(views.update(fixture.player, List.of(fixture.door), false).isEmpty());
        when(fixture.player.level()).thenReturn(fixture.level);
        when(fixture.doors.projectionDestination(fixture.door, fixture.observerId)).thenReturn(Optional.empty());
        assertTrue(views.update(fixture.player, List.of(fixture.door), true).isEmpty());
    }

    private static final class Fixture {
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        private final MinecraftDoorService doors = mock(MinecraftDoorService.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final UUID observerId = UUID.randomUUID();
        private final UUID destinationId = UUID.randomUUID();
        private final WormholesSettings settings = MinecraftTestSettings.defaults();
        private final MinecraftDoorService.DoorView door;

        private Fixture() {
            settings.getDoors().projectionEnabled = true;
            DoorItemIdentity identity = DoorItemIdentity.newPersonal();
            PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 2, 64, 3), identity,
                DoorOpenState.OPEN, DoorProjectionState.INHERIT);
            door = new MinecraftDoorService.DoorView(endpoint, level, new DoorwayPlane(2, 64, 3, Face.N), true);
            when(runtime.configuration()).thenReturn(configuration);
            when(configuration.settings()).thenReturn(settings);
            when(runtime.doors()).thenReturn(doors);
            when(player.level()).thenReturn(level);
            when(player.getUUID()).thenReturn(observerId);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            route(new Vec3(20.5D, 65.0D, 30.5D));
        }

        private void route(Vec3 origin) {
            when(doors.projectionDestination(door, observerId)).thenReturn(Optional.of(new MinecraftDoorService.ProjectionDestination(
                destinationId, level, origin, Frame.fromNormalUp(Face.S, Face.U))));
        }
    }
}
