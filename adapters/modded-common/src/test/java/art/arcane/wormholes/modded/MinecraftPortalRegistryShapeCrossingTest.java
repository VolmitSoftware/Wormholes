package art.arcane.wormholes.modded;

import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import art.arcane.wormholes.network.MinecraftEntityTransfers;
import art.arcane.wormholes.network.MinecraftPlayerHandoffs;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.wormholes.rules.RuleDocument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalRegistryShapeCrossingTest extends MinecraftTestBase {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void aSegmentThroughACornerCellOutsideTheCircleIsNotACrossing() throws Exception {
        Fixture fixture = fixture(0.3D);
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            shapedPair(fixture, registry);
            registry.tick();
            verify(fixture.leases(), never()).retain(any(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void aSegmentThroughTheCenterIsACrossing() throws Exception {
        Fixture fixture = fixture(3.5D);
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            shapedPair(fixture, registry);
            registry.tick();
            verify(fixture.leases(), times(1)).retain(any(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void theSameCornerSegmentCrossesAFullAperture() throws Exception {
        Fixture fixture = fixture(0.3D);
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = wall(registry, fixture.level(), 0);
            MinecraftPortal destination = wall(registry, fixture.level(), 20);
            assertTrue(registry.link(fixture.actor(), source.getId(), destination.getId()));
            registry.tick();
            verify(fixture.leases(), times(1)).retain(any(), any(), anyInt(), anyInt());
        }
    }

    private void shapedPair(Fixture fixture, MinecraftPortalRegistry registry) {
        MinecraftPortal source = wall(registry, fixture.level(), 0);
        MinecraftPortal destination = wall(registry, fixture.level(), 20);
        assertTrue(registry.link(fixture.actor(), source.getId(), destination.getId()));
        assertTrue(registry.update(fixture.actor(), source.getId(), portal -> assertTrue(portal.setApertureShape(ShapeDescriptor.parse("circle")))));
    }

    private MinecraftPortal wall(MinecraftPortalRegistry registry, ServerLevel level, int x) {
        List<BlockPos> cells = new ArrayList<>(49);
        for (int y = 64; y <= 70; y++) {
            for (int z = 0; z <= 6; z++) {
                cells.add(new BlockPos(x, y, z));
            }
        }
        return registry.create(UUID.randomUUID(), level, cells, PortalType.PORTAL, new Vec3(1, 0, 0));
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
    private Fixture fixture(double z) {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        when(runtime.nexus()).thenReturn(mock(MinecraftNexus.class));
        when(runtime.doors()).thenReturn(mock(MinecraftDoorService.class));
        MinecraftNetworkService network = mock(MinecraftNetworkService.class);
        when(runtime.network()).thenReturn(network);
        when(runtime.rtp()).thenReturn(mock(MinecraftRtpRuntime.class));
        when(runtime.clientViews()).thenReturn(mock(MinecraftClientViewService.class));
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
        when(runtime.server()).thenReturn(server);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(MinecraftTestSettings.defaults());
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
        when(entity.position()).thenReturn(new Vec3(0.1D, 64.0D, z));
        when(entity.getDeltaMovement()).thenReturn(new Vec3(-0.4D, 0.0D, 0.0D));
        when(entity.getLookAngle()).thenReturn(new Vec3(-1.0D, 0.0D, 0.0D));
        when(entity.getEyeHeight()).thenReturn(1.62F);
        when(entity.isAlive()).thenReturn(true);
        when(entity.getPassengers()).thenReturn(List.of());
        when(entity.getSelfAndPassengers()).thenAnswer(ignored -> Stream.of(entity));
        when(entity.getInterpolation()).thenReturn(InterpolationHandler.NO_OP);
        when(entity.getBoundingBox()).thenReturn(new AABB(-0.2D, 64.0D, z - 0.3D, 0.4D, 65.8D, z + 0.3D));
        entity.xo = 1.0D;
        entity.yo = 64.0D;
        entity.zo = z;
        when(runtime.leases()).thenReturn(leases);
        when(leases.retain(any(), any(), anyInt(), anyInt())).thenReturn(lease);
        when(lease.ready()).thenReturn(new CompletableFuture<>());
        MinecraftPortalRegistry registry = new MinecraftPortalRegistry(runtime, options());
        when(runtime.portals()).thenReturn(registry);
        return new Fixture(registry, level, actor, leases);
    }

    private record Fixture(MinecraftPortalRegistry registry, ServerLevel level, ServerPlayer actor, ChunkLeaseRegistry<ServerLevel> leases) {
    }
}
