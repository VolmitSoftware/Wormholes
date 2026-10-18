package art.arcane.wormholes.modded.client.render.iris;

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

record IrisWorldSettings(Object2IntMap<BlockState> blocks, Map<Block, BlockRenderType> blockTypes,
                         Object2IntFunction<NamespacedId> entities, Object2IntFunction<NamespacedId> items,
                         ChunkVertexType format, float ambientOcclusion, boolean directional, boolean separateAo,
                         boolean separateEntities, boolean voxelize, boolean anisotropy, boolean reload) {
    static IrisWorldSettings capture() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        return new IrisWorldSettings(settings.getBlockStateIds(), settings.getBlockTypeIds(), settings.getEntityIds(), settings.getItemIds(),
            settings.getVertexFormat(), settings.getAmbientOcclusionLevel(), settings.shouldDisableDirectionalShading(),
            settings.shouldUseSeparateAo(), settings.shouldSeparateEntityDraws(), settings.shouldVoxelizeLightBlocks(),
            settings.breaksAnisotropy(), settings.isReloadRequired());
    }

    boolean matchesTerrain(IrisWorldSettings other) {
        return format == other.format && ambientOcclusion == other.ambientOcclusion && directional == other.directional
            && separateAo == other.separateAo && separateEntities == other.separateEntities && voxelize == other.voxelize
            && anisotropy == other.anisotropy && Objects.equals(blocks, other.blocks) && Objects.equals(blockTypes, other.blockTypes);
    }

    void apply() {
        WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
        settings.setBlockStateIds(blocks);
        settings.setBlockTypeIds(blockTypes);
        settings.setEntityIds(entities);
        settings.setItemIds(items);
        settings.setVertexFormat(format);
        settings.setAmbientOcclusionLevel(ambientOcclusion);
        settings.setDisableDirectionalShading(directional);
        settings.setUseSeparateAo(separateAo);
        settings.setSeparateEntityDraws(separateEntities);
        settings.setVoxelizeLightBlocks(voxelize);
        settings.setBreaksAnisotropy(anisotropy);
        ((IrisPortalSettingsAccess) settings).wormholes$reloadRequired(reload);
    }
}
