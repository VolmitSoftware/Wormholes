package art.arcane.wormholes.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.plate.PlateWorkers;

final class BukkitOpticsSchedulerTest {
    private final PlateWorkers workers = new PlateWorkers("Optics-Scheduler-Test-", 1);

    @AfterEach
    void shutdownWorkers() {
        workers.shutdown();
    }

    @Test
    void delayedTasksRoundMillisecondsUpToTicksAndKeepRejections() {
        ManualOperations operations = new ManualOperations();
        operations.asyncAccepted = false;
        BukkitOpticsScheduler scheduler = new BukkitOpticsScheduler(plugin(Logger.getLogger("BukkitOpticsSchedulerReject")), workers, operations);
        List<String> ran = new ArrayList<String>();

        assertFalse(scheduler.schedule(() -> ran.add("rejected"), 51L));
        assertEquals(2L, operations.lastDelayTicks);
        operations.asyncAccepted = true;
        assertTrue(scheduler.schedule(() -> ran.add("exact"), 100L));
        assertEquals(2L, operations.lastDelayTicks);
        assertTrue(scheduler.schedule(() -> ran.add("now"), -5L));
        assertEquals(0L, operations.lastDelayTicks);
        assertEquals(List.of("exact", "now"), ran);
    }

    @Test
    void aSchedulerFailureIsLoggedWithItsStacktraceAndReportedAsRejection() {
        Logger logger = Logger.getLogger("BukkitOpticsSchedulerFailure");
        RecordingHandler handler = new RecordingHandler();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        ManualOperations operations = new ManualOperations();
        IllegalStateException failure = new IllegalStateException("scheduler unavailable");
        operations.asyncFailure = failure;
        BukkitOpticsScheduler scheduler = new BukkitOpticsScheduler(plugin(logger), workers, operations);
        try {
            assertFalse(scheduler.schedule(() -> { }, 1L));
            assertSame(failure, handler.lastThrown);
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void observerAndRegionTasksGoToTheirOwningSchedulers() {
        ManualOperations operations = new ManualOperations();
        BukkitOpticsScheduler scheduler = new BukkitOpticsScheduler(plugin(Logger.getLogger("BukkitOpticsSchedulerOwners")), workers, operations);
        Player observer = mock(Player.class);
        World world = mock(World.class);
        List<String> ran = new ArrayList<String>();

        assertTrue(scheduler.runForObserver(observer, () -> ran.add("observer")));
        assertTrue(scheduler.runForRegion(world, 4, -7, () -> ran.add("region")));

        assertSame(observer, operations.lastEntity);
        assertSame(world, operations.lastWorld);
        assertEquals(4, operations.lastChunkX);
        assertEquals(-7, operations.lastChunkZ);
        assertEquals(List.of("observer", "region"), ran);
    }

    @Test
    void computeIsThePlateWorkerPool() {
        BukkitOpticsScheduler scheduler = new BukkitOpticsScheduler(plugin(Logger.getLogger("BukkitOpticsSchedulerCompute")), workers,
            new ManualOperations());
        assertSame(workers, scheduler.compute());
    }

    @Test
    void theTickCountsProjectionTicksAndIgnoresWallTime() throws InterruptedException {
        BukkitOpticsScheduler scheduler = new BukkitOpticsScheduler(plugin(Logger.getLogger("BukkitOpticsSchedulerTick")), workers,
            new ManualOperations());
        assertEquals(0L, scheduler.tick());
        Thread.sleep(60L);
        assertEquals(0L, scheduler.tick());
        scheduler.advanceTick();
        scheduler.advanceTick();
        assertEquals(2L, scheduler.tick());
    }

    @Test
    void theInstalledSchedulerIsSharedUntilShutdown() {
        Plugin plugin = plugin(Logger.getLogger("BukkitOpticsSchedulerInstall"));
        BukkitOpticsScheduler installed = BukkitOpticsScheduler.install(plugin);
        try {
            assertSame(installed, BukkitOpticsScheduler.active());
            assertThrows(IllegalStateException.class, () -> BukkitOpticsScheduler.install(plugin));
            assertSame(installed, BukkitOpticsScheduler.active());
        } finally {
            BukkitOpticsScheduler.shutdown();
        }
        assertNull(BukkitOpticsScheduler.active());
        assertEquals(0, installed.compute().threads(), "shutdown stops the plate worker pool");
    }

    private static Plugin plugin(Logger logger) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(logger);
        return plugin;
    }

    private static final class RecordingHandler extends Handler {
        private Throwable lastThrown;

        @Override
        public void publish(LogRecord record) {
            lastThrown = record.getThrown();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static final class ManualOperations implements BukkitOpticsScheduler.Operations {
        private boolean asyncAccepted = true;
        private RuntimeException asyncFailure;
        private long lastDelayTicks = -1L;
        private Entity lastEntity;
        private World lastWorld;
        private int lastChunkX;
        private int lastChunkZ;

        @Override
        public boolean runEntity(Plugin plugin, Entity entity, Runnable task) {
            lastEntity = entity;
            task.run();
            return true;
        }

        @Override
        public boolean runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
            lastWorld = world;
            lastChunkX = chunkX;
            lastChunkZ = chunkZ;
            task.run();
            return true;
        }

        @Override
        public boolean runAsync(Plugin plugin, Runnable task, long delayTicks) {
            lastDelayTicks = delayTicks;
            if (asyncFailure != null) {
                throw asyncFailure;
            }
            if (asyncAccepted) {
                task.run();
            }
            return asyncAccepted;
        }
    }
}
