package art.arcane.optics.scan;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.volume.LodPolicy;

public final class ProjectorPassRevision {
    private ProjectorPassRevision() {
    }

    public static long transform(Frame localFrame,
                                       Frame remoteFrame,
                                       double localOriginX,
                                       double localOriginY,
                                       double localOriginZ,
                                       double remoteOriginX,
                                       double remoteOriginY,
                                       double remoteOriginZ,
                                       int depth,
                                       int lateral,
                                       double aperturePadding,
                                       boolean buriedCellCulling,
                                       LodPolicy lod,
                                       boolean blockEntities) {
        long hash = 1125899906842597L;
        hash = mix(hash, localFrame.getNormal().ordinal());
        hash = mix(hash, localFrame.getRight().ordinal());
        hash = mix(hash, localFrame.getUp().ordinal());
        hash = mix(hash, remoteFrame.getNormal().ordinal());
        hash = mix(hash, remoteFrame.getRight().ordinal());
        hash = mix(hash, remoteFrame.getUp().ordinal());
        hash = mix(hash, Double.doubleToLongBits(localOriginX));
        hash = mix(hash, Double.doubleToLongBits(localOriginY));
        hash = mix(hash, Double.doubleToLongBits(localOriginZ));
        hash = mix(hash, Double.doubleToLongBits(remoteOriginX));
        hash = mix(hash, Double.doubleToLongBits(remoteOriginY));
        hash = mix(hash, Double.doubleToLongBits(remoteOriginZ));
        hash = mix(hash, depth);
        hash = mix(hash, lateral);
        hash = mix(hash, Double.doubleToLongBits(aperturePadding));
        hash = mix(hash, buriedCellCulling ? 1L : 0L);
        hash = mix(hash, lod.mergeRuns() ? 1L : 0L);
        hash = mix(hash, lod.distanceBlocks());
        hash = mix(hash, lod.detailCutoffBlocks());
        hash = mix(hash, blockEntities ? 1L : 0L);
        return hash;
    }

    public static long mix(long hash, long value) {
        long mixed = (hash ^ value) * 0x100000001B3L;
        return mixed ^ (mixed >>> 29);
    }

}
