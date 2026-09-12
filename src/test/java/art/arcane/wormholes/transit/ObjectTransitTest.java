package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.transit.TransitTestSupport.Rig;

final class ObjectTransitTest {
    @Test
    void projectilesAndItemsAreObjectsPlayersAndMobsAreNot() {
        assertTrue(ObjectTransit.isObject(projectile(new AtomicReference<Vector>(), new AtomicReference<ProjectileSource>())));
        assertTrue(ObjectTransit.isObject(item(new AtomicReference<Vector>())));
        assertFalse(ObjectTransit.isObject(Rig.player("steve", new Location(TransitTestSupport.world("objects"), 0.0D, 65.0D, 0.0D)).entity()));
        assertFalse(ObjectTransit.isObject(Rig.mob("cow", new Location(TransitTestSupport.world("objects"), 0.0D, 65.0D, 0.0D), 0.9D, 1.4D).entity()));
    }

    @Test
    void rearmRestoresTheShooterAndReappliesTheExitVelocityForProjectiles() {
        AtomicReference<Vector> velocity = new AtomicReference<Vector>();
        AtomicReference<ProjectileSource> shooter = new AtomicReference<ProjectileSource>();
        Player archer = (Player) Rig.player("archer", new Location(TransitTestSupport.world("rearm"), 0.0D, 65.0D, 0.0D)).entity();
        shooter.set(archer);
        Entity arrow = projectile(velocity, shooter);

        ObjectTransit.rearm(arrow, new Vector(0.0D, 0.4D, -2.5D));

        assertSame(archer, shooter.get());
        assertEquals(new Vector(0.0D, 0.4D, -2.5D), velocity.get());
    }

    @Test
    void rearmLeavesItemsWithTheirExitVelocityOnly() {
        AtomicReference<Vector> velocity = new AtomicReference<Vector>();
        Entity drop = item(velocity);

        ObjectTransit.rearm(drop, new Vector(0.1D, 0.2D, 0.3D));

        assertEquals(new Vector(0.1D, 0.2D, 0.3D), velocity.get());
    }

    static Entity projectile(AtomicReference<Vector> velocity, AtomicReference<ProjectileSource> shooter) {
        UUID id = UUID.randomUUID();
        return (Projectile) Proxy.newProxyInstance(ObjectTransitTest.class.getClassLoader(), new Class<?>[] {Projectile.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "arrow";
                case "getShooter" -> shooter.get();
                case "setShooter" -> {
                    shooter.set((ProjectileSource) arguments[0]);
                    yield null;
                }
                case "getVelocity" -> velocity.get() == null ? new Vector() : velocity.get().clone();
                case "setVelocity" -> {
                    velocity.set(((Vector) arguments[0]).clone());
                    yield null;
                }
                case "isValid" -> Boolean.TRUE;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "projectile-" + id;
                default -> TransitTestSupport.defaultValue(method.getReturnType());
            });
    }

    static Entity item(AtomicReference<Vector> velocity) {
        UUID id = UUID.randomUUID();
        return (Item) Proxy.newProxyInstance(ObjectTransitTest.class.getClassLoader(), new Class<?>[] {Item.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "item";
                case "getVelocity" -> velocity.get() == null ? new Vector() : velocity.get().clone();
                case "setVelocity" -> {
                    velocity.set(((Vector) arguments[0]).clone());
                    yield null;
                }
                case "isValid" -> Boolean.TRUE;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "item-" + id;
                default -> TransitTestSupport.defaultValue(method.getReturnType());
            });
    }
}
