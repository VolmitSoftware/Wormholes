package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class MinecraftProjectionScheduleGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");
    private static final int DISCOVERY_TICKS = 120;
    private static final int FAR_CHANGE_TICKS = 30;
    private static final int NEAR_CHANGE_TICKS = 60;
    private static final int GAZE_TICKS = 40;
    private static final int GAZE_IN_VIEW_MINIMUM = 20;
    private static final int GAZE_BEHIND_MAXIMUM = 4;
    private static final int GAZE_BEHIND_MINIMUM = 1;
    private static final double WALK_STEP = 0.3D;
    private static final BlockState CHANGED = Blocks.DIAMOND_BLOCK.defaultBlockState();
    private static final int CYCLE_LENGTH = 13;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final List<ClientboundSectionBlocksUpdatePacket> updates = new ArrayList<>();
    private final List<PhysicalBlock> physical = new ArrayList<>();
    private final List<MinecraftPortal> created = new ArrayList<>();
    private MinecraftGameTestPlayer connection;
    private Probe ahead;
    private Probe behind;
    private BlockPos far;
    private Vec3 standing;
    private int remaining;
    private int aheadUpdates;
    private int behindUpdates;

    private MinecraftProjectionScheduleGameTest(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
    }

    static CompletableFuture<Boolean> dirt(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftProjectionScheduleGameTest test = new MinecraftProjectionScheduleGameTest(helper, runtime);
        try {
            test.connect("dirt-probe");
            test.ahead = test.probe(new Probe.Layout(2, 6, 16, new Vec3(0, 0, -1)), Blocks.GOLD_BLOCK.defaultBlockState(),
                Blocks.EMERALD_BLOCK.defaultBlockState());
            test.far = test.helper.absolutePos(new BlockPos(66, 3, 6));
            GeometryVector origin = test.ahead.source().getOrigin();
            test.place(new Vec3(origin.x(), origin.y() - test.connection.player().getEyeHeight(), origin.z() - 3.0D));
            test.remaining = DISCOVERY_TICKS;
            test.helper.runAfterDelay(1, test::discoverForDirt);
        } catch (Throwable failure) {
            test.finish(failure);
        }
        return test.result;
    }

    static CompletableFuture<Boolean> gaze(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftProjectionScheduleGameTest test = new MinecraftProjectionScheduleGameTest(helper, runtime);
        try {
            test.connect("gaze-probe");
            test.ahead = test.probe(new Probe.Layout(9, 15, 24, new Vec3(0, 0, -1)), Blocks.GOLD_BLOCK.defaultBlockState(),
                Blocks.EMERALD_BLOCK.defaultBlockState());
            test.behind = test.probe(new Probe.Layout(9, 6, 40, new Vec3(0, 0, 1)), Blocks.LAPIS_BLOCK.defaultBlockState(),
                Blocks.REDSTONE_BLOCK.defaultBlockState());
            test.standing = Vec3.atBottomCenterOf(test.helper.absolutePos(new BlockPos(10, 2, 10)));
            test.place(test.standing);
            test.remaining = DISCOVERY_TICKS;
            test.helper.runAfterDelay(1, test::discoverForGaze);
        } catch (Throwable failure) {
            test.finish(failure);
        }
        return test.result;
    }

    private void discoverForDirt() {
        try {
            drain();
            if (!ahead.discovered(updates)) {
                waitFor(this::discoverForDirt, "Destination marker never reached the observer through the projection");
                return;
            }
            updates.clear();
            track(far);
            helper.getLevel().setBlockAndUpdate(far, Blocks.OBSIDIAN.defaultBlockState());
            remaining = FAR_CHANGE_TICKS;
            helper.runAfterDelay(1, this::watchFarChange);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void watchFarChange() {
        try {
            drain();
            helper.assertTrue(ahead.updatesAt(updates) == 0, "A change far outside the destination footprint re-sent the projected marker");
            updates.clear();
            if (--remaining > 0) {
                helper.runAfterDelay(1, this::watchFarChange);
                return;
            }
            helper.assertTrue(runtime.projections().projectorCount() > 0, "Observer lost its projector while idle");
            helper.getLevel().setBlockAndUpdate(ahead.marker(), CHANGED);
            remaining = NEAR_CHANGE_TICKS;
            helper.runAfterDelay(1, this::watchNearChange);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void watchNearChange() {
        try {
            drain();
            if (!ahead.showed(updates, CHANGED)) {
                updates.clear();
                waitFor(this::watchNearChange, "A change to a projected destination block never reached the observer");
                return;
            }
            LOGGER.info("WORMHOLES_GAME_TEST_PASS projection_dirt_runtime far_change_idle near_change_resample");
            finish(null);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void discoverForGaze() {
        try {
            drain();
            boolean aheadFound = ahead.discovered(updates);
            boolean behindFound = behind.discovered(updates);
            if (!aheadFound || !behindFound) {
                waitFor(this::discoverForGaze, "Destination markers never reached the observer: ahead=" + aheadFound + " behind=" + behindFound);
                return;
            }
            updates.clear();
            remaining = GAZE_TICKS;
            helper.runAfterDelay(1, this::walkAndToggle);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void walkAndToggle() {
        try {
            drain();
            aheadUpdates += ahead.updatesAt(updates);
            behindUpdates += behind.updatesAt(updates);
            updates.clear();
            if (remaining-- <= 0) {
                helper.assertTrue(aheadUpdates >= GAZE_IN_VIEW_MINIMUM, "Portal in view refreshed only " + aheadUpdates + " times in " + GAZE_TICKS + " ticks");
                helper.assertTrue(behindUpdates >= GAZE_BEHIND_MINIMUM && behindUpdates <= GAZE_BEHIND_MAXIMUM,
                    "Portal behind the observer refreshed " + behindUpdates + " times in " + GAZE_TICKS + " ticks instead of only on starvation");
                LOGGER.info("WORMHOLES_GAME_TEST_PASS projection_gaze_runtime in_view={} behind={}", aheadUpdates, behindUpdates);
                finish(null);
                return;
            }
            place(standing.add((remaining & 1) == 0 ? WALK_STEP : 0.0D, 0.0D, 0.0D));
            BlockState next = Blocks.WOOL.pick(DyeColor.values()[remaining % CYCLE_LENGTH]).defaultBlockState();
            helper.getLevel().setBlockAndUpdate(ahead.marker(), next);
            helper.getLevel().setBlockAndUpdate(behind.marker(), next);
            helper.runAfterDelay(1, this::walkAndToggle);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void waitFor(Runnable step, String timeout) {
        helper.assertTrue(--remaining > 0, timeout);
        helper.runAfterDelay(1, step);
    }

    private void connect(String name) {
        connection = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), name);
        connection.channel().pipeline().addLast("wormholes-schedule-packets", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                if (message instanceof ClientboundSectionBlocksUpdatePacket update) {
                    updates.add(update);
                }
                context.write(message, promise);
            }
        });
    }

    private Probe probe(Probe.Layout layout, BlockState front, BlockState back) {
        ServerLevel level = helper.getLevel();
        MinecraftPortal source = portal(layout.sourceX(), layout.z(), layout.facing());
        MinecraftPortal destination = portal(layout.destinationX(), layout.z(), layout.facing());
        helper.assertTrue(runtime.portals().link(connection.player(), source.getId(), destination.getId()), "Schedule fixture did not link portals");
        BlockPos frontMarker = helper.absolutePos(new BlockPos(layout.destinationX() + 1, 3, layout.z() - 3));
        BlockPos backMarker = helper.absolutePos(new BlockPos(layout.destinationX() + 1, 3, layout.z() + 3));
        track(frontMarker);
        track(backMarker);
        level.setBlockAndUpdate(frontMarker, front);
        level.setBlockAndUpdate(backMarker, back);
        return new Probe(source, new Probe.Markers(frontMarker, front, backMarker, back));
    }

    private MinecraftPortal portal(int x, int z, Vec3 facing) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int dx = 0; dx < 3; dx++) {
            for (int y = 2; y < 5; y++) {
                cells.add(helper.absolutePos(new BlockPos(x + dx, y, z)));
            }
        }
        MinecraftPortal portal = runtime.portals().create(connection.player().getUUID(), helper.getLevel(), cells, PortalType.PORTAL, facing);
        created.add(portal);
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setNetworkViewDepth(8);
        portal.setNetworkViewLateralPad(8);
        return portal;
    }

    private void place(Vec3 position) {
        connection.player().setPos(position);
        connection.player().setYRot(0.0F);
        connection.player().setXRot(0.0F);
    }

    private void track(BlockPos position) {
        physical.add(new PhysicalBlock(position, helper.getLevel().getBlockState(position)));
    }

    private void drain() {
        connection.channel().runPendingTasks();
        for (Object message : connection.channel().outboundMessages()) {
            ReferenceCountUtil.release(message);
        }
        connection.channel().outboundMessages().clear();
    }

    private void finish(Throwable failure) {
        try {
            for (PhysicalBlock block : physical) {
                helper.getLevel().setBlockAndUpdate(block.position(), block.state());
            }
            if (connection != null) {
                for (MinecraftPortal portal : created) {
                    runtime.portals().remove(connection.player(), portal.getId());
                }
                connection.close();
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

    private record PhysicalBlock(BlockPos position, BlockState state) {
    }

    private static final class Probe {
        private final MinecraftPortal source;
        private final Markers markers;
        private BlockPos marker;
        private BlockPos cell;

        private Probe(MinecraftPortal source, Markers markers) {
            this.source = source;
            this.markers = markers;
        }

        private MinecraftPortal source() {
            return source;
        }

        private BlockPos marker() {
            return marker;
        }

        private boolean discovered(List<ClientboundSectionBlocksUpdatePacket> updates) {
            if (cell != null) {
                return true;
            }
            for (ClientboundSectionBlocksUpdatePacket update : updates) {
                update.runUpdates((position, state) -> {
                    if (cell == null && state == markers.front()) {
                        cell = position.immutable();
                        marker = markers.frontPosition();
                    } else if (cell == null && state == markers.back()) {
                        cell = position.immutable();
                        marker = markers.backPosition();
                    }
                });
            }
            return cell != null;
        }

        private int updatesAt(List<ClientboundSectionBlocksUpdatePacket> updates) {
            int[] count = {0};
            for (ClientboundSectionBlocksUpdatePacket update : updates) {
                update.runUpdates((position, state) -> {
                    if (position.equals(cell)) {
                        count[0]++;
                    }
                });
            }
            return count[0];
        }

        private boolean showed(List<ClientboundSectionBlocksUpdatePacket> updates, BlockState expected) {
            boolean[] found = {false};
            for (ClientboundSectionBlocksUpdatePacket update : updates) {
                update.runUpdates((position, state) -> {
                    if (position.equals(cell) && state == expected) {
                        found[0] = true;
                    }
                });
            }
            return found[0];
        }

        private record Layout(int sourceX, int z, int destinationX, Vec3 facing) {
        }

        private record Markers(BlockPos frontPosition, BlockState front, BlockPos backPosition, BlockState back) {
        }
    }
}
