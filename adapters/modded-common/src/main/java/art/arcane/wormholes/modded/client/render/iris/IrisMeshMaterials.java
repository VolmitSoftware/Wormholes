package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.PortalTerrainMaterials;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.world.level.block.state.BlockState;

public final class IrisMeshMaterials {
    private static Object2IntMap<BlockState> blockIds;
    private static PortalTerrainMaterials materials;
    private static long revision;

    private IrisMeshMaterials() {
    }

    public static PortalTerrainMaterials current() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        Object2IntMap<BlockState> ids = settings.getBlockStateIds();
        if (ids == null) {
            return null;
        }
        PortalTerrainMaterials.Lighting lighting = new PortalTerrainMaterials.Lighting(settings.getAmbientOcclusionLevel(),
            settings.shouldDisableDirectionalShading(), settings.shouldUseSeparateAo());
        if (materials == null || ids != blockIds || !lighting.equals(materials.lighting())) {
            blockIds = ids;
            materials = new PortalTerrainMaterials(true, ids, ++revision, lighting);
        }
        return materials;
    }
}
