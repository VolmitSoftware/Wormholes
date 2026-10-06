package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.modded.MinecraftDoorService;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPreparedTravelEntranceTest extends MinecraftTestBase {
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
        PlaneCrossing crossing = crossing(door);
        when(fixture.doors().crossPrepared(fixture.player(), door.getId(), crossing)).thenReturn(true);
        assertTrue(fixture.travel().eligible(fixture.peer(), fixture.player(), door, receiver));
        assertTrue(fixture.travel().dispatchCross(fixture.peer(), fixture.player(), door, receiver,
            ApertureDescriptor.KIND_DOOR, crossing));
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
        PlaneCrossing crossing = crossing(portal);
        when(fixture.registry().crossPrepared(fixture.player(), portal.getId(), receiver, crossing)).thenReturn(true);
        assertTrue(fixture.travel().dispatchCross(fixture.peer(), fixture.player(), portal, receiver,
            ApertureDescriptor.KIND_FRAME, crossing));
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
            ApertureDescriptor.KIND_DOOR, crossing(door)));
        verify(fixture.registry(), never()).canDepart(any(), any());
        verify(fixture.registry(), never()).crossPrepared(any(), any(), any(), any());
        verify(fixture.doors(), never()).crossPrepared(any(), any(), any());
    }

    private MinecraftPortal descriptor() {
        UUID id = UUID.randomUUID();
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, new Vec3(0.5D, 65, 0.5D),
            id.toString(), Frame.canonical(Face.N), true), new ApertureCells(), "minecraft:overworld",
            Map.of("type", PortalType.PORTAL.name())));
    }

    private PlaneCrossing crossing(MinecraftPortal portal) {
        return new PlaneCrossing(portal.getFrame(), portal.getOrigin(), portal.getOrigin(),
            new Vec3(0, 0, -0.4D), new Vec3(0, 0, -1), true);
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
