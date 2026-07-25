package art.arcane.wormholes.render;

import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.render.view.ProjectionWorldView;

final class ProjectorSample {
    private static final ProjectorSample NO_SAMPLE = new ProjectorSample(
        Kind.NO_SAMPLE, null, null, ProjectedBlockClaim.NO_REMOTE_KEY);

    final Kind kind;
    final BlockData data;
    private final ProjectionWorldView lightView;
    private final long remoteKey;

    ProjectorSample(Kind kind, BlockData data, ProjectionWorldView lightView, long remoteKey) {
        this.kind = kind;
        this.data = data;
        this.lightView = lightView;
        this.remoteKey = remoteKey;
    }

    static ProjectorSample noSample() {
        return NO_SAMPLE;
    }

    static ProjectorSample maskAir(BlockData airBlockData) {
        return new ProjectorSample(Kind.MASK_AIR, airBlockData, null, ProjectedBlockClaim.NO_REMOTE_KEY);
    }

    ProjectorSample withData(BlockData transformed) {
        return new ProjectorSample(Kind.BLOCK, transformed, lightView, remoteKey);
    }

    ProjectedBlockClaim asClaim(BlockData projectedData) {
        if (kind == Kind.MASK_AIR) {
            return new ProjectedBlockClaim(projectedData, null, ProjectedBlockClaim.NO_REMOTE_KEY, true);
        }
        return new ProjectedBlockClaim(projectedData, lightView, remoteKey, false);
    }

    boolean matchesClaim(ProjectedBlockClaim claim, BlockData projectedData, boolean maskAir) {
        if (claim == null
            || claim.getLightRemoteKey() != remoteKey
            || claim.isMaskAir() != maskAir
            || !claim.getData().equals(projectedData)) {
            return false;
        }
        ProjectionWorldView previousLightView = claim.getLightView();
        return previousLightView == null ? lightView == null : previousLightView.equals(lightView);
    }

    enum Kind {
        BLOCK,
        REMOTE_AIR,
        MASK_AIR,
        OCCLUDED,
        NO_SAMPLE
    }
}
