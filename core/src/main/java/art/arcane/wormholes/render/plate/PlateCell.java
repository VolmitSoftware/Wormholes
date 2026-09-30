package art.arcane.wormholes.render.plate;


import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;

/**
 * One local cell of a shared view plate: the sampler classification, the untransformed destination
 * sample, the block state after the frame transform, the remote cell it was sampled from and, when
 * the destination carries one, the sanitized block-entity snapshot.
 */
public final class PlateCell<B> {
    static final int BYTES = 40;

    private final ProjectorSample.Kind kind;
    private final B sourceData;
    private final B data;
    private final long remoteKey;
    private final BlockEntitySample blockEntity;

    public PlateCell(ProjectorSample.Kind kind, B sourceData, B data, long remoteKey, BlockEntitySample blockEntity) {
        this.kind = kind;
        this.sourceData = sourceData;
        this.data = data;
        this.remoteKey = remoteKey;
        this.blockEntity = blockEntity;
    }

    public ProjectorSample.Kind kind() {
        return kind;
    }

    public B sourceData() {
        return sourceData;
    }

    public B data() {
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

    public <V> ProjectorSample<B, V> sample(V lightView) {
        return new ProjectorSample<B, V>(kind, sourceData, lightView, remoteKey);
    }

    int bytes() {
        return BYTES + (blockEntity == null ? 0 : blockEntity.bytes());
    }
}
