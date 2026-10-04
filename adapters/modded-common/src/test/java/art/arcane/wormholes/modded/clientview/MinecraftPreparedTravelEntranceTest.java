package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.MinecraftDoorService;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.util.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.Test;
import org.junit.BeforeClass;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPreparedTravelEntranceTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void syntheticDoorAdmissionAndCrossingUseDoorAccessAndItsOwnReceiver() {
        Fixture fixture = fixture();
        MinecraftPortal door = descriptor();
        MinecraftPortal receiver = descriptor();
        when(fixture.peer().door(door.getId())).thenReturn(door);
        when(fixture.doors().canPrepare(fixture.player(), door.getId(), receiver.getId())).thenReturn(true);
        when(fixture.registry().canDepart(fixture.player(), door)).thenAnswer(ignored -> {
            door.getOwner();
            return false;
        });
        PortalCrossing crossing = crossing(door);
        when(fixture.doors().crossPrepared(fixture.player(), door.getId(), crossing)).thenReturn(true);
        assertTrue(fixture.travel().eligible(fixture.peer(), fixture.player(), door, receiver));
        assertTrue(fixture.travel().dispatchCross(fixture.peer(), fixture.player(), door, receiver,
            ClientPortalGeometry.KIND_DOOR, crossing));
        verify(fixture.registry(), never()).canDepart(any(), any());
        verify(fixture.registry(), never()).canArrive(any(), any());
        verify(fixture.registry(), never()).crossPrepared(any(), any(), any(), any());
        verify(fixture.doors()).crossPrepared(fixture.player(), door.getId(), crossing);
    }

    @Test
    public void nearbyOrdinaryPortalKeepsItsIndependentReceiverAndAuthority() {
        Fixture fixture = fixture();
        MinecraftPortal portal = descriptor();
        MinecraftPortal receiver = descriptor();
        when(fixture.registry().get(portal.getId())).thenReturn(portal);
        when(fixture.registry().canDepart(fixture.player(), portal)).thenReturn(true);
        when(fixture.registry().canArrive(fixture.player(), receiver)).thenReturn(true);
        PortalCrossing crossing = crossing(portal);
        when(fixture.registry().crossPrepared(fixture.player(), portal.getId(), receiver, crossing)).thenReturn(true);
        assertTrue(fixture.travel().dispatchCross(fixture.peer(), fixture.player(), portal, receiver,
            ClientPortalGeometry.KIND_FRAME, crossing));
        verify(fixture.registry()).crossPrepared(fixture.player(), portal.getId(), receiver, crossing);
        verify(fixture.doors(), never()).crossPrepared(any(), any(), any());
    }

    @Test
    public void withdrawnDoorOrDeniedAccessCannotBecomeAnOrdinaryPortal() {
        Fixture fixture = fixture();
        MinecraftPortal door = descriptor();
        MinecraftPortal receiver = descriptor();
        when(fixture.peer().door(door.getId())).thenReturn(door);
        assertFalse(fixture.travel().eligible(fixture.peer(), fixture.player(), door, receiver));
        when(fixture.peer().door(door.getId())).thenReturn(null);
        assertFalse(fixture.travel().eligible(fixture.peer(), fixture.player(), door, receiver));
        assertFalse(fixture.travel().dispatchCross(fixture.peer(), fixture.player(), door, receiver,
            ClientPortalGeometry.KIND_DOOR, crossing(door)));
        verify(fixture.registry(), never()).canDepart(any(), any());
        verify(fixture.registry(), never()).crossPrepared(any(), any(), any(), any());
        verify(fixture.doors(), never()).crossPrepared(any(), any(), any());
    }

    private MinecraftPortal descriptor() {
        UUID id = UUID.randomUUID();
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, new GeometryVector(0.5D, 65, 0.5D),
            id.toString(), PortalFrame.canonical(Direction.N), true), new PortalGeometry(), "minecraft:overworld",
            Map.of("type", PortalType.PORTAL.name())));
    }

    private PortalCrossing crossing(MinecraftPortal portal) {
        return new PortalCrossing(portal.getFrame(), portal.getOrigin(), portal.getOrigin(),
            new GeometryVector(0, 0, -0.4D), new GeometryVector(0, 0, -1), true);
    }

    private Fixture fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftDoorService doors = mock(MinecraftDoorService.class);
        when(runtime.portals()).thenReturn(registry);
        when(runtime.doors()).thenReturn(doors);
        return new Fixture(new MinecraftPreparedTravel(runtime, mock(MinecraftClientViewPortalAccess.class)),
            registry, doors, mock(MinecraftClientViewPeer.class), mock(ServerPlayer.class));
    }

    private record Fixture(MinecraftPreparedTravel travel, MinecraftPortalRegistry registry, MinecraftDoorService doors,
                           MinecraftClientViewPeer peer, ServerPlayer player) {
    }
}
