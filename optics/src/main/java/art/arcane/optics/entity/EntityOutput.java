package art.arcane.optics.entity;

import java.util.List;
import java.util.UUID;

public interface EntityOutput<O, R, T, V, E> {
    T type(String key);

    boolean isItemFrame(T type);

    boolean isHanging(T type);

    boolean isLiving(T type);

    R position(double x, double y, double z);

    double x(R position);

    double y(R position);

    double z(R position);

    void spawn(O observer, SpoofedEntity state, SnapshotProjector.Spawn<R, T> spawn);

    void entityState(O observer, SpoofedEntity state, SnapshotProjector.State<V> update);

    void motion(O observer, SpoofRegistry.Motion<R> motion);

    void headLook(O observer, int entityId, float yaw);

    void velocity(O observer, int entityId, R velocity);

    void passengers(O observer, int entityId, int[] passengers);

    void leash(O observer, int entityId, int holderId);

    void destroy(O observer, int[] entityIds);

    void playerInfo(O observer, SpoofedEntity state, EntityProfile profile);

    void removePlayerInfo(O observer, List<UUID> ids);

    void label(O observer, SpoofedEntity state, SnapshotProjector.Label<R> label, boolean initial);

    void releaseName(O observer, SpoofedEntity state);

    void team(O observer, TeamOp op, String team, String member);

    void map(O observer, MapSnapshot map, int virtualMapId);

    void hideLocal(O observer, E entity);

    void showLocal(O observer, E entity);

    boolean online(O observer);

    UUID id(O observer);

    boolean schedule(O observer, Runnable task);

    void warning(O observer, String context, RuntimeException error);

    enum TeamOp {
        CREATE, ADD, REMOVE, REMOVE_TEAM
    }
}
