package art.arcane.optics.entity;

import art.arcane.optics.fidelity.FidelityOptions;
import java.util.Objects;
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
    private final EntityFeed<O, W, V, ?> feed;
    private final EntityOutput<O, R, T, V, ?> output;
    private final EntityProjection projection;
    private final Supplier<FidelityOptions> fidelity;

    public SnapshotProjector(SpoofRegistry<O, R> registry, EntityFeed<O, W, V, ?> feed, EntityOutput<O, R, T, V, ?> output,
                             Supplier<FidelityOptions> fidelity) {
        this.registry = registry;
        this.feed = feed;
        this.output = output;
        this.projection = new EntityProjection();
        this.fidelity = fidelity;
    }

    public boolean projectRemoteVisual(O observer, OpticTransform transform, ViewVolume frustum, V entityView, EntitySnapshot visual,
                                       boolean upsideDown) {
        return projectSnapshotVisual(observer, transform, frustum, entityView, visual, upsideDown, null);
    }

    public boolean projectSnapshotVisual(O observer, OpticTransform transform, ViewVolume frustum, V entityView, EntitySnapshot visual,
                                         boolean upsideDown, EntityPath<W, P> projectionPath) {
        T packetType = output.type(visual.typeKey());
        if (packetType == null) {
            return false;
        }

        boolean itemFrame = output.isItemFrame(packetType);
        boolean hanging = output.isHanging(packetType);
        boolean projected = projectionPath == null ? projection.project(visual, transform, frustum, itemFrame, hanging)
            : projection.project(visual, projectionPath, itemFrame, hanging);
        if (!projected) {
            return false;
        }
        R position = output.position(projection.x(), projection.y(), projection.z());
        R velocity = output.position(projection.velocityX(), projection.velocityY(), projection.velocityZ());
        float yaw = projection.yaw();
        float pitch = projection.pitch();
        int metadataTransform = projection.metadataTransform();

        SpoofedEntity state = registry.get(visual.id());
        if (state != null && (state.upsideDown() != upsideDown
            || visual.isPlayer() && !Objects.equals(state.playerProfile(), feed.profile(entityView, visual.id())))) {
            registry.destroySingle(observer, visual.id(), state);
            state = null;
        }
        if (state == null) {
            state = SpoofedEntity.create(output::allocateEntityId, visual.isPlayer(), upsideDown,
                visual.isPlayer() || output.isLiving(packetType));
            registry.track(visual.id(), state);
            if (visual.isPlayer()) {
                output.playerInfo(observer, state, feed.profile(entityView, visual.id()));
            }
            output.spawn(observer, state, new Spawn<>(packetType, position, velocity, yaw, pitch,
                ItemFrameTransform.spawnData(metadataTransform)));
            output.label(observer, state, new Label<>(position, visual.height(), null), true);
            state.updateRotation(yaw, pitch);
            state.updateMetadataTransform(metadataTransform);
            state.rememberPosition(output.x(position), output.y(position), output.z(position));
            registry.syncHeadLook(observer, state, yaw);
            state.setRemoteStateVersion(feed.stateVersion(entityView, visual.id()));
            output.entityState(observer, state, new State<>(entityView, visual, metadataTransform, true));
            state.resetMapCooldown();
            return true;
        }

        SpoofedEntity.Move move = state.updatePosition(output.x(position), output.y(position), output.z(position));
        boolean rotationChanged = state.updateRotation(yaw, pitch);
        boolean metadataTransformChanged = state.updateMetadataTransform(metadataTransform);
        registry.syncMotion(observer, state, move, rotationChanged, position, yaw, pitch, visual.onGround());
        output.label(observer, state, new Label<>(position, visual.height(), feed.profile(entityView, visual.id())), false);
        if (rotationChanged) {
            registry.syncHeadLook(observer, state, yaw);
        }
        if (state.updateVelocity(output.x(velocity), output.y(velocity), output.z(velocity),
            fidelity.get().entityVelocityEpsilon())) {
            output.velocity(observer, state.fakeId(), velocity);
        }
        int stateVersion = feed.stateVersion(entityView, visual.id());
        boolean mapRefreshDue = itemFrame && feed.hasMap(entityView, visual.id()) && state.shouldRefreshMap();
        if (stateVersion != state.remoteStateVersion() || metadataTransformChanged || mapRefreshDue) {
            state.setRemoteStateVersion(stateVersion);
            output.entityState(observer, state, new State<>(entityView, visual, metadataTransform, false));
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
        List<EntitySnapshot> visuals = feed.entities(pass.view(), new EntityRange(origin.getX(), origin.getY(), origin.getZ(), pass.range()));
        for (EntitySnapshot visual : visuals) {
            if (count >= pass.limit()) {
                break;
            }
            if (!feed.visible(observer, pass.view(), visual.id())) {
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

    public record Spawn<R, T>(T type, R position, R velocity, float yaw, float pitch, int data) {
    }

    public record Label<R>(R position, double height, EntityProfile profile) {
    }

    public record State<V>(V view, EntitySnapshot visual, int metadataTransform, boolean initial) {
    }
}
