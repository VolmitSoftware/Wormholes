package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.math.CellKeys;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

final class ProjectionPacketFailureAuditTest {
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000091");
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000092");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000093");

    @Test
    void chunkCancelledAfterTrackerDoesNotBecomeSentWhenPromiseCompletes() {
        ProjectionClientChunkTracker tracker = new ProjectionClientChunkTracker();
        try (ProjectionBlockPacketFixture fixture = new ProjectionBlockPacketFixture(OBSERVER_ID)) {
            PacketSendEvent event = fixture.chunk(-3, 5);
            tracker.onPacketSend(event);
            when(event.isCancelled()).thenReturn(true);
            fixture.finish(event);
            assertFalse(tracker.isTracked(OBSERVER_ID, -3, 5));
        }
    }

    @Test
    void blockUpdateCancelledAfterTrackerDoesNotScheduleRepair() {
        List<Runnable> scheduled = new ArrayList<Runnable>();
        ProjectionBlockUpdateRepair repair = new ProjectionBlockUpdateRepair(task -> scheduled.add(task), () -> { });
        repair.claim(CellKeys.pack(3, 70, 5), 42);
        ProjectionClientChunkTracker tracker = new ProjectionClientChunkTracker();
        tracker.watchBlockUpdates(OBSERVER_ID, repair);
        try (ProjectionBlockPacketFixture fixture = new ProjectionBlockPacketFixture(OBSERVER_ID)) {
            PacketSendEvent event = fixture.event(new WrapperPlayServerBlockChange(new Vector3i(3, 70, 5), 5));
            tracker.onPacketSend(event);
            when(event.isCancelled()).thenReturn(true);
            fixture.finish(event);
            assertTrue(scheduled.isEmpty());
        }
    }

    @Test
    void partialFallbackSendFailureRetriesUndeliveredCellsWithoutAnotherDelta() {
        World world = world();
        Player observer = observer(world);
        AtomicInteger attempts = new AtomicInteger();
        LongOpenHashSet delivered = new LongOpenHashSet();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 2) {
                throw new IllegalStateException("packet send failed");
            }
            Location location = invocation.getArgument(0, Location.class);
            delivered.add(CellKeys.pack(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
            return null;
        }).when(observer).sendBlockChange(any(Location.class), any(BlockData.class));
        ProjectionClaimArbiter arbiter = arbiter();
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims = claims(-1);

        assertThrows(IllegalStateException.class, () -> arbiter.submit(observer, OWNER_ID, world, claims, 2.0D, false));
        assertEquals(1, delivered.size());
        arbiter.retryPending(observer, world);
        assertEquals(claims.keySet(), delivered);
    }

    @Test
    void rejectedSectionWriteRetriesWithoutAnotherDelta() {
        World world = world();
        Player observer = observer(world);
        ProjectionClaimArbiter arbiter = arbiter();
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims = claims(42);
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.installWithBatchUser();
        try {
            recorder.failNextSend();
            assertThrows(IllegalStateException.class, () -> arbiter.submit(observer, OWNER_ID, world, claims, 2.0D, false));
            assertTrue(recorder.sent().isEmpty());
            assertTrue(arbiter.retryPending(observer, world).getBlockChanges() > 0);
            assertFalse(recorder.sent().isEmpty());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    void failedRestorationRetainsEvidenceAndRetriesWithoutAnotherDelta() {
        World world = world();
        Player observer = observer(world);
        BlockData localData = mock(BlockData.class);
        when(localData.getMaterial()).thenReturn(Material.DIRT);
        ProjectionWorldView view = mock(ProjectionWorldView.class);
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(localData);
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(ignored -> view,
            new BukkitProjectionOutput((player, chunkX, chunkZ) -> true, ignored -> List.of()));
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims = claims(-1);
        arbiter.submit(observer, OWNER_ID, world, claims, 2.0D, false);
        AtomicInteger attempts = new AtomicInteger();
        LongOpenHashSet restored = new LongOpenHashSet();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 2) {
                throw new IllegalStateException("restoration send failed");
            }
            Location location = invocation.getArgument(0, Location.class);
            restored.add(CellKeys.pack(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
            assertEquals(localData, invocation.getArgument(1, BlockData.class));
            return null;
        }).when(observer).sendBlockChange(any(Location.class), any(BlockData.class));

        assertThrows(IllegalStateException.class, () -> arbiter.release(observer, OWNER_ID, world, false));
        assertEquals(1, restored.size());
        arbiter.retryPending(observer, world);
        assertEquals(claims.keySet(), restored);
        int completedAttempts = attempts.get();
        arbiter.retryPending(observer, world);
        assertEquals(completedAttempts, attempts.get());
    }

    private static ProjectionClaimArbiter arbiter() {
        return new ProjectionClaimArbiter(ignored -> null,
            new BukkitProjectionOutput((observer, chunkX, chunkZ) -> true, ignored -> List.of()));
    }

    private static World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        return world;
    }

    private static Player observer(World world) {
        Player observer = mock(Player.class);
        when(observer.getUniqueId()).thenReturn(OBSERVER_ID);
        when(observer.isOnline()).thenReturn(true);
        when(observer.getWorld()).thenReturn(world);
        return observer;
    }

    private static Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims(int globalId) {
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(Material.STONE);
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims =
            new Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>>(3);
        for (int x = 3; x < 6; x++) {
            BlockClaim<BlockData, ProjectionWorldView> claim =
                new BlockClaim<BlockData, ProjectionWorldView>(data, null, BlockClaim.NO_REMOTE_KEY, false);
            claim.setGlobalId(globalId);
            claims.put(CellKeys.pack(x, 70, 5), claim);
        }
        return claims;
    }
}
