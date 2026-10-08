package art.arcane.wormholes.render;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.optics.entity.EntityFeed;
import art.arcane.optics.entity.EntityProfile;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.entity.SnapshotProjector;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.render.view.ProjectionEntityView;

public final class BukkitEntityVisualHost implements EntityFeed<Player, World, ProjectionEntityView, Entity> {
    public static final BukkitEntityVisualHost FEED = new BukkitEntityVisualHost();

    private BukkitEntityVisualHost() {
    }

    @Override
    public List<EntitySnapshot> entities(ProjectionEntityView view, SnapshotProjector.EntityRange range) {
        return view.getEntities(range.x(), range.y(), range.z(), range.range());
    }

    @Override
    public Collection<Entity> localEntities(World world, Vec3d center, int range) {
        return world.getNearbyEntities(new Location(world, center.x(), center.y(), center.z()), range, range, range);
    }

    @Override
    public boolean visible(Player observer, ProjectionEntityView view, UUID entityId) {
        return view.isVisibleTo(observer, entityId);
    }

    @Override
    public boolean isObserver(Player observer, UUID entityId) {
        return observer.getUniqueId().equals(entityId);
    }

    @Override
    public EntityProfile profile(ProjectionEntityView view, UUID entityId) {
        return view.getProfile(entityId);
    }

    @Override
    public int stateVersion(ProjectionEntityView view, UUID entityId) {
        return view.getStateVersion(entityId);
    }

    @Override
    public boolean hasMap(ProjectionEntityView view, UUID entityId) {
        return view.getMapView(entityId) != null;
    }

    @Override
    public boolean valid(Entity entity) {
        return entity.isValid() && !entity.isDead();
    }
}
