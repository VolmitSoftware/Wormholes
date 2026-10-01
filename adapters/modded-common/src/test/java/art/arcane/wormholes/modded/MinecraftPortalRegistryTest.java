package art.arcane.wormholes.modded;

import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.wormholes.network.MinecraftPlayerHandoffs;
import art.arcane.wormholes.network.MinecraftEntityTransfers;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalRegistryTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void reloadPreservesLinksSparseCellsAndStablePermissionIdentity() throws Exception {
        Fixture fixture = fixture();
        UUID first;
        UUID second;
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = create(registry, fixture.level(), 0);
            MinecraftPortal destination = create(registry, fixture.level(), 10);
            first = source.getId();
            second = destination.getId();
            assertTrue(registry.link(fixture.actor(), first, second));
            String permissionKey = (String) source.setting("access.permissionKey");
            assertTrue(registry.update(fixture.actor(), first, portal -> portal.setName("Renamed")));
            assertEquals(permissionKey, source.setting("access.permissionKey"));
        }
        try (MinecraftPortalRegistry loaded = new MinecraftPortalRegistry(fixture.runtime(), options())) {
            loaded.load();
            assertEquals(2, loaded.snapshot().size());
            MinecraftPortal source = loaded.get(first);
            assertEquals("Renamed", source.getName());
            assertEquals(second, source.getDestinationId());
            assertFalse(source.getGeometry().containsBlock(0, 65, 0));
            assertEquals(2, source.getGeometry().getBlockPositions().size());
        }
    }

    @Test
    public void unlinkWhileDestinationChunkLoadsCancelsTheCrossing() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = create(registry, fixture.level(), 0);
            MinecraftPortal destination = create(registry, fixture.level(), 10);
            registry.link(fixture.actor(), source.getId(), destination.getId());
            registry.tick();
            registry.link(fixture.actor(), source.getId(), null);
            fixture.ready().complete(true);
            fixture.tasks().removeFirst().run();
            verify(fixture.entity(), never()).teleport(any(TeleportTransition.class));
            verify(fixture.lease()).close();
        }
    }

    @Test
    public void leavingTheCapturedPositionWhileLoadingCancelsTheCrossing() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = create(registry, fixture.level(), 0);
            MinecraftPortal destination = create(registry, fixture.level(), 10);
            registry.link(fixture.actor(), source.getId(), destination.getId());
            registry.tick();
            when(fixture.entity().position()).thenReturn(new Vec3(100, 64.2, 0.2));
            fixture.ready().complete(true);
            fixture.tasks().removeFirst().run();
            verify(fixture.entity(), never()).teleport(any(TeleportTransition.class));
            verify(fixture.lease()).close();
        }
    }

    @Test
    public void readyChunkCommitsFrameTransformedNativeTeleport() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = create(registry, fixture.level(), 0);
            MinecraftPortal destination = create(registry, fixture.level(), 10);
            registry.link(fixture.actor(), source.getId(), destination.getId());
            when(fixture.entity().teleport(any(TeleportTransition.class))).thenAnswer(invocation -> {
                TeleportTransition transition = invocation.getArgument(0);
                assertEquals(10.1D, transition.position().x, 1e-9D);
                assertEquals(-0.4D, transition.deltaMovement().x, 1e-9D);
                assertEquals(fixture.level(), transition.newLevel());
                return fixture.entity();
            });
            registry.tick();
            verify(fixture.entity(), never()).teleport(any(TeleportTransition.class));
            fixture.ready().complete(true);
            fixture.tasks().removeFirst().run();
            verify(fixture.entity()).teleport(any(TeleportTransition.class));
            verify(fixture.lease()).close();
        }
    }

    @Test
    public void apiResolverDestinationSurvivesChunkPreparationWithoutStoredLink() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = create(registry, fixture.level(), 0);
            MinecraftPortal destination = create(registry, fixture.level(), 10);
            MinecraftWormholesApi api = mock(MinecraftWormholesApi.class);
            when(fixture.runtime().api()).thenReturn(api);
            when(api.hasResolvers()).thenReturn(true);
            when(api.resolve(source, fixture.entity().getUUID())).thenReturn(new NetworkMember(destination.getId(), "", "", 0, null));
            when(fixture.entity().teleport(any(TeleportTransition.class))).thenReturn(fixture.entity());
            registry.tick();
            fixture.ready().complete(true);
            fixture.tasks().removeFirst().run();
            verify(fixture.entity()).teleport(any(TeleportTransition.class));
            assertEquals(null, source.getDestinationId());
            verify(fixture.lease()).close();
        }
    }

    @Test
    public void bouncingPortalReflectsMotionWithoutLoadingDestination() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = create(registry, fixture.level(), 0);
            MinecraftPortal destination = create(registry, fixture.level(), 10);
            registry.link(fixture.actor(), source.getId(), destination.getId());
            source.setBounce(true);
            registry.tick();
            verify(fixture.entity()).setDeltaMovement(argThat(velocity ->
                Math.abs(velocity.x - 0.4D) < 1e-9D && velocity.y == 0 && velocity.z == 0));
            verify(fixture.lease(), never()).ready();
            verify(fixture.entity(), never()).teleport(any(TeleportTransition.class));
        }
    }

    @Test
    public void membraneRefusesBackFaceBeforeLoadingDestination() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = create(registry, fixture.level(), 0);
            MinecraftPortal destination = create(registry, fixture.level(), 10);
            registry.link(fixture.actor(), source.getId(), destination.getId());
            source.setMembrane(true);
            fixture.entity().xo = 0.1D;
            when(fixture.entity().position()).thenReturn(new Vec3(1, 64.2D, 0.2D));
            when(fixture.entity().getDeltaMovement()).thenReturn(new Vec3(0.4D, 0, 0));
            registry.tick();
            verify(fixture.lease(), never()).ready();
            verify(fixture.entity(), never()).teleport(any(TeleportTransition.class));
        }
    }

    @Test
    public void atomicWriterReplacesDocumentAndLeavesNoTemporaryFile() throws Exception {
        Path file = directory.getRoot().toPath().resolve("bucket/portal.json");
        MinecraftPortalRegistry.write(file, "first");
        MinecraftPortalRegistry.write(file, "second");
        assertEquals("second", Files.readString(file));
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList());
        }
    }

    private MinecraftPortal create(MinecraftPortalRegistry registry, ServerLevel level, int x) {
        return registry.create(UUID.randomUUID(), level, List.of(new BlockPos(x, 64, 0), new BlockPos(x, 66, 0)),
            PortalType.PORTAL, new Vec3(1, 0, 0));
    }

    private MinecraftPortalRegistry.Options options() {
        return new MinecraftPortalRegistry.Options(directory.getRoot().toPath(), new MinecraftPortalRegistry.Access() {
            @Override
            public boolean administrator(ServerPlayer player) {
                return true;
            }

            @Override
            public boolean permission(ServerPlayer player, String node) {
                return false;
            }

            @Override
            public boolean nameAlias() {
                return true;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        when(runtime.nexus()).thenReturn(mock(MinecraftNexus.class));
        MinecraftNetworkService network = mock(MinecraftNetworkService.class);
        when(runtime.network()).thenReturn(network);
        when(runtime.rtp()).thenReturn(mock(MinecraftRtpRuntime.class));
        when(runtime.clientViews()).thenReturn(new MinecraftClientViewService(runtime));
        when(network.handoffs()).thenReturn(mock(MinecraftPlayerHandoffs.class));
        when(network.entityTransfers()).thenReturn(mock(MinecraftEntityTransfers.class));
        MinecraftRules rules = mock(MinecraftRules.class);
        when(runtime.rules()).thenReturn(rules);
        when(rules.document(any())).thenReturn(RuleDocument.EMPTY);
        when(rules.screeningAllowed(any(), any())).thenReturn(true);
        when(rules.depart(any(), any(), any())).thenReturn(true);
        when(rules.arrivalAllowed(any(), any(), anyBoolean())).thenReturn(true);
        when(rules.reserve(any(), any())).thenReturn(true);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer actor = mock(ServerPlayer.class);
        Entity entity = mock(Entity.class);
        PlayerList players = mock(PlayerList.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        ChunkLease lease = mock(ChunkLease.class);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        List<Runnable> tasks = new ArrayList<>();
        when(runtime.server()).thenReturn(server);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new NetworkConfig()));
        when(server.getAllLevels()).thenReturn(List.of(level));
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(320);
        when(level.getEntities((Entity) isNull(), any(AABB.class), any(Predicate.class))).thenReturn(List.of(entity));
        when(actor.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getRootVehicle()).thenReturn(entity);
        when(entity.level()).thenReturn(level);
        when(entity.position()).thenReturn(new Vec3(0.1, 64.2, 0.2));
        when(entity.getDeltaMovement()).thenReturn(new Vec3(-0.4, 0, 0));
        when(entity.getLookAngle()).thenReturn(new Vec3(-1, 0, 0));
        when(entity.isAlive()).thenReturn(true);
        when(entity.getPassengers()).thenReturn(List.of());
        when(entity.getSelfAndPassengers()).thenAnswer(ignored -> Stream.of(entity));
        when(entity.getBoundingBox()).thenReturn(new AABB(0, 64, 0, 0.5, 65, 0.5));
        entity.xo = 1;
        entity.yo = 64.2;
        entity.zo = 0.2;
        when(runtime.leases()).thenReturn(leases);
        when(leases.retain(any(), any(), anyInt(), anyInt())).thenReturn(lease);
        when(lease.ready()).thenReturn(ready);
        doAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return null;
        }).when(server).execute(any(Runnable.class));
        MinecraftPortalRegistry registry = new MinecraftPortalRegistry(runtime, options());
        when(runtime.portals()).thenReturn(registry);
        return new Fixture(runtime, registry, level, actor, entity, lease, ready, tasks);
    }

    private record Fixture(WormholesModRuntime runtime, MinecraftPortalRegistry registry, ServerLevel level,
                           ServerPlayer actor, Entity entity, ChunkLease lease, CompletableFuture<Boolean> ready,
                           List<Runnable> tasks) {
    }
}
