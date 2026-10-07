package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.SeamlessChunkMapAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MinecraftRemoteViewGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");
    private static final int STAGE_TICKS = 400;
    private static final int DESTINATION_OFFSET = 4_096;
    private static final double MARKER_OFFSET = 32.0D;
    private static final int SETTLE_TICKS = 20;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private SeamlessGameFixture fixture;
    private SeamlessGameFixture leaver;
    private MinecraftPortal source;
    private MinecraftPortal destination;
    private ArmorStand stand;
    private BlockPos marker;
    private ChunkPos forced;
    private BlockState original;
    private Stage stage = Stage.LEAVE;
    private int handle;
    private int remaining = STAGE_TICKS;
    private int routedChunks;
    private int changedAt;
    private int settled;
    private int settledRevision = -1;
    private String delivery = "";

    private MinecraftRemoteViewGameTest(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
    }

    public static CompletableFuture<Boolean> run(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftRemoteViewGameTest test = new MinecraftRemoteViewGameTest(helper, runtime);
        try {
            test.start();
        } catch (Throwable failure) {
            test.finish(failure);
        }
        return test.result;
    }

    private void start() throws Exception {
        ServerLevel level = helper.getLevel();
        fixture = SeamlessGameFixture.connect(runtime, level, "seamless-view");
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 6));
        source = fixture.portal(level, base);
        destination = fixture.portal(level, base.offset(DESTINATION_OFFSET, 0, 0));
        helper.assertTrue(fixture.link(source, destination), "Remote view fixture did not link portals");
        Vec3d arrival = destination.getOrigin();
        stand = new ArmorStand(level, arrival.x() + 1.0D, Math.floor(arrival.y()) - 1.0D, arrival.z() + 2.5D);
        stand.setNoGravity(true);
        helper.assertTrue(level.addFreshEntity(stand), "Remote view fixture could not spawn the destination marker entity");
        fixture.stand(source, -3.0D);
        fixture.negotiate();
        leaver = SeamlessGameFixture.connect(runtime, level, "seamless-leaver");
        leaver.stand(source, -3.0D);
        leaver.negotiate();
        helper.runAfterDelay(1, this::step);
    }

    private void step() {
        try {
            fixture.pump();
            if (leaver != null) {
                leaver.pump();
            }
            if (advance()) {
                return;
            }
            helper.assertTrue(--remaining > 0, "Remote view stage " + stage + " timed out; travel " + fixture.travel().size()
                + " messages, routed " + fixture.routed().size() + " packets, after change " + afterChange());
            helper.runAfterDelay(1, this::step);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private boolean advance() throws InterruptedException {
        switch (stage) {
            case LEAVE -> {
                if (runtime.remoteRoutes().routes(leaver.player().getUUID()).isEmpty()) {
                    return false;
                }
                disconnectFromNetwork(leaver);
                next(Stage.LEFT);
            }
            case LEFT -> {
                if (!runtime.remoteRoutes().routes(leaver.player().getUUID()).isEmpty()) {
                    return false;
                }
                leaver.close();
                leaver = null;
                next(Stage.STREAM);
            }
            case STREAM -> {
                TravelMessage.RemoteLevelOpen open = opened();
                if (open == null || !streamed(open) || !paired(open.levelHandle(), stand.getId())) {
                    return false;
                }
                handle = open.levelHandle();
                marker = BlockPos.containing(destination.getOrigin().x() + MARKER_OFFSET, destination.getOrigin().y(), destination.getOrigin().z() + 3.0D);
                next(Stage.DELIVERED);
            }
            case DELIVERED -> {
                RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
                ChunkPos column = ChunkPos.containing(marker);
                if (route == null || !route.stream().delivered(column.pack())) {
                    return false;
                }
                helper.assertTrue(!ticking(column), "The marker column was already ticking when it was delivered");
                forced = column;
                helper.getLevel().setChunkForced(column.x(), column.z(), true);
                next(Stage.TICKING);
            }
            case TICKING -> {
                if (!settled()) {
                    return false;
                }
                changedAt = fixture.routed().size();
                original = helper.getLevel().getBlockState(marker);
                helper.getLevel().setBlockAndUpdate(marker, original.is(Blocks.GOLD_BLOCK)
                    ? Blocks.DIAMOND_BLOCK.defaultBlockState() : Blocks.GOLD_BLOCK.defaultBlockState());
                next(Stage.BLOCK);
            }
            case BLOCK -> {
                if (!updated(marker)) {
                    return false;
                }
                stand.discard();
                next(Stage.REMOVED);
            }
            case REMOVED -> {
                if (!removed(stand.getId())) {
                    return false;
                }
                fixture.shift(0.0D, 2_000.0D);
                next(Stage.CLOSE);
            }
            case CLOSE -> {
                if (!closed(handle)) {
                    return false;
                }
                helper.assertTrue(!vanillaChunk(), "The destination chunk leaked into the vanilla chunk stream");
                LOGGER.info("WORMHOLES_GAME_TEST_PASS remote_view handle={} routed_chunks={} entity={} block={} delivery={} close=true disconnect=network",
                    handle, routedChunks, stand.getId(), marker.toShortString(), delivery);
                finish(null);
                return true;
            }
        }
        return false;
    }

    private boolean settled() {
        RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
        long key = forced.pack();
        if (route == null || !ticking(forced) || !route.stream().delivered(key)) {
            settled = 0;
            settledRevision = -1;
            return false;
        }
        int revision = route.stream().revision(key);
        settled = revision == settledRevision ? settled + 1 : 0;
        settledRevision = revision;
        return settled >= SETTLE_TICKS;
    }

    private boolean ticking(ChunkPos column) {
        ChunkHolder holder = ((SeamlessChunkMapAccess) helper.getLevel().getChunkSource().chunkMap).wormholesVisibleChunk(column.pack());
        return holder != null && holder.getTickingChunk() != null;
    }

    private boolean streamed(TravelMessage.RemoteLevelOpen open) {
        boolean centered = false;
        routedChunks = 0;
        boolean arrival = false;
        for (SeamlessGameFixture.Routed routed : fixture.routed()) {
            if (routed.handle() != open.levelHandle()) {
                continue;
            }
            if (routed.packet() instanceof ClientboundSetChunkCacheCenterPacket center) {
                centered |= center.getX() == open.center().x() && center.getZ() == open.center().z();
            } else if (routed.packet() instanceof ClientboundLevelChunkWithLightPacket chunk) {
                helper.assertTrue(centered, "A routed chunk arrived before the resident level cache centre");
                routedChunks++;
                arrival |= chunk.x() == open.center().x() && chunk.z() == open.center().z();
            }
        }
        return centered && arrival;
    }

    private TravelMessage.RemoteLevelOpen opened() {
        int x = (int) Math.floor(destination.getOrigin().x()) >> 4;
        int z = (int) Math.floor(destination.getOrigin().z()) >> 4;
        for (TravelMessage message : fixture.travel()) {
            if (message instanceof TravelMessage.RemoteLevelOpen open && open.center().x() == x && open.center().z() == z) {
                return open;
            }
        }
        return null;
    }

    private boolean paired(int levelHandle, int entity) {
        for (SeamlessGameFixture.Routed routed : fixture.routed()) {
            if (routed.handle() == levelHandle && routed.packet() instanceof ClientboundAddEntityPacket add && add.getId() == entity) {
                return true;
            }
        }
        return false;
    }

    private boolean updated(BlockPos position) {
        AtomicBoolean found = new AtomicBoolean();
        List<SeamlessGameFixture.Routed> routed = fixture.routed();
        for (int index = changedAt; index < routed.size(); index++) {
            SeamlessGameFixture.Routed packet = routed.get(index);
            if (packet.handle() != handle) {
                continue;
            }
            if (packet.packet() instanceof ClientboundBlockUpdatePacket update && update.getPos().equals(position)) {
                delivery = "block_update";
                return true;
            }
            if (packet.packet() instanceof ClientboundSectionBlocksUpdatePacket section) {
                section.runUpdates((changed, state) -> found.compareAndSet(false, changed.equals(position)));
                if (found.get()) {
                    delivery = "section_update";
                    return true;
                }
            }
            if (packet.packet() instanceof ClientboundLevelChunkWithLightPacket chunk && chunk.x() == position.getX() >> 4
                && chunk.z() == position.getZ() >> 4) {
                helper.fail("The block change in a delivered ticking column was re-sent as a whole chunk instead of a live update");
            }
        }
        return false;
    }

    private String afterChange() {
        List<SeamlessGameFixture.Routed> routed = fixture.routed();
        StringBuilder detail = new StringBuilder();
        for (int index = changedAt; index < routed.size(); index++) {
            SeamlessGameFixture.Routed packet = routed.get(index);
            detail.append(packet.handle()).append(':').append(packet.packet().type().id().getPath());
            if (packet.packet() instanceof ClientboundBlockUpdatePacket update) {
                detail.append('@').append(update.getPos().toShortString()).append('=').append(update.getBlockState());
            }
            detail.append(' ');
        }
        for (TravelMessage message : fixture.travel()) {
            if (message instanceof TravelMessage.RemoteLevelClose close) {
                detail.append("close:").append(close.levelHandle()).append(' ');
            }
        }
        detail.append(" marker ").append(marker == null ? "none" : marker.toShortString());
        if (marker != null) {
            RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
            long key = ChunkPos.containing(marker).pack();
            detail.append(" state=").append(helper.getLevel().getBlockState(marker)).append(" ticking=").append(ticking(ChunkPos.containing(marker)));
            if (route != null) {
                detail.append(" delivered=").append(route.stream().delivered(key)).append(" live=").append(route.stream().live(key))
                    .append(" revision=").append(route.stream().revision(key)).append(" window=").append(route.window().centerX()).append(',')
                    .append(route.window().centerZ()).append('r').append(route.window().radius());
            }
        }
        return detail.toString().trim();
    }

    private boolean closed(int levelHandle) {
        for (TravelMessage message : fixture.travel()) {
            if (message instanceof TravelMessage.RemoteLevelClose close && close.levelHandle() == levelHandle) {
                return true;
            }
        }
        return false;
    }

    private boolean removed(int entity) {
        for (SeamlessGameFixture.Routed routed : fixture.routed()) {
            if (routed.handle() == handle && routed.packet() instanceof ClientboundRemoveEntitiesPacket remove && remove.entityIds().contains(entity)) {
                return true;
            }
        }
        return false;
    }

    private boolean vanillaChunk() {
        int x = (int) Math.floor(destination.getOrigin().x()) >> 4;
        int z = (int) Math.floor(destination.getOrigin().z()) >> 4;
        for (Object packet : fixture.vanilla()) {
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk && chunk.x() == x && chunk.z() == z) {
                return true;
            }
        }
        return false;
    }

    private void disconnectFromNetwork(SeamlessGameFixture leaving) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread network = new Thread(() -> {
            try {
                runtime.playerDisconnected(leaving.player());
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "seamless-test-network");
        network.start();
        network.join();
        if (failure.get() != null) {
            throw new IllegalStateException("A disconnect reported from the network thread failed", failure.get());
        }
    }

    private void next(Stage value) {
        stage = value;
        remaining = STAGE_TICKS;
    }

    private void finish(Throwable failure) {
        try {
            if (original != null) {
                helper.getLevel().setBlockAndUpdate(marker, original);
            }
            if (forced != null) {
                helper.getLevel().setChunkForced(forced.x(), forced.z(), false);
            }
            if (stand != null && !stand.isRemoved()) {
                stand.remove(Entity.RemovalReason.DISCARDED);
            }
            if (leaver != null) {
                leaver.close();
            }
            if (fixture != null) {
                fixture.close();
            }
        } catch (Throwable cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure == null) {
            result.complete(true);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private enum Stage {
        LEAVE, LEFT, STREAM, DELIVERED, TICKING, BLOCK, REMOVED, CLOSE
    }
}
