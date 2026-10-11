package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.fidelity.BiomeClaimSet;
import art.arcane.optics.fidelity.BlockEntityLayer;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.light.LightOverlay;
import art.arcane.optics.spi.OpticsMetrics;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.world.chunk.LightData;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateLight;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.util.Vector2i;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkBiomes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

final class ProjectionFidelityReplayTest {
    private static final UUID OBSERVER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final long CELL = CellKeys.pack(2, 2, 2);

    @Test
    void unchangedBlockEntityReplaysAfterChunkReplacementAndStandaloneNbt() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            packets.send(fixture.tracker, packets.chunk(0, 0));
            fixture.claim();
            assertEquals(1, fixture.flush());
            packets.send(fixture.tracker, packets.chunk(0, 0));
            fixture.retry();
            assertEquals(1, fixture.flush());
            packets.send(fixture.tracker, packets.event(new WrapperPlayServerBlockEntityData(new Vector3i(2, 2, 2),
                BlockEntityTypes.CHEST, new NBTCompound())));
            fixture.retry();
            assertEquals(1, fixture.flush());
            assertEquals(3, fixture.nbtPackets.size());
            assertEquals(0, fixture.flush());
        }
    }

    @Test
    void ownNbtDoesNotLoopAndFailedOwnNbtReplays() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            packets.send(fixture.tracker, packets.chunk(0, 0));
            fixture.claim();
            fixture.flush();
            PacketSendEvent own = packets.event(new WrapperPlayServerBlockEntityData(new Vector3i(2, 2, 2),
                BlockEntityTypes.CHEST, new NBTCompound()));
            ProjectionPacketOrigin.mark(own.getByteBuf());
            packets.send(fixture.tracker, own);
            fixture.retry();
            assertEquals(0, fixture.flush());
            PacketSendEvent failed = packets.event(new WrapperPlayServerBlockEntityData(new Vector3i(2, 2, 2),
                BlockEntityTypes.CHEST, new NBTCompound()));
            ProjectionPacketOrigin.mark(failed.getByteBuf());
            fixture.tracker.onPacketSend(failed);
            packets.fail(failed);
            fixture.retry();
            assertEquals(1, fixture.flush());
        }
    }

    @Test
    void unsentChunkDefersNbtAndNewWinnerFencesOldOwner() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            fixture.claim();
            assertEquals(0, fixture.flush());
            packets.send(fixture.tracker, packets.chunk(0, 0));
            fixture.retry();
            assertEquals(1, fixture.flush());
            Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> replacement = new Long2ObjectOpenHashMap<>();
            replacement.put(CELL, new BlockClaim<BlockData, ProjectionWorldView>(fixture.data, null, BlockClaim.NO_REMOTE_KEY, false));
            fixture.arbiter.submit(fixture.observer, UUID.randomUUID(), fixture.world, replacement, 0.0D, false);
            fixture.layer.invalidateCell(CELL);
            assertEquals(0, fixture.flush());
        }
    }

    @Test
    void standaloneBiomeReplayPreservesOverridesAndReleaseUsesReceivedLocalBaseline() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            packets.send(fixture.tracker, packets.chunk(0, 0));
            Long2IntOpenHashMap overrides = new Long2IntOpenHashMap();
            overrides.put(CellKeys.pack(0, 0, 0), 7);
            fixture.arbiter.submitBiomes(fixture.observer, OWNER, fixture.world, overrides);
            assertEquals(1, fixture.biomePackets.size());
            PacketSendEvent update = packets.event(biomePacket(9));
            packets.send(fixture.tracker, update);
            fixture.retry();
            assertEquals(2, fixture.biomePackets.size());
            assertEquals(7, fixture.biomePackets.getLast().sections()[0][0]);
            assertEquals(9, fixture.biomePackets.getLast().sections()[0][1]);
            fixture.arbiter.release(fixture.observer, OWNER, fixture.world, false);
            fixture.retry();
            assertEquals(9, fixture.biomePackets.getLast().sections()[0][0]);
        }
    }

    @Test
    void fullChunkReplaysUnchangedBiomesAndRefreshesReleaseBaseline() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            packets.send(fixture.tracker, packets.chunk(0, 0));
            Long2IntOpenHashMap overrides = new Long2IntOpenHashMap();
            overrides.put(CellKeys.pack(0, 0, 0), 7);
            fixture.arbiter.submitBiomes(fixture.observer, OWNER, fixture.world, overrides);
            packets.send(fixture.tracker, packets.fullChunk(0, 0, 9));
            fixture.retry();
            assertEquals(2, fixture.biomePackets.size());
            assertEquals(7, fixture.biomePackets.getLast().sections()[0][0]);
            assertEquals(9, fixture.biomePackets.getLast().sections()[0][1]);
            fixture.arbiter.release(fixture.observer, OWNER, fixture.world, false);
            fixture.retry();
            assertEquals(9, fixture.biomePackets.getLast().sections()[0][0]);
        }
    }

    @Test
    void ownBiomeFailureReplaysWithoutDiscardingCurrentLocalBiomes() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            packets.send(fixture.tracker, packets.chunk(0, 0));
            Long2IntOpenHashMap overrides = new Long2IntOpenHashMap();
            overrides.put(CellKeys.pack(0, 0, 0), 7);
            fixture.arbiter.submitBiomes(fixture.observer, OWNER, fixture.world, overrides);
            packets.send(fixture.tracker, packets.event(biomePacket(9)));
            fixture.retry();
            PacketSendEvent own = packets.event(biomePacket(7));
            ProjectionPacketOrigin.mark(own.getByteBuf());
            fixture.tracker.onPacketSend(own);
            packets.fail(own);
            fixture.retry();
            assertEquals(7, fixture.biomePackets.getLast().sections()[0][0]);
            assertEquals(9, fixture.biomePackets.getLast().sections()[0][1]);
            int count = fixture.biomePackets.size();
            PacketSendEvent success = packets.event(biomePacket(7));
            ProjectionPacketOrigin.mark(success.getByteBuf());
            packets.send(fixture.tracker, success);
            fixture.retry();
            assertEquals(count, fixture.biomePackets.size());
        }
    }

    @Test
    void standaloneLightReplaysUnchangedClaimsAndOwnLightDoesNotLoop() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            packets.send(fixture.tracker, packets.chunk(0, 0));
            Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims = new Long2ObjectOpenHashMap<>();
            BlockClaim<BlockData, ProjectionWorldView> claim = new BlockClaim<BlockData, ProjectionWorldView>(fixture.data,
                null, BlockClaim.NO_REMOTE_KEY, false, BlockClaim.LightingPolicy.FULL_BRIGHT);
            claim.setGlobalId(-1);
            claims.put(CELL, claim);
            fixture.arbiter.submit(fixture.observer, OWNER, fixture.world, claims, 1.0D, true);
            assertEquals(1, fixture.lightPackets.size());
            BitSet mask = new BitSet();
            mask.set(1);
            byte[] sky = new byte[2048];
            byte[] block = new byte[2048];
            Arrays.fill(sky, (byte) 0x99);
            Arrays.fill(block, (byte) 0x66);
            LightData light = new LightData(true, mask, mask, new BitSet(), new BitSet(), 1, 1,
                new byte[][] {sky}, new byte[][] {block});
            packets.send(fixture.tracker, packets.event(new WrapperPlayServerUpdateLight(0, 0, light)));
            fixture.retry();
            assertEquals(2, fixture.lightPackets.size());
            assertEquals((byte) 0x99, fixture.lightPackets.getLast().skyArrays()[0][0]);
            PacketSendEvent own = packets.event(new WrapperPlayServerUpdateLight(0, 0, light));
            ProjectionPacketOrigin.mark(own.getByteBuf());
            packets.send(fixture.tracker, own);
            fixture.retry();
            assertEquals(2, fixture.lightPackets.size());
            PacketSendEvent failed = packets.event(new WrapperPlayServerUpdateLight(0, 0, light));
            ProjectionPacketOrigin.mark(failed.getByteBuf());
            fixture.tracker.onPacketSend(failed);
            packets.fail(failed);
            fixture.retry();
            assertEquals(3, fixture.lightPackets.size());
        }
    }

    @Test
    void failedBlockRestorationRetainsObserverAndRetriesCurrentTerrainUntilAcknowledged() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            packets.send(fixture.tracker, packets.chunk(0, 0));
            List<PacketSendEvent> writes = new ArrayList<PacketSendEvent>();
            List<BlockData> sent = new ArrayList<BlockData>();
            doAnswer(invocation -> {
                BlockData data = invocation.getArgument(1, BlockData.class);
                sent.add(data);
                PacketSendEvent event = packets.event(new WrapperPlayServerBlockChange(new Vector3i(2, 2, 2), 5));
                fixture.tracker.onPacketSend(event);
                writes.add(event);
                return null;
            }).when(fixture.observer).sendBlockChange(any(Location.class), any(BlockData.class));
            fixture.claim();
            packets.finish(writes.getLast());
            fixture.retry();
            BlockData first = mock(BlockData.class);
            when(first.getMaterial()).thenReturn(Material.DIRT);
            when(fixture.view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(first);
            fixture.arbiter.release(fixture.observer, OWNER, fixture.world, false);
            assertFalse(fixture.arbiter.isIdle());
            PacketSendEvent rejected = writes.getLast();
            BlockData current = mock(BlockData.class);
            when(current.getMaterial()).thenReturn(Material.STONE);
            when(fixture.view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(current);
            packets.fail(rejected);
            fixture.retry();
            assertEquals(current, sent.getLast());
            assertFalse(fixture.arbiter.isIdle());
            packets.finish(writes.getLast());
            fixture.retry();
            assertTrue(fixture.arbiter.isIdle());
        }
    }

    @Test
    void failedRetiringNbtRetriesAndLateReceiptCannotOverwriteANewWinner() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            packets.send(fixture.tracker, packets.chunk(0, 0));
            fixture.holdReceipts = true;
            fixture.layer.restoreCell(CELL, new BlockEntitySample("minecraft:chest", new byte[] {4}));
            assertEquals(1, fixture.arbiter.restoreBlockEntities(fixture.observer, fixture.world, fixture.layer, 1));
            assertFalse(fixture.arbiter.isIdle());
            fixture.localNbt = new BlockEntitySample("minecraft:chest", new byte[] {5});
            fixture.receipts.removeFirst().accept(false);
            fixture.retry();
            assertEquals(2, fixture.nbtPackets.size());
            assertEquals(fixture.localNbt, fixture.nbtPackets.getLast());
            Consumer<Boolean> old = fixture.receipts.removeFirst();
            fixture.claim();
            old.accept(false);
            fixture.retry();
            assertEquals(2, fixture.nbtPackets.size());
            assertEquals(1, fixture.flush());
        }
    }

    @Test
    void retiringNbtAndBiomeStateRemainUntilWriteAcknowledgement() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            FidelityFixture fixture = new FidelityFixture();
            packets.send(fixture.tracker, packets.chunk(0, 0));
            fixture.holdReceipts = true;
            fixture.layer.restoreCell(CELL, new BlockEntitySample("minecraft:chest", new byte[] {4}));
            fixture.arbiter.restoreBlockEntities(fixture.observer, fixture.world, fixture.layer, 1);
            assertFalse(fixture.arbiter.isIdle());
            fixture.receipts.removeFirst().accept(true);
            fixture.retry();
            assertTrue(fixture.arbiter.isIdle());
            Long2IntOpenHashMap overrides = new Long2IntOpenHashMap();
            overrides.put(CellKeys.pack(0, 0, 0), 7);
            fixture.arbiter.submitBiomes(fixture.observer, OWNER, fixture.world, overrides);
            fixture.receipts.removeFirst().accept(true);
            fixture.retry();
            fixture.arbiter.release(fixture.observer, OWNER, fixture.world, false);
            assertFalse(fixture.arbiter.isIdle());
            fixture.receipts.removeFirst().accept(false);
            fixture.retry();
            assertFalse(fixture.arbiter.isIdle());
            fixture.receipts.removeFirst().accept(true);
            fixture.retry();
            assertTrue(fixture.arbiter.isIdle());
        }
    }

    @Test
    void respawnAndChunkReplacementFenceOldRetiringNbtReceipts() {
        for (boolean respawn : List.of(false, true)) {
            try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
                FidelityFixture fixture = new FidelityFixture();
                packets.send(fixture.tracker, packets.chunk(0, 0));
                fixture.holdReceipts = true;
                fixture.layer.restoreCell(CELL, new BlockEntitySample("minecraft:chest", new byte[] {4}));
                fixture.arbiter.restoreBlockEntities(fixture.observer, fixture.world, fixture.layer, 1);
                Consumer<Boolean> old = fixture.receipts.removeFirst();
                if (respawn) {
                    packets.send(fixture.tracker, packets.lifecycle(PacketType.Play.Server.RESPAWN));
                } else {
                    packets.send(fixture.tracker, packets.chunk(0, 0));
                }
                old.accept(false);
                fixture.retry();
                assertEquals(1, fixture.nbtPackets.size());
                assertTrue(fixture.arbiter.isIdle());
            }
        }
    }

    @Test
    void originIdentityDoesNotSuppressAnExternalPacketWithIdenticalBytes() {
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            PacketSendEvent own = packets.event(new WrapperPlayServerBlockEntityData(new Vector3i(2, 2, 2),
                BlockEntityTypes.CHEST, new NBTCompound()));
            PacketSendEvent external = packets.event(new WrapperPlayServerBlockEntityData(new Vector3i(2, 2, 2),
                BlockEntityTypes.CHEST, new NBTCompound()));
            ProjectionPacketOrigin.mark(own.getByteBuf());
            assertFalse(ProjectionPacketOrigin.consume(external.getByteBuf()));
            assertTrue(ProjectionPacketOrigin.consume(own.getByteBuf()));
        }
    }

    @Test
    void repairSchedulerFailureDoesNotStrandFollowingCompletedPackets() {
        ProjectionClientChunkTracker tracker = new ProjectionClientChunkTracker();
        ProjectionBlockUpdateRepair repair = new ProjectionBlockUpdateRepair(task -> {
            throw new IllegalStateException("observer scheduler failed");
        }, () -> { });
        repair.claim(CELL, 42);
        tracker.watchBlockUpdates(OBSERVER, repair);
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            PacketSendEvent block = packets.event(new WrapperPlayServerBlockChange(new Vector3i(2, 2, 2), 5));
            tracker.onPacketSend(block);
            PacketSendEvent following = packets.chunk(1, 0);
            tracker.onPacketSend(following);
            packets.finish(following);
            assertThrows(IllegalStateException.class, () -> packets.finish(block));
            assertTrue(tracker.isTracked(OBSERVER, 1, 0));
        }
    }

    @Test
    void cancelledLifecyclePreservesStateAndFollowingChunkOrderAcrossOutOfOrderCompletions() {
        for (PacketType.Play.Server type : List.of(PacketType.Play.Server.JOIN_GAME, PacketType.Play.Server.RESPAWN,
            PacketType.Play.Server.DISCONNECT, PacketType.Play.Server.UNLOAD_CHUNK)) {
            ProjectionClientChunkTracker tracker = new ProjectionClientChunkTracker();
            try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
                packets.send(tracker, packets.chunk(0, 0));
                long initial = tracker.chunkRevision(OBSERVER, 0, 0);
                PacketSendEvent lifecycle = type == PacketType.Play.Server.UNLOAD_CHUNK
                    ? packets.unload(0, 0) : packets.lifecycle(type);
                tracker.onPacketSend(lifecycle);
                PacketSendEvent following = packets.chunk(1, 0);
                tracker.onPacketSend(following);
                packets.finish(following);
                assertFalse(tracker.isTracked(OBSERVER, 1, 0));
                when(lifecycle.isCancelled()).thenReturn(true);
                packets.finish(lifecycle);
                assertTrue(tracker.isTracked(OBSERVER, 0, 0));
                assertEquals(initial, tracker.chunkRevision(OBSERVER, 0, 0));
                assertTrue(tracker.isTracked(OBSERVER, 1, 0));
            }
        }
    }

    @Test
    void failedChunkAndRespawnDoNotCommitDeliveryAndFollowingChunkStillCommits() {
        ProjectionClientChunkTracker tracker = new ProjectionClientChunkTracker();
        try (ProjectionBlockPacketFixture packets = new ProjectionBlockPacketFixture(OBSERVER)) {
            packets.send(tracker, packets.chunk(0, 0));
            PacketSendEvent failed = packets.lifecycle(PacketType.Play.Server.RESPAWN);
            tracker.onPacketSend(failed);
            PacketSendEvent following = packets.chunk(1, 0);
            tracker.onPacketSend(following);
            packets.finish(following);
            packets.fail(failed);
            assertTrue(tracker.isTracked(OBSERVER, 0, 0));
            assertTrue(tracker.isTracked(OBSERVER, 1, 0));
            PacketSendEvent rejected = packets.chunk(2, 0);
            tracker.onPacketSend(rejected);
            packets.fail(rejected);
            assertFalse(tracker.isTracked(OBSERVER, 2, 0));
        }
    }

    private static WrapperPlayServerChunkBiomes biomePacket(int id) {
        DataPalette palette = DataPalette.createForBiome();
        for (int index = 0; index < 64; index++) {
            palette.set(index & 3, (index >> 4) & 3, (index >> 2) & 3, id);
        }
        return new WrapperPlayServerChunkBiomes(Map.of(new Vector2i(0, 0),
            WrapperPlayServerChunkBiomes.ChunkBiomeData.createWithRepeatingPalette(palette, 1)));
    }

    private record TrackedVisibility(ProjectionClientChunkTracker tracker) implements ProjectionChunkVisibility {
        @Override
        public boolean isChunkSent(Player observer, int x, int z) {
            return tracker.isTracked(observer.getUniqueId(), x, z);
        }

        @Override
        public boolean tracksWriteCompletion() {
            return true;
        }

        @Override
        public long revision(Player observer) {
            return tracker.revision(observer);
        }

        @Override
        public long chunkRevision(Player observer, int x, int z) {
            return tracker.chunkRevision(observer, x, z);
        }

        @Override
        public void watchBlockUpdates(UUID observerId, ProjectionBlockUpdateRepair repair) {
            tracker.watchBlockUpdates(observerId, repair);
        }

        @Override
        public void unwatchBlockUpdates(UUID observerId, ProjectionBlockUpdateRepair repair) {
            tracker.unwatchBlockUpdates(observerId, repair);
        }
    }

    private static final class FidelityFixture {
        private final ProjectionClientChunkTracker tracker = new ProjectionClientChunkTracker();
        private final Player observer = mock(Player.class);
        private final World world = mock(World.class);
        private final BlockData data = mock(BlockData.class);
        private final ProjectionWorldView view = mock(ProjectionWorldView.class);
        private final BukkitProjectionOutput output = mock(BukkitProjectionOutput.class);
        private final List<Consumer<Boolean>> receipts = new ArrayList<Consumer<Boolean>>();
        private boolean holdReceipts;
        private BlockEntitySample localNbt = new BlockEntitySample("minecraft:chest", new byte[] {4});
        private final List<LightOverlay.ChunkLight> lightPackets = new ArrayList<LightOverlay.ChunkLight>();
        private final List<BlockEntitySample> nbtPackets = new ArrayList<BlockEntitySample>();
        private final List<BiomeClaimSet.ChunkBiomes> biomePackets = new ArrayList<BiomeClaimSet.ChunkBiomes>();
        private final BlockEntityLayer<Player> layer = new BlockEntityLayer<Player>(OpticsMetrics.none());
        private final ProjectionClaimArbiter arbiter;

        private FidelityFixture() {
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(observer.getUniqueId()).thenReturn(OBSERVER);
            when(observer.isOnline()).thenReturn(true);
            when(observer.getWorld()).thenReturn(world);
            when(data.getMaterial()).thenReturn(Material.CHEST);
            when(view.getMinHeight()).thenReturn(0);
            when(view.getMaxHeight()).thenReturn(16);
            when(view.isChunkReady(0, 0)).thenReturn(true);
            when(view.sampleBlockEntity(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> localNbt);
            when(view.biomeId(anyInt(), anyInt(),
                anyInt())).thenReturn(1);
            when(output.visibility()).thenReturn(new TrackedVisibility(tracker));
            when(output.lighting()).thenReturn(new LightOverlay<Player, BlockData, ProjectionWorldView>(output,
                () -> null, OpticsMetrics.none()));
            when(output.online(observer)).thenReturn(true);
            when(output.lightSectionBudget()).thenReturn(Integer.MAX_VALUE);
            doAnswer(invocation -> {
                lightPackets.add(invocation.getArgument(1, LightOverlay.ChunkLight.class));
                noteReceipt();
                return null;
            }).when(output).light(any(Player.class), any(LightOverlay.ChunkLight.class));
            when(output.chunkSent(observer, 0, 0)).thenAnswer(invocation -> tracker.isTracked(OBSERVER, 0, 0));
            doAnswer(invocation -> {
                nbtPackets.add(invocation.getArgument(2, BlockEntitySample.class));
                noteReceipt();
                return null;
            }).when(output).blockEntity(any(Player.class), anyLong(), any(BlockEntitySample.class));
            doAnswer(invocation -> {
                List<BiomeClaimSet.ChunkBiomes> columns = invocation.getArgument(1);
                biomePackets.addAll(columns);
                noteReceipt();
                return null;
            }).when(output).biomes(any(Player.class), any());
            arbiter = new ProjectionClaimArbiter(ignored -> view, output);
        }

        private void noteReceipt() {
            Consumer<Boolean> completion = ProjectionPacketOrigin.currentCompletion();
            if (completion == null) {
                return;
            }
            if (holdReceipts) {
                receipts.add(completion);
            } else {
                completion.accept(true);
            }
        }

        private void claim() {
            Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims = new Long2ObjectOpenHashMap<>();
            BlockClaim<BlockData, ProjectionWorldView> claim = new BlockClaim<BlockData, ProjectionWorldView>(data, null,
                BlockClaim.NO_REMOTE_KEY, false);
            claim.setGlobalId(-1);
            claims.put(CELL, claim);
            arbiter.submit(observer, OWNER, world, claims, 2.0D, false);
            Long2ObjectOpenHashMap<BlockEntitySample> desired = new Long2ObjectOpenHashMap<>();
            desired.put(CELL, new BlockEntitySample("minecraft:chest", new byte[] {1, 2, 3}));
            layer.update(desired, (x, y, z) -> null);
            arbiter.trackBlockEntities(observer, OWNER, world, layer, claims);
        }

        private int flush() {
            return arbiter.flushBlockEntities(observer, OWNER, layer, 1);
        }

        private void retry() {
            arbiter.retryPending(observer, world);
        }
    }
}
