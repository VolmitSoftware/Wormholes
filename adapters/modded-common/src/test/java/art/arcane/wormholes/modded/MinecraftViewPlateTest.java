package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.plate.PlateCell;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateKey;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftViewPlateTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void nativePlateBuildRetainsEveryCellAcrossOneCellServerSlices() {
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        BlockState stone = Blocks.STONE.defaultBlockState();
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        UUID worldId = UUID.randomUUID();
        when(view.worldId()).thenReturn(worldId);
        PortalGeometry geometry = new PortalGeometry();
        geometry.setArea(new AxisAlignedBB(0, 2, 64, 67, 0, 1));
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        ViewPlateBuilder.Request<BlockState, BlockState, MinecraftProjectionWorldView> request = new ViewPlateBuilder.Request<>(
            new ViewPlateKey(UUID.randomUUID(), view, true, 0, 0L), geometry, view, frame, frame,
            1.0D, 65.0D, 0.0D, 1.0D, 65.0D, 0.0D, false, 0,
            4.0D, 0.0D, 0.0D, false, Blocks.AIR.defaultBlockState(), LodPolicy.NONE,
            false, 7L, 3L, 2L, MinecraftProjectorBlocks.INSTANCE);
        ViewPlate<BlockState> full = ViewPlateBuilder.build(request);
        ViewPlateBuilder.Job<BlockState, ServerLevel> sliced = ViewPlateBuilder.job(request);
        int ticks = 0;
        while (!sliced.step(1)) {
            assertTrue(++ticks < 1000);
        }
        ViewPlate<BlockState> resumed = sliced.result();
        assertFalse(full.isEmpty());
        assertTrue(ticks > 1);
        assertEquals(full.cellKeys(), resumed.cellKeys());
        assertEquals(worldId, resumed.destinationWorldId());
        assertEquals(7L, resumed.destinationRevision());
        for (long key : full.cellKeys()) {
            PlateCell<BlockState> first = full.cell(key);
            PlateCell<BlockState> second = resumed.cell(key);
            assertEquals(ProjectorSample.Kind.BLOCK, second.kind());
            assertSame(first.data(), second.data());
            assertSame(first.sourceData(), second.sourceData());
        }
    }
}
