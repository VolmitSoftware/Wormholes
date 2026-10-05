package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Objects;

public final class PortalTerrainMaterials {
    public static final PortalTerrainMaterials VANILLA = new PortalTerrainMaterials(false, Map.of(), 0, Lighting.VANILLA);

    private final boolean enabled;
    private final Map<BlockState, Integer> blockIds;
    private final long revision;
    private final Lighting lighting;
    private final int hashCode;

    public PortalTerrainMaterials(boolean enabled, Map<BlockState, Integer> blockIds, long revision, Lighting lighting) {
        this.enabled = enabled;
        this.blockIds = Map.copyOf(Objects.requireNonNull(blockIds, "blockIds"));
        this.revision = revision;
        this.lighting = Objects.requireNonNull(lighting, "lighting");
        int hash = Boolean.hashCode(enabled);
        hash = 31 * hash + this.blockIds.hashCode();
        hash = 31 * hash + Long.hashCode(revision);
        hashCode = 31 * hash + lighting.hashCode();
    }

    public boolean enabled() {
        return enabled;
    }

    public Map<BlockState, Integer> blockIds() {
        return blockIds;
    }

    public long revision() {
        return revision;
    }

    public Lighting lighting() {
        return lighting;
    }

    public VertexFormat format() {
        return enabled ? PortalTerrainVertices.FORMAT : DefaultVertexFormat.BLOCK;
    }

    public int blockId(BlockState state) {
        return blockIds.getOrDefault(state, -1);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PortalTerrainMaterials materials
            && hashCode == materials.hashCode && enabled == materials.enabled && revision == materials.revision
            && lighting.equals(materials.lighting) && blockIds.equals(materials.blockIds);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "PortalTerrainMaterials[enabled=" + enabled + ", blockIds=" + blockIds + ", revision=" + revision
            + ", lighting=" + lighting + "]";
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
