package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.portal.ProjectionRenderMode;

import org.bukkit.World;

import java.util.UUID;

public final class ReplicationTestStream {
    private static final UUID PORTAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private ReplicationTestStream() {
    }

    public static ReplicationStreamKey stream(long chunkKey) {
        return new ReplicationStreamKey(PORTAL_ID, WORLD_ID, chunkKey, ProjectionRenderMode.PANOPTIC);
    }

    public static ReplicationStreamKey stream(UUID portalId, World world, long chunkKey) {
        return new ReplicationStreamKey(portalId, world.getUID(), chunkKey, ProjectionRenderMode.PANOPTIC);
    }

    public static ReplicationStreamKey stream(UUID portalId, World world, long chunkKey, ProjectionRenderMode renderMode) {
        return new ReplicationStreamKey(portalId, world.getUID(), chunkKey, renderMode);
    }
}
