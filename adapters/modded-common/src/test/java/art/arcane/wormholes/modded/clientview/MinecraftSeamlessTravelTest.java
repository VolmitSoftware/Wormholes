package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.wormholes.modded.MinecraftDoorService;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.seamless.RemoteRoutes;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.render.client.session.ClientViewTravel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftSeamlessTravelTest extends MinecraftTestBase {
    @Test
    public void seamlessSessionArmsRemoteRoutesWithoutCapturingChunks() {
        Fixture fixture = new Fixture();

        fixture.seamless.tick(fixture.session, fixture.player);

        verify(fixture.routes).update(eq(fixture.player), eq(fixture.session), eq(List.of()), anyLong());
        verify(fixture.leases, never()).retain(any(), any(), anyInt(), anyInt());
    }

    @Test
    public void aCrossingForAnUnarmedRouteIsRejectedWithTheVanillaCorrection() {
        Fixture fixture = new Fixture();
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(0.5D, 64.0D, 0.4D, 0.0F, 0.0F), new Vec3d(0.5D, 65.62D, 0.6D), new Vec3d(0.5D, 65.62D, 0.4D));
        when(fixture.session.takeSeamlessCross()).thenReturn(cross, (TravelMessage.TravelCross) null);

        fixture.seamless.settle(fixture.session, fixture.player);

        verify(fixture.player.connection).teleport(4.0D, 64.0D, -2.0D, 30.0F, 5.0F);
        verify(fixture.session).sendTravel(new TravelMessage.TravelCancel(cross.token(), cross.generation()));
        verify(fixture.registry, never()).crossSeamless(any(), any(), any(), any());
        verify(fixture.doors, never()).crossSeamless(any(), any(), any());
    }

    @Test
    public void serverDetectedCrossingsThroughUnarmedPortalsKeepTheOrdinaryPath() {
        Fixture fixture = new Fixture();

        fixture.seamless.tick(fixture.session, fixture.player);

        assertFalse(fixture.seamless.defer(fixture.player, UUID.randomUUID()));
        assertFalse(fixture.seamless.crossing(fixture.player.getUUID()));
    }

    @Test
    public void arrivalStaysOnTheTeleportPathWithoutACrossingInFlight() {
        Fixture fixture = new Fixture();

        assertNull(fixture.seamless.arrival(fixture.session, fixture.player, UUID.randomUUID(), fixture.level,
            new TravelMessage.TravelPose(0, 64, 0, 0, 0), new Vec3d(0, 0, 0)));
    }

    @Test
    public void acceptPoseKeepsTheCrossingYawContinuous() {
        Frame frame = Frame.canonical(Face.N);
        Vec3d feet = new Vec3d(0.5D, 64.0D, -0.1D);
        TravelMessage.TravelCross cross = new TravelMessage.TravelCross(UUID.randomUUID(), 1L, 1L,
            new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), 725.0F, 10.0F), new Vec3d(0.5D, 65.62D, 0.1D),
            new Vec3d(0.5D, 65.62D, -0.1D));
        PlaneCrossing crossing = new PlaneCrossing(frame.view(true), new Vec3d(0.5D, 65.0D, 0.0D), feet, new Vec3d(0.0D, 0.0D, -0.2D),
            Angles.direction(725.0F, 10.0F), true);
        Vec3d destination = new Vec3d(400.5D, 65.0D, 0.0D);
        Vec3d arrived = crossing.outPoint(frame, destination);
        Angles.Look look = ArrivalOrientation.apply(crossing, frame, TravelMessage.ArrivalRules.FRAME.orientation(), false);

        TravelMessage.TravelPose pose = MinecraftSeamlessTravel.pose(new TravelMessage.TravelPose(arrived.x(), arrived.y(), arrived.z(),
            look.yaw(), look.pitch()), cross, crossing, TravelMessage.ArrivalRules.FRAME, frame, destination);

        assertEquals(725.0F, pose.yaw(), 1.0E-3F);
        assertEquals(look.pitch(), pose.pitch(), 1.0E-3F);
        assertEquals(arrived.x(), pose.x(), 0.0D);
        assertEquals(arrived.z(), pose.z(), 0.0D);
    }

    private static final class Fixture {
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        private final MinecraftDoorService doors = mock(MinecraftDoorService.class);
        private final RemoteRoutes routes = mock(RemoteRoutes.class);
        private final MinecraftClientViewPortalAccess portals = mock(MinecraftClientViewPortalAccess.class);
        private final MinecraftClientViewPeer peer = mock(MinecraftClientViewPeer.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final ServerLevel level = mock(ServerLevel.class);
        @SuppressWarnings("unchecked")
        private final ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        @SuppressWarnings("unchecked")
        private final ClientViewTravel<MinecraftClientViewPeer> session = mock(ClientViewTravel.class);
        private final MinecraftSeamlessTravel seamless = new MinecraftSeamlessTravel(runtime, portals);

        private Fixture() {
            UUID playerId = UUID.randomUUID();
            MinecraftServer server = mock(MinecraftServer.class);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            when(runtime.portals()).thenReturn(registry);
            when(registry.observedVelocity(player)).thenReturn(new Vec3d(0.0D, 0.0D, 0.0D));
            when(runtime.doors()).thenReturn(doors);
            when(runtime.remoteRoutes()).thenReturn(routes);
            when(runtime.leases()).thenReturn(leases);
            when(runtime.server()).thenReturn(server);
            when(server.getTickCount()).thenReturn(20);
            when(player.getUUID()).thenReturn(playerId);
            when(player.getX()).thenReturn(4.0D);
            when(player.getY()).thenReturn(64.0D);
            when(player.getZ()).thenReturn(-2.0D);
            when(player.getYRot()).thenReturn(30.0F);
            when(player.getXRot()).thenReturn(5.0F);
            when(player.level()).thenReturn(level);
            when(session.player()).thenReturn(peer);
            when(session.playerId()).thenReturn(playerId);
            when(session.seamlessSelected()).thenReturn(true);
            when(routes.routes(playerId)).thenReturn(List.of());
            PlayerList list = mock(PlayerList.class);
            when(server.getPlayerList()).thenReturn(list);
            when(list.getViewDistance()).thenReturn(10);
        }
    }
}
