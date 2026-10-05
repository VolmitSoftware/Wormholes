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
import java.util.Objects;

record PortalIrisSettings(boolean reloadRequired, Object2IntMap<BlockState> blockIds, Map<Block, BlockRenderType> blockTypes,
                          Object2IntFunction<NamespacedId> entityIds, Object2IntFunction<NamespacedId> itemIds,
                          float ambientOcclusion, boolean directionalShading, boolean separateAo,
                          ChunkVertexType vertexFormat, boolean voxelizeLights, boolean separateEntities, boolean breaksAnisotropy) {
    boolean terrainCompatible(PortalIrisSettings other) {
        return other != null && Float.compare(ambientOcclusion, other.ambientOcclusion) == 0
            && directionalShading == other.directionalShading && separateAo == other.separateAo
            && vertexFormat == other.vertexFormat && voxelizeLights == other.voxelizeLights
            && separateEntities == other.separateEntities && breaksAnisotropy == other.breaksAnisotropy
            && Objects.equals(blockIds, other.blockIds) && defaultValue(blockIds) == defaultValue(other.blockIds)
            && Objects.equals(blockTypes, other.blockTypes)
            && Objects.equals(entityIds, other.entityIds) && defaultValue(entityIds) == defaultValue(other.entityIds)
            && Objects.equals(itemIds, other.itemIds) && defaultValue(itemIds) == defaultValue(other.itemIds);
    }

    private static int defaultValue(Object2IntFunction<?> ids) {
        return ids == null ? 0 : ids.defaultReturnValue();
    }

    static PortalIrisSettings capture() {
        return capture(WorldRenderingSettings.INSTANCE);
    }

    static PortalIrisSettings capture(WorldRenderingSettings settings) {
        return new PortalIrisSettings(settings.isReloadRequired(), settings.getBlockStateIds(), settings.getBlockTypeIds(),
            settings.getEntityIds(), settings.getItemIds(), settings.getAmbientOcclusionLevel(), settings.shouldDisableDirectionalShading(),
            settings.shouldUseSeparateAo(), settings.getVertexFormat(), settings.shouldVoxelizeLightBlocks(),
            settings.shouldSeparateEntityDraws(), settings.breaksAnisotropy());
    }

    void apply() {
        apply(WorldRenderingSettings.INSTANCE);
    }

    void apply(WorldRenderingSettings settings) {
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
