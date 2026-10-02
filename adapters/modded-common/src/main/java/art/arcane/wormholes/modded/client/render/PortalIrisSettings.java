package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalSettingsAccess;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.shaderpack.materialmap.BlockRenderType;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

record PortalIrisSettings(boolean reloadRequired, Object2IntMap<BlockState> blockIds, Map<Block, BlockRenderType> blockTypes,
                          Object2IntFunction<NamespacedId> entityIds, Object2IntFunction<NamespacedId> itemIds,
                          float ambientOcclusion, boolean directionalShading, boolean separateAo,
                          ChunkVertexType vertexFormat, boolean voxelizeLights, boolean separateEntities, boolean breaksAnisotropy) {
    static PortalIrisSettings capture() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        return new PortalIrisSettings(settings.isReloadRequired(), settings.getBlockStateIds(), settings.getBlockTypeIds(),
            settings.getEntityIds(), settings.getItemIds(), settings.getAmbientOcclusionLevel(), settings.shouldDisableDirectionalShading(),
            settings.shouldUseSeparateAo(), settings.getVertexFormat(), settings.shouldVoxelizeLightBlocks(),
            settings.shouldSeparateEntityDraws(), settings.breaksAnisotropy());
    }

    void apply() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setBlockStateIds(blockIds);
        settings.setBlockTypeIds(blockTypes);
        settings.setEntityIds(entityIds);
        settings.setItemIds(itemIds);
        settings.setAmbientOcclusionLevel(ambientOcclusion);
        settings.setDisableDirectionalShading(directionalShading);
        settings.setUseSeparateAo(separateAo);
        settings.setVertexFormat(vertexFormat);
        settings.setVoxelizeLightBlocks(voxelizeLights);
        settings.setSeparateEntityDraws(separateEntities);
        settings.setBreaksAnisotropy(breaksAnisotropy);
        ((IrisPortalSettingsAccess) settings).wormholes$reloadRequired(reloadRequired);
    }
}
