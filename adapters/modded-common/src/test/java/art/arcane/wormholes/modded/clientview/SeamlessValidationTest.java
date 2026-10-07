package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.SeamlessListenerAccess;
import art.arcane.wormholes.modded.seamless.RemoteRoute;
import art.arcane.wormholes.modded.seamless.RouteWindow;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.client.session.SeamlessCrossCheck;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class SeamlessValidationTest extends MinecraftTestBase {
    @Test
    public void pendingVanillaTeleportIsReportedToTheCrossingValidation() {
        ServerPlayer player = player();
        when(((SeamlessListenerAccess) player.connection).wormholesAwaitingPosition()).thenReturn(new Vec3(1, 2, 3));

        SeamlessCrossCheck.Server authority = MinecraftSeamlessTravel.authority(player, null, new Vec3d(0, -3.9D, 0));

        assertTrue(authority.awaitingTeleport());
        assertFalse(authority.changingDimension());
        assertEquals("minecraft:overworld", authority.world());
        assertEquals(-3.9D, authority.velocity().y(), 0.0D);
        assertEquals(1.62D, authority.eyeHeight(), 1.0E-6D);
    }

    @Test
    public void changingDimensionIsReported() {
        ServerPlayer player = player();
        when(player.isChangingDimension()).thenReturn(true);
        assertTrue(MinecraftSeamlessTravel.authority(player, null, new Vec3d(0, 0, 0)).changingDimension());
    }

    @Test
    public void arrivalMustLandInADeliveredResidentColumn() {
        RemoteRoute route = new RemoteRoute(new RemoteRoute.Key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), null,
            new Vec3d(40.0D, 70.0D, 40.0D), new RouteWindow(2, 2, 2), 4);
        route.stream().markDelivered(ChunkPos.pack(2, 2));

        assertTrue(MinecraftSeamlessTravel.residentArrival(route, player(), new Vec3d(40.0D, 70.0D, 40.0D)));
        assertFalse(MinecraftSeamlessTravel.residentArrival(route, player(), new Vec3d(56.0D, 70.0D, 40.0D)));
    }

    @Test
    public void resolvedDestinationMustMatchThePreparedDestination() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        when(runtime.portals()).thenReturn(registry);
        MinecraftClientViewPortalAccess access = mock(MinecraftClientViewPortalAccess.class);
        MinecraftSeamlessTravel travel = new MinecraftSeamlessTravel(runtime, access, new MinecraftPreparedTravel(runtime, access));
        MinecraftClientViewPeer peer = mock(MinecraftClientViewPeer.class);
        ServerPlayer player = player();
        MinecraftPortal source = portal();
        MinecraftPortal destination = portal();
        PlaneCrossing crossing = new PlaneCrossing(source.getFrame(), source.getOrigin(), source.getOrigin(), new Vec3d(0, 0, -0.4D),
            new Vec3d(0, 0, -1), true);

        when(registry.resolveDestination(source, player, crossing)).thenReturn(new NetworkMember(destination.getId(), "", "", 0, null));
        assertTrue(travel.destinationMatches(peer, player, source, destination, crossing));
        when(registry.resolveDestination(source, player, crossing)).thenReturn(new NetworkMember(UUID.randomUUID(), "", "", 0, null));
        assertFalse(travel.destinationMatches(peer, player, source, destination, crossing));
        when(registry.resolveDestination(source, player, crossing)).thenReturn(new NetworkMember(destination.getId(), "", "", 0, "other"));
        assertFalse(travel.destinationMatches(peer, player, source, destination, crossing));
        when(registry.resolveDestination(source, player, crossing)).thenReturn(null);
        assertFalse(travel.destinationMatches(peer, player, source, destination, crossing));
        when(peer.door(source.getId())).thenReturn(source);
        assertTrue(travel.destinationMatches(peer, player, source, destination, crossing));
    }

    private static ServerPlayer player() {
        ServerPlayer player = mock(ServerPlayer.class);
        player.connection = mock(ServerGamePacketListenerImpl.class, withSettings().extraInterfaces(SeamlessListenerAccess.class));
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.level()).thenReturn(level);
        when(player.getEyeHeight()).thenReturn(1.62F);
        return player;
    }

    private static MinecraftPortal portal() {
        UUID id = UUID.randomUUID();
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, new Vec3d(0.5D, 65, 0.5D),
            id.toString(), Frame.canonical(Face.N), true), new ApertureCells(), "minecraft:overworld",
            Map.of("type", PortalType.PORTAL.name())));
    }
}
