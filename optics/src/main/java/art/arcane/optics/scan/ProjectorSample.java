package art.arcane.optics.scan;

import art.arcane.optics.claim.ProjectedBlockClaim;

public final class ProjectorSample<B, V> {
    private static final ProjectorSample<?, ?> NO_SAMPLE = new ProjectorSample<Object, Object>(
        Kind.NO_SAMPLE, null, null, ProjectedBlockClaim.NO_REMOTE_KEY);

    public final Kind kind;
    public final B data;
    private final V lightView;
    private final long remoteKey;

    public ProjectorSample(Kind kind, B data, V lightView, long remoteKey) {
        this.kind = kind;
        this.data = data;
        this.lightView = lightView;
        this.remoteKey = remoteKey;
    }

    @SuppressWarnings("unchecked")
    public static <B, V> ProjectorSample<B, V> noSample() {
        return (ProjectorSample<B, V>) NO_SAMPLE;
    }

    public static <B, V> ProjectorSample<B, V> maskAir(B airBlockData) {
        return new ProjectorSample<B, V>(Kind.MASK_AIR, airBlockData, null, ProjectedBlockClaim.NO_REMOTE_KEY);
    }

    public Kind kind() {
        return kind;
    }

    public B data() {
        return data;
    }

    public V lightView() {
        return lightView;
    }

    public long remoteKey() {
        return remoteKey;
    }

    public ProjectorSample<B, V> withData(B transformed) {
        return new ProjectorSample<B, V>(kind, transformed, lightView, remoteKey);
    }

    public ProjectorSample<B, V> withLightView(V view) {
        if (view == lightView) {
            return this;
        }
        return new ProjectorSample<B, V>(kind, data, view, remoteKey);
    }

    public ProjectedBlockClaim<B, V> asClaim(B projectedData) {
        return asClaim(projectedData, defaultLightingPolicy());
    }

    public ProjectedBlockClaim<B, V> asClaim(B projectedData, ProjectedBlockClaim.LightingPolicy lightingPolicy) {
        boolean maskAir = kind == Kind.MASK_AIR;
        return new ProjectedBlockClaim<B, V>(projectedData, lightView, remoteKey, maskAir, lightingPolicy);
    }

    public boolean matchesClaim(ProjectedBlockClaim<B, V> claim, B projectedData, boolean maskAir) {
        return matchesClaim(claim, projectedData, maskAir, defaultLightingPolicy());
    }

    public boolean matchesClaim(ProjectedBlockClaim<B, V> claim,
                         B projectedData,
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
        V previousLightView = claim.getLightView();
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
