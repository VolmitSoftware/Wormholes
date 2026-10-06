package art.arcane.optics.entity;

import art.arcane.optics.fidelity.FidelityOptions;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.view.BlockView;
import art.arcane.optics.aperture.Endpoint;

import java.util.function.Supplier;
import art.arcane.optics.occlusion.ProjectedEntityOcclusion;
import art.arcane.optics.recursion.EntityPath;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.frame.OpticTransform;
public final class SnapshotProjector<O, W, P extends Endpoint, R, T, V> {
    private final SpoofRegistry<O, R> registry;
    private final Host<O, R, T, V> host;
    private final EntityProjection projection;
    private final Supplier<FidelityOptions> fidelity;

    public SnapshotProjector(SpoofRegistry<O, R> registry, Host<O, R, T, V> host, Supplier<FidelityOptions> fidelity) {
        this.registry = registry;
        this.host = host;
        this.projection = new EntityProjection();
        this.fidelity = fidelity;
    }

    public boolean projectRemoteVisual(O observer, OpticTransform transform, ViewVolume frustum, V entityView, EntitySnapshot visual,
                                       boolean upsideDown) {
        return projectSnapshotVisual(observer, transform, frustum, entityView, visual, upsideDown, null);
    }

    public boolean projectSnapshotVisual(O observer, OpticTransform transform, ViewVolume frustum, V entityView, EntitySnapshot visual,
                                         boolean upsideDown, EntityPath<W, P> projectionPath) {
        T packetType = host.packetType(visual.typeKey());
        if (packetType == null) {
            return false;
        }

        boolean itemFrame = host.isItemFrame(packetType);
        boolean hanging = host.isHanging(packetType);
        boolean projected = projectionPath == null ? projection.project(visual, transform, frustum, itemFrame, hanging)
            : projection.project(visual, projectionPath, itemFrame, hanging);
        if (!projected) {
            return false;
        }
        R position = host.position(projection.x(), projection.y(), projection.z());
        R velocity = host.position(projection.velocityX(), projection.velocityY(), projection.velocityZ());
        float yaw = projection.yaw();
        float pitch = projection.pitch();
        int metadataTransform = projection.metadataTransform();

        SpoofedEntity state = registry.get(visual.id());
        if (state != null && (state.upsideDown != upsideDown
            || visual.isPlayer() && !Objects.equals(state.playerProfile, host.profile(entityView, visual.id())))) {
            registry.destroySingle(observer, visual.id(), state);
            state = null;
        }
        if (state == null) {
            state = SpoofedEntity.create(visual.isPlayer(), upsideDown,
                visual.isPlayer() || host.isLiving(packetType));
            registry.track(visual.id(), state);
            if (visual.isPlayer()) {
                host.playerInfo(observer, state, host.profile(entityView, visual.id()));
            }
            host.spawn(observer, state, new Spawn<>(packetType, position, velocity, yaw, pitch,
                ItemFrameTransform.spawnData(metadataTransform)));
            host.spawnLabel(observer, state, new Label<>(position, visual.height(), null));
            state.updateRotation(yaw, pitch);
            state.updateMetadataTransform(metadataTransform);
            state.rememberPosition(host.x(position), host.y(position), host.z(position));
            registry.syncHeadLook(observer, state, yaw);
            state.remoteStateVersion = host.stateVersion(entityView, visual.id());
            host.entityState(observer, state, new State<>(entityView, visual, metadataTransform, true));
            state.resetMapCooldown();
            return true;
        }

        SpoofedEntity.Move move = state.updatePosition(host.x(position), host.y(position), host.z(position));
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        boolean metadataTransformChanged = state.updateMetadataTransform(metadataTransform);
        registry.syncMotion(observer, state, move, rotationChanged, position, yaw, pitch, visual.onGround());
        host.updateLabel(observer, state, new Label<>(position, visual.height(), host.profile(entityView, visual.id())));
        if (rotationChanged) {
            registry.syncHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(host.x(velocity), host.y(velocity), host.z(velocity),
            fidelity.get().entityVelocityEpsilon())) {
            host.velocity(observer, state.fakeId, velocity);
        }
        int stateVersion = host.stateVersion(entityView, visual.id());
        boolean mapRefreshDue = itemFrame && host.hasMap(entityView, visual.id()) && state.shouldRefreshMap();
        if (stateVersion != state.remoteStateVersion || metadataTransformChanged || mapRefreshDue) {
            state.remoteStateVersion = stateVersion;
            host.entityState(observer, state, new State<>(entityView, visual, metadataTransform, false));
            state.resetMapCooldown();
        }
        return true;
    }

    public <B, BV extends BlockView<B>> void apply(O observer, Pass<W, P, V, B, BV> pass) {
        Vec3d origin = pass.remote().origin();
        EntityPath<W, P> path = pass.path();
        boolean upsideDown = path == null ? pass.transform().flipsWorldUp() : path.transform().flipsWorldUp();
        registry.clearVisible();
        int count = 0;
        List<EntitySnapshot> visuals = host.entities(pass.view(), new EntityRange(origin.getX(), origin.getY(), origin.getZ(), pass.range()));
        for (EntitySnapshot visual : visuals) {
            if (count >= pass.limit()) {
                break;
            }
            if (!host.visible(observer, pass.view(), visual.id())) {
                continue;
            }
            if (fullyHidden(pass.occlusion(), visual, path)) {
                continue;
            }
            if (!projectSnapshotVisual(observer, pass.transform(), pass.frustum(), pass.view(), visual, upsideDown, path)) {
                continue;
            }
            registry.markVisible(visual.id());
            count++;
        }
        registry.destroyHidden(observer);
        registry.applyRelationships(observer, visuals);
    }

    public static <W, P extends Endpoint, B, BV extends BlockView<B>> boolean fullyHidden(
            ProjectedEntityOcclusion<B, BV> occlusion, EntitySnapshot visual, EntityPath<W, P> path) {
        if (path == null || !path.nested() || visual == null) {
            return occlusion.fullyHidden(visual);
        }
        return path.fullyHidden(occlusion, visual.x() - ProjectedEntityOcclusion.VISUAL_HALF_WIDTH, visual.y(),
            visual.z() - ProjectedEntityOcclusion.VISUAL_HALF_WIDTH, visual.x() + ProjectedEntityOcclusion.VISUAL_HALF_WIDTH,
            visual.y() + Math.max(ProjectedEntityOcclusion.MIN_VISUAL_HEIGHT, visual.height()) + ProjectedEntityOcclusion.LABEL_VERTICAL_MARGIN,
            visual.z() + ProjectedEntityOcclusion.VISUAL_HALF_WIDTH);
    }

    public record Pass<W, P extends Endpoint, V, B, BV extends BlockView<B>>(
        P local, Endpoint remote, V view, OpticTransform transform, ViewVolume frustum, EntityPath<W, P> path,
        ProjectedEntityOcclusion<B, BV> occlusion, double range, int limit) {
    }

    public record EntityRange(double x, double y, double z, double range) {
    }

    public interface Host<O, R, T, V> {
        T packetType(String key);
        List<EntitySnapshot> entities(V view, EntityRange range);
        boolean visible(O observer, V view, UUID entityId);
        boolean isItemFrame(T type);
        boolean isHanging(T type);
        boolean isLiving(T type);
        R position(double x, double y, double z);
        double x(R position);
        double y(R position);
        double z(R position);
        EntityProfile profile(V view, UUID entityId);
        int stateVersion(V view, UUID entityId);
        boolean hasMap(V view, UUID entityId);
        void playerInfo(O observer, SpoofedEntity state, EntityProfile profile);
        void spawn(O observer, SpoofedEntity state, Spawn<R, T> spawn);
        void spawnLabel(O observer, SpoofedEntity state, Label<R> label);
        void updateLabel(O observer, SpoofedEntity state, Label<R> label);
        void entityState(O observer, SpoofedEntity state, State<V> update);
        void velocity(O observer, int entityId, R velocity);
    }

    public record Spawn<R, T>(T type, R position, R velocity, float yaw, float pitch, int data) {
    }

    public record Label<R>(R position, double height, EntityProfile profile) {
    }

    public record State<V>(V view, EntitySnapshot visual, int metadataTransform, boolean initial) {
    }
}
