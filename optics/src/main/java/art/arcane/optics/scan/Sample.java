package art.arcane.optics.scan;

import art.arcane.optics.claim.BlockClaim;

public final class Sample<B, V> {
    private static final Sample<?, ?> NO_SAMPLE = new Sample<Object, Object>(
        Kind.NO_SAMPLE, null, null, BlockClaim.NO_REMOTE_KEY);

    public final Kind kind;
    public final B data;
    private final V lightView;
    private final long remoteKey;

    public Sample(Kind kind, B data, V lightView, long remoteKey) {
        this.kind = kind;
        this.data = data;
        this.lightView = lightView;
        this.remoteKey = remoteKey;
    }

    @SuppressWarnings("unchecked")
    public static <B, V> Sample<B, V> noSample() {
        return (Sample<B, V>) NO_SAMPLE;
    }

    public static <B, V> Sample<B, V> maskAir(B airBlockData) {
        return new Sample<B, V>(Kind.MASK_AIR, airBlockData, null, BlockClaim.NO_REMOTE_KEY);
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

    public Sample<B, V> withData(B transformed) {
        return new Sample<B, V>(kind, transformed, lightView, remoteKey);
    }

    public BlockClaim<B, V> asClaim(B projectedData) {
        return asClaim(projectedData, defaultLightingPolicy());
    }

    public BlockClaim<B, V> asClaim(B projectedData, BlockClaim.LightingPolicy lightingPolicy) {
        boolean maskAir = kind == Kind.MASK_AIR;
        return new BlockClaim<B, V>(projectedData, lightView, remoteKey, maskAir, lightingPolicy);
    }

    public boolean matchesClaim(BlockClaim<B, V> claim, B projectedData, boolean maskAir) {
        return matchesClaim(claim, projectedData, maskAir, defaultLightingPolicy());
    }

    public boolean matchesClaim(BlockClaim<B, V> claim,
                         B projectedData,
                         boolean maskAir,
                         BlockClaim.LightingPolicy lightingPolicy) {
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

    private BlockClaim.LightingPolicy defaultLightingPolicy() {
        return lightView != null && remoteKey != BlockClaim.NO_REMOTE_KEY
            ? BlockClaim.LightingPolicy.SOURCE
            : BlockClaim.LightingPolicy.LOCAL;
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
