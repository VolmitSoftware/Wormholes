package art.arcane.optics.stream;

public record ViewStreamOptions(boolean enabled,
                                boolean configurationHandshake,
                                int helloGraceMillis,
                                int maxFrameBytes,
                                int ackWindowFrames,
                                boolean brickCache,
                                boolean destinationLight,
                                boolean entityFrames,
                                boolean zeroCopy,
                                boolean standbyPrestream,
                                boolean viewStats,
                                boolean clientMirror,
                                boolean clientRecursion,
                                int interestGraceTicks,
                                long withheldCaps) {
    public static final int DEFAULT_INTEREST_GRACE_TICKS = 5;

    public ViewStreamOptions {
        helloGraceMillis = Math.max(0, Math.min(ViewStreamLimits.MAX_HELLO_GRACE_MILLIS, helloGraceMillis));
        maxFrameBytes = ViewStreamLimits.clampMaxFrameBytes(maxFrameBytes);
        ackWindowFrames = Math.max(0, Math.min(ViewStreamLimits.MAX_ACK_WINDOW_FRAMES, ackWindowFrames));
        interestGraceTicks = Math.max(0, interestGraceTicks);
        withheldCaps &= ViewStreamCapability.ALL;
    }

    public long serverCaps(ViewStreamPhase phase) {
        long caps = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.ATMOSPHERE,
            ViewStreamCapability.LINK_UNCOMPRESSED, ViewStreamCapability.MESH_RENDER, ViewStreamCapability.LOCAL_MESH,
            ViewStreamCapability.MESH_REUSE);
        if (brickCache) {
            caps |= ViewStreamCapability.BRICK_CACHE.mask();
        }
        if (destinationLight) {
            caps |= ViewStreamCapability.DEST_LIGHT.mask();
        }
        if (entityFrames) {
            caps |= ViewStreamCapability.ENTITY_FRAMES.mask() | ViewStreamCapability.ENTITY_EVENTS.mask()
                | ViewStreamCapability.ENTITY_SELF.mask();
        }
        if (zeroCopy) {
            caps |= ViewStreamCapability.ZERO_COPY.mask();
        }
        if (viewStats) {
            caps |= ViewStreamCapability.VIEW_STATS.mask();
        }
        if (clientMirror) {
            caps |= ViewStreamCapability.CLIENT_MIRROR.mask();
        }
        if (clientRecursion) {
            caps |= ViewStreamCapability.CLIENT_RECURSION.mask();
        }
        if (phase == ViewStreamPhase.CONFIGURATION) {
            caps |= ViewStreamCapability.CONFIG_PHASE.mask();
        }
        return caps;
    }
}
