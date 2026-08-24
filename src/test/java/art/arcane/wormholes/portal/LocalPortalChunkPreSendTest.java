package art.arcane.wormholes.portal;

import art.arcane.wormholes.chunk.presend.RecordingBukkitChunkPreSend;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public final class LocalPortalChunkPreSendTest {
    @Test
    void successfulLocalMovementKeepsTheDestinationPreSend() {
        World world = LocalPortalTestSupport.world("chunk-presend-success");
        LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
            "presend-success",
            new Location(world, 0.5D, 65.0D, 1.0D)
        );
        Traversive traversive = LocalPortalTestSupport.traversive(
            portal,
            traveler.entity(),
            new Vector(0.5D, 65.0D, 1.0D)
        );
        AtomicReference<String> owner = new AtomicReference<String>("source");

        try (RecordingBukkitChunkPreSend recording = RecordingBukkitChunkPreSend.install(owner::get)) {
            new LocalPortalTraversal(portal, runtime(recording, true, owner)).receive(traversive);

            assertEquals(1, recording.announcements());
            assertEquals(1, recording.chunks());
            assertEquals(List.of("destination"), recording.announcementOwners());
            assertEquals(List.of("destination"), recording.chunkOwners());
        }
    }

    @Test
    void failedLocalMovementRollsBackTheDestinationPreSendOnce() {
        World world = LocalPortalTestSupport.world("chunk-presend-failure");
        LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
            "presend-failure",
            new Location(world, 0.5D, 65.0D, 1.0D)
        );
        Traversive traversive = LocalPortalTestSupport.traversive(
            portal,
            traveler.entity(),
            new Vector(0.5D, 65.0D, 1.0D)
        );
        AtomicReference<String> owner = new AtomicReference<String>("source");

        try (RecordingBukkitChunkPreSend recording = RecordingBukkitChunkPreSend.install(owner::get)) {
            new LocalPortalTraversal(portal, runtime(recording, false, owner)).receive(traversive);

            assertEquals(2, recording.announcements());
            assertEquals(1, recording.chunks());
            assertEquals(List.of("destination", "traveler"), recording.announcementOwners());
            assertEquals(List.of("destination"), recording.chunkOwners());
        }
    }

    @Test
    void retiredDestinationRegionRefundsAndReleasesTheTraveler() {
        World world = LocalPortalTestSupport.world("chunk-presend-retired");
        LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
            "presend-retired",
            new Location(world, 0.5D, 65.0D, 1.0D)
        );
        Traversive traversive = LocalPortalTestSupport.traversive(
            portal,
            traveler.entity(),
            new Vector(0.5D, 65.0D, 1.0D)
        );
        AtomicInteger refunds = new AtomicInteger();
        AtomicInteger teleports = new AtomicInteger();
        PortalTravelCost.Reservation reservation = new PortalTravelCost.Reservation() {
            @Override
            public void commit() {
            }

            @Override
            public void refund() {
                refunds.incrementAndGet();
            }
        };
        LocalPortalRuntime runtime = new LocalPortalRuntime() {
            @Override
            public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks) {
                retired.run();
                return true;
            }

            @Override
            public boolean dispatchRegion(World targetWorld, int chunkX, int chunkZ, Runnable task, long delayTicks) {
                return false;
            }

            @Override
            public boolean dispatchRegion(
                World targetWorld,
                int chunkX,
                int chunkZ,
                Runnable task,
                Runnable retired,
                long delayTicks
            ) {
                retired.run();
                return true;
            }

            @Override
            public CompletionStage<Boolean> teleport(Entity entity, Location target) {
                teleports.incrementAndGet();
                return CompletableFuture.completedFuture(Boolean.TRUE);
            }
        };

        try (RecordingBukkitChunkPreSend recording = RecordingBukkitChunkPreSend.install(() -> "source")) {
            new LocalPortalTraversal(portal, runtime).receive(traversive, reservation);

            assertEquals(0, teleports.get());
            assertEquals(0, recording.announcements());
            assertEquals(1, refunds.get());
            assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        }
    }

    @Test
    void retiredTravelerRecoveryRollsBackOnSourceWithoutMutatingTheTraveler() {
        World world = LocalPortalTestSupport.world("chunk-presend-source-fallback");
        LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player(
            "presend-source-fallback",
            new Location(world, 0.5D, 65.0D, 1.0D)
        );
        Traversive traversive = LocalPortalTestSupport.traversive(
            portal,
            traveler.entity(),
            new Vector(0.5D, 65.0D, 1.0D)
        );
        AtomicInteger refunds = new AtomicInteger();
        AtomicInteger regionDispatches = new AtomicInteger();
        AtomicInteger teleports = new AtomicInteger();
        AtomicReference<String> owner = new AtomicReference<String>("source");
        PortalTravelCost.Reservation reservation = new PortalTravelCost.Reservation() {
            @Override
            public void commit() {
            }

            @Override
            public void refund() {
                refunds.incrementAndGet();
            }
        };
        LocalPortalRuntime runtime = new LocalPortalRuntime() {
            @Override
            public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks) {
                retired.run();
                return true;
            }

            @Override
            public boolean dispatchRegion(World targetWorld, int chunkX, int chunkZ, Runnable task, long delayTicks) {
                return dispatchRegion(targetWorld, chunkX, chunkZ, task, () -> { }, delayTicks);
            }

            @Override
            public boolean dispatchRegion(
                World targetWorld,
                int chunkX,
                int chunkZ,
                Runnable task,
                Runnable retired,
                long delayTicks
            ) {
                String previous = owner.get();
                owner.set(regionDispatches.incrementAndGet() == 1 ? "destination" : "source");
                try {
                    task.run();
                } finally {
                    owner.set(previous);
                }
                return true;
            }

            @Override
            public CompletionStage<Boolean> teleport(Entity entity, Location target) {
                teleports.incrementAndGet();
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
        };

        try (RecordingBukkitChunkPreSend recording = RecordingBukkitChunkPreSend.install(owner::get)) {
            new LocalPortalTraversal(portal, runtime).receive(traversive, reservation);

            assertEquals(List.of("destination", "source"), recording.announcementOwners());
            assertEquals(0, teleports.get());
            assertEquals(1, refunds.get());
            assertEquals(new Vector(), traveler.velocity());
            assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        }
    }

    private static LocalPortalRuntime runtime(
        RecordingBukkitChunkPreSend recording,
        boolean succeeds,
        AtomicReference<String> owner
    ) {
        AtomicInteger regionDispatches = new AtomicInteger();
        return new LocalPortalRuntime() {
            @Override
            public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks) {
                String previous = owner.get();
                owner.set("traveler");
                try {
                    task.run();
                } finally {
                    owner.set(previous);
                }
                return true;
            }

            @Override
            public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
                String previous = owner.get();
                owner.set(regionDispatches.incrementAndGet() == 1 ? "destination" : "source");
                try {
                    task.run();
                } finally {
                    owner.set(previous);
                }
                return true;
            }

            @Override
            public CompletionStage<Boolean> teleport(Entity entity, Location target) {
                assertEquals("traveler", owner.get());
                assertEquals(1, recording.announcements());
                return CompletableFuture.completedFuture(Boolean.valueOf(succeeds));
            }
        };
    }
}
