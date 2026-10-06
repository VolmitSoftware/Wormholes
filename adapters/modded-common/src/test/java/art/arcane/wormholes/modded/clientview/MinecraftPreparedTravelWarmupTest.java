package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.MinecraftChunkPacketEncoding;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftProjectionService;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.render.PortalEnvironmentTest;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.ClientViewEntityTransform;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import art.arcane.wormholes.util.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPreparedTravelWarmupTest extends MinecraftTestBase {
    @Test
    public void coldLandingStartsOneAsyncLeaseAndWaitsForReadinessBeforeMetadataCapture() {
        Fixture fixture = new Fixture();
        try (MockedStatic<MinecraftPortalEnvironment> environment = environment(fixture)) {
            fixture.tick();
            fixture.tick();
            fixture.loaded.set(true);
            fixture.tick();

            UUID worldId = MinecraftProjectionWorldView.worldId(fixture.level);
            verify(fixture.leases).retain(eq(fixture.level), eq(worldId), eq(0), eq(0));
            verify(fixture.session, never()).travelGeometry(any());
            environment.verifyNoInteractions();

            fixture.ready.complete(true);
            fixture.tick();

            ArgumentCaptor<ClientViewMessage.TravelBegin> begin = ArgumentCaptor.forClass(ClientViewMessage.TravelBegin.class);
            verify(fixture.travel).begin(begin.capture(), anyLong());
            assertEquals(169, begin.getValue().chunks().size());
            assertEquals(new ClientViewMessage.TravelCoordinate(0, 0), begin.getValue().chunks().getFirst());
            verify(fixture.lease, never()).close();
            fixture.prepared.clear();
            verify(fixture.lease).close();
        }
    }

    @Test
    public void changingRouteIdentityReplacesTheColdLeaseBeforeTheOldLoadCanComplete() {
        Fixture fixture = new Fixture();
        ChunkLease replacement = mock(ChunkLease.class);
        when(replacement.isValid()).thenReturn(true);
        when(replacement.ready()).thenReturn(new CompletableFuture<>());
        when(fixture.leases.retain(any(), any(), anyInt(), anyInt())).thenReturn(fixture.lease, replacement);

        fixture.tick();
        fixture.route.incrementAndGet();
        fixture.tick();
        fixture.ready.complete(true);
        fixture.loaded.set(true);
        fixture.tick();

        verify(fixture.lease).close();
        verify(fixture.leases, times(2)).retain(any(), any(), eq(0), eq(0));
        verify(fixture.travel, never()).begin(any(), anyLong());
        fixture.prepared.clear();
        verify(replacement).close();
    }

    @Test
    public void losingInterestOrPreparedCapabilityReleasesTheUnfinishedLanding() {
        Fixture withdrawn = new Fixture();
        withdrawn.tick();
        withdrawn.interested.set(false);
        withdrawn.tick();
        verify(withdrawn.lease).close();
        verify(withdrawn.travel, never()).begin(any(), anyLong());

        Fixture disabled = new Fixture();
        disabled.tick();
        when(disabled.session.preparedTravelSelected()).thenReturn(false);
        disabled.tick();
        verify(disabled.lease).close();
        verify(disabled.travel, never()).begin(any(), anyLong());
    }

    @Test
    public void unsuccessfulLoadReleasesTheLeaseAndDoesNotCaptureOrRetryEveryTick() {
        Fixture fixture = new Fixture();
        try (MockedStatic<MinecraftPortalEnvironment> environment = environment(fixture)) {
            fixture.tick();
            fixture.ready.complete(false);
            fixture.tick();
            fixture.tick();

            verify(fixture.lease).close();
            verify(fixture.leases).retain(any(), any(), eq(0), eq(0));
            verify(fixture.travel, never()).begin(any(), anyLong());
            environment.verifyNoInteractions();
        }
    }

    @Test
    public void rejectedChunkBudgetReleasesTransferredLeasesAndBacksOffAnotherPreparation() {
        Fixture fixture = new Fixture();
        ClientViewMessage.TravelCoordinate coordinate = new ClientViewMessage.TravelCoordinate(0, 0);
        when(fixture.travel.nextCapture()).thenReturn(coordinate);
        when(fixture.travel.nextRevision(coordinate)).thenReturn(1);
        try (MockedStatic<MinecraftPortalEnvironment> environment = environment(fixture);
             MockedStatic<MinecraftChunkPacketEncoding> encoding = mockStatic(MinecraftChunkPacketEncoding.class);
             MockedConstruction<ClientboundLevelChunkWithLightPacket> packets = mockConstruction(ClientboundLevelChunkWithLightPacket.class)) {
            encoding.when(() -> MinecraftChunkPacketEncoding.encode(any(), any())).thenReturn(new byte[]{1});
            fixture.tick();
            fixture.loaded.set(true);
            fixture.ready.complete(true);
            fixture.tick();
            fixture.tick();

            verify(fixture.travel).begin(any(), anyLong());
            verify(fixture.travel).column(eq(coordinate), eq(1), any());
            verify(fixture.leases).retain(any(), any(), eq(0), eq(0));
            verify(fixture.lease).close();
            assertEquals(1, packets.constructed().size());
        }
    }

    private static MockedStatic<MinecraftPortalEnvironment> environment(Fixture fixture) {
        MockedStatic<MinecraftPortalEnvironment> environment = mockStatic(MinecraftPortalEnvironment.class);
        environment.when(() -> MinecraftPortalEnvironment.capture(eq(fixture.level), any(), any(), anyBoolean()))
            .thenReturn(PortalEnvironmentTest.environment(ClientViewEnvironment.Transform.IDENTITY));
        return environment;
    }

    private static MinecraftPortal portal() {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        when(portal.getId()).thenReturn(UUID.randomUUID());
        when(portal.getOrigin()).thenReturn(new GeometryVector(0, 64, 0));
        when(portal.getFrame()).thenReturn(PortalFrame.canonical(Direction.N));
        when(portal.isOpen()).thenReturn(true);
        return portal;
    }

    private static final class Fixture {
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final MinecraftClientViewPortalAccess portals = mock(MinecraftClientViewPortalAccess.class);
        private final MinecraftProjectorPortalAccess access = mock(MinecraftProjectorPortalAccess.class);
        private final MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        private final MinecraftClientViewPeer peer = mock(MinecraftClientViewPeer.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final MinecraftPortal source = portal();
        private final MinecraftPortal destination = portal();
        private final ClientPreparedTravelServer travel = mock(ClientPreparedTravelServer.class);
        private final ChunkLease lease = mock(ChunkLease.class);
        private final CompletableFuture<Boolean> ready = new CompletableFuture<>();
        private final AtomicBoolean loaded = new AtomicBoolean();
        private final AtomicBoolean interested = new AtomicBoolean(true);
        private final AtomicLong route = new AtomicLong(1L);
        @SuppressWarnings("unchecked")
        private final ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        @SuppressWarnings("unchecked")
        private final ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = mock(ClientViewServerSession.class);
        private final MinecraftPreparedTravel prepared = new MinecraftPreparedTravel(runtime, portals);

        @SuppressWarnings("unchecked")
        private Fixture() {
            UUID playerId = UUID.randomUUID();
            ServerChunkCache chunks = mock(ServerChunkCache.class);
            LevelChunk chunk = mock(LevelChunk.class);
            MinecraftClientViewScene scene = mock(MinecraftClientViewScene.class);
            MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
            MinecraftServer server = mock(MinecraftServer.class);
            PlayerList players = mock(PlayerList.class);
            Holder.Reference<DimensionType> dimension = mock(Holder.Reference.class);
            PortalFrame frame = PortalFrame.canonical(Direction.N);
            ClientPortalGeometry geometry = new ClientPortalGeometry(0, 64, 0, Direction.N.ordinal(), true, 0, false,
                2, 3, new long[]{63}, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 11, List.of());
            when(runtime.portals()).thenReturn(registry);
            when(runtime.leases()).thenReturn(leases);
            when(runtime.server()).thenReturn(server);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getViewDistance()).thenReturn(5);
            when(runtime.projections()).thenReturn(projections);
            when(projections.changes()).thenReturn(new ProjectionWorldChangeTracker());
            when(player.getUUID()).thenReturn(playerId);
            when(player.getY()).thenReturn(64.0D);
            when(player.level()).thenReturn(level);
            when(player.requestedViewDistance()).thenReturn(8);
            when(peer.portals()).thenReturn(access);
            when(access.projectionDestination(source)).thenReturn(destination);
            when(access.routeIdentity(source)).thenAnswer(ignored -> route.get());
            when(portals.portal(peer, source.getId())).thenReturn(source);
            when(portals.scene()).thenReturn(scene);
            when(scene.destination(peer, source.getId(), true)).thenReturn(new MinecraftClientViewScene.Destination(level,
                destination, new ClientViewEntityTransform.Frame(0, 64, 0, frame, 0, 64, 0, frame, false, 0, true, 64)));
            when(registry.get(source.getId())).thenReturn(source);
            when(registry.resolveLevel(destination)).thenReturn(level);
            when(registry.canDepart(player, source)).thenReturn(true);
            when(registry.canArrive(player, destination)).thenReturn(true);
            when(session.player()).thenReturn(peer);
            when(session.playerId()).thenReturn(playerId);
            when(session.travel()).thenReturn(travel);
            when(session.preparedTravelSelected()).thenReturn(true);
            when(session.travelGeometry(source.getId())).thenReturn(geometry);
            when(travel.takeCross()).thenReturn(Optional.empty());
            when(travel.preparing()).thenReturn(Optional.empty());
            when(level.getChunkSource()).thenReturn(chunks);
            when(chunks.getChunkNow(anyInt(), anyInt())).thenAnswer(ignored -> loaded.get() ? chunk : null);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.dimensionTypeRegistration()).thenReturn(dimension);
            when(dimension.unwrapKey()).thenReturn(Optional.of(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse("minecraft:overworld"))));
            when(level.getMinY()).thenReturn(-64);
            when(level.getHeight()).thenReturn(384);
            when(level.getSeaLevel()).thenReturn(63);
            when(leases.retain(any(), any(), anyInt(), anyInt())).thenReturn(lease);
            when(lease.isValid()).thenReturn(true);
            when(lease.ready()).thenReturn(ready);
            doAnswer(invocation -> {
                List<UUID> ids = invocation.getArgument(1);
                if (interested.get()) {
                    ids.add(source.getId());
                }
                return null;
            }).when(portals).interested(eq(peer), any());
        }

        private void tick() {
            prepared.tick(session, player);
        }
    }
}
