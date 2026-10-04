package art.arcane.wormholes.render.client.session;

import art.arcane.wormholes.config.toml.ClientViewConfig;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewProtocol;

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
        maxFrameBytes = ClientViewProtocol.clampMaxFrameBytes(maxFrameBytes);
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
        long caps = ClientViewCapability.of(ClientViewCapability.PLATES, ClientViewCapability.FX_EMITTERS,
            ClientViewCapability.ATMOSPHERE, ClientViewCapability.LINK_UNCOMPRESSED, ClientViewCapability.MESH_RENDER,
            ClientViewCapability.LOCAL_MESH, ClientViewCapability.MESH_REUSE, ClientViewCapability.PREPARED_TRAVEL,
            ClientViewCapability.PREPARED_TRAVEL_CACHE);
        if (brickCache) {
            caps |= ClientViewCapability.BRICK_CACHE.mask();
        }
        if (destinationLight) {
            caps |= ClientViewCapability.DEST_LIGHT.mask();
        }
        if (entityFrames) {
            caps |= ClientViewCapability.ENTITY_FRAMES.mask() | ClientViewCapability.ENTITY_EVENTS.mask()
                | ClientViewCapability.ENTITY_SELF.mask();
        }
        if (zeroCopy) {
            caps |= ClientViewCapability.ZERO_COPY.mask();
        }
        if (viewStats) {
            caps |= ClientViewCapability.VIEW_STATS.mask();
        }
        if (clientMirror) {
            caps |= ClientViewCapability.CLIENT_MIRROR.mask();
        }
        if (clientRecursion) {
            caps |= ClientViewCapability.CLIENT_RECURSION.mask();
        }
        if (phase == ClientViewPhase.CONFIGURATION) {
            caps |= ClientViewCapability.CONFIG_PHASE.mask();
        }
        return caps;
    }
}
