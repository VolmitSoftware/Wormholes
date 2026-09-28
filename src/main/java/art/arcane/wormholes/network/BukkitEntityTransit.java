package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.BukkitGeometry;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

final class BukkitEntityTransit implements TraversalEntityTransit.Host<Entity, Traversive> {
    static final NamespacedKey TRANSIT_STAMP_KEY = new NamespacedKey("wormholes", "entity_transit_state");
    private final TraversalEntityScheduler scheduler;

    BukkitEntityTransit(TraversalEntityScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public UUID id(Entity entity) { return entity.getUniqueId(); }
    public boolean valid(Entity entity) { return entity.isValid(); }
    public boolean player(Entity entity) { return entity instanceof Player; }
    public void remove(Entity entity) { entity.remove(); }
    public void clearInFlight(UUID entityId) { LocalPortal.clearTeleportInFlight(entityId); }

    public TraversalEntityTransit.TransitState capture(Entity entity) {
        return new TraversalEntityTransit.TransitState(entity.isInvulnerable(), entity.isSilent(), entity.hasGravity(), BukkitGeometry.vector(entity.getVelocity()));
    }

    public void freeze(Entity entity, byte stamp) {
        entity.getPersistentDataContainer().set(TRANSIT_STAMP_KEY, PersistentDataType.BYTE, Byte.valueOf(stamp));
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.setGravity(false);
        entity.setVelocity(entity.getVelocity().zero());
    }

    public void restore(Entity entity, TraversalEntityTransit.TransitState state) {
        entity.setInvulnerable(state.invulnerable());
        entity.setSilent(state.silent());
        entity.setGravity(state.gravity());
        entity.setVelocity(BukkitGeometry.bukkit(state.velocity()));
        entity.getPersistentDataContainer().remove(TRANSIT_STAMP_KEY);
    }

    public Byte stamp(Entity entity) {
        return entity.getPersistentDataContainer().get(TRANSIT_STAMP_KEY, PersistentDataType.BYTE);
    }

    public void restoreStamp(Entity entity, byte stamp) {
        entity.getPersistentDataContainer().remove(TRANSIT_STAMP_KEY);
        entity.setInvulnerable(TraversalEntityTransit.stampInvulnerable(stamp));
        entity.setSilent(TraversalEntityTransit.stampSilent(stamp));
        entity.setGravity(TraversalEntityTransit.stampGravity(stamp));
    }

    public void rejectDeparture(Entity entity, TraversalEntityTransit.Rejection<Traversive> rejection) {
        ILocalPortal source = Wormholes.portalManager == null || rejection.sourcePortalId() == null
            ? null : Wormholes.portalManager.getLocalPortal(rejection.sourcePortalId());
        if (source != null) {
            source.rejectDeparture(entity, rejection.traversive());
        }
    }

    public boolean schedule(Entity entity, TraversalEntityTransit.Task task) {
        return scheduler.schedule(entity, task.run(), task.retired(), task.delayTicks());
    }
}
