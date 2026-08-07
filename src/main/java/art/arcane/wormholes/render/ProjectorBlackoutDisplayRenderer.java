package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesTelemetry;

final class ProjectorBlackoutDisplayRenderer {
    static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    private static final String FAILURE_REASON = "PROJECTION_BLACKOUT_DISPLAY_FAILURE";
    private static final double MIN_CLIENT_ENTITY_DISTANCE_SCALE = 0.5D;
    private static final double VIEW_RANGE_BLOCKS = 64.0D;
    private static final double VIEW_RANGE_MARGIN_BLOCKS = 16.0D;
    private static final double VIEW_RANGE_QUANTUM = 0.5D;
    private static final float MAX_VIEW_RANGE = 32.0F;
    private static final AtomicInteger NEXT_DISPLAY_ID = new AtomicInteger(1_700_000_000);

    private final EntityRenderPacketChannel channel;
    private final Map<BlockData, Integer> globalIdCache;
    private Map<ProjectorBlackoutMesh.Panel, DisplayState> active;
    private final Map<Integer, DisplayState> uncertain;
    private Map<ProjectorBlackoutMesh.Panel, DisplayState> pending;
    private boolean failureLogged;
    private int spawns;
    private int metadataUpdates;
    private int destroys;

    ProjectorBlackoutDisplayRenderer() {
        this(new EntityRenderPacketChannel());
    }

    ProjectorBlackoutDisplayRenderer(EntityRenderPacketChannel channel) {
        this.channel = channel;
        this.globalIdCache = new HashMap<BlockData, Integer>();
        this.active = new LinkedHashMap<ProjectorBlackoutMesh.Panel, DisplayState>();
        this.uncertain = new LinkedHashMap<Integer, DisplayState>();
        this.pending = null;
        this.failureLogged = false;
        this.spawns = 0;
        this.metadataUpdates = 0;
        this.destroys = 0;
    }

    boolean prepare(Player observer,
                    List<ProjectorBlackoutMesh.Panel> panels,
                    BlockData data,
                    double projectionDepth) {
        if (panels.isEmpty()) {
            return prepareEmpty();
        }
        ServerVersion serverVersion;
        try {
            serverVersion = PacketEvents.getAPI().getServerManager().getVersion();
        } catch (RuntimeException ex) {
            noteFailure("version", ex);
            return false;
        }
        if (!supports(serverVersion)) {
            return false;
        }
        int globalId = globalIdFor(data);
        if (globalId < 0) {
            return false;
        }
        return prepare(observer, panels, globalId, projectionDepth);
    }

    boolean prepare(Player observer,
                    List<ProjectorBlackoutMesh.Panel> panels,
                    int globalId,
                    double projectionDepth) {
        if (panels.isEmpty()) {
            return prepareEmpty();
        }

        mergeAbandonedPending();
        if (!cleanupUncertain(observer)) {
            return false;
        }
        Map<ProjectorBlackoutMesh.Panel, DisplayState> target =
            new LinkedHashMap<ProjectorBlackoutMesh.Panel, DisplayState>(Math.max(4, panels.size() * 2));
        List<DisplayState> newStates = new ArrayList<DisplayState>();
        float viewRange = viewRange(observer, panels, projectionDepth);
        boolean prepared;
        try {
            channel.begin(observer);
            for (ProjectorBlackoutMesh.Panel panel : panels) {
                DisplayState existing = active.get(panel);
                DisplayState targetState = existing == null
                    ? new DisplayState(NEXT_DISPLAY_ID.getAndIncrement(), UUID.randomUUID(), globalId, viewRange)
                    : new DisplayState(existing.entityId(), existing.entityUuid(), globalId, viewRange);
                target.put(panel, targetState);
                if (existing == null) {
                    newStates.add(targetState);
                    sendSpawn(observer, panel, targetState);
                    continue;
                }
                if (existing.globalId() != globalId || Float.compare(existing.viewRange(), viewRange) != 0) {
                    sendMetadata(observer, panel, targetState);
                    metadataUpdates++;
                }
            }
            pending = target;
            failureLogged = false;
            prepared = true;
        } catch (RuntimeException ex) {
            markUncertain(newStates);
            pending = null;
            noteFailure("prepare", ex);
            prepared = false;
        }
        if (!finishBatch("prepare-flush")) {
            markUncertain(newStates);
            pending = null;
            return false;
        }
        return prepared;
    }

    boolean prepareEmpty() {
        mergeAbandonedPending();
        pending = new LinkedHashMap<ProjectorBlackoutMesh.Panel, DisplayState>();
        return true;
    }

    void finish(Player observer) {
        Map<ProjectorBlackoutMesh.Panel, DisplayState> target = pending;
        if (target == null) {
            return;
        }
        pending = null;
        List<DisplayState> staleActive = new ArrayList<DisplayState>();
        for (Map.Entry<ProjectorBlackoutMesh.Panel, DisplayState> entry : active.entrySet()) {
            if (!target.containsKey(entry.getKey())) {
                staleActive.add(entry.getValue());
            }
        }
        List<DisplayState> stale = new ArrayList<DisplayState>(staleActive.size() + uncertain.size());
        stale.addAll(staleActive);
        stale.addAll(uncertain.values());
        if (stale.isEmpty()) {
            active = target;
            return;
        }

        boolean destroyed;
        try {
            channel.begin(observer);
            sendDestroy(observer, stale);
            failureLogged = false;
            destroyed = true;
        } catch (RuntimeException ex) {
            noteFailure("finish", ex);
            destroyed = false;
        }
        boolean flushed = finishBatch("finish-flush");
        if (destroyed && flushed) {
            active = target;
            uncertain.clear();
        } else {
            markUncertain(staleActive);
            active = target;
        }
    }

    void close(Player observer) {
        mergeAbandonedPending();
        if (active.isEmpty() && uncertain.isEmpty()) {
            return;
        }
        if (observer == null || !observer.isOnline()) {
            discard();
            return;
        }
        boolean destroyed;
        try {
            channel.begin(observer);
            List<DisplayState> states = new ArrayList<DisplayState>(active.size() + uncertain.size());
            states.addAll(active.values());
            states.addAll(uncertain.values());
            sendDestroy(observer, states);
            failureLogged = false;
            destroyed = true;
        } catch (RuntimeException ex) {
            noteFailure("close", ex);
            destroyed = false;
        }
        boolean flushed = finishBatch("close-flush");
        if (destroyed && flushed) {
            active.clear();
            uncertain.clear();
        } else {
            markUncertain(active.values());
            active.clear();
        }
    }

    void discard() {
        active.clear();
        uncertain.clear();
        if (pending != null) {
            pending.clear();
            pending = null;
        }
    }

    int getPaneCount() {
        return active.size() + uncertain.size();
    }

    int getSpawns() {
        return spawns;
    }

    int getMetadataUpdates() {
        return metadataUpdates;
    }

    int getDestroys() {
        return destroys;
    }

    private void sendSpawn(Player observer, ProjectorBlackoutMesh.Panel panel, DisplayState state) {
        ProjectorBlackoutMesh.Transform transform = panel.transform();
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
            state.entityId(),
            Optional.of(state.entityUuid()),
            EntityTypes.BLOCK_DISPLAY,
            new Vector3d(transform.x(), transform.y(), transform.z()),
            0.0F,
            0.0F,
            0.0F,
            0,
            Optional.empty());
        channel.send(observer, spawn);
        channel.send(observer, new WrapperPlayServerEntityMetadata(
            state.entityId(), displayMetadata(transform, state.globalId(), state.viewRange())));
        spawns++;
    }

    private void sendMetadata(Player observer, ProjectorBlackoutMesh.Panel panel, DisplayState state) {
        channel.send(observer, new WrapperPlayServerEntityMetadata(
            state.entityId(), displayMetadata(panel.transform(), state.globalId(), state.viewRange())));
    }

    private void sendDestroy(Player observer, Iterable<DisplayState> states) {
        List<Integer> ids = new ArrayList<Integer>();
        for (DisplayState state : states) {
            ids.add(Integer.valueOf(state.entityId()));
        }
        if (ids.isEmpty()) {
            return;
        }
        int[] packedIds = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            packedIds[i] = ids.get(i).intValue();
        }
        channel.send(observer, new WrapperPlayServerDestroyEntities(packedIds));
        destroys += packedIds.length;
    }

    static List<EntityData<?>> displayMetadata(ProjectorBlackoutMesh.Transform transform,
                                               int globalId,
                                               float viewRange) {
        List<EntityData<?>> metadata = new ArrayList<EntityData<?>>(14);
        metadata.add(new EntityData<Byte>(0, EntityDataTypes.BYTE, Byte.valueOf((byte) 0)));
        metadata.add(new EntityData<Boolean>(5, EntityDataTypes.BOOLEAN, Boolean.TRUE));
        metadata.add(new EntityData<Integer>(8, EntityDataTypes.INT, Integer.valueOf(0)));
        metadata.add(new EntityData<Integer>(9, EntityDataTypes.INT, Integer.valueOf(0)));
        metadata.add(new EntityData<Integer>(10, EntityDataTypes.INT, Integer.valueOf(0)));
        metadata.add(new EntityData<Vector3f>(11, EntityDataTypes.VECTOR3F,
            new Vector3f(0.0F, 0.0F, 0.0F)));
        metadata.add(new EntityData<Vector3f>(12, EntityDataTypes.VECTOR3F,
            new Vector3f((float) transform.scaleX(), (float) transform.scaleY(), (float) transform.scaleZ())));
        metadata.add(new EntityData<Quaternion4f>(13, EntityDataTypes.QUATERNION,
            new Quaternion4f(0.0F, 0.0F, 0.0F, 1.0F)));
        metadata.add(new EntityData<Quaternion4f>(14, EntityDataTypes.QUATERNION,
            new Quaternion4f(0.0F, 0.0F, 0.0F, 1.0F)));
        metadata.add(new EntityData<Byte>(15, EntityDataTypes.BYTE, Byte.valueOf((byte) 0)));
        metadata.add(new EntityData<Integer>(16, EntityDataTypes.INT, Integer.valueOf(FULL_BRIGHT)));
        metadata.add(new EntityData<Float>(17, EntityDataTypes.FLOAT, Float.valueOf(viewRange)));
        metadata.add(new EntityData<Float>(20, EntityDataTypes.FLOAT, Float.valueOf(0.0F)));
        metadata.add(new EntityData<Float>(21, EntityDataTypes.FLOAT, Float.valueOf(0.0F)));
        metadata.add(new EntityData<Integer>(23, EntityDataTypes.BLOCK_STATE, Integer.valueOf(globalId)));
        return metadata;
    }

    static boolean supports(ServerVersion version) {
        // BlockDisplay metadata layout (indices 0,5,8-17,20,21,23; 23=BLOCK_STATE) verified
        // bit-identical between 26.1.2 and 26.2 server jars (Entity/Display/BlockDisplay
        // accessor order and EntityDataSerializers registry match).
        return version == ServerVersion.V_26_1_2 || version == ServerVersion.V_26_2;
    }

    static float viewRange(double projectionDepth) {
        double requiredBlocks = Math.max(0.0D, projectionDepth) + VIEW_RANGE_MARGIN_BLOCKS;
        return clampViewRange(requiredBlocks);
    }

    static float viewRange(double eyeX,
                           double eyeY,
                           double eyeZ,
                           List<ProjectorBlackoutMesh.Panel> panels,
                           double projectionDepth) {
        double farthestSquared = 0.0D;
        for (ProjectorBlackoutMesh.Panel panel : panels) {
            ProjectorBlackoutMesh.Transform transform = panel.transform();
            double deltaX = transform.x() - eyeX;
            double deltaY = transform.y() - eyeY;
            double deltaZ = transform.z() - eyeZ;
            farthestSquared = Math.max(farthestSquared,
                (deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ));
        }
        double requiredBlocks = Math.max(
            Math.max(0.0D, projectionDepth), Math.sqrt(farthestSquared))
            + VIEW_RANGE_MARGIN_BLOCKS;
        return clampViewRange(requiredBlocks);
    }

    private static float viewRange(Player observer,
                                   List<ProjectorBlackoutMesh.Panel> panels,
                                   double projectionDepth) {
        if (observer == null) {
            return viewRange(projectionDepth);
        }
        Location eye = observer.getEyeLocation();
        if (eye == null) {
            return viewRange(projectionDepth);
        }
        return viewRange(eye.getX(), eye.getY(), eye.getZ(), panels, projectionDepth);
    }

    private static float clampViewRange(double requiredBlocks) {
        double units = requiredBlocks / (VIEW_RANGE_BLOCKS * MIN_CLIENT_ENTITY_DISTANCE_SCALE);
        double rounded = Math.ceil(units / VIEW_RANGE_QUANTUM) * VIEW_RANGE_QUANTUM;
        return (float) Math.max(1.0D, Math.min(MAX_VIEW_RANGE, rounded));
    }

    private int globalIdFor(BlockData data) {
        if (data == null) {
            return -1;
        }
        Integer cached = globalIdCache.get(data);
        if (cached != null) {
            return cached.intValue();
        }
        int globalId;
        try {
            globalId = SpigotConversionUtil.fromBukkitBlockData(data).getGlobalId();
        } catch (RuntimeException ex) {
            noteFailure("block-state", ex);
            return -1;
        }
        globalIdCache.put(data, Integer.valueOf(globalId));
        return globalId;
    }

    private void mergeAbandonedPending() {
        if (pending == null) {
            return;
        }
        active.putAll(pending);
        pending = null;
    }

    private void markUncertain(Iterable<DisplayState> states) {
        for (DisplayState state : states) {
            uncertain.put(Integer.valueOf(state.entityId()), state);
        }
    }

    private boolean cleanupUncertain(Player observer) {
        if (uncertain.isEmpty()) {
            return true;
        }
        boolean destroyed;
        try {
            channel.begin(observer);
            sendDestroy(observer, uncertain.values());
            failureLogged = false;
            destroyed = true;
        } catch (RuntimeException ex) {
            noteFailure("retry", ex);
            destroyed = false;
        }
        boolean flushed = finishBatch("retry-flush");
        if (!destroyed || !flushed) {
            return false;
        }
        uncertain.clear();
        return true;
    }

    private boolean finishBatch(String stage) {
        try {
            channel.end();
            return true;
        } catch (RuntimeException ex) {
            noteFailure(stage, ex);
            return false;
        }
    }

    private void noteFailure(String stage, RuntimeException ex) {
        WormholesTelemetry.countFailure(FAILURE_REASON);
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        Wormholes plugin = Wormholes.instance;
        if (plugin != null) {
            plugin.getLogger().log(Level.WARNING,
                "[Projector] blackout display " + stage + " failed; renderer will fall back or retry safely", ex);
        }
    }

    private record DisplayState(int entityId, UUID entityUuid, int globalId, float viewRange) {
    }
}
