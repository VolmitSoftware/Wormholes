package art.arcane.wormholes.modded.client.render;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PortalIrisSettingsCompatibilityTest {
    @Test
    public void equivalentMaterialsAreCompatibleAndDefaultIdChangesInvalidateThem() {
        WorldRenderingSettings left = mock(WorldRenderingSettings.class);
        WorldRenderingSettings right = mock(WorldRenderingSettings.class);
        BlockState block = mock(BlockState.class);
        Object2IntOpenHashMap<BlockState> leftIds = new Object2IntOpenHashMap<>();
        Object2IntOpenHashMap<BlockState> rightIds = new Object2IntOpenHashMap<>();
        leftIds.put(block, 27);
        rightIds.put(block, 27);
        when(left.getBlockStateIds()).thenReturn(leftIds);
        when(right.getBlockStateIds()).thenReturn(rightIds);
        assertTrue(PortalIrisSettings.capture(left).terrainCompatible(PortalIrisSettings.capture(right)));
        rightIds.defaultReturnValue(-1);
        assertFalse(PortalIrisSettings.capture(left).terrainCompatible(PortalIrisSettings.capture(right)));
        rightIds.defaultReturnValue(0);
        rightIds.put(block, 28);
        assertFalse(PortalIrisSettings.capture(left).terrainCompatible(PortalIrisSettings.capture(right)));
    }

    @Test
    public void everyGeometryAndLightingFlagAndVertexFormatRequiresMatchingSettings() {
        WorldRenderingSettings settings = mock(WorldRenderingSettings.class);
        PortalIrisSettings source = PortalIrisSettings.capture(settings);
        when(settings.isReloadRequired()).thenReturn(true);
        assertTrue(source.terrainCompatible(PortalIrisSettings.capture(settings)));
        when(settings.getAmbientOcclusionLevel()).thenReturn(0.65f);
        assertFalse(source.terrainCompatible(PortalIrisSettings.capture(settings)));
        when(settings.getAmbientOcclusionLevel()).thenReturn(0.0f);
        when(settings.shouldDisableDirectionalShading()).thenReturn(true);
        assertFalse(source.terrainCompatible(PortalIrisSettings.capture(settings)));
        when(settings.shouldDisableDirectionalShading()).thenReturn(false);
        when(settings.shouldUseSeparateAo()).thenReturn(true);
        assertFalse(source.terrainCompatible(PortalIrisSettings.capture(settings)));
        when(settings.shouldUseSeparateAo()).thenReturn(false);
        when(settings.shouldVoxelizeLightBlocks()).thenReturn(true);
        assertFalse(source.terrainCompatible(PortalIrisSettings.capture(settings)));
        when(settings.shouldVoxelizeLightBlocks()).thenReturn(false);
        when(settings.shouldSeparateEntityDraws()).thenReturn(true);
        assertFalse(source.terrainCompatible(PortalIrisSettings.capture(settings)));
        when(settings.shouldSeparateEntityDraws()).thenReturn(false);
        when(settings.breaksAnisotropy()).thenReturn(true);
        assertFalse(source.terrainCompatible(PortalIrisSettings.capture(settings)));
        when(settings.breaksAnisotropy()).thenReturn(false);
        when(settings.getVertexFormat()).thenReturn(mock(ChunkVertexType.class));
        assertFalse(source.terrainCompatible(PortalIrisSettings.capture(settings)));
    }

    @Test
    public void entityAndItemIdDefaultsAlsoAffectShaderCompatibility() {
        WorldRenderingSettings settings = mock(WorldRenderingSettings.class);
        Object2IntOpenHashMap<NamespacedId> sourceIds = new Object2IntOpenHashMap<>();
        Object2IntOpenHashMap<NamespacedId> destinationIds = new Object2IntOpenHashMap<>();
        destinationIds.defaultReturnValue(-1);
        when(settings.getEntityIds()).thenReturn(sourceIds);
        when(settings.getItemIds()).thenReturn(sourceIds);
        PortalIrisSettings source = PortalIrisSettings.capture(settings);
        when(settings.getEntityIds()).thenReturn(destinationIds);
        assertFalse(source.terrainCompatible(PortalIrisSettings.capture(settings)));
        when(settings.getEntityIds()).thenReturn(sourceIds);
        when(settings.getItemIds()).thenReturn(destinationIds);
        assertFalse(source.terrainCompatible(PortalIrisSettings.capture(settings)));
    }
}
