package art.arcane.wormholes.render;

import art.arcane.wormholes.network.view.BukkitRemoteViewCodec;

import com.github.retrooper.packetevents.protocol.player.Equipment;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;

import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.RemoteWorldView;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectorResampleScheduleTest {
    @Test
    void unchangedRemoteRevisionDoesNotTriggerPeriodicResamples() {
        ILocalPortal portal = proxy(ILocalPortal.class);
        ProjectorResampleSchedule schedule = new ProjectorResampleSchedule(portal);
        RemoteViewCache<BlockData, EntityData<?>, Equipment> cache = new RemoteViewCache<>(BukkitRemoteViewCodec.INSTANCE, RemoteViewCache.Options.defaults());
        RemoteWorldView view = new RemoteWorldView(cache.getOrCreate("peer", UUID.randomUUID()), null);

        assertTrue(schedule.stableResample(false, view, null, 0.0D, 0.0D, new ProjectorRemoteFootprint()));
        schedule.noteSourceViewRevision(view.getRevision());

        for (int pass = 0; pass < 2_000; pass++) {
            schedule.beginBlockPass();
            assertFalse(schedule.stableResample(true, view, null, 0.0D, 0.0D, new ProjectorRemoteFootprint()));
        }
    }

    @Test
    void farDestinationChurnNeverForcesACadenceResampleAndNearChangesWaitForTheCadence() {
        ProjectionWorldChangeTracker previousTracker = Wormholes.projectionChangeTracker;
        int previousCadence = Settings.PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS;
        int previousRefresh = Settings.PROJECTION_REFRESH_INTERVAL_TICKS;
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        Wormholes.projectionChangeTracker = tracker;
        Settings.PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS = 4;
        Settings.PROJECTION_REFRESH_INTERVAL_TICKS = 1;
        try {
            UUID worldId = UUID.nameUUIDFromBytes("resample-churn".getBytes(StandardCharsets.UTF_8));
            World world = world(worldId);
            ProjectionWorldView view = localView();
            ProjectorRemoteFootprint footprint = new ProjectorRemoteFootprint();
            footprint.record(8, 64, 8);
            ProjectorResampleSchedule schedule = new ProjectorResampleSchedule(standardPortal());

            assertTrue(schedule.stableResample(false, view, world, 8.0D, 8.0D, footprint));
            assertTrue(schedule.consumeForcedResample(true));
            schedule.noteSourceViewRevision(view.getRevision());

            int forced = 0;
            for (int pass = 1; pass <= 120; pass++) {
                for (int change = 0; change < 5_000; change++) {
                    tracker.markChanged(worldId, 1_600 + (change & 63), 64, 1_600 + (change >> 6));
                }
                schedule.beginBlockPass();
                if (schedule.stableResample(true, view, world, 8.0D, 8.0D, footprint)) {
                    forced++;
                    schedule.consumeForcedResample(true);
                }
            }
            assertEquals(0, forced, "churn outside the footprint must not force cadence resamples");

            schedule.beginBlockPass();
            tracker.markChanged(worldId, 8, 64, 8);
            long passes = schedule.passCount();
            int passesUntilForced = 0;
            while (!schedule.stableResample(true, view, world, 8.0D, 8.0D, footprint)) {
                passesUntilForced++;
                assertTrue(passesUntilForced < 8, "a change inside the footprint must force the next cadence pass");
                schedule.beginBlockPass();
            }
            assertEquals(0L, (passes + passesUntilForced) % 4L, "the forced resample lands on a cadence pass");
            schedule.consumeForcedResample(true);
            for (int pass = 0; pass < 12; pass++) {
                schedule.beginBlockPass();
                assertFalse(schedule.stableResample(true, view, world, 8.0D, 8.0D, footprint));
            }
        } finally {
            Wormholes.projectionChangeTracker = previousTracker;
            Settings.PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS = previousCadence;
            Settings.PROJECTION_REFRESH_INTERVAL_TICKS = previousRefresh;
        }
    }

    private static ILocalPortal standardPortal() {
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(),
            new Class<?>[]{ILocalPortal.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getNetworkViewDepth" -> 64;
                case "getNetworkViewHeartbeatTicks" -> 60;
                case "getNetworkViewEntityIntervalTicks" -> 10;
                case "getNetworkViewUnsubscribeGraceSeconds" -> 30;
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "ILocalPortalProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static World world(UUID worldId) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getUID" -> worldId;
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "WorldProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static ProjectionWorldView localView() {
        return (ProjectionWorldView) Proxy.newProxyInstance(
            ProjectionWorldView.class.getClassLoader(),
            new Class<?>[]{ProjectionWorldView.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getRevision" -> 0L;
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "ProjectionWorldViewProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (instance, method, arguments) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> type.getSimpleName() + "Proxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
