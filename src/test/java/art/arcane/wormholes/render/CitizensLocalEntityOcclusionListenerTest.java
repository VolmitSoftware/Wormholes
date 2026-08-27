package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.npc.NPC;

public final class CitizensLocalEntityOcclusionListenerTest {
    @Test
    public void cancelsOnlyCitizensEntitiesClaimedForThatObserver() {
        UUID observerId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        CitizensLocalEntityOcclusionListener listener = new CitizensLocalEntityOcclusionListener(
            (claimedObserver, claimedEntity) -> observerId.equals(claimedObserver) && entityId.equals(claimedEntity));

        assertTrue(listener.shouldCancel(player(observerId), npc(entity(entityId))));
        assertFalse(listener.shouldCancel(player(UUID.randomUUID()), npc(entity(entityId))));
        assertFalse(listener.shouldCancel(player(observerId), npc(entity(UUID.randomUUID()))));
        assertFalse(listener.shouldCancel(player(observerId), npc(null)));
    }

    private static Player player(UUID id) {
        return proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            default -> defaultValue(proxy, method, args);
        });
    }

    private static Entity entity(UUID id) {
        return proxy(Entity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            default -> defaultValue(proxy, method, args);
        });
    }

    private static NPC npc(Entity entity) {
        return proxy(NPC.class, (proxy, method, args) -> switch (method.getName()) {
            case "getEntity" -> entity;
            default -> defaultValue(proxy, method, args);
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
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
        return null;
    }
}
