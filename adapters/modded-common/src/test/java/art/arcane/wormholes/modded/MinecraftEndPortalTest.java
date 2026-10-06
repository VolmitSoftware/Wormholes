package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.optics.math.Face;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftEndPortalTest extends MinecraftTestBase {
    @Test
    public void vanillaFountainAperturePreservesTheBedrockPillarAndDeactivatesOnRespawn() throws Exception {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        WorldGenLevel generated = mock(WorldGenLevel.class);
        when(generated.getBlockState(any(BlockPos.class))).thenAnswer(invocation ->
            blocks.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()));
        when(generated.setBlockAndUpdate(any(BlockPos.class), any(BlockState.class))).thenAnswer(invocation -> {
            blocks.put(((BlockPos) invocation.getArgument(0)).immutable(), invocation.getArgument(1));
            return true;
        });
        BlockPos center = new BlockPos(0, 64, 0);
        new EndPodiumFeature(true).place(generated, null, RandomSource.create(1), center);
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
        new EndPodiumFeature(false).place(generated, null, RandomSource.create(2), center);
        assertNull(shape(level, center.east()));
        assertTrue(blocks.get(center).is(Blocks.BEDROCK));
        Method active = MinecraftVanillaPortals.class.getDeclaredMethod("exitActive", ServerLevel.class, List.class);
        active.setAccessible(true);
        assertEquals(false, active.invoke(null, level, cells));
    }

    @Test
    public void endEntryLinksItsReceiverWithoutAReverseDestination() throws Exception {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        ServerLevel sourceLevel = mock(ServerLevel.class);
        ServerLevel end = mock(ServerLevel.class);
        when(runtime.portals()).thenReturn(registry);
        when(runtime.access()).thenReturn(access);
        when(access.canPlace(any())).thenReturn(true);
        when(sourceLevel.dimension()).thenReturn(Level.OVERWORLD);
        when(end.dimension()).thenReturn(Level.END);
        when(sourceLevel.hasChunk(anyInt(), anyInt())).thenReturn(true);
        when(sourceLevel.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
            BlockPos position = invocation.getArgument(0);
            return position.getY() == 64 && position.getX() >= 10 && position.getX() <= 12
                && position.getZ() >= 10 && position.getZ() <= 12 ? Blocks.END_PORTAL.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });
        MinecraftPortal source = portal("minecraft:overworld", 11, 64, 11);
        MinecraftPortal arrival = portal("minecraft:the_end", 12, 74, 9);
        arrival.setDimensionalKind(DimensionalPortalKind.END_ARRIVAL);
        when(registry.snapshot()).thenReturn(List.of(arrival));
        when(registry.create(eq(null), eq(sourceLevel), any(), eq(PortalType.PORTAL), any())).thenReturn(source);
        Object shape = shape(sourceLevel, new BlockPos(10, 64, 10));
        Method pair = MinecraftVanillaPortals.class.getDeclaredMethod("pair", ServerPlayer.class, ServerLevel.class,
            ServerLevel.class, shape.getClass(), BlockPos.class);
        pair.setAccessible(true);
        assertEquals(true, pair.invoke(new MinecraftVanillaPortals(runtime), mock(ServerPlayer.class), sourceLevel,
            end, shape, new BlockPos(12, 74, 9)));
        assertEquals(arrival.getId(), source.getDestinationId());
        assertNull(arrival.getDestinationId());
        assertTrue(source.isOutgoingTraversalsEnabled());
        assertFalse(source.isIncomingTraversalsEnabled());
        assertFalse(arrival.isOutgoingTraversalsEnabled());
        assertTrue(arrival.isIncomingTraversalsEnabled());
        assertEquals(ProjectionMode.OFF, arrival.getProjectionMode());
        arrival.setOutgoingTraversalsEnabled(true);
        assertFalse(arrival.isOutgoingTraversalsEnabled());
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
            Frame.canonical(Face.U), true), geometry, world, Map.of("owner", id.toString(), "type", "PORTAL")));
    }

    private static Object shape(ServerLevel level, BlockPos anchor) throws Exception {
        Method shape = MinecraftVanillaPortals.class.getDeclaredMethod("shape", ServerLevel.class, BlockPos.class);
        shape.setAccessible(true);
        return shape.invoke(null, level, anchor);
    }
}
