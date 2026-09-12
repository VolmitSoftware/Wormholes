package art.arcane.wormholes.render;

import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectorSample {
    private static final ProjectorSample NO_SAMPLE = new ProjectorSample(
        Kind.NO_SAMPLE, null, null, ProjectedBlockClaim.NO_REMOTE_KEY);

    final Kind kind;
    final BlockData data;
    private final ProjectionWorldView lightView;
    private final long remoteKey;

    public ProjectorSample(Kind kind, BlockData data, ProjectionWorldView lightView, long remoteKey) {
        this.kind = kind;
        this.data = data;
        this.lightView = lightView;
        this.remoteKey = remoteKey;
    }

    public static ProjectorSample noSample() {
        return NO_SAMPLE;
    }

    public static ProjectorSample maskAir(BlockData airBlockData) {
        return new ProjectorSample(Kind.MASK_AIR, airBlockData, null, ProjectedBlockClaim.NO_REMOTE_KEY);
    }

    public Kind kind() {
        return kind;
    }

    public BlockData data() {
        return data;
    }

    public ProjectionWorldView lightView() {
        return lightView;
    }

    public long remoteKey() {
        return remoteKey;
    }

    ProjectorSample withData(BlockData transformed) {
        return new ProjectorSample(kind, transformed, lightView, remoteKey);
    }

    public ProjectorSample withLightView(ProjectionWorldView view) {
        if (view == lightView) {
            return this;
        }
        return new ProjectorSample(kind, data, view, remoteKey);
    }

    ProjectedBlockClaim asClaim(BlockData projectedData) {
        return asClaim(projectedData, defaultLightingPolicy());
    }

    ProjectedBlockClaim asClaim(BlockData projectedData, ProjectedBlockClaim.LightingPolicy lightingPolicy) {
        boolean maskAir = kind == Kind.MASK_AIR;
        return new ProjectedBlockClaim(projectedData, lightView, remoteKey, maskAir, lightingPolicy);
    }

    boolean matchesClaim(ProjectedBlockClaim claim, BlockData projectedData, boolean maskAir) {
        return matchesClaim(claim, projectedData, maskAir, defaultLightingPolicy());
    }

    boolean matchesClaim(ProjectedBlockClaim claim,
                         BlockData projectedData,
                         boolean maskAir,
                         ProjectedBlockClaim.LightingPolicy lightingPolicy) {
        if (claim == null || claim.getLightRemoteKey() != remoteKey) {
            return false;
        }
        if (claim.isMaskAir() != maskAir
            || claim.getLightingPolicy() != lightingPolicy
            || !claim.getData().equals(projectedData)) {
            return false;
        }
        ProjectionWorldView previousLightView = claim.getLightView();
        return previousLightView == lightView
            || (previousLightView != null && previousLightView.equals(lightView));
    }

    private ProjectedBlockClaim.LightingPolicy defaultLightingPolicy() {
        return lightView != null && remoteKey != ProjectedBlockClaim.NO_REMOTE_KEY
            ? ProjectedBlockClaim.LightingPolicy.SOURCE
            : ProjectedBlockClaim.LightingPolicy.LOCAL;
    }

    public enum Kind {
        BLOCK,
        BACKING_BLOCK,
        REMOTE_AIR,
        MASK_AIR,
        OCCLUDED,
        NO_SAMPLE
    }
}
