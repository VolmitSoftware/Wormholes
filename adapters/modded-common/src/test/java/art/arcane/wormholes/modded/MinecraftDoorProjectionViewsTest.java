package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.Test;
import org.junit.BeforeClass;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftDoorProjectionViewsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void observerRoutesKeepStableDescriptorsAndInvalidateOnlyWhenDestinationMoves() {
        Fixture fixture = new Fixture();
        MinecraftDoorProjectionViews views = new MinecraftDoorProjectionViews(fixture.runtime);
        MinecraftPortal source = views.update(fixture.player, List.of(fixture.door)).getFirst();
        MinecraftPortal destination = views.destination(source);
        assertTrue(views.current(source));
        assertTrue(source.getGeometry().containsBlock(2, 64, 3));
        assertTrue(source.getGeometry().containsBlock(2, 65, 3));
        assertEquals(4, source.getNetworkViewLateralPad());
        fixture.route(new GeometryVector(20.5D, 65.0D, 30.5D));
        assertSame(source, views.update(fixture.player, List.of(fixture.door)).getFirst());
        assertSame(destination, views.destination(source));
        fixture.route(new GeometryVector(30.5D, 65.0D, 30.5D));
        MinecraftPortal moved = views.update(fixture.player, List.of(fixture.door)).getFirst();
        assertNotSame(source, moved);
        assertFalse(views.current(source));
        assertEquals(30.5D, views.destination(moved).getOrigin().x(), 0.0D);
    }

    @Test
    public void globalAndPerDoorSuppressionRemoveTransientApertures() {
        Fixture fixture = new Fixture();
        MinecraftDoorProjectionViews views = new MinecraftDoorProjectionViews(fixture.runtime);
        assertEquals(1, views.update(fixture.player, List.of(fixture.door)).size());
        fixture.settings.getDoors().projectionEnabled = false;
        assertTrue(views.update(fixture.player, List.of(fixture.door)).isEmpty());
        assertFalse(views.contains(fixture.door.endpoint().identity().itemId()));
        fixture.settings.getDoors().projectionEnabled = true;
        MinecraftDoorService.DoorView disabled = new MinecraftDoorService.DoorView(fixture.door.endpoint().withProjection(DoorProjectionState.OFF),
            fixture.level, fixture.door.plane(), true);
        assertTrue(views.update(fixture.player, List.of(disabled)).isEmpty());
    }

    private static final class Fixture {
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        private final MinecraftDoorService doors = mock(MinecraftDoorService.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final UUID observerId = UUID.randomUUID();
        private final UUID destinationId = UUID.randomUUID();
        private final WormholesSettings settings = new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new NetworkConfig());
        private final MinecraftDoorService.DoorView door;

        private Fixture() {
            settings.getDoors().projectionEnabled = true;
            DoorItemIdentity identity = DoorItemIdentity.newPersonal();
            PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 2, 64, 3), identity,
                DoorOpenState.OPEN, DoorProjectionState.INHERIT);
            door = new MinecraftDoorService.DoorView(endpoint, level, new DoorwayPlane(2, 64, 3, Direction.N), true);
            when(runtime.configuration()).thenReturn(configuration);
            when(configuration.settings()).thenReturn(settings);
            when(runtime.doors()).thenReturn(doors);
            when(player.level()).thenReturn(level);
            when(player.getUUID()).thenReturn(observerId);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            route(new GeometryVector(20.5D, 65.0D, 30.5D));
        }

        private void route(GeometryVector origin) {
            when(doors.projectionDestination(door, observerId)).thenReturn(Optional.of(new MinecraftDoorService.ProjectionDestination(
                destinationId, level, origin, PortalFrame.fromNormalUp(Direction.S, Direction.U))));
        }
    }
}
