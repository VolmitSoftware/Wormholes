package art.arcane.wormholes.network.view;

import art.arcane.optics.math.CellKeys;

import art.arcane.optics.math.BlockBox;

import art.arcane.wormholes.network.replication.ReplicationStreamKey;
import art.arcane.wormholes.portal.ProjectionRenderMode;

import org.bukkit.World;
import org.bukkit.entity.Pose;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ViewSession extends ViewEntityState<Pose> {
    final UUID subscriptionId;
    final World world;
    final BlockBox box;
    int meshDistance;
    long nextEnvironmentTick;
    final Map<String, Integer> peerMeshDistances = new ConcurrentHashMap<>();
    final ProjectionRenderMode renderMode;
    final int centerChunkX;
    final int centerChunkZ;
    final List<long[]> columns;
    final List<Long> chunkKeys;
    final List<ReplicationStreamKey> streamKeys;
    final BoundingBox bounds;
    final Map<String, ViewServer.TimeDeliveryState> timeDeliveryStates = new ConcurrentHashMap<>();
    final Map<String, InitialSubscriptionProgress> initialSubscriptionProgress = new ConcurrentHashMap<>();
    final AtomicBoolean entityCaptureRunning = new AtomicBoolean(false);
    final AtomicLong entityCaptureGeneration = new AtomicLong();
    volatile ViewServer.TicketLease ticketLease;
    volatile ViewServer.EntityCaptureToken activeEntityCapture;
    volatile int lastSkyDarken = -1;
    volatile int lastWeather = -1;

    ViewSession(UUID portalId, World world, BlockBox box, ProjectionRenderMode renderMode, int centerChunkX, int centerChunkZ,
                double portalCenterX, double portalCenterY, double portalCenterZ) {
        super(portalId, new Center(portalCenterX, portalCenterY, portalCenterZ));
        this.subscriptionId = UUID.randomUUID();
        this.world = world;
        this.box = box;
        this.renderMode = renderMode == null ? ProjectionRenderMode.VENTICULAR : renderMode;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.columns = columnsFor(box);
        this.chunkKeys = chunkKeysFor(columns);
        this.streamKeys = streamKeysFor(portalId, world.getUID(), chunkKeys, this.renderMode);
        this.bounds = new BoundingBox(box.minX(), box.minY(), box.minZ(),
            box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
    }

    boolean containsChunk(int chunkX, int chunkZ) {
        for (long[] column : columns) {
            if ((int) column[0] == chunkX && (int) column[1] == chunkZ) {
                return true;
            }
        }
        return false;
    }

    ReplicationStreamKey streamFor(long chunkKey) {
        return new ReplicationStreamKey(portalId, world.getUID(), chunkKey, renderMode);
    }

    static List<long[]> columnsFor(BlockBox box) {
        List<long[]> columns = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                columns.add(new long[]{cx, cz});
            }
        }
        return columns;
    }

    static List<Long> chunkKeysFor(List<long[]> columns) {
        List<Long> chunkKeys = new ArrayList<>(columns.size());
        for (long[] column : columns) {
            chunkKeys.add(CellKeys.chunkKey((int) column[0], (int) column[1]));
        }
        return List.copyOf(chunkKeys);
    }

    private static List<ReplicationStreamKey> streamKeysFor(UUID portalId, UUID worldId, List<Long> chunkKeys,
                                                             ProjectionRenderMode renderMode) {
        List<ReplicationStreamKey> streams = new ArrayList<>(chunkKeys.size());
        for (Long chunkKey : chunkKeys) {
            streams.add(new ReplicationStreamKey(portalId, worldId, chunkKey.longValue(), renderMode));
        }
        return List.copyOf(streams);
    }
}
