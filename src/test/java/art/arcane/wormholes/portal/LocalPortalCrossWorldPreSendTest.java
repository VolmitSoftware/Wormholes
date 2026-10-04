package art.arcane.wormholes.portal;

import art.arcane.wormholes.chunk.presend.RecordingBukkitChunkPreSend;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class LocalPortalCrossWorldPreSendTest {
    @Test
    void crossWorldTravelSkipsRawPreSendAndContinuesOnTheTravelerOwner() {
        World sourceWorld = LocalPortalTestSupport.world("presend-source");
        World destinationWorld = LocalPortalTestSupport.world("presend-destination");
        LocalPortal source = LocalPortalTestSupport.portal(sourceWorld, PortalType.PORTAL);
        LocalPortal destination = LocalPortalTestSupport.portal(destinationWorld, PortalType.PORTAL);
        LocalPortalTestSupport.FakeEntity traveler = LocalPortalTestSupport.FakeEntity.player("cross-world-presend",
            new Location(sourceWorld, 0.5D, 65.0D, 1.0D));
        Traversive crossing = LocalPortalTestSupport.traversive(source, traveler.entity(), new Vector(0.5D, 65.0D, 1.0D));
        AtomicReference<String> owner = new AtomicReference<>("source");
        AtomicInteger moves = new AtomicInteger();
        try (RecordingBukkitChunkPreSend recording = RecordingBukkitChunkPreSend.install(owner::get)) {
            LocalPortalRuntime runtime = new LocalPortalRuntime() {
                @Override
                public boolean dispatch(Entity entity, Runnable task, Runnable retired, long delayTicks) {
                    String previous = owner.getAndSet("traveler");
                    try {
                        task.run();
                    } finally {
                        owner.set(previous);
                    }
                    return true;
                }

                @Override
                public boolean dispatchRegion(World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
                    assertSame(destinationWorld, world);
                    String previous = owner.getAndSet("destination");
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
                    assertSame(destinationWorld, target.getWorld());
                    assertEquals(0, recording.announcements());
                    assertEquals(0, recording.chunks());
                    moves.incrementAndGet();
                    return CompletableFuture.completedFuture(false);
                }
            };
            new LocalPortalTraversal(destination, runtime).receive(crossing);
            assertEquals(1, moves.get());
            assertEquals(0, recording.announcements());
            assertEquals(0, recording.chunks());
        }
    }
}
