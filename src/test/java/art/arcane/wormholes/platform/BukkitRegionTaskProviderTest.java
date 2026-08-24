package art.arcane.wormholes.platform;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitRegionTaskProviderTest {
    private static final long COMPLETION_TIMEOUT_MILLIS = 2_000L;

    @Test
    void shutdownWaitsForClaimedExecutionAndRejectsLaterWork() throws Exception {
        AtomicReference<Runnable> scheduled = new AtomicReference<Runnable>();
        BukkitRegionTaskProvider.Service service = service(scheduled);
        World world = world(UUID.randomUUID());
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        AtomicInteger retired = new AtomicInteger();
        assertTrue(service.run(
            world,
            0,
            0,
            () -> {
                taskStarted.countDown();
                await(releaseTask);
            },
            retired::incrementAndGet,
            0L
        ));

        CompletableFuture<Void> execution = CompletableFuture.runAsync(scheduled.get());
        assertTrue(taskStarted.await(COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() -> {
            shutdownStarted.countDown();
            service.shutdown();
        });
        assertTrue(shutdownStarted.await(COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        try {
            assertThrows(TimeoutException.class, () -> shutdown.get(100L, TimeUnit.MILLISECONDS));
        } finally {
            releaseTask.countDown();
        }

        execution.get(COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        shutdown.get(COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertEquals(0, retired.get());
        assertFalse(service.run(world, 0, 0, () -> {
        }, retired::incrementAndGet, 0L));
        assertEquals(1, retired.get());
    }

    @Test
    void retireWorldWaitsForClaimedExecutionAndBlocksUntilActivation() throws Exception {
        AtomicReference<Runnable> scheduled = new AtomicReference<Runnable>();
        BukkitRegionTaskProvider.Service service = service(scheduled);
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        assertTrue(service.run(
            world,
            0,
            0,
            () -> {
                taskStarted.countDown();
                await(releaseTask);
                completed.incrementAndGet();
            },
            retired::incrementAndGet,
            0L
        ));

        CompletableFuture<Void> execution = CompletableFuture.runAsync(scheduled.get());
        assertTrue(taskStarted.await(COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        CountDownLatch retirementStarted = new CountDownLatch(1);
        CompletableFuture<Void> retirement = CompletableFuture.runAsync(() -> {
            retirementStarted.countDown();
            service.retireWorld(worldId);
        });
        assertTrue(retirementStarted.await(COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        try {
            assertThrows(TimeoutException.class, () -> retirement.get(100L, TimeUnit.MILLISECONDS));
        } finally {
            releaseTask.countDown();
        }

        execution.get(COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        retirement.get(COMPLETION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertEquals(1, completed.get());
        assertEquals(0, retired.get());
        assertFalse(service.run(world, 0, 0, completed::incrementAndGet, retired::incrementAndGet, 0L));
        assertEquals(1, completed.get());
        assertEquals(1, retired.get());

        service.activateWorld(worldId);
        assertTrue(service.run(world, 0, 0, completed::incrementAndGet, retired::incrementAndGet, 0L));
        scheduled.get().run();
        assertEquals(2, completed.get());
        assertEquals(1, retired.get());
        service.shutdown();
    }

    @Test
    void schedulerExceptionRetiresExactlyOnceRemovesPendingAndLogsContext() throws Exception {
        UUID worldId = UUID.randomUUID();
        RuntimeException failure = new IllegalStateException("scheduler unavailable");
        AtomicInteger retired = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        AtomicReference<LogRecord> logged = new AtomicReference<LogRecord>();
        Logger logger = Logger.getLogger(getClass().getName() + "." + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = recordingHandler(logged);
        logger.addHandler(handler);
        BukkitRegionTaskProvider.Service service = new BukkitRegionTaskProvider.Service(
            plugin(logger),
            (plugin, world, chunkX, chunkZ, task, delayTicks) -> {
                throw failure;
            }
        );

        assertFalse(service.run(
            world(worldId),
            12,
            -7,
            executed::incrementAndGet,
            retired::incrementAndGet,
            9L
        ));

        assertEquals(0, executed.get());
        assertEquals(1, retired.get());
        assertEquals(0, pendingCount(service));
        LogRecord record = logged.get();
        assertNotNull(record);
        assertSame(failure, record.getThrown());
        assertTrue(record.getMessage().contains(worldId.toString()));
        assertTrue(record.getMessage().contains("12,-7"));
        assertTrue(record.getMessage().contains("9 ticks"));

        service.retireWorld(worldId);
        service.shutdown();
        assertEquals(1, retired.get());
        logger.removeHandler(handler);
    }

    private static BukkitRegionTaskProvider.Service service(AtomicReference<Runnable> scheduled) {
        return new BukkitRegionTaskProvider.Service(
            plugin(),
            (plugin, world, chunkX, chunkZ, task, delayTicks) -> {
                scheduled.set(task);
                return true;
            }
        );
    }

    private static Plugin plugin() {
        Logger logger = Logger.getLogger(BukkitRegionTaskProviderTest.class.getName());
        return plugin(logger);
    }

    private static Plugin plugin(Logger logger) {
        return (Plugin) Proxy.newProxyInstance(
            BukkitRegionTaskProviderTest.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getLogger" -> logger;
                case "getName" -> "BukkitRegionTaskProviderTest";
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "BukkitRegionTaskProviderTestPlugin";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Handler recordingHandler(AtomicReference<LogRecord> logged) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.set(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static int pendingCount(BukkitRegionTaskProvider.Service service) throws ReflectiveOperationException {
        Field pendingField = BukkitRegionTaskProvider.Service.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        ConcurrentHashMap<?, ?> pending = (ConcurrentHashMap<?, ?>) pendingField.get(service);
        return pending.size();
    }

    private static World world(UUID worldId) {
        return (World) Proxy.newProxyInstance(
            BukkitRegionTaskProviderTest.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> worldId;
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "toString" -> "BukkitRegionTaskProviderTestWorld[" + worldId + "]";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the provider lifecycle test", exception);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        return Double.valueOf(0.0D);
    }
}
