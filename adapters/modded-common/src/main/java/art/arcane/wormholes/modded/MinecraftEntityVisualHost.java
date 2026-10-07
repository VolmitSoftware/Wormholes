package art.arcane.wormholes.modded;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import art.arcane.optics.entity.EntityFeed;
import art.arcane.optics.entity.EntityProfile;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.entity.SnapshotProjector;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.render.view.ProjectionEntityData;

public final class MinecraftEntityVisualHost implements EntityFeed<ServerPlayer, ServerLevel,
    ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment>, Entity> {
    public static final MinecraftEntityVisualHost FEED = new MinecraftEntityVisualHost();

    private MinecraftEntityVisualHost() {
    }

    @Override
    public List<EntitySnapshot> entities(ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view,
                                         SnapshotProjector.EntityRange range) {
        return view.getEntities(range.x(), range.y(), range.z(), range.range());
    }

    @Override
    public Collection<Entity> localEntities(ServerLevel world, Vec3d center, int range) {
        return world.getEntities((Entity) null, new AABB(center.x() - range, center.y() - range, center.z() - range,
            center.x() + range, center.y() + range, center.z() + range));
    }

    @Override
    public boolean visible(ServerPlayer observer, ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view,
                           UUID entityId) {
        return !(view instanceof MinecraftLocalEntityView local) || local.visible(observer, entityId);
    }

    @Override
    public EntityProfile profile(ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view, UUID entityId) {
        return view.getProfile(entityId);
    }

    @Override
    public int stateVersion(ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view, UUID entityId) {
        return view.getStateVersion(entityId);
    }

    @Override
    public boolean hasMap(ProjectionEntityData<SynchedEntityData.DataValue<?>, MinecraftPacketBlobs.Equipment> view, UUID entityId) {
        return MinecraftEntityMetadata.FRAMES.mapId(view.getMetadata(entityId)) != null;
    }

    @Override
    public boolean valid(Entity entity) {
        return entity.isAlive() && !entity.isRemoved();
    }
}
