package art.arcane.wormholes.modded;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.Portal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftVanillaPortalIntegrityTest extends MinecraftTestBase {
    @Test
    public void maskedAndTurnedNetherFramesRemainIntactOnBothPhysicalPlanes() {
        for (Face physical : List.of(Face.N, Face.E)) {
            MinecraftPortal portal = portal(physical, DimensionalPortalKind.NETHER);
            Fixture fixture = fixture(portal);
            assertTrue(portal.setApertureShape(ShapeDescriptor.parse("circle")));
            assertTrue(portal.getGeometry().getBlockPositions().size() < portal.getBuiltGeometry().getBlockPositions().size());
            portal.setFrame(portal.getFrame().rotateClockwise());
            validate(fixture);
            for (Face traversal : List.of(Face.N, Face.S, Face.E, Face.W, Face.U, Face.D)) {
                portal.setFrame(Frame.canonical(traversal));
                validate(fixture);
            }
            verify(fixture.registry(), never()).remove(portal.getId());
        }
    }

    @Test
    public void breakingTheOriginalNetherBorderStillRemovesTheManagedPortal() {
        MinecraftPortal portal = portal(Face.N, DimensionalPortalKind.NETHER);
        Fixture fixture = fixture(portal);
        assertTrue(portal.setApertureShape(ShapeDescriptor.parse("circle")));
        portal.setFrame(Frame.canonical(Face.E));
        when(fixture.level().getBlockState(new BlockPos(-1, 67, 0))).thenReturn(Blocks.AIR.defaultBlockState());
        validate(fixture);
        verify(fixture.registry()).remove(portal.getId());
    }

    @Test
    public void endSourceChecksItsOriginalHorizontalFrameAndEyesAfterTurning() {
        MinecraftPortal portal = portal(Face.U, DimensionalPortalKind.END_SOURCE);
        Fixture fixture = fixture(portal);
        portal.setFrame(Frame.canonical(Face.E));
        validate(fixture);
        verify(fixture.registry(), never()).remove(portal.getId());
        when(fixture.level().getBlockState(new BlockPos(-1, 64, 1))).thenReturn(Blocks.END_PORTAL_FRAME.defaultBlockState());
        validate(fixture);
        verify(fixture.registry()).remove(portal.getId());
    }

    @Test
    public void endArrivalRemainsReceiverOnlyWithoutAFrameIntegrityCheck() {
        MinecraftPortal portal = portal(Face.U, DimensionalPortalKind.END_ARRIVAL);
        Fixture fixture = fixture(portal);
        validate(fixture);
        verify(fixture.registry(), never()).resolveLevel(portal);
        verify(fixture.registry(), never()).remove(portal.getId());
    }

    private static MinecraftPortal portal(Face physical, DimensionalPortalKind kind) {
        ApertureCells built = new ApertureCells();
        built.setArea(switch (physical) {
            case N -> new Box(0, 6.999D, 64, 70.999D, 0, 0.999D);
            case E -> new Box(0, 0.999D, 64, 70.999D, 0, 6.999D);
            default -> new Box(0, 2.999D, 64, 64.999D, 0, 2.999D);
        });
        UUID id = UUID.randomUUID();
        MinecraftPortal portal = new MinecraftPortal(new MinecraftPortal.Definition(
            new Portal.State(id, built.getApertureCenter(), "Managed", Frame.canonical(physical), true),
            built, "minecraft:overworld", Map.of("owner", id.toString(), "type", "PORTAL")));
        portal.setDimensionalKind(kind);
        return portal;
    }

    private static Fixture fixture(MinecraftPortal portal) {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new NetworkConfig());
        settings.getMain().replaceNetherAndEndPortals = true;
        MinecraftPortalRegistry registry = mock(MinecraftPortalRegistry.class);
        ServerLevel level = mock(ServerLevel.class);
        when(runtime.running()).thenReturn(true);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(runtime.server()).thenReturn(mock(MinecraftServer.class));
        when(runtime.portals()).thenReturn(registry);
        when(registry.snapshot()).thenReturn(List.of(portal));
        when(registry.resolveLevel(portal)).thenReturn(level);
        when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);
        BlockState frame = portal.getDimensionalKind() == DimensionalPortalKind.END_SOURCE
            ? Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.HAS_EYE, true)
            : Blocks.OBSIDIAN.defaultBlockState();
        when(level.getBlockState(any(BlockPos.class))).thenReturn(frame);
        for (Vec3d cell : portal.getBuiltGeometry().getBlockPositions()) {
            when(level.getBlockState(new BlockPos(cell.blockX(), cell.blockY(), cell.blockZ()))).thenReturn(Blocks.AIR.defaultBlockState());
        }
        return new Fixture(registry, level, new MinecraftVanillaPortals(runtime));
    }

    private static void validate(Fixture fixture) {
        for (int tick = 0; tick < 40; tick++) {
            fixture.replacer().tick();
        }
    }

    private record Fixture(MinecraftPortalRegistry registry, ServerLevel level, MinecraftVanillaPortals replacer) {
    }
}
