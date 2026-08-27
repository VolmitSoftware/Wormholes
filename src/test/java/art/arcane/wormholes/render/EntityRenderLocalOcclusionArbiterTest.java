package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

public final class EntityRenderLocalOcclusionArbiterTest {
    @Test
    public void entityRemainsHiddenUntilItsLastPortalClaimIsReleased() {
        VisibilityRecorder visibility = new VisibilityRecorder();
        EntityRenderLocalOcclusionArbiter arbiter = new EntityRenderLocalOcclusionArbiter(visibility);
        Player observer = player(UUID.randomUUID());
        Entity entity = entity(UUID.randomUUID());
        UUID firstPortal = UUID.randomUUID();
        UUID secondPortal = UUID.randomUUID();

        arbiter.replace(observer, firstPortal, Map.of(entity.getUniqueId(), entity));
        arbiter.replace(observer, secondPortal, Map.of(entity.getUniqueId(), entity));

        assertEquals(1, visibility.hides.get());
        assertEquals(0, visibility.shows.get());
        assertTrue(arbiter.isClaimed(observer.getUniqueId(), entity.getUniqueId()));

        arbiter.release(observer, firstPortal);

        assertEquals(0, visibility.shows.get());
        assertTrue(arbiter.isClaimed(observer.getUniqueId(), entity.getUniqueId()));

        arbiter.release(observer, secondPortal);

        assertEquals(1, visibility.shows.get());
        assertFalse(arbiter.isClaimed(observer.getUniqueId(), entity.getUniqueId()));
    }

    @Test
    public void frameHandoffBetweenPortalsDoesNotFlickerVisibility() {
        VisibilityRecorder visibility = new VisibilityRecorder();
        EntityRenderLocalOcclusionArbiter arbiter = new EntityRenderLocalOcclusionArbiter(visibility);
        Player observer = player(UUID.randomUUID());
        Entity entity = entity(UUID.randomUUID());
        UUID firstPortal = UUID.randomUUID();
        UUID secondPortal = UUID.randomUUID();

        arbiter.replace(observer, firstPortal, Map.of(entity.getUniqueId(), entity));
        arbiter.beginFrame(observer);
        arbiter.release(observer, firstPortal);
        arbiter.replace(observer, secondPortal, Map.of(entity.getUniqueId(), entity));

        assertEquals(1, visibility.hides.get());
        assertEquals(0, visibility.shows.get());

        arbiter.flushFrame(observer);

        assertEquals(1, visibility.hides.get());
        assertEquals(0, visibility.shows.get());
        assertTrue(arbiter.isClaimed(observer.getUniqueId(), entity.getUniqueId()));
    }

    @Test
    public void untouchedPortalClaimsPersistAcrossObserverFrames() {
        VisibilityRecorder visibility = new VisibilityRecorder();
        EntityRenderLocalOcclusionArbiter arbiter = new EntityRenderLocalOcclusionArbiter(visibility);
        Player observer = player(UUID.randomUUID());
        Entity first = entity(UUID.randomUUID());
        Entity second = entity(UUID.randomUUID());
        UUID scheduledPortal = UUID.randomUUID();
        UUID deferredPortal = UUID.randomUUID();

        arbiter.replace(observer, scheduledPortal, Map.of(first.getUniqueId(), first));
        arbiter.replace(observer, deferredPortal, Map.of(second.getUniqueId(), second));
        arbiter.beginFrame(observer);
        arbiter.replace(observer, scheduledPortal, Map.of());
        arbiter.flushFrame(observer);

        assertEquals(2, visibility.hides.get());
        assertEquals(1, visibility.shows.get());
        assertFalse(arbiter.isClaimed(observer.getUniqueId(), first.getUniqueId()));
        assertTrue(arbiter.isClaimed(observer.getUniqueId(), second.getUniqueId()));
    }

    private static Player player(UUID id) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline" -> Boolean.TRUE;
            default -> defaultValue(proxy, method, args);
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class }, handler);
    }

    private static Entity entity(UUID id) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isValid" -> Boolean.TRUE;
            case "isDead" -> Boolean.FALSE;
            default -> defaultValue(proxy, method, args);
        };
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[] { Entity.class }, handler);
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> Boolean.valueOf(proxy == args[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "test";
            default -> primitiveDefault(method.getReturnType());
        };
    }

    private static Object primitiveDefault(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        if (returnType == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        return null;
    }

    private static final class VisibilityRecorder implements EntityRenderLocalOcclusionArbiter.VisibilityController {
        private final AtomicInteger hides = new AtomicInteger();
        private final AtomicInteger shows = new AtomicInteger();

        @Override
        public void hide(Player observer, Entity entity) {
            hides.incrementAndGet();
        }

        @Override
        public void show(Player observer, Entity entity) {
            shows.incrementAndGet();
        }
    }
}
