package art.arcane.optics.claim;

public final class ProjectedBlockClaim<B, V> implements BlockProjectionClaim<ProjectedBlockClaim<B, V>> {
    public static final long NO_REMOTE_KEY = Long.MIN_VALUE;
    public static final int UNRESOLVED_GLOBAL_ID = Integer.MIN_VALUE;

    private final B data;
    private final V lightView;
    private final long lightRemoteKey;
    private final boolean maskAir;
    private final LightingPolicy lightingPolicy;
    private final boolean blackout;
    private final boolean held;
    private int globalId;

    public ProjectedBlockClaim(B data, V lightView, long lightRemoteKey, boolean maskAir) {
        this(data, lightView, lightRemoteKey, maskAir,
            lightView != null && lightRemoteKey != NO_REMOTE_KEY ? LightingPolicy.SOURCE : LightingPolicy.LOCAL);
    }

    public ProjectedBlockClaim(B data,
                        V lightView,
                        long lightRemoteKey,
                        boolean maskAir,
                        LightingPolicy lightingPolicy) {
        this(data, lightView, lightRemoteKey, maskAir, lightingPolicy, false, false);
    }

    private ProjectedBlockClaim(B data,
                                V lightView,
                                long lightRemoteKey,
                                boolean maskAir,
                                LightingPolicy lightingPolicy,
                                boolean blackout,
                                boolean held) {
        this.data = data;
        this.lightView = lightView;
        this.lightRemoteKey = lightRemoteKey;
        this.maskAir = maskAir;
        this.lightingPolicy = lightingPolicy;
        this.blackout = blackout;
        this.held = held;
        this.globalId = UNRESOLVED_GLOBAL_ID;
    }

    /** A blackout shell cell: the seal block, full bright, never a mask, keyed to the destination cell it covers. */
    public static <B, V> ProjectedBlockClaim<B, V> blackout(B data, V lightView, long lightRemoteKey) {
        return new ProjectedBlockClaim<B, V>(data, lightView, lightRemoteKey, false, LightingPolicy.FULL_BRIGHT, true, false);
    }

    public B getData() {
        return data;
    }

    public V getLightView() {
        return lightView;
    }

    public long getLightRemoteKey() {
        return lightRemoteKey;
    }

    public boolean isMaskAir() {
        return maskAir;
    }

    public LightingPolicy getLightingPolicy() {
        return lightingPolicy;
    }

    public boolean isFullBright() {
        return lightingPolicy == LightingPolicy.FULL_BRIGHT;
    }

    /** True for shell cells the blackout pass synthesized rather than sampled from the destination. */
    public boolean isBlackout() {
        return blackout;
    }

    @Override
    public boolean isHeld() {
        return held;
    }

    public int getGlobalId() {
        return globalId;
    }

    public void setGlobalId(int globalId) {
        this.globalId = globalId;
    }

    public boolean hasRemoteLight() {
        return lightView != null && lightRemoteKey != NO_REMOTE_KEY;
    }

    public boolean requiresLightOverlay(boolean sourceLightingEnabled) {
        return isFullBright()
            || (sourceLightingEnabled && lightingPolicy == LightingPolicy.SOURCE && hasRemoteLight());
    }

    public ProjectedBlockClaim<B, V> withLightingPolicy(LightingPolicy nextPolicy) {
        if (lightingPolicy == nextPolicy) {
            return this;
        }
        ProjectedBlockClaim<B, V> updated = new ProjectedBlockClaim<B, V>(
            data, lightView, lightRemoteKey, maskAir, nextPolicy, blackout, held);
        updated.globalId = globalId;
        return updated;
    }

    public ProjectedBlockClaim<B, V> withFullBright(boolean enabled) {
        LightingPolicy nextPolicy = enabled
            ? LightingPolicy.FULL_BRIGHT
            : hasRemoteLight() ? LightingPolicy.SOURCE : LightingPolicy.LOCAL;
        return withLightingPolicy(nextPolicy);
    }

    public ProjectedBlockClaim<B, V> withHeld(boolean nextHeld) {
        if (held == nextHeld) {
            return this;
        }
        ProjectedBlockClaim<B, V> updated = new ProjectedBlockClaim<B, V>(
            data, lightView, lightRemoteKey, maskAir, lightingPolicy, blackout, nextHeld);
        updated.globalId = globalId;
        return updated;
    }

    @Override
    public boolean sameBlock(ProjectedBlockClaim<B, V> other) {
        return other != null && data.equals(other.data);
    }

    public boolean sameLightSource(ProjectedBlockClaim<B, V> other) {
        if (other == null) {
            return false;
        }
        if (lightingPolicy != other.lightingPolicy) {
            return false;
        }
        if (lightingPolicy != LightingPolicy.SOURCE) {
            return true;
        }
        if (lightRemoteKey != other.lightRemoteKey) {
            return false;
        }
        if (lightView == null) {
            return other.lightView == null;
        }
        return lightView.equals(other.lightView);
    }

    public enum LightingPolicy {
        LOCAL,
        SOURCE,
        FULL_BRIGHT
    }
}
