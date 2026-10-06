package art.arcane.wormholes.render.client.session;

import art.arcane.wormholes.config.toml.ClientViewConfig;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ClientViewPhase;

public record ClientViewOptions(boolean enabled,
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
                                int interestGraceTicks) {
    public static final int DEFAULT_INTEREST_GRACE_TICKS = 5;

    public ClientViewOptions {
        helloGraceMillis = Math.max(0, Math.min(ClientViewConfig.MAX_HELLO_GRACE_MILLIS, helloGraceMillis));
        maxFrameBytes = ViewStreamLimits.clampMaxFrameBytes(maxFrameBytes);
        ackWindowFrames = Math.max(0, Math.min(ClientViewConfig.MAX_ACK_WINDOW_FRAMES, ackWindowFrames));
        interestGraceTicks = Math.max(0, interestGraceTicks);
    }

    public static ClientViewOptions from(ClientViewConfig config, int interestGraceTicks) {
        return new ClientViewOptions(config.enabled, config.configurationHandshake, config.helloGraceMillis, config.maxFrameBytes(),
            config.ackWindowFrames, config.brickCache, config.destinationLight, config.entityFrames, config.zeroCopy,
            config.standbyPrestream, config.viewStats, config.clientMirror, config.clientRecursion, interestGraceTicks);
    }

    public static ClientViewOptions defaults() {
        return from(new ClientViewConfig(), DEFAULT_INTEREST_GRACE_TICKS);
    }

    public long serverCaps(ClientViewPhase phase) {
        long caps = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.FX_EMITTERS,
            ViewStreamCapability.ATMOSPHERE, ViewStreamCapability.LINK_UNCOMPRESSED, ViewStreamCapability.MESH_RENDER,
            ViewStreamCapability.LOCAL_MESH, ViewStreamCapability.MESH_REUSE, ViewStreamCapability.PREPARED_TRAVEL,
            ViewStreamCapability.PREPARED_TRAVEL_CACHE);
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
        if (phase == ClientViewPhase.CONFIGURATION) {
            caps |= ViewStreamCapability.CONFIG_PHASE.mask();
        }
        return caps;
    }
}
