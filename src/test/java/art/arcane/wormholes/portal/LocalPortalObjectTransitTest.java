package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** Projectiles and items keep their arc through a local tunnel and never pick up the reentry cooldown. */
public final class LocalPortalObjectTransitTest {
    private static final double EPSILON = 1e-9D;

    @Test
    void anArrowKeepsItsArcAndShooterAndIsNotCooledDown() {
        World world = LocalPortalTestSupport.world("object-arrow");
        LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        AtomicReference<Vector> velocity = new AtomicReference<Vector>(new Vector(0.0D, 0.3D, -2.0D));
        AtomicReference<ProjectileSource> shooter = new AtomicReference<ProjectileSource>();
        LocalPortalTestSupport.FakeEntity archer = LocalPortalTestSupport.FakeEntity.player("archer", new Location(world, 0.5D, 65.0D, 4.0D));
        shooter.set(archer.player());
        Entity arrow = projectile(velocity, shooter);
        Traversive crossing = new Traversive(arrow, source.getFrame().view(true), source.getOrigin(), new Vector(0.5D, 65.0D, 1.0D),
            new Vector(0.0D, 0.3D, -2.0D), new Vector(0.0D, 0.15D, -1.0D), true, source.getId());

        new LocalPortalTraversal(destination, inlineRuntime()).receive(crossing);

        Vector expected = crossing.getOutVelocity(destination.getFrame());
        assertEquals(expected.getX(), velocity.get().getX(), EPSILON);
        assertEquals(expected.getY(), velocity.get().getY(), EPSILON);
        assertEquals(expected.getZ(), velocity.get().getZ(), EPSILON);
        assertSame(archer.player(), shooter.get());
        assertFalse(LocalPortal.isTeleportCoolingDown(arrow.getUniqueId(), System.currentTimeMillis()), "objects take no reentry cooldown");
        assertTrue(LocalPortal.isReentryLatched(arrow.getUniqueId()), "the arrival latch still stops an immediate return trip");
        LocalPortal.clearReentryLatch(arrow.getUniqueId());
    }

    @Test
    void aDroppedItemKeepsItsVelocity() {
        World world = LocalPortalTestSupport.world("object-item");
        LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortal destination = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        AtomicReference<Vector> velocity = new AtomicReference<Vector>(new Vector(0.1D, -0.2D, -0.3D));
        Entity drop = item(velocity);
        Traversive crossing = new Traversive(drop, source.getFrame().view(true), source.getOrigin(), new Vector(0.5D, 65.0D, 1.0D),
            new Vector(0.1D, -0.2D, -0.3D), new Vector(0.0D, 0.0D, -1.0D), true, source.getId());

        new LocalPortalTraversal(destination, inlineRuntime()).receive(crossing);

        Vector expected = crossing.getOutVelocity(destination.getFrame());
        assertEquals(expected.getX(), velocity.get().getX(), EPSILON);
        assertEquals(expected.getY(), velocity.get().getY(), EPSILON);
        assertEquals(expected.getZ(), velocity.get().getZ(), EPSILON);
        assertFalse(LocalPortal.isTeleportCoolingDown(drop.getUniqueId(), System.currentTimeMillis()));
        LocalPortal.clearReentryLatch(drop.getUniqueId());
    }

    private static LocalPortalRuntime inlineRuntime() {
        return new LocalPortalRuntime() {
            @Override
            public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks) {
                task.run();
                return true;
            }

            @Override
            public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
                task.run();
                return true;
            }

            @Override
            public CompletionStage<Boolean> teleport(Entity entity, Location target) {
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
        };
    }

    private static Entity projectile(AtomicReference<Vector> velocity, AtomicReference<ProjectileSource> shooter) {
        UUID id = UUID.randomUUID();
        return (Projectile) Proxy.newProxyInstance(LocalPortalObjectTransitTest.class.getClassLoader(), new Class<?>[] {Projectile.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "arrow";
                case "getShooter" -> shooter.get();
                case "setShooter" -> {
                    shooter.set((ProjectileSource) arguments[0]);
                    yield null;
                }
                case "getVelocity" -> velocity.get().clone();
                case "setVelocity" -> {
                    velocity.set(((Vector) arguments[0]).clone());
                    yield null;
                }
                case "getLocation" -> new Location(null, 0.5D, 65.0D, 1.0D);
                case "isValid" -> Boolean.TRUE;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "projectile-" + id;
                default -> LocalPortalTestSupport.defaultValue(method.getReturnType());
            });
    }

    private static Entity item(AtomicReference<Vector> velocity) {
        UUID id = UUID.randomUUID();
        return (Item) Proxy.newProxyInstance(LocalPortalObjectTransitTest.class.getClassLoader(), new Class<?>[] {Item.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "item";
                case "getVelocity" -> velocity.get().clone();
                case "setVelocity" -> {
                    velocity.set(((Vector) arguments[0]).clone());
                    yield null;
                }
                case "getLocation" -> new Location(null, 0.5D, 65.0D, 1.0D);
                case "isValid" -> Boolean.TRUE;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "item-" + id;
                default -> LocalPortalTestSupport.defaultValue(method.getReturnType());
            });
    }
}
