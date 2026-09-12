package art.arcane.wormholes.transit;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * Continuous transit for projectiles and dropped items: they keep their transformed velocity exactly
 * (momentum policies apply to travelers, not objects), take no reentry cooldown so a volley can chain
 * through several portals, and projectiles get their shooter and velocity re-applied after the move so
 * hit detection is armed again on the far side.
 */
public final class ObjectTransit {
    private ObjectTransit() {
    }

    public static boolean isObject(Entity entity) {
        return entity instanceof Projectile || entity instanceof Item;
    }

    /** Whether {@code entity} takes the continuous path under the current configuration. */
    public static boolean continuous(Entity entity) {
        return TransitSubsystem.config().objectTransitContinuous && isObject(entity);
    }

    public static void rearm(Entity entity, Vector outVelocity) {
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter != null) {
                projectile.setShooter(shooter);
            }
        }
        entity.setVelocity(outVelocity.clone());
    }
}
