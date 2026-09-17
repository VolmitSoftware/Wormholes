package art.arcane.wormholes.render;

import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectedBlockClaim {
    public static final long NO_REMOTE_KEY = Long.MIN_VALUE;
    static final int UNRESOLVED_GLOBAL_ID = Integer.MIN_VALUE;

    private final BlockData data;
    private final ProjectionWorldView lightView;
    private final long lightRemoteKey;
    private final boolean maskAir;
    private final LightingPolicy lightingPolicy;
    private final boolean blackout;
    private int globalId;

    public ProjectedBlockClaim(BlockData data, ProjectionWorldView lightView, long lightRemoteKey, boolean maskAir) {
        this(data, lightView, lightRemoteKey, maskAir,
            lightView != null && lightRemoteKey != NO_REMOTE_KEY ? LightingPolicy.SOURCE : LightingPolicy.LOCAL);
    }

    ProjectedBlockClaim(BlockData data,
                        ProjectionWorldView lightView,
                        long lightRemoteKey,
                        boolean maskAir,
                        LightingPolicy lightingPolicy) {
        this(data, lightView, lightRemoteKey, maskAir, lightingPolicy, false);
    }

    private ProjectedBlockClaim(BlockData data,
                                ProjectionWorldView lightView,
                                long lightRemoteKey,
                                boolean maskAir,
                                LightingPolicy lightingPolicy,
                                boolean blackout) {
        this.data = data;
        this.lightView = lightView;
        this.lightRemoteKey = lightRemoteKey;
        this.maskAir = maskAir;
        this.lightingPolicy = lightingPolicy;
        this.blackout = blackout;
        this.globalId = UNRESOLVED_GLOBAL_ID;
    }

    /** A blackout shell cell: the seal block, full bright, never a mask, keyed to the destination cell it covers. */
    static ProjectedBlockClaim blackout(BlockData data, ProjectionWorldView lightView, long lightRemoteKey) {
        return new ProjectedBlockClaim(data, lightView, lightRemoteKey, false, LightingPolicy.FULL_BRIGHT, true);
    }

    public BlockData getData() {
        return data;
    }

    ProjectionWorldView getLightView() {
        return lightView;
    }

    public long getLightRemoteKey() {
        return lightRemoteKey;
    }

    public boolean isMaskAir() {
        return maskAir;
    }

    LightingPolicy getLightingPolicy() {
        return lightingPolicy;
    }

    boolean isFullBright() {
        return lightingPolicy == LightingPolicy.FULL_BRIGHT;
    }

    /** True for shell cells the blackout pass synthesized rather than sampled from the destination. */
    boolean isBlackout() {
        return blackout;
    }

    int getGlobalId() {
        return globalId;
    }

    void setGlobalId(int globalId) {
        this.globalId = globalId;
    }

    boolean hasRemoteLight() {
        return lightView != null && lightRemoteKey != NO_REMOTE_KEY;
    }

    boolean requiresLightOverlay(boolean sourceLightingEnabled) {
        return isFullBright()
            || (sourceLightingEnabled && lightingPolicy == LightingPolicy.SOURCE && hasRemoteLight());
    }

    ProjectedBlockClaim withLightingPolicy(LightingPolicy nextPolicy) {
        if (lightingPolicy == nextPolicy) {
            return this;
        }
        ProjectedBlockClaim updated = new ProjectedBlockClaim(
            data, lightView, lightRemoteKey, maskAir, nextPolicy, blackout);
        updated.globalId = globalId;
        return updated;
    }

    ProjectedBlockClaim withFullBright(boolean enabled) {
        LightingPolicy nextPolicy = enabled
            ? LightingPolicy.FULL_BRIGHT
            : hasRemoteLight() ? LightingPolicy.SOURCE : LightingPolicy.LOCAL;
        return withLightingPolicy(nextPolicy);
    }

    boolean sameLightSource(ProjectedBlockClaim other) {
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

    enum LightingPolicy {
        LOCAL,
        SOURCE,
        FULL_BRIGHT
    }
}
