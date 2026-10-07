package art.arcane.optics.plate;


import art.arcane.optics.scan.Sample;
import art.arcane.optics.fidelity.BlockEntitySample;

/**
 * One palette entry of a shared view plate: the sampler classification, the untransformed destination
 * sample, the block state after the frame transform and, when the destination carries one, the
 * sanitized block-entity snapshot. Cells without a block entity are shared by every plate position
 * with the same classification and source state.
 */
public final class PlateCell<B> {
    static final int BYTES = 40;

    private final Sample.Kind kind;
    private final B sourceData;
    private final B data;
    private final BlockEntitySample blockEntity;

    public PlateCell(Sample.Kind kind, B sourceData, B data, BlockEntitySample blockEntity) {
        this.kind = kind;
        this.sourceData = sourceData;
        this.data = data;
        this.blockEntity = blockEntity;
    }

    public Sample.Kind kind() {
        return kind;
    }

    public B sourceData() {
        return sourceData;
    }

    public B data() {
        return data;
    }

    public BlockEntitySample blockEntity() {
        return blockEntity;
    }

    public boolean isAir() {
        return kind == Sample.Kind.REMOTE_AIR || kind == Sample.Kind.MASK_AIR;
    }

    public <V> Sample<B, V> sample(V lightView, long remoteKey) {
        return new Sample<B, V>(kind, sourceData, lightView, remoteKey);
    }

    int bytes() {
        return BYTES + (blockEntity == null ? 0 : blockEntity.bytes());
    }
}
