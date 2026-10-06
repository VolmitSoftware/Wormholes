package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.entity.ProjectedEntityEvent;
import art.arcane.optics.entity.ProjectionRecovery;

import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.recursion.EntityPath;
import art.arcane.optics.entity.SpoofRegistry;
import art.arcane.optics.entity.SnapshotProjector;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.occlusion.ProjectedEntityOcclusion;
import art.arcane.optics.volume.LocalEntityEnvelope;
import art.arcane.optics.volume.ProjectionVolume;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.view.EntityData;
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
    private final WormholesModRuntime runtime;
    private final ServerPlayer observer;
    private final MinecraftPortal source;
    private final MinecraftEntityPackets packets;
    private final SpoofRegistry<ServerPlayer, Vec3> registry;
    private final SnapshotProjector<ServerPlayer, ServerLevel, MinecraftPortal, Vec3, EntityType<?>,
        EntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment>> projector;
    private final RecursiveEndpoints<ServerLevel, MinecraftPortal> recursive;
    private final Map<UUID, MinecraftProjectedEntities> nested = new HashMap<>();
    private final ProjectionRecovery<ServerPlayer> recovery;
    private final UUID visibilityOwner = UUID.randomUUID();

    public MinecraftProjectedEntities(WormholesModRuntime runtime, Context context) {
        this.runtime = runtime;
        this.observer = context.observer();
        this.source = context.source();
        this.recursive = context.recursive();
        this.packets = new MinecraftEntityPackets(runtime);
        this.registry = new SpoofRegistry<>(packets);
        this.projector = new SnapshotProjector<>(registry, MinecraftEntityVisualHost.FEED, packets, FidelitySettings::snapshot);
        this.recovery = new ProjectionRecovery<>(packets, runtime.projections().scheduler(),
            new ProjectionRecovery.Teardown<>(this::hasState, this::sendTeardown, this::dropState, this::releaseVisibility));
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
        recursive.revalidate();
        EntityPath<ServerLevel, MinecraftPortal> path = view.destination() == null ? null
            : new EntityPath<>(new EntityPath.Root<>(source, view.destination(), view.transform(), view.eye(), view.frustum(), view.world(),
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
        } else if (MinecraftEntityPackets.projectsAnimation(event.animation())) {
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

    private void render(View view, EntityPath<ServerLevel, MinecraftPortal> path, int limit) {
        double range = Math.min(runtime.configuration().settings().getRender().entitySpoofRange, view.depth());
        projector.apply(observer, new SnapshotProjector.Pass<>(source, view.anchor(), view.entities(), view.transform(), view.frustum(), path,
            view.occlusion(), range, limit));
        registry.commitDestroyed();
    }

    private void renderRecursive(View view, EntityPath<ServerLevel, MinecraftPortal> path, int[] budget) {
        Set<UUID> visible = new HashSet<>();
        if (path != null) {
            for (RecursiveEndpoints<ServerLevel, MinecraftPortal>.Candidate candidate : path.index.paths()) {
                if (budget[0] <= 0 || budget[1] <= 0) {
                    break;
                }
                EntityPath<ServerLevel, MinecraftPortal> childPath = path.child(candidate, recursive);
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
                View childView = new View(destination, destination, candidate.nestedWorld, entities, childPath.transform(), view.frustum(),
                    view.eye(), view.occlusion(), view.depth());
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
        Vec3d origin = source.getOrigin();
        Frame frame = source.getFrame();
        double eyeDot = LocalEntityEnvelope.dot(view.eye().x() - origin.x(), view.eye().y() - origin.y(), view.eye().z() - origin.z(), frame);
        double clearance = ProjectionVolume.portalPlaneClearance(source.getGeometry().getArea(), frame);
        double maxDepth = view.depth() + clearance;
        Collection<Entity> candidates = runtime.projections().localEntities(observer.level(), source, maxDepth);
        Map<UUID, Entity> desired = new HashMap<>(Math.max(4, candidates.size()));
        for (Entity entity : candidates) {
            if (entity == observer || !entity.isAlive() || entity.isRemoved() || entity.level() != observer.level()) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            if (LocalEntityEnvelope.envelopeFullyProjected(box.minX - 0.5D, box.minY, box.minZ - 0.5D,
                box.maxX + 0.5D, box.maxY + 0.75D, box.maxZ + 0.5D, origin, frame, view.frustum(), eyeDot >= 0.0D, clearance, maxDepth)) {
                desired.put(entity.getUUID(), entity);
            }
        }
        runtime.projections().entityVisibility().replace(observer, visibilityOwner, desired);
    }

    private boolean hasState() {
        return registry.size() != 0 || packets.hasNameTeam();
    }

    private void sendTeardown(ServerPlayer player) {
        registry.destroyAll(player);
        packets.close(player);
        registry.commitDestroyed();
    }

    private void dropState(ServerPlayer player) {
        registry.clear();
        packets.discard();
    }

    private void releaseVisibility(ServerPlayer player) {
        if (player.hasDisconnected()) {
            runtime.projections().entityVisibility().discardObserver(player.getUUID());
        } else {
            runtime.projections().entityVisibility().release(player, visibilityOwner);
        }
    }

    public record Context(ServerPlayer observer, MinecraftPortal source, RecursiveEndpoints<ServerLevel, MinecraftPortal> recursive) {
    }

    public record View(MinecraftPortal destination, IPortal anchor, ServerLevel world,
                       EntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> entities,
                       OpticTransform transform, ViewVolume frustum, Vec3d eye,
                       ProjectedEntityOcclusion<BlockState, ContentView<BlockState, BlockState>> occlusion, double depth) {
    }
}
