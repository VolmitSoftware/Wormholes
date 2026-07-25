package art.arcane.wormholes.network.view;

import art.arcane.wormholes.portal.ProjectionRenderMode;

import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ViewSession {
    final UUID portalId;
    final UUID subscriptionId;
    final World world;
    final ViewBox box;
    final ProjectionRenderMode renderMode;
    final int centerChunkX;
    final int centerChunkZ;
    final double portalCenterX;
    final double portalCenterY;
    final double portalCenterZ;
    final List<long[]> columns;
    final List<Long> chunkKeys;
    final BoundingBox bounds;
    final Set<String> peers = ConcurrentHashMap.newKeySet();
    final Set<UUID> sentProfiles = ConcurrentHashMap.newKeySet();
    final Map<String, Map<UUID, EntitySendState>> sendStates = new ConcurrentHashMap<>();
    final Map<String, Set<UUID>> lastSentPresentIds = new ConcurrentHashMap<>();
    final Map<String, Long> sidebandEntityNextTick = new ConcurrentHashMap<>();
    final Map<String, Boolean> lastPeerSideband = new ConcurrentHashMap<>();
    final Map<String, ViewServer.TimeDeliveryState> timeDeliveryStates = new ConcurrentHashMap<>();
    final Map<UUID, EntityVisual> lastCapturedSnapshots = new ConcurrentHashMap<>();
    final Map<UUID, ViewServer.BlobCaptureState> blobCaptureStates = new ConcurrentHashMap<>();
    final AtomicBoolean entityCaptureRunning = new AtomicBoolean(false);
    final AtomicLong entityCaptureGeneration = new AtomicLong();
    final AtomicBoolean captureFailureLogged = new AtomicBoolean(false);
    volatile ViewServer.TicketLease ticketLease;
    volatile ViewServer.EntityCaptureToken activeEntityCapture;
    volatile int lastSkyDarken = -1;

    ViewSession(UUID portalId, World world, ViewBox box, ProjectionRenderMode renderMode, int centerChunkX, int centerChunkZ,
                double portalCenterX, double portalCenterY, double portalCenterZ) {
        this.portalId = portalId;
        this.subscriptionId = UUID.randomUUID();
        this.world = world;
        this.box = box;
        this.renderMode = renderMode == null ? ProjectionRenderMode.PANOPTIC : renderMode;
        this.centerChunkX = centerChunkX;
        this.centerChunkZ = centerChunkZ;
        this.portalCenterX = portalCenterX;
        this.portalCenterY = portalCenterY;
        this.portalCenterZ = portalCenterZ;
        this.columns = columnsFor(box);
        this.chunkKeys = chunkKeysFor(columns);
        this.bounds = new BoundingBox(box.minX(), box.minY(), box.minZ(),
            box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
    }

    Map<UUID, EntitySendState> sendStatesFor(String peerName) {
        return sendStates.computeIfAbsent(peerName, name -> new ConcurrentHashMap<>());
    }

    boolean containsChunk(int chunkX, int chunkZ) {
        for (long[] column : columns) {
            if ((int) column[0] == chunkX && (int) column[1] == chunkZ) {
                return true;
            }
        }
        return false;
    }

    static List<long[]> columnsFor(ViewBox box) {
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
            chunkKeys.add(ViewSlice.columnKey((int) column[0], (int) column[1]));
        }
        return List.copyOf(chunkKeys);
    }
}
