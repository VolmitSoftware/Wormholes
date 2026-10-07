package art.arcane.wormholes.modded;

import art.arcane.optics.entity.EntityProfile;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.network.view.ViewEntityState;
import art.arcane.wormholes.render.view.ProjectionEntityData;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MinecraftLocalEntityView implements ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> {
    private final ServerLevel level;
    private final MinecraftPacketBlobs blobs;
    private final MinecraftEntityVisualCapture capture;
    private final ViewEntityState<Pose> state;
    private final Map<UUID, Sample> samples = new HashMap<>();
    private List<Entity> candidates = List.of();
    private List<EntitySnapshot> visuals = List.of();
    private long candidateTick = Long.MIN_VALUE;
    private long captureTick = Long.MIN_VALUE;
    private Vec3d origin;
    private int range;

    public MinecraftLocalEntityView(ServerLevel level, UUID portalId) {
        this.level = level;
        this.blobs = new MinecraftPacketBlobs(level.registryAccess());
        this.capture = new MinecraftEntityVisualCapture(blobs);
        this.state = new ViewEntityState<>(portalId, new ViewEntityState.Center(0, 0, 0));
    }

    public void update(Vec3d center, Options options) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Entity projection capture requires the server thread");
        }
        int queryRange = Double.isFinite(options.range()) ? Math.max(1, (int) Math.ceil(options.range())) : 1;
        long tick = level.getGameTime();
        boolean changed = !center.equals(origin) || range != queryRange;
        if (!changed && captureTick == tick) {
            return;
        }
        if (changed || candidateTick == Long.MIN_VALUE || tick - candidateTick >= Math.max(1, options.candidateCacheTicks())) {
            origin = center;
            range = queryRange;
            candidates = level.getEntities((Entity) null, new AABB(center.getX() - range, center.getY() - range, center.getZ() - range,
                center.getX() + range, center.getY() + range, center.getZ() + range), MinecraftLocalEntityView::eligible);
            candidateTick = tick;
        }
        Set<UUID> present = new HashSet<>(candidates.size());
        List<EntitySnapshot> next = new ArrayList<>(candidates.size());
        for (Entity entity : candidates) {
            if (!eligible(entity) || entity.level() != level) {
                continue;
            }
            EntitySnapshot visual = capture.capture(entity, state, tick);
            Sample previous = samples.get(visual.id());
            boolean metadataChanged = previous == null || previous.visual().metadata() != visual.metadata();
            boolean equipmentChanged = previous == null || previous.visual().equipment() != visual.equipment();
            List<SynchedEntityData.DataValue<?>> metadata = metadataChanged ? blobs.readMetadata(visual.metadata()) : previous.metadata();
            List<MinecraftPacketBlobs.Equipment> equipment = equipmentChanged ? blobs.readEquipment(visual.equipment()) : previous.equipment();
            EntityProfile profile = previous == null ? null : previous.profile();
            if (visual.isPlayer() && (profile == null || !visual.textureValue().isEmpty())) {
                profile = new EntityProfile(visual.playerName(), visual.textureValue(), visual.textureSignature());
            }
            int version = previous == null ? 1 : previous.version();
            if (previous != null && (metadataChanged || equipmentChanged || !Arrays.equals(previous.visual().mapData(), visual.mapData()))) {
                version++;
            }
            samples.put(visual.id(), new Sample(visual, metadata, equipment, profile, version));
            present.add(visual.id());
            next.add(visual);
        }
        samples.keySet().retainAll(present);
        state.lastCapturedSnapshots().keySet().retainAll(present);
        state.blobCaptureStates().keySet().retainAll(present);
        state.sentProfiles().retainAll(present);
        visuals = next;
        captureTick = tick;
    }

    public boolean visible(ServerPlayer observer, UUID entityId) {
        Entity entity = level.getEntity(entityId);
        return entity != null && eligible(entity) && (!(entity instanceof ServerPlayer player) || !player.isSpectator() || observer.isSpectator());
    }

    @Override
    public List<EntitySnapshot> getEntities(double centerX, double centerY, double centerZ, double range) { return visuals; }
    @Override
    public EntityProfile getProfile(UUID entityId) { Sample sample = samples.get(entityId); return sample == null ? null : sample.profile(); }
    @Override
    public List<SynchedEntityData.DataValue<?>> getMetadata(UUID entityId) { Sample sample = samples.get(entityId); return sample == null ? List.of() : sample.metadata(); }
    @Override
    public List<MinecraftPacketBlobs.Equipment> getEquipment(UUID entityId) { Sample sample = samples.get(entityId); return sample == null ? List.of() : sample.equipment(); }
    @Override
    public int getStateVersion(UUID entityId) { Sample sample = samples.get(entityId); return sample == null ? -1 : sample.version(); }

    private static boolean eligible(Entity entity) {
        return entity.isAlive() && !entity.isRemoved();
    }

    public record Options(double range, int candidateCacheTicks) {
    }

    private record Sample(EntitySnapshot visual, List<SynchedEntityData.DataValue<?>> metadata,
                          List<MinecraftPacketBlobs.Equipment> equipment, EntityProfile profile, int version) {
    }
}
