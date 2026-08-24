package art.arcane.wormholes.portal;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPortalMenusRefreshTest {
    @Test
    void viewerAndWindowStateAreReadOnlyInsideTheViewerTask() {
        UUID viewerId = UUID.randomUUID();
        AtomicInteger onlineChecks = new AtomicInteger();
        Player viewer = player(viewerId, true, onlineChecks);
        TestWindow window = new TestWindow(viewer, true);
        Map<UUID, TestWindow> openMenus = new ConcurrentHashMap<UUID, TestWindow>();
        openMenus.put(viewerId, window);
        AtomicReference<Runnable> scheduled = new AtomicReference<Runnable>();
        AtomicInteger refreshes = new AtomicInteger();

        int rejected = LocalPortalMenus.dispatchMenuRefreshes(
            openMenus,
            TestWindow::viewer,
            (target, task, retired) -> {
                assertSame(viewer, target);
                scheduled.set(task);
                return true;
            },
            (target, player) -> player.isOnline() && target.visible(),
            (target, player) -> refreshes.incrementAndGet()
        );

        assertEquals(0, rejected);
        assertEquals(0, onlineChecks.get());
        assertEquals(0, refreshes.get());
        scheduled.get().run();
        assertEquals(1, onlineChecks.get());
        assertEquals(1, refreshes.get());
        assertTrue(openMenus.containsKey(viewerId));
    }

    @Test
    void rejectedViewerTasksRemainTrackedForTheNextRefreshAndAreCounted() {
        UUID viewerId = UUID.randomUUID();
        Player viewer = player(viewerId, true, new AtomicInteger());
        TestWindow window = new TestWindow(viewer, true);
        Map<UUID, TestWindow> openMenus = new ConcurrentHashMap<UUID, TestWindow>();
        openMenus.put(viewerId, window);

        int rejected = LocalPortalMenus.dispatchMenuRefreshes(
            openMenus,
            TestWindow::viewer,
            (target, task, retired) -> false,
            (target, player) -> player.isOnline() && target.visible(),
            (target, player) -> {
            }
        );

        assertEquals(1, rejected);
        assertSame(window, openMenus.get(viewerId));
    }

    @Test
    void retiredViewerTasksRemoveTheirWindowWithoutRefreshingIt() {
        UUID viewerId = UUID.randomUUID();
        Player viewer = player(viewerId, true, new AtomicInteger());
        TestWindow window = new TestWindow(viewer, true);
        Map<UUID, TestWindow> openMenus = new ConcurrentHashMap<UUID, TestWindow>();
        openMenus.put(viewerId, window);
        AtomicReference<Runnable> retired = new AtomicReference<Runnable>();
        AtomicInteger refreshes = new AtomicInteger();

        int rejected = LocalPortalMenus.dispatchMenuRefreshes(
            openMenus,
            TestWindow::viewer,
            (target, task, retiredTask) -> {
                retired.set(retiredTask);
                return true;
            },
            (target, player) -> player.isOnline() && target.visible(),
            (target, player) -> refreshes.incrementAndGet()
        );

        assertEquals(0, rejected);
        retired.get().run();
        assertTrue(openMenus.isEmpty());
        assertEquals(0, refreshes.get());
    }

    private static Player player(UUID viewerId, boolean online, AtomicInteger onlineChecks) {
        return (Player) Proxy.newProxyInstance(
            LocalPortalMenusRefreshTest.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> viewerId;
                case "isOnline" -> {
                    onlineChecks.incrementAndGet();
                    yield Boolean.valueOf(online);
                }
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == (arguments == null ? null : arguments[0]));
                case "toString" -> "viewer-" + viewerId;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }

    private record TestWindow(Player viewer, boolean visible) {
    }
}
