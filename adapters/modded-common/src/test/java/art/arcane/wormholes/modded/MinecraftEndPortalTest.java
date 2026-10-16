package art.arcane.wormholes.modded;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import art.arcane.wormholes.network.MinecraftEntityTransfers;
import art.arcane.wormholes.network.MinecraftPlayerHandoffs;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.wormholes.rules.RuleDocument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftEndPortalTest extends MinecraftTestBase {
    private static final int FLOOR = 64;
    private static final int ARRIVAL = 80;

    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void vanillaFountainAperturePreservesTheBedrockPillarAndDeactivatesOnRespawn() throws Exception {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        WorldGenLevel generated = mock(WorldGenLevel.class);
        when(generated.getBlockState(any(BlockPos.class))).thenAnswer(invocation ->
            blocks.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()));
        MinecraftTestAccess.recordPodium(generated, blocks);
        BlockPos center = new BlockPos(0, 64, 0);
        MinecraftTestAccess.podium(generated, true, center, 1);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.END);
        when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);
        when(level.getBlockState(any(BlockPos.class))).thenAnswer(invocation ->
            blocks.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()));
        Object shape = shape(level, center.east());
        Method cellsMethod = shape.getClass().getDeclaredMethod("cells");
        cellsMethod.setAccessible(true);
        List<?> cells = (List<?>) cellsMethod.invoke(shape);
        assertEquals(20, cells.size());
        assertFalse(cells.contains(center));
        assertTrue(blocks.get(center).is(Blocks.BEDROCK));
        for (Object value : cells) {
            BlockPos cell = (BlockPos) value;
            assertEquals(64, cell.getY());
            assertTrue(blocks.get(cell).is(Blocks.END_PORTAL));
        }
        MinecraftTestAccess.podium(generated, false, center, 2);
        assertNull(shape(level, center.east()));
        assertTrue(blocks.get(center).is(Blocks.BEDROCK));
        Method active = MinecraftVanillaPortals.class.getDeclaredMethod("exitActive", ServerLevel.class, List.class);
        active.setAccessible(true);
        assertEquals(false, active.invoke(null, level, cells));
    }

    @Test
    public void oneWayEndEntryLinksItsReceiverWithoutAReverseDestination() throws Exception {
        Pairing pairing = new Pairing(false);

        assertTrue(pairing.pair());

        assertEquals(pairing.arrival.getId(), pairing.source.getDestinationId());
        assertNull(pairing.arrival.getDestinationId());
        assertTrue(pairing.source.isOutgoingTraversalsEnabled());
        assertFalse(pairing.source.isIncomingTraversalsEnabled());
        assertFalse(pairing.arrival.isOutgoingTraversalsEnabled());
        assertTrue(pairing.arrival.isIncomingTraversalsEnabled());
        assertEquals(ProjectionMode.OFF, pairing.arrival.getProjectionMode());
        pairing.arrival.setOutgoingTraversalsEnabled(true);
        pairing.arrival.setProjectionMode(ProjectionMode.ON);
        when(pairing.registry.snapshot()).thenReturn(List.of(pairing.source, pairing.arrival));
        pairing.vanilla.applyEndTravel();
        assertFalse(pairing.arrival.isOutgoingTraversalsEnabled());
        assertEquals(ProjectionMode.OFF, pairing.arrival.getProjectionMode());
    }

    @Test
    public void twoWayEndEntryLinksTheArrivalBackToItsSourceWithBothViewsOn() throws Exception {
        Pairing pairing = new Pairing(true);

        assertTrue(pairing.pair());

        assertEquals(pairing.arrival.getId(), pairing.source.getDestinationId());
        assertEquals(pairing.source.getId(), pairing.arrival.getDestinationId());
        assertEquals("DIMENSIONAL", pairing.arrival.getTunnelType());
        assertEquals(pairing.source.getId(), pairing.arrival.getCounterpartId());
        assertTrue(pairing.source.isOutgoingTraversalsEnabled());
        assertTrue(pairing.source.isIncomingTraversalsEnabled());
        assertTrue(pairing.arrival.isOutgoingTraversalsEnabled());
        assertTrue(pairing.arrival.isIncomingTraversalsEnabled());
        assertEquals(ProjectionMode.ON, pairing.source.getProjectionMode());
        assertEquals(ProjectionMode.ON, pairing.arrival.getProjectionMode());
    }

    @Test
    public void reloadingTheEndTravelSettingConvertsExistingPairsBothWays() throws Exception {
        Pairing pairing = new Pairing(true);
        assertTrue(pairing.pair());
        when(pairing.registry.snapshot()).thenReturn(List.of(pairing.source, pairing.arrival));

        pairing.settings.getMain().twoWayEndPortals = false;
        pairing.vanilla.applyEndTravel();

        assertNull(pairing.arrival.getDestinationId());
        assertEquals(pairing.source.getId(), pairing.arrival.getCounterpartId());
        assertFalse(pairing.arrival.isOutgoingTraversalsEnabled());
        assertFalse(pairing.source.isIncomingTraversalsEnabled());
        assertEquals(ProjectionMode.OFF, pairing.arrival.getProjectionMode());
        verify(pairing.registry, times(2)).save(pairing.arrival);

        pairing.settings.getMain().twoWayEndPortals = true;
        pairing.vanilla.applyEndTravel();

        assertEquals(pairing.source.getId(), pairing.arrival.getDestinationId());
        assertTrue(pairing.arrival.isOutgoingTraversalsEnabled());
        assertTrue(pairing.source.isIncomingTraversalsEnabled());
        assertEquals(ProjectionMode.ON, pairing.arrival.getProjectionMode());
        assertEquals(pairing.arrival.getId(), pairing.source.getDestinationId());
    }

    @Test
    public void aTravellerStandingOnTheFloorInsideAnEndSourceIsSentToJustBelowTheArrivalPlane() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = fixture.endPair(registry, DimensionalPortalKind.END_SOURCE);
            Entity entity = fixture.entity(mock(Entity.class), 11.5D, FLOOR, 11.5D);

            registry.tick();

            verify(fixture.leases(), times(1)).retain(any(), any(), anyInt(), anyInt());
            PlaneCrossing crossing = fixture.crossing(source, entity);
            assertFalse(crossing.frontSide());
            assertEquals(Face.U, crossing.frame().getNormal());
            assertEquals(11.5D, crossing.point().x(), 1.0E-9D);
            assertEquals(FLOOR, crossing.point().y(), 1.0E-9D);
            assertEquals(11.5D, crossing.point().z(), 1.0E-9D);
            Vec3d arrival = fixture.arrival(source, crossing);
            assertEquals(ARRIVAL, arrival.y(), 1.0E-9D);
            assertTrue(arrival.y() < ARRIVAL + 0.5D);
            assertTrue(fixture.arrivalPortal().getGeometry().getArea().contains(new Vec3d(arrival.x(), ARRIVAL + 0.5D, arrival.z())));
        }
    }

    @Test
    public void aTravellerStillOnTheFrameRingAboveTheEndPortalTopIsNotSent() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            fixture.endPair(registry, DimensionalPortalKind.END_SOURCE);
            fixture.entity(mock(Entity.class), 11.5D, FLOOR + 0.8125D, 11.5D);

            registry.tick();

            verify(fixture.leases(), never()).retain(any(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void aTravellerDroppingOffTheRingIsSentFromJustBelowTheSourcePlane() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = fixture.endPair(registry, DimensionalPortalKind.END_SOURCE);
            Entity entity = fixture.entity(mock(Entity.class), 11.5D, FLOOR + 0.7D, 11.5D);

            registry.tick();

            PlaneCrossing crossing = fixture.crossing(source, entity);
            assertEquals(FLOOR + 0.5D - 1.0E-3D, crossing.point().y(), 1.0E-9D);
            assertEquals(ARRIVAL + 0.5D - 1.0E-3D, fixture.arrival(source, crossing).y(), 1.0E-9D);
        }
    }

    @Test
    public void anArmedPlayerStandingOnTheFloorInsideAnEndSourceIsSentWithoutWaitingForItsEye() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = fixture.endPair(registry, DimensionalPortalKind.END_SOURCE);
            ServerPlayer player = fixture.entity(worldGravity(mock(ServerPlayer.class)), 11.5D, FLOOR, 11.5D);
            when(fixture.views().seamlessGeometry(player.getUUID(), source.getId())).thenReturn(Registry.armed(ApertureKind.VANILLA_REPLACEMENT));

            registry.tick();

            verify(fixture.leases(), times(1)).retain(any(), any(), anyInt(), anyInt());
            assertEquals(FLOOR, fixture.crossing(source, player).point().y(), 1.0E-9D);
        }
    }

    @Test
    public void anArmedPlayerInsideAFrameFloorPortalOverAirWaitsForItsEye() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = fixture.endPair(registry, DimensionalPortalKind.NONE);
            ServerPlayer player = fixture.entity(worldGravity(mock(ServerPlayer.class)), 11.5D, FLOOR, 11.5D);
            when(fixture.views().seamlessGeometry(player.getUUID(), source.getId())).thenReturn(Registry.armed(ApertureKind.FRAME));

            registry.tick();

            verify(fixture.leases(), never()).retain(any(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void anArmedPlayerRestingOnTheBlockUnderAFrameFloorPortalIsSentFromJustPastThePlane() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = fixture.endPair(registry, DimensionalPortalKind.NONE);
            ServerPlayer player = fixture.entity(worldGravity(mock(ServerPlayer.class)), 11.5D, FLOOR, 11.5D);
            when(fixture.views().seamlessGeometry(player.getUUID(), source.getId())).thenReturn(Registry.armed(ApertureKind.FRAME));
            fixture.backing(FLOOR);

            registry.tick();

            verify(fixture.leases(), times(1)).retain(any(), any(), anyInt(), anyInt());
            PlaneCrossing crossing = fixture.crossing(source, player);
            assertFalse(crossing.frontSide());
            assertEquals(FLOOR + 0.5D - 1.0E-3D, crossing.point().y() + 1.62D, 1.0E-6D);
            assertEquals(11.5D, crossing.point().x(), 0.0D);
            assertEquals(ARRIVAL + 0.5D - 1.0E-3D, fixture.arrival(source, crossing).y() + 1.62D, 1.0E-6D);
        }
    }

    @Test
    public void anUnarmedEntityStandingInsideAFrameFloorPortalIsNotSent() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            fixture.endPair(registry, DimensionalPortalKind.NONE);
            fixture.entity(mock(Entity.class), 11.5D, FLOOR, 11.5D);
            fixture.backing(FLOOR);

            registry.tick();

            verify(fixture.leases(), never()).retain(any(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void anArrivalInsideTheBlockUnderAFloorDestinationIsLiftedAboveItsCells() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = fixture.endPair(registry, DimensionalPortalKind.NONE);
            Entity entity = fixture.entity(mock(Entity.class), 11.5D, FLOOR - 1.0D, 11.5D);
            when(fixture.level.noCollision(any(), any(AABB.class))).thenAnswer(invocation -> ((AABB) invocation.getArgument(1)).minY >= ARRIVAL);
            PlaneCrossing upward = new PlaneCrossing(source.getFrame().view(true), source.getOrigin(), new Vec3d(11.5D, FLOOR + 0.501D - 1.62D, 11.5D),
                new Vec3d(0.0D, 0.2D, 0.0D), new Vec3d(0.0D, 1.0D, 0.0D), true);

            PlaneCrossing cleared = MinecraftPortalRegistry.cleared(entity, source, fixture.arrivalPortal(), fixture.level, upward);

            assertEquals(ARRIVAL + 1.0D + 1.0E-3D, fixture.arrival(source, cleared).y(), 1.0E-9D);
            assertEquals(11.5D, cleared.point().x(), 0.0D);
            assertEquals(upward.frontSide(), cleared.frontSide());
        }
    }

    @Test
    public void aClearArrivalKeepsItsCrossingPoint() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = fixture.endPair(registry, DimensionalPortalKind.NONE);
            Entity entity = fixture.entity(mock(Entity.class), 11.5D, FLOOR, 11.5D);
            when(fixture.level.noCollision(any(), any(AABB.class))).thenReturn(true);
            PlaneCrossing downward = new PlaneCrossing(source.getFrame().view(false), source.getOrigin(), new Vec3d(11.5D, FLOOR - 1.121D, 11.5D),
                new Vec3d(0.0D, -0.5D, 0.0D), new Vec3d(0.0D, -1.0D, 0.0D), false);

            assertSame(downward, MinecraftPortalRegistry.cleared(entity, source, fixture.arrivalPortal(), fixture.level, downward));
        }
    }

    @Test
    public void aRisingEntityCrossesATwoWayEndArrivalAndLandsOnTheEndPortalFrame() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = fixture.twoWayPair(registry);
            MinecraftPortal arrival = fixture.arrivalPortal();
            Entity entity = fixture.entity(mock(Entity.class), 30.4D, ARRIVAL + 0.7D, 31.5D);
            entity.yo = ARRIVAL + 0.3D;
            when(fixture.level.noCollision(any(), any(AABB.class))).thenReturn(true);

            registry.tick();

            verify(fixture.leases(), times(1)).retain(any(), any(), anyInt(), anyInt());
            PlaneCrossing crossing = fixture.crossing(arrival, entity);
            assertTrue(crossing.frontSide());
            PlaneCrossing landed = MinecraftPortalRegistry.cleared(entity, arrival, source, fixture.level, crossing);
            Vec3d feet = MinecraftPortalRegistry.passage(arrival, source, landed).toward().point(landed.point());
            assertEquals(FLOOR + 1.0D, feet.y(), 1.0E-9D);
            assertEquals(9.5D, feet.x(), 1.0E-9D);
            assertEquals(11.5D, feet.z(), 1.0E-9D);
        }
    }

    @Test
    public void anObstructedFrameRowSendsTheReturningTravellerToTheNextNearestRow() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = fixture.twoWayPair(registry);
            MinecraftPortal arrival = fixture.arrivalPortal();
            Entity entity = fixture.entity(mock(Entity.class), 30.4D, ARRIVAL + 0.7D, 31.5D);
            when(fixture.level.noCollision(any(), any(AABB.class))).thenAnswer(invocation -> ((AABB) invocation.getArgument(1)).maxX > 10.0D);
            PlaneCrossing rising = new PlaneCrossing(arrival.getFrame().view(true), arrival.getOrigin(), new Vec3d(30.4D, ARRIVAL + 0.501D, 31.5D),
                new Vec3d(0.0D, 0.3D, 0.0D), new Vec3d(0.0D, 1.0D, 0.0D), true);

            PlaneCrossing landed = MinecraftPortalRegistry.cleared(entity, arrival, source, fixture.level, rising);
            Vec3d feet = MinecraftPortalRegistry.passage(arrival, source, landed).toward().point(landed.point());

            assertEquals(FLOOR + 1.0D, feet.y(), 1.0E-9D);
            assertEquals(10.4D, feet.x(), 1.0E-9D);
            assertEquals(9.5D, feet.z(), 1.0E-9D);
        }
    }

    @Test
    public void anEntityFallingThroughATwoWayEndArrivalIsNotSentBack() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            fixture.twoWayPair(registry);
            Entity entity = fixture.entity(mock(Entity.class), 30.4D, ARRIVAL + 0.3D, 31.5D);
            entity.yo = ARRIVAL + 0.7D;

            registry.tick();

            verify(fixture.leases(), never()).retain(any(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void aOneWayEndArrivalNeverSendsARisingEntity() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            fixture.settings().getMain().twoWayEndPortals = false;
            fixture.endPair(registry, DimensionalPortalKind.END_SOURCE);
            Entity entity = fixture.entity(mock(Entity.class), 30.4D, ARRIVAL + 0.7D, 31.5D);
            entity.yo = ARRIVAL + 0.3D;

            registry.tick();

            verify(fixture.leases(), never()).retain(any(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void anEnderDragonOrItsPartTouchingAnEndPortalNeverTravels() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            fixture.twoWayPair(registry);
            Entity dragon = fixture.entity(mock(Entity.class), 11.5D, FLOOR, 11.5D);
            when(dragon.getType()).thenAnswer(ignored -> MinecraftEntityTypes.ENDER_DRAGON);
            Entity rising = fixture.entity(mock(Entity.class), 30.4D, ARRIVAL + 0.7D, 31.5D);
            rising.yo = ARRIVAL + 0.3D;
            when(rising.getType()).thenAnswer(ignored -> MinecraftEntityTypes.ENDER_DRAGON);

            registry.tick();

            verify(fixture.leases(), never()).retain(any(), any(), anyInt(), anyInt());
        }
    }

    @Test
    public void theEndExitFountainIsNeverAWormholesDepartureEvenForAdministrators() {
        Registry fixture = new Registry();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal exit = registry.create(null, fixture.level, Registry.floor(50, FLOOR, 50), PortalType.PORTAL, new Vec3(0, 1, 0));
            exit.setDimensionalKind(DimensionalPortalKind.END_EXIT);
            ServerPlayer player = fixture.entity(worldGravity(mock(ServerPlayer.class)), 51.5D, FLOOR + 2.0D, 51.5D);

            assertFalse(registry.canDepart(player, exit));
        }
    }

    static MinecraftPortal portal(String world, int x, int y, int z) {
        List<Vec3d> cells = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                cells.add(new Vec3d(x + dx, y, z + dz));
            }
        }
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(cells);
        UUID id = UUID.randomUUID();
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, geometry.getApertureCenter(), "End portal",
            Frame.canonical(Face.D), true), geometry, world, Map.of("owner", id.toString(), "type", "PORTAL")));
    }

    private static Object shape(ServerLevel level, BlockPos anchor) throws Exception {
        Method shape = MinecraftVanillaPortals.class.getDeclaredMethod("shape", ServerLevel.class, BlockPos.class);
        shape.setAccessible(true);
        return shape.invoke(null, level, anchor);
    }

    private static final class Pairing {
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        private final ServerLevel sourceLevel = mock(ServerLevel.class);
        private final ServerLevel end = mock(ServerLevel.class);
        private final WormholesSettings settings = MinecraftTestSettings.defaults();
        private final MinecraftPortal source = portal("minecraft:overworld", 11, 64, 11);
        private final MinecraftPortal arrival = portal("minecraft:the_end", 12, 74, 9);
        private final MinecraftVanillaPortals vanilla = new MinecraftVanillaPortals(runtime);

        private Pairing(boolean twoWay) {
            MinecraftAccessService access = mock(MinecraftAccessService.class);
            WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
            settings.getMain().twoWayEndPortals = twoWay;
            when(runtime.portals()).thenReturn(registry);
            when(runtime.access()).thenReturn(access);
            when(runtime.configuration()).thenReturn(configuration);
            when(configuration.settings()).thenReturn(settings);
            when(access.canPlace(any())).thenReturn(true);
            when(sourceLevel.dimension()).thenReturn(Level.OVERWORLD);
            when(end.dimension()).thenReturn(Level.END);
            when(sourceLevel.hasChunk(anyInt(), anyInt())).thenReturn(true);
            when(sourceLevel.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
                BlockPos position = invocation.getArgument(0);
                return position.getY() == 64 && position.getX() >= 10 && position.getX() <= 12
                    && position.getZ() >= 10 && position.getZ() <= 12 ? Blocks.END_PORTAL.defaultBlockState() : Blocks.AIR.defaultBlockState();
            });
            arrival.setDimensionalKind(DimensionalPortalKind.END_ARRIVAL);
            arrival.setOutgoingTraversalsEnabled(false);
            arrival.setProjectionMode(ProjectionMode.OFF);
            when(registry.snapshot()).thenReturn(List.of(arrival));
            when(registry.get(source.getId())).thenReturn(source);
            when(registry.get(arrival.getId())).thenReturn(arrival);
            when(registry.create(eq(null), eq(sourceLevel), any(), eq(PortalType.PORTAL), any())).thenReturn(source);
        }

        private boolean pair() throws Exception {
            Object shape = shape(sourceLevel, new BlockPos(10, 64, 10));
            Method pair = MinecraftVanillaPortals.class.getDeclaredMethod("pair", ServerPlayer.class, ServerLevel.class,
                ServerLevel.class, shape.getClass(), BlockPos.class);
            pair.setAccessible(true);
            return (boolean) pair.invoke(vanilla, mock(ServerPlayer.class), sourceLevel, end, shape, new BlockPos(12, 74, 9));
        }
    }

    private final class Registry {
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final MinecraftNexus nexus = mock(MinecraftNexus.class);
        private final MinecraftClientViewService views = mock(MinecraftClientViewService.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final List<Entity> entities = new ArrayList<>();
        @SuppressWarnings("unchecked")
        private final ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        private MinecraftPortal arrival;

        @SuppressWarnings("unchecked")
        private Registry() {
            when(runtime.nexus()).thenReturn(nexus);
            when(runtime.doors()).thenReturn(mock(MinecraftDoorService.class));
            MinecraftNetworkService network = mock(MinecraftNetworkService.class);
            when(runtime.network()).thenReturn(network);
            when(runtime.rtp()).thenReturn(mock(MinecraftRtpRuntime.class));
            when(runtime.clientViews()).thenReturn(views);
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
            PlayerList players = mock(PlayerList.class);
            WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
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
            when(level.getEntities((Entity) isNull(), any(AABB.class), any(Predicate.class))).thenReturn(entities);
            when(runtime.leases()).thenReturn(leases);
            when(leases.retain(any(), any(), anyInt(), anyInt())).thenReturn(lease);
            when(lease.ready()).thenReturn(new CompletableFuture<>());
        }

        private MinecraftPortalRegistry registry() {
            MinecraftPortalRegistry registry = new MinecraftPortalRegistry(runtime, new MinecraftPortalRegistry.Options(directory.getRoot().toPath(),
                new MinecraftPortalRegistry.Access() {
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
                }));
            when(runtime.portals()).thenReturn(registry);
            return registry;
        }

        private MinecraftPortal twoWayPair(MinecraftPortalRegistry registry) {
            MinecraftPortal source = endPair(registry, DimensionalPortalKind.END_SOURCE);
            arrival.link(source);
            source.setIncomingTraversalsEnabled(true);
            arrival.setOutgoingTraversalsEnabled(true);
            arrival.setProjectionMode(ProjectionMode.ON);
            source.setCounterpartId(arrival.getId());
            arrival.setCounterpartId(source.getId());
            return source;
        }

        private WormholesSettings settings() {
            return runtime.configuration().settings();
        }

        private MinecraftPortal endPair(MinecraftPortalRegistry registry, DimensionalPortalKind kind) {
            MinecraftPortal source = registry.create(null, level, floor(10, FLOOR, 10), PortalType.PORTAL, new Vec3(0, 1, 0));
            arrival = registry.create(null, level, floor(30, ARRIVAL, 30), PortalType.PORTAL, new Vec3(0, 1, 0));
            source.setDimensionalKind(kind);
            arrival.setDimensionalKind(kind == DimensionalPortalKind.END_SOURCE ? DimensionalPortalKind.END_ARRIVAL : DimensionalPortalKind.NONE);
            source.link(arrival);
            source.setIncomingTraversalsEnabled(false);
            arrival.setOutgoingTraversalsEnabled(false);
            arrival.setIncomingTraversalsEnabled(true);
            assertEquals(Face.D, source.getFrame().getNormal());
            assertEquals(Face.D, arrival.getFrame().getNormal());
            return source;
        }

        private static ApertureDescriptor armed(int kind) {
            return new ApertureDescriptor(10, FLOOR, 10, Face.D.ordinal(), false, 0, false, 3, 3, new long[]{511L}, ShapeDescriptor.FULL,
                0, 0, 1, 64, 0, 0, 0, 0, 0, 0, kind, 0.0D, 0, 1, List.of());
        }

        private void backing(int cellY) {
            when(level.getBlockCollisions(any(), any(AABB.class))).thenAnswer(invocation ->
                ((AABB) invocation.getArgument(1)).minY < cellY ? List.of(Shapes.block().move(10, cellY - 1, 10)) : List.of());
        }

        private <T extends Entity> T entity(T entity, double x, double feet, double z) {
            worldGravity(entity);
            when(entity.getUUID()).thenReturn(UUID.randomUUID());
            when(entity.getDimensions(any())).thenReturn(EntityDimensions.scalable(0.6F, 1.8F));
            when(entity.getRootVehicle()).thenReturn(entity);
            when(entity.level()).thenReturn(level);
            when(entity.position()).thenReturn(new Vec3(x, feet, z));
            when(entity.getDeltaMovement()).thenReturn(new Vec3(0.0D, -0.0784D, 0.0D));
            when(entity.getLookAngle()).thenReturn(new Vec3(0.0D, -0.5D, -0.866D));
            when(entity.getEyeHeight()).thenReturn(1.62F);
            when(entity.isAlive()).thenReturn(true);
            when(entity.getPassengers()).thenReturn(List.of());
            when(entity.getSelfAndPassengers()).thenAnswer(ignored -> Stream.of(entity));
            when(entity.getInterpolation()).thenReturn(MinecraftTestAccess.idleInterpolation());
            when(entity.getBoundingBox()).thenReturn(new AABB(x - 0.3D, feet, z - 0.3D, x + 0.3D, feet + 1.8D, z + 0.3D));
            entity.xo = x;
            entity.yo = feet;
            entity.zo = z;
            entities.add(entity);
            return entity;
        }

        private PlaneCrossing crossing(MinecraftPortal source, Entity entity) {
            ArgumentCaptor<PlaneCrossing> crossing = ArgumentCaptor.forClass(PlaneCrossing.class);
            verify(nexus).destination(eq(source), eq(entity), crossing.capture());
            return crossing.getValue();
        }

        private Vec3d arrival(MinecraftPortal source, PlaneCrossing crossing) {
            return MinecraftPortalRegistry.passage(source, arrival, crossing).toward().point(crossing.point());
        }

        private MinecraftPortal arrivalPortal() {
            return arrival;
        }

        private ChunkLeaseRegistry<ServerLevel> leases() {
            return leases;
        }

        private MinecraftClientViewService views() {
            return views;
        }

        private static List<BlockPos> floor(int x, int y, int z) {
            List<BlockPos> cells = new ArrayList<>(9);
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    cells.add(new BlockPos(x + dx, y, z + dz));
                }
            }
            return cells;
        }
    }
}
