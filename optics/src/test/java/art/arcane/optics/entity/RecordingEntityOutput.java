package art.arcane.optics.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import art.arcane.optics.math.Vec3d;

final class RecordingEntityOutput implements EntityOutput<Object, Vec3d, String, Object, Object> {
    final List<int[]> passengers = new ArrayList<>();
    final List<int[]> leashes = new ArrayList<>();
    final List<int[]> destroyed = new ArrayList<>();
    final List<UUID> removedPlayers = new ArrayList<>();
    final List<SpoofRegistry.MotionKind> motion = new ArrayList<>();
    final List<String> teams = new ArrayList<>();
    final List<Integer> maps = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<Object> hidden = new ArrayList<>();
    final List<Object> shown = new ArrayList<>();
    final UUID observerId = UUID.randomUUID();
    boolean online = true;
    int hideFailures;
    private int nextEntityId = 1;

    @Override
    public int allocateEntityId() { return nextEntityId++; }
    @Override
    public String type(String key) { return key; }
    @Override
    public boolean isItemFrame(String type) { return "item_frame".equals(type); }
    @Override
    public boolean isHanging(String type) { return isItemFrame(type) || "painting".equals(type); }
    @Override
    public boolean isLiving(String type) { return !isHanging(type); }
    @Override
    public Vec3d position(double x, double y, double z) { return new Vec3d(x, y, z); }
    @Override
    public double x(Vec3d position) { return position.x(); }
    @Override
    public double y(Vec3d position) { return position.y(); }
    @Override
    public double z(Vec3d position) { return position.z(); }
    @Override
    public void spawn(Object observer, SpoofedEntity state, SnapshotProjector.Spawn<Vec3d, String> spawn) { }
    @Override
    public void entityState(Object observer, SpoofedEntity state, SnapshotProjector.State<Object> update) { }
    @Override
    public void motion(Object observer, SpoofRegistry.Motion<Vec3d> value) { motion.add(value.kind()); }
    @Override
    public void headLook(Object observer, int entityId, float yaw) { }
    @Override
    public void velocity(Object observer, int entityId, Vec3d velocity) { }
    @Override
    public void passengers(Object observer, int entityId, int[] ids) { passengers.add(ids); }
    @Override
    public void leash(Object observer, int entityId, int holderId) { leashes.add(new int[] {entityId, holderId}); }
    @Override
    public void destroy(Object observer, int[] ids) { destroyed.add(ids); }
    @Override
    public void playerInfo(Object observer, SpoofedEntity state, EntityProfile profile) { }
    @Override
    public void removePlayerInfo(Object observer, List<UUID> ids) { removedPlayers.addAll(ids); }
    @Override
    public void label(Object observer, SpoofedEntity state, SnapshotProjector.Label<Vec3d> label, boolean initial) { }
    @Override
    public void releaseName(Object observer, SpoofedEntity state) { }
    @Override
    public void team(Object observer, TeamOp op, String team, String member) { teams.add(op + " " + member); }
    @Override
    public void map(Object observer, MapSnapshot map, int virtualMapId) { maps.add(virtualMapId); }
    @Override
    public void hideLocal(Object observer, Object entity) {
        if (hideFailures > 0) {
            hideFailures--;
            throw new IllegalStateException("entity is owned by another region");
        }
        hidden.add(entity);
    }
    @Override
    public void showLocal(Object observer, Object entity) { shown.add(entity); }
    @Override
    public boolean online(Object observer) { return online; }
    @Override
    public UUID id(Object observer) { return observerId; }
    @Override
    public void warning(Object observer, String context, RuntimeException error) { warnings.add(context); }
}
