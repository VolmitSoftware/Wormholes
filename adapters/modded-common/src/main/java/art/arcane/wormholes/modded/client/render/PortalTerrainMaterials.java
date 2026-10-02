package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Objects;

public record PortalTerrainMaterials(boolean enabled, Map<BlockState, Integer> blockIds, long revision, Lighting lighting) {
    public static final PortalTerrainMaterials VANILLA = new PortalTerrainMaterials(false, Map.of(), 0, Lighting.VANILLA);

    public PortalTerrainMaterials {
        blockIds = Map.copyOf(Objects.requireNonNull(blockIds, "blockIds"));
        Objects.requireNonNull(lighting, "lighting");
    }

    public VertexFormat format() {
        return enabled ? PortalTerrainVertices.FORMAT : DefaultVertexFormat.BLOCK;
    }

    public int blockId(BlockState state) {
        return blockIds.getOrDefault(state, -1);
    }

    public record Lighting(float ambientOcclusion, boolean disableDirectionalShading, boolean separateAo) {
        public static final Lighting VANILLA = new Lighting(1, false, false);

        public Lighting {
            if (!Float.isFinite(ambientOcclusion)) {
                throw new IllegalArgumentException("Terrain ambient occlusion must be finite");
            }
        }
    }
}
