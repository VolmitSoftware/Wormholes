package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.wormholes.modded.MinecraftChunkLeasePlatform;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftProjectionService;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.wormholes.network.client.RemoteViewOptions;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.render.client.session.ClientViewTravel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class RemoteRoutesRemovalTest extends MinecraftTestBase {
    private static final Vec3d ANCHOR = new Vec3d(0.5D, 64.0D, 0.5D);

    @Test
    public void removingEitherEndOfARouteClosesItsResidentLevelAtOnce() {
        for (int end = 0; end < 2; end++) {
            try (Fixture fixture = new Fixture()) {
                UUID arrival = UUID.randomUUID();
                UUID departure = UUID.randomUUID();
                UUID unrelated = UUID.randomUUID();
                RemoteRoute returning = fixture.adopt(arrival, departure, 3);
                RemoteRoute other = fixture.adopt(unrelated, UUID.randomUUID(), 4);

                fixture.routes.portalRemoved(end == 0 ? arrival : departure);

                assertEquals(List.of(new TravelMessage.RemoteLevelClose(3)), fixture.sent);
                assertTrue(returning.closed());
                assertNull(fixture.routes.route(fixture.player.getUUID(), arrival));
                assertEquals(List.of(other), fixture.routes.routes(fixture.player.getUUID()));
                assertSame(other, fixture.routes.route(fixture.player.getUUID(), unrelated));
            }
        }
    }

    @Test
    public void removingAPortalWithoutRoutesSendsNothing() {
        try (Fixture fixture = new Fixture()) {
            RemoteRoute route = fixture.adopt(UUID.randomUUID(), UUID.randomUUID(), 5);

            fixture.routes.portalRemoved(UUID.randomUUID());

            assertTrue(fixture.sent.isEmpty());
            assertEquals(List.of(route), fixture.routes.routes(fixture.player.getUUID()));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final List<TravelMessage> sent = new ArrayList<>();
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final MockedStatic<MinecraftChunkLeasePlatform> leases = mockStatic(MinecraftChunkLeasePlatform.class);
        private final RemoteRoutes routes;

        private Fixture() {
            MinecraftServer server = mock(MinecraftServer.class);
            PlayerList players = mock(PlayerList.class);
            WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
            MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
            MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
            when(runtime.server()).thenReturn(server);
            when(server.registryAccess()).thenReturn(RegistryAccess.EMPTY);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getViewDistance()).thenReturn(8);
            when(runtime.configuration()).thenReturn(configuration);
            when(configuration.remoteView()).thenReturn(new RemoteViewOptions(true, 4, 8, 1 << 20));
            when(runtime.portals()).thenReturn(portals);
            when(portals.observedVelocity(any())).thenReturn(new Vec3d(0.0D, 0.0D, 0.0D));
            when(runtime.projections()).thenReturn(projections);
            when(projections.changes()).thenReturn(mock(WorldChangeTracker.class));
            ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class, withSettings().extraInterfaces(ServerConnectionAccess.class));
            when(((ServerConnectionAccess) listener).wormholesConnection()).thenReturn(mock(Connection.class));
            player.connection = listener;
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(player.requestedViewDistance()).thenReturn(8);
            when(player.getEyePosition()).thenReturn(new Vec3(0.5D, 65.62D, 0.5D));
            when(player.getBoundingBox()).thenReturn(new AABB(0.2D, 64.0D, 0.2D, 0.8D, 65.8D, 0.8D));
            ClientViewTravel<?> travel = mock(ClientViewTravel.class);
            when(travel.sendTravel(any())).thenAnswer(invocation -> sent.add(invocation.getArgument(0)));
            routes = new RemoteRoutes(runtime);
            routes.update(player, travel, List.of(), 0L);
        }

        private RemoteRoute adopt(UUID source, UUID destination, int handle) {
            return routes.adopt(player, new RemoteRoute.Key(player.getUUID(), source, destination), level, ANCHOR, new RouteWindow(0, 0, 2), handle);
        }

        @Override
        public void close() {
            leases.close();
        }
    }
}
