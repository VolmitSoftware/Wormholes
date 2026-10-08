package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.PortalTerrainMaterials;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class IrisMeshMaterialsTest {
    @Test
    public void materialsFollowThePacksTerrainLightingAndStayCachedOtherwise() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setBlockStateIds(new Object2IntOpenHashMap<BlockState>());
        settings.setAmbientOcclusionLevel(0.5F);
        PortalTerrainMaterials first = IrisMeshMaterials.current();
        assertTrue(first.enabled());
        assertEquals(0.5F, first.lighting().ambientOcclusion(), 0.0F);
        assertSame(first, IrisMeshMaterials.current());

        settings.setAmbientOcclusionLevel(0.25F);
        PortalTerrainMaterials relit = IrisMeshMaterials.current();
        assertNotSame(first, relit);
        assertNotEquals(first.revision(), relit.revision());
        assertEquals(0.25F, relit.lighting().ambientOcclusion(), 0.0F);
    }
}
