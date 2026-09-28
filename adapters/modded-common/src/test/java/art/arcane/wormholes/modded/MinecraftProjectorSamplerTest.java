package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.PortalFrame;
import static art.arcane.wormholes.util.Direction.E;
import art.arcane.wormholes.render.ProjectorRecursivePortals;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.ProjectorSampleMemo;
import art.arcane.wormholes.render.ProjectorSampler;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftProjectorSamplerTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void nativeSamplingPreservesAirMissingBackingAndBuriedClassification() {
        Fixture fixture = fixture();
        BlockState stone = Blocks.STONE.defaultBlockState();
        when(fixture.view().sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        when(fixture.view().sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        fixture.sampler().setBuriedCellCullingPass(true);
        assertEquals(ProjectorSample.Kind.OCCLUDED, sample(fixture, true).kind());

        fixture.memo().clearDestinationSamples();
        when(fixture.view().sampleMaterial(2, 64, 0)).thenReturn(Blocks.AIR.defaultBlockState());
        assertEquals(ProjectorSample.Kind.BACKING_BLOCK, sample(fixture, true).kind());

        fixture.memo().clearDestinationSamples();
        when(fixture.view().sampleMaterial(1, 64, 0)).thenReturn(Blocks.AIR.defaultBlockState());
        assertEquals(ProjectorSample.Kind.BLOCK, sample(fixture, true).kind());

        fixture.memo().clearDestinationSamples();
        when(fixture.view().sampleBlockData(0, 64, 0)).thenReturn(Blocks.CAVE_AIR.defaultBlockState());
        ProjectorSample<BlockState, MinecraftProjectionWorldView> air = sample(fixture, true);
        assertEquals(ProjectorSample.Kind.REMOTE_AIR, air.kind());
        assertSame(Blocks.AIR.defaultBlockState(), air.data());
        assertSame(fixture.view(), air.lightView());

        fixture.memo().clearDestinationSamples();
        when(fixture.view().sampleBlockData(0, 64, 0)).thenReturn(null);
        assertEquals(ProjectorSample.Kind.NO_SAMPLE, sample(fixture, true).kind());
    }

    @Test
    public void nativeMirrorTransformUsesSharedOrientationAndMemoizedBlockState() {
        Fixture fixture = fixture();
        PortalFrame frame = PortalFrame.canonical(E);
        BlockState eastFacing = Blocks.FURNACE.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        fixture.sampler().prepareTransformCache(frame, frame, true, 0);
        BlockState mirrored = fixture.sampler().transformProjectedBlockData(eastFacing, frame, frame, true, frame, 0);
        assertEquals(Direction.WEST, mirrored.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertSame(mirrored, fixture.sampler().transformProjectedBlockData(eastFacing, frame, frame, true, frame, 0));
    }

    @Test
    public void detachedOccludedSentinelCannotBeConfusedWithRealStone() {
        Fixture fixture = fixture();
        BlockState sentinel = MinecraftProjectorBlocks.INSTANCE.occluded();
        assertNotSame(Blocks.STONE.defaultBlockState(), sentinel);
        assertFalse(MinecraftProjectorBlocks.INSTANCE.isOccluded(Blocks.STONE.defaultBlockState()));
        when(fixture.view().sampleBlockData(0, 64, 0)).thenReturn(sentinel);
        assertEquals(ProjectorSample.Kind.OCCLUDED, sample(fixture, false).kind());
    }

    private static ProjectorSample<BlockState, MinecraftProjectionWorldView> sample(Fixture fixture, boolean culling) {
        return fixture.sampler().resolve(fixture.view(), 0.1D, 64.1D, 0.1D,
            0.0D, 64.0D, -5.0D, null, 3, culling, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        ServerLevel level = mock(ServerLevel.class);
        when(view.getWorld()).thenReturn(level);
        ProjectorRecursivePortals.PortalAccess<ServerLevel, MinecraftPortal> access = mock(ProjectorRecursivePortals.PortalAccess.class);
        when(access.portals()).thenReturn(List.of());
        ProjectorRecursivePortals<ServerLevel, MinecraftPortal> portals = new ProjectorRecursivePortals<>(access,
            () -> new ProjectorRecursivePortals.Options(0.0D, 64.0D));
        ProjectorSampleMemo<BlockState, BlockState, MinecraftProjectionWorldView> memo = new ProjectorSampleMemo<>(
            MinecraftProjectorBlocks.INSTANCE, () -> null);
        ProjectorSampler<BlockState, BlockState, ServerLevel, MinecraftPortal, MinecraftProjectionWorldView> sampler = new ProjectorSampler<>(
            new ProjectorSampler.Options<>(memo, portals, world -> view, MinecraftProjectionWorldView::getWorld));
        return new Fixture(view, memo, sampler);
    }

    private record Fixture(MinecraftProjectionWorldView view,
                           ProjectorSampleMemo<BlockState, BlockState, MinecraftProjectionWorldView> memo,
                           ProjectorSampler<BlockState, BlockState, ServerLevel, MinecraftPortal, MinecraftProjectionWorldView> sampler) {
    }
}
