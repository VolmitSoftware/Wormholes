package art.arcane.wormholes.render.plate;

import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.view.ProjectionWorldView;

/**
 * One local cell of a shared view plate: the sampler classification, the untransformed destination
 * sample, the block state after the frame transform, the remote cell it was sampled from and, when
 * the destination carries one, the sanitized block-entity snapshot.
 */
public final class PlateCell {
    static final int BYTES = 72;

    private final ProjectorSample.Kind kind;
    private final BlockData sourceData;
    private final BlockData data;
    private final long remoteKey;
    private final BlockEntitySample blockEntity;

    public PlateCell(ProjectorSample.Kind kind, BlockData sourceData, BlockData data, long remoteKey, BlockEntitySample blockEntity) {
        this.kind = kind;
        this.sourceData = sourceData;
        this.data = data;
        this.remoteKey = remoteKey;
        this.blockEntity = blockEntity;
    }

    public ProjectorSample.Kind kind() {
        return kind;
    }

    public BlockData sourceData() {
        return sourceData;
    }

    public BlockData data() {
        return data;
    }

    public long remoteKey() {
        return remoteKey;
    }

    public BlockEntitySample blockEntity() {
        return blockEntity;
    }

    public boolean isAir() {
        return kind == ProjectorSample.Kind.REMOTE_AIR || kind == ProjectorSample.Kind.MASK_AIR;
    }

    public ProjectorSample sample(ProjectionWorldView lightView) {
        return new ProjectorSample(kind, sourceData, lightView, remoteKey);
    }

    int bytes() {
        return BYTES + (blockEntity == null ? 0 : blockEntity.bytes());
    }
}
