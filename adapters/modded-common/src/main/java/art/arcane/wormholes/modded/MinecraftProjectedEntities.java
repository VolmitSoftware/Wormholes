package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.ProjectedEntityEvent;
import art.arcane.wormholes.render.EntityProjectionRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.EntityProjectionPath;
import art.arcane.wormholes.render.EntityRenderSpoofRegistry;
import art.arcane.wormholes.render.EntityRenderVisualProjector;
import art.arcane.wormholes.render.Frustum4D;
import art.arcane.wormholes.render.ProjectedEntityOcclusion;
import art.arcane.wormholes.render.ProjectorFrameTransform;
import art.arcane.wormholes.render.ProjectorLocalEntityEnvelope;
import art.arcane.wormholes.render.ProjectorRecursivePortals;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.render.view.ProjectionEntityData;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MinecraftProjectedEntities implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private final ServerPlayer observer;
    private final MinecraftPortal source;
    private final MinecraftEntityPackets packets = new MinecraftEntityPackets();
    private final EntityRenderSpoofRegistry<ServerPlayer, Vec3> registry = new EntityRenderSpoofRegistry<>(packets);
    private final EntityRenderVisualProjector<ServerPlayer, ServerLevel, MinecraftPortal, Vec3, EntityType<?>,
        ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment>> projector;
    private final ProjectorRecursivePortals<ServerLevel, MinecraftPortal> recursive;
    private final Map<UUID, MinecraftProjectedEntities> nested = new HashMap<>();
    private final EntityProjectionRecovery<ServerPlayer> recovery = new EntityProjectionRecovery<>(new RecoveryHost());
    private final UUID visibilityOwner = UUID.randomUUID();

    public MinecraftProjectedEntities(WormholesModRuntime runtime, Context context) {
        this.runtime = runtime;
        this.observer = context.observer();
        this.source = context.source();
        this.recursive = context.recursive();
        this.projector = new EntityRenderVisualProjector<>(registry, new MinecraftEntityVisualHost(observer, packets));
    }

    public void apply(View view) {
        runtime.requireServerThread();
        RenderConfig settings = runtime.configuration().settings().getRender();
        int entityLimit = MinecraftClientProfiles.profile(observer).entityLimit(settings.maxSpoofedEntities);
        if (!settings.entitySpoofing || entityLimit <= 0) {
            close();
            return;
        }
        if (recovery.pending()) {
            recovery.teardown(observer);
            if (recovery.pending()) {
                return;
            }
        }
        recursive.clear();
        EntityProjectionPath<ServerLevel, MinecraftPortal> path = view.destination() == null ? null
            : new EntityProjectionPath<>(new EntityProjectionPath.Root<>(source, view.destination(), view.localFrame(), view.remoteFrame(),
                view.mirror(), view.quarterTurns(), view.eye(), view.frustum(), view.world(),
                runtime.configuration().settings().getProjection().recursivePortalDepth), recursive);
        try {
            view.occlusion().startBatch();
            hideLocal(view);
            render(view, path, entityLimit);
            int[] budget = {Math.max(0, entityLimit - registry.size()), 256};
            renderRecursive(view, path, budget);
        } catch (RuntimeException error) {
            recovery.markPending(observer);
            throw error;
        }
    }

    public void event(ProjectedEntityEvent event) {
        if (observer.hasDisconnected()) {
            return;
        }
        for (MinecraftProjectedEntities child : nested.values()) {
            child.event(event);
        }
        int fakeId = registry.livingId(event.entityId());
        if (fakeId < 0) {
            return;
        }
        if (event.hurt()) {
            MinecraftEntityPackets.send(observer, new ClientboundHurtAnimationPacket(fakeId, event.yaw()));
        } else if (event.animation() >= 0 && event.animation() <= 5 && event.animation() != 1) {
            MinecraftEntityPackets.send(observer, MinecraftEntityPackets.animation(fakeId, event.animation()));
        }
    }

    @Override
    public void close() {
        for (MinecraftProjectedEntities child : nested.values()) {
            child.close();
        }
        nested.clear();
        recovery.teardown(observer);
    }

    private void render(View view, EntityProjectionPath<ServerLevel, MinecraftPortal> path, int limit) {
        double range = Math.min(runtime.configuration().settings().getRender().entitySpoofRange, view.depth());
        projector.apply(observer, new EntityRenderVisualProjector.Pass<>(source, view.anchor(), view.entities(),
            view.localFrame(), view.remoteFrame(), view.frustum(), view.mirror(), view.quarterTurns(), path, view.occlusion(), range, limit));
        registry.commitDestroyed();
    }

    private void renderRecursive(View view, EntityProjectionPath<ServerLevel, MinecraftPortal> path, int[] budget) {
        Set<UUID> visible = new HashSet<>();
        if (path != null) {
            for (ProjectorRecursivePortals<ServerLevel, MinecraftPortal>.Candidate candidate : path.index.paths()) {
                if (budget[0] <= 0 || budget[1] <= 0) {
                    break;
                }
                EntityProjectionPath<ServerLevel, MinecraftPortal> childPath = path.child(candidate, recursive);
                if (childPath == null) {
                    continue;
                }
                budget[1]--;
                MinecraftPortal destination = candidate.nestedDestination;
                double range = Math.min(runtime.configuration().settings().getRender().entitySpoofRange, view.depth());
                MinecraftLocalEntityView entities = runtime.projections().scene(candidate.nestedWorld, destination, range);
                MinecraftProjectedEntities child = nested.computeIfAbsent(candidate.portalId,
                    ignored -> new MinecraftProjectedEntities(runtime, new Context(observer, source, recursive)));
                visible.add(candidate.portalId);
                View childView = new View(destination, destination, candidate.nestedWorld, entities, view.localFrame(), destination.getFrame(),
                    view.frustum(), view.eye(), false, 0, view.occlusion(), view.depth());
                child.render(childView, childPath, budget[0]);
                budget[0] -= child.registry.size();
                child.renderRecursive(childView, childPath, budget);
            }
        }
        Iterator<Map.Entry<UUID, MinecraftProjectedEntities>> iterator = nested.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MinecraftProjectedEntities> entry = iterator.next();
            if (!visible.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    private void hideLocal(View view) {
        GeometryVector origin = source.getOrigin();
        PortalFrame frame = source.getFrame();
        double eyeDot = ProjectorLocalEntityEnvelope.dot(view.eye().x() - origin.x(), view.eye().y() - origin.y(), view.eye().z() - origin.z(), frame);
        double clearance = ProjectorFrameTransform.portalPlaneClearance(source.getGeometry().getArea(), frame);
        double maxDepth = view.depth() + clearance;
        Collection<Entity> candidates = runtime.projections().localEntities(observer.level(), source, maxDepth);
        Map<UUID, Entity> desired = new HashMap<>(Math.max(4, candidates.size()));
        for (Entity entity : candidates) {
            if (entity == observer || !entity.isAlive() || entity.isRemoved() || entity.level() != observer.level()) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            if (ProjectorLocalEntityEnvelope.envelopeFullyProjected(box.minX - 0.5D, box.minY, box.minZ - 0.5D,
                box.maxX + 0.5D, box.maxY + 0.75D, box.maxZ + 0.5D, origin, frame, view.frustum(), eyeDot >= 0.0D, clearance, maxDepth)) {
                desired.put(entity.getUUID(), entity);
            }
        }
        runtime.projections().entityVisibility().replace(observer, visibilityOwner, desired);
    }

    private final class RecoveryHost implements EntityProjectionRecovery.Host<ServerPlayer> {
        public boolean online(ServerPlayer player) { return !player.hasDisconnected(); }
        public boolean hasState() { return registry.size() != 0 || packets.hasNameTeam(); }
        public boolean schedule(ServerPlayer player, Runnable task) { return runtime.schedule(task, 1L); }

        public void send(ServerPlayer player) {
            registry.destroyAll(player);
            packets.close(player);
            registry.commitDestroyed();
        }

        public void drop(ServerPlayer player) {
            registry.clear();
            packets.discard();
        }

        public void release(ServerPlayer player) {
            if (player.hasDisconnected()) {
                runtime.projections().entityVisibility().discardObserver(player.getUUID());
            } else {
                runtime.projections().entityVisibility().release(player, visibilityOwner);
            }
        }

        public void warning(ServerPlayer player, RuntimeException error) {
            LOGGER.error("Wormholes failed to send projected entity teardown to {}", player.getUUID(), error);
        }
    }

    public record Context(ServerPlayer observer, MinecraftPortal source, ProjectorRecursivePortals<ServerLevel, MinecraftPortal> recursive) {
    }

    public record View(MinecraftPortal destination, IPortal anchor, ServerLevel world,
                       ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> entities,
                       PortalFrame localFrame, PortalFrame remoteFrame, Frustum4D frustum, GeometryVector eye,
                       boolean mirror, int quarterTurns, ProjectedEntityOcclusion<BlockState, ProjectionContentView<BlockState, BlockState>> occlusion,
                       double depth) {
    }
}
