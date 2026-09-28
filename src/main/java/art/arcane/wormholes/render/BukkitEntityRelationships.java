package art.arcane.wormholes.render;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import art.arcane.wormholes.platform.WormholesPlatform;

final class BukkitEntityRelationships {
    private BukkitEntityRelationships() {
    }

    static EntityRelationship of(Entity entity) {
        Entity vehicle = entity.getVehicle();
        List<Entity> riders = entity.getPassengers();
        List<UUID> passengers = List.of();
        if (riders != null && !riders.isEmpty()) {
            List<UUID> collected = new ArrayList<UUID>(riders.size());
            for (Entity passenger : riders) {
                collected.add(passenger.getUniqueId());
            }
            passengers = List.copyOf(collected);
        }
        UUID leashHolder = null;
        if (entity instanceof LivingEntity living && WormholesPlatform.isLeashed(living)) {
            Entity holder = living.getLeashHolder();
            leashHolder = holder == null ? null : holder.getUniqueId();
        }
        return new EntityRelationship(entity.getUniqueId(), vehicle == null ? null : vehicle.getUniqueId(),
            passengers, leashHolder);
    }
}
