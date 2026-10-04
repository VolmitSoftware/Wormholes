package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.door.DoorTransit;
import art.arcane.wormholes.door.DoorwayCrossing;
import art.arcane.wormholes.door.DoorOpenCycle;
import art.arcane.wormholes.door.DoorAutoCloseBook;
import art.arcane.wormholes.door.DoorTravelerClass;
import art.arcane.wormholes.door.DoorVec3;
import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PairEndpoint;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

public class MinecraftPreparedDoorCloseTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void closedMateOpensBeforeExactCollisionValidationAndFailedArrivalRestoresIt() throws Exception {
        MinecraftDoorService service = new MinecraftDoorService(mock(WormholesModRuntime.class));
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getLevel(any())).thenReturn(level);
        Field serverField = MinecraftDoorService.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(service, server);
        Field presentationField = MinecraftDoorService.class.getDeclaredField("presentation");
        presentationField.setAccessible(true);
        presentationField.set(service, mock(MinecraftDoorPresentation.class));
        BlockPos block = new BlockPos(10, 80, 7);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.HORIZONTAL_FACING.getValue("west").orElseThrow())
            .setValue(DoorBlock.OPEN, false).setValue(DoorBlock.POWERED, false);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(block, lower);
        blocks.put(block.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> blocks.get(call.getArgument(0)));
        when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(320);
        when(level.getWorldBorder()).thenReturn(new WorldBorder());
        doAnswer(call -> { blocks.put(call.getArgument(0), call.getArgument(1)); return true; })
            .when(level).setBlock(any(BlockPos.class), any(BlockState.class), anyInt());
        Entity entity = mock(Entity.class);
        when(entity.getBoundingBox()).thenReturn(new AABB(-0.3D, 0, -0.3D, 0.3D, 1.8D, 0.3D));
        when(level.noCollision(any(Entity.class), any(AABB.class))).thenAnswer(call -> {
            assertTrue(blocks.get(block).getValue(DoorBlock.OPEN));
            return false;
        });
        PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 10, 80, 7),
            DoorItemIdentity.paired(UUID.randomUUID(), UUID.randomUUID(), PairEndpoint.A));
        DoorwayPlane source = new DoorwayPlane(4, 80, 7, Direction.W);
        DoorwayPlane destination = new DoorwayPlane(10, 80, 7, Direction.W);
        DoorVec3 center = source.center();
        PortalCrossing crossing = new PortalCrossing(
            DoorApertureFrames.of(source),
            new GeometryVector(center.x(), center.y(), center.z()),
            new GeometryVector(center.x() + 0.1D, 80, center.z()),
            new GeometryVector(0.2D, 0, 0),
            new GeometryVector(1, 0, 0), true);
        DoorTransit transit = new DoorTransit(source, new DoorwayCrossing(center, 1, 0, 0, DoorwayCrossing.Direction.FRONT_TO_BACK),
            -90, 0, 0.3D, 1.8D, DoorTravelerClass.LIVING, null, crossing);
        Class<?> activeType = Class.forName(MinecraftDoorService.class.getName() + "$ActiveDoor");
        Constructor<?> activeConstructor = activeType.getDeclaredConstructor(PlacedDoorEndpoint.class);
        activeConstructor.setAccessible(true);
        Object active = activeConstructor.newInstance(endpoint);
        Class<?> snapshotType = Class.forName(MinecraftDoorService.class.getName() + "$Snapshot");
        Constructor<?> snapshotConstructor = snapshotType.getDeclaredConstructor(PlacedDoorEndpoint.class, ServerLevel.class,
            BlockPos.class, DoorwayPlane.class, boolean.class, boolean.class);
        snapshotConstructor.setAccessible(true);
        Object snapshot = snapshotConstructor.newInstance(endpoint, level, block, destination, false, false);
        Method arrive = MinecraftDoorService.class.getDeclaredMethod("arrive", Entity.class, activeType, DoorTransit.class, snapshotType);
        arrive.setAccessible(true);
        assertFalse((boolean) arrive.invoke(service, entity, active, transit, snapshot));
        verify(level).noCollision(any(Entity.class), any(AABB.class));
        assertFalse(blocks.get(block).getValue(DoorBlock.OPEN));
        assertFalse(blocks.get(block.above()).getValue(DoorBlock.OPEN));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void externalTeleportReleasesOldDoorCycleAndLateFlightCannotCompleteTheSuccessor() throws Exception {
        MinecraftDoorService service = new MinecraftDoorService(mock(WormholesModRuntime.class));
        ServerPlayer player = mock(ServerPlayer.class);
        UUID traveler = UUID.randomUUID();
        when(player.getUUID()).thenReturn(traveler);
        PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 4, 80, 7),
            DoorItemIdentity.paired(UUID.randomUUID(), UUID.randomUUID(), PairEndpoint.A));
        DoorwayPlane plane = new DoorwayPlane(4, 80, 7, Direction.W);
        DoorTransit transit = new DoorTransit(plane, DoorwayCrossing.Direction.FRONT_TO_BACK, 0, 0);
        Class<?> activeType = Class.forName(MinecraftDoorService.class.getName() + "$ActiveDoor");
        Constructor<?> activeConstructor = activeType.getDeclaredConstructor(PlacedDoorEndpoint.class);
        activeConstructor.setAccessible(true);
        Object active = activeConstructor.newInstance(endpoint);
        Field cycleField = activeType.getDeclaredField("cycle");
        cycleField.setAccessible(true);
        DoorOpenCycle cycle = (DoorOpenCycle) cycleField.get(active);
        assertTrue(cycle.tryBegin(true));
        Class<?> flightType = Class.forName(MinecraftDoorService.class.getName() + "$Flight");
        Constructor<?> flightConstructor = flightType.getDeclaredConstructor(ChunkLease.class, Level.class, Vec3.class,
            long.class, activeType, DoorTransit.class);
        flightConstructor.setAccessible(true);
        ChunkLease oldLease = mock(ChunkLease.class);
        Object oldFlight = flightConstructor.newInstance(oldLease, mock(ServerLevel.class), Vec3.ZERO, Long.MAX_VALUE, active, transit);
        Field flightsField = MinecraftDoorService.class.getDeclaredField("flights");
        flightsField.setAccessible(true);
        Map<UUID, Object> flights = (Map<UUID, Object>) flightsField.get(service);
        flights.put(traveler, oldFlight);
        service.cancelDeparture(player);
        verify(oldLease).close();
        assertEquals(DoorOpenCycle.Phase.ARMED, cycle.phase());
        assertTrue(cycle.tryBegin(true));
        ChunkLease newLease = mock(ChunkLease.class);
        Object newFlight = flightConstructor.newInstance(newLease, mock(ServerLevel.class), Vec3.ZERO, Long.MAX_VALUE, active, transit);
        flights.put(traveler, newFlight);
        Method finish = MinecraftDoorService.class.getDeclaredMethod("finishFlight", Entity.class, flightType, boolean.class);
        finish.setAccessible(true);
        assertFalse((boolean) finish.invoke(service, player, oldFlight, false));
        assertEquals(DoorOpenCycle.Phase.IN_TRANSIT, cycle.phase());
        assertTrue(flights.get(traveler) == newFlight);
        verify(oldLease).close();
        verify(newLease, never()).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoCloseDefersWhileThePreparedBodyStraddlesThenReleasesWhenItLeavesOrDisconnects() throws Exception {
        MinecraftDoorService service = new MinecraftDoorService(mock(WormholesModRuntime.class));
        UUID doorId = UUID.randomUUID();
        PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 4, 80, 7),
            DoorItemIdentity.paired(doorId, UUID.randomUUID(), PairEndpoint.A));
        DoorwayPlane plane = new DoorwayPlane(4, 80, 7, Direction.W);
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.isAlive()).thenReturn(true);
        when(player.level()).thenReturn(level);
        when(player.getBoundingBox()).thenReturn(new AABB(3.8D, 80, 7.2D, 4.4D, 81.8D, 7.8D));
        Field field = MinecraftDoorService.class.getDeclaredField("preparedArrivalBodies");
        field.setAccessible(true);
        Map<UUID, Set<Entity>> bodies = (Map<UUID, Set<Entity>>) field.get(service);
        bodies.put(doorId, new HashSet<>(Set.of(player)));
        Class<?> snapshot = Class.forName(MinecraftDoorService.class.getName() + "$Snapshot");
        Constructor<?> constructor = snapshot.getDeclaredConstructor(PlacedDoorEndpoint.class, ServerLevel.class, BlockPos.class,
            DoorwayPlane.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object captured = constructor.newInstance(endpoint, level, new BlockPos(4, 80, 7), plane, true, false);
        Method occupied = MinecraftDoorService.class.getDeclaredMethod("preparedArrivalOccupied", PlacedDoorEndpoint.class, snapshot);
        occupied.setAccessible(true);
        DoorAutoCloseBook book = new DoorAutoCloseBook();
        long token = book.arm(doorId);
        assertTrue((boolean) occupied.invoke(service, endpoint, captured));
        assertEquals(DoorAutoCloseBook.Decision.DEFER,
            book.decide(doorId, token, true, (boolean) occupied.invoke(service, endpoint, captured), 0));
        when(player.getBoundingBox()).thenReturn(new AABB(2, 80, 7.2D, 2.6D, 81.8D, 7.8D));
        assertFalse((boolean) occupied.invoke(service, endpoint, captured));
        assertFalse(bodies.containsKey(doorId));
        assertEquals(DoorAutoCloseBook.Decision.CLOSE, book.decide(doorId, token, true, false, 1));
        bodies.put(doorId, new HashSet<>(Set.of(player)));
        when(player.getBoundingBox()).thenReturn(new AABB(3.8D, 80, 7.2D, 4.4D, 81.8D, 7.8D));
        when(player.hasDisconnected()).thenReturn(true);
        assertFalse((boolean) occupied.invoke(service, endpoint, captured));
        assertTrue(bodies.isEmpty());
    }
}
