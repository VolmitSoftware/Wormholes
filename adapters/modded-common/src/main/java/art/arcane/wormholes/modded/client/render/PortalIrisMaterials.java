package art.arcane.wormholes.modded.client.render;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.BlockRenderType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

final class PortalIrisMaterials {
    private Object2IntMap<BlockState> states;
    private Map<Block, BlockRenderType> types;
    private Map<BlockState, Integer> terrain;

    void apply(ShaderPack pack) {
        if (states == null) {
            states = Object2IntMaps.unmodifiable(BlockMaterialMapping.createBlockStateIdMap(
                pack.getIdMap().getBlockProperties(), pack.getIdMap().getTagEntries()));
            types = Map.copyOf(BlockMaterialMapping.createBlockTypeMap(pack.getIdMap().getBlockRenderTypeMap()));
            terrain = Map.copyOf(states);
        }
        WorldRenderingSettings.INSTANCE.setBlockStateIds(states);
        WorldRenderingSettings.INSTANCE.setBlockTypeIds(types);
    }

    Map<BlockState, Integer> terrain() {
        return terrain;
    }
}
