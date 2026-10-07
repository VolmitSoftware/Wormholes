package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftTraversalContext;
import art.arcane.wormholes.modded.MinecraftTraversalCostProvider;
import art.arcane.wormholes.modded.MinecraftTravelCosts;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinecraftSeamlessValidationGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");
    private static final int STAGE_TICKS = 600;
    private static final double BEFORE = -0.3D;
    private static final double AFTER = 0.2D;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final List<String> passed = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private SeamlessGameFixture fixture;
    private MinecraftPortal source;
    private MinecraftPortal destination;
    private ServerLevel destinationLevel;
    private AutoCloseable costs;
    private DenyingCost denying;
    private Attempt attempt;
    private Phase phase = Phase.PREPARE;
    private TravelMessage.TravelBegin begin;
    private TravelMessage.TravelBegin known;
    private TravelMessage.TravelBegin claimed;
    private long barrier;
    private BlockPos broken;
    private ArmorStand resident;
    private RecordingEvents events;
    private int remaining = STAGE_TICKS;

    private MinecraftSeamlessValidationGameTest(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
    }

    public static CompletableFuture<Boolean> run(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftSeamlessValidationGameTest test = new MinecraftSeamlessValidationGameTest(helper, runtime);
        try {
            test.start();
        } catch (Throwable failure) {
            test.finish(failure);
        }
        return test.result;
    }

    private void start() throws Exception {
        attempts.add(new Attempt("cross_dimension_accept", Expect.ACCEPT_LEVEL_CHANGE));
        attempts.add(new Attempt("wrong_direction", Expect.REJECT));
        attempts.add(new Attempt("outside_aperture", Expect.REJECT));
        attempts.add(new Attempt("no_access", Expect.REJECT));
        attempts.add(new Attempt("no_cost", Expect.REJECT));
        attempts.add(new Attempt("same_level_accept", Expect.ACCEPT_SAME_LEVEL));
        attempts.add(new Attempt("same_level_again", Expect.ACCEPT_SAME_LEVEL));
        next(0);
        helper.runAfterDelay(1, this::step);
    }

    private void next(int index) throws Exception {
        if (fixture != null && (index == 1 || index >= attempts.size())) {
            fixture.close();
            fixture = null;
        }
        if (index >= attempts.size()) {
            return;
        }
        attempt = attempts.get(index);
        attempt.index = index;
        if (fixture == null) {
            open(index == 0);
        }
        fixture.stand(source, BEFORE);
        fixture.forgetResolved();
        begin = null;
        phase = Phase.PREPARE;
        remaining = STAGE_TICKS;
    }

    private void open(boolean nether) throws Exception {
        ServerLevel level = helper.getLevel();
        fixture = SeamlessGameFixture.connect(runtime, level, nether ? "seamless-nether" : "seamless-reject");
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, nether ? 4 : 12));
        source = fixture.portal(level, base);
        destinationLevel = nether ? runtime.server().getLevel(Level.NETHER) : level;
        helper.assertTrue(destinationLevel != null, "The seamless fixture needs a nether level");
        BlockPos target = nether ? new BlockPos(base.getX() + 48, 70, base.getZ()) : base.offset(400, 0, 0);
        destination = fixture.portal(destinationLevel, target);
        helper.assertTrue(fixture.link(source, destination), "Seamless fixture did not link portals");
        if (nether) {
            helper.assertTrue(fixture.link(destination, source), "Seamless fixture did not link the return portal");
            resident = new ArmorStand(destinationLevel, destination.getOrigin().x() + 1.0D, Math.floor(destination.getOrigin().y()) - 1.0D,
                destination.getOrigin().z() + 2.5D);
            resident.setNoGravity(true);
            helper.assertTrue(destinationLevel.addFreshEntity(resident), "The seamless fixture could not spawn the destination entity");
        }
        fixture.stand(source, BEFORE);
        fixture.negotiate();
    }

    private void step() {
        try {
            fixture.pump();
            if (!advance()) {
                helper.assertTrue(--remaining > 0, "Seamless attempt " + attempt.label + " timed out in " + phase + "; travel " + fixture.travel());
            }
            if (!result.isDone()) {
                helper.runAfterDelay(1, this::step);
            }
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private boolean advance() throws Exception {
        switch (phase) {
            case PREPARE -> {
                TravelMessage.TravelBegin latest = arm();
                if (latest == null || !arrivalDelivered()) {
                    return false;
                }
                helper.assertTrue(latest.seamless() && latest.resident(), attempt.label + " armed a far route without a resident level");
                if (attempt.expect == Expect.ACCEPT_LEVEL_CHANGE && !residentPaired()) {
                    return false;
                }
                begin = latest;
                claimed = latest;
                barrier = 1L;
                phase = Phase.CROSS;
                return true;
            }
            case CROSS -> {
                arrange();
                fixture.forget();
                if (attempt.expect == Expect.ACCEPT_LEVEL_CHANGE) {
                    events = new RecordingEvents(runtime.seamlessEvents());
                    runtime.seamlessEvents(events);
                }
                if (attempt.expect == Expect.ACCEPT_SAME_LEVEL) {
                    Vec3 arrival = arrival();
                    broken = BlockPos.containing(arrival.x, arrival.y - 1.0D, arrival.z);
                    fixture.sendFromNetwork(crossing(), List.of(
                        new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, broken, Direction.UP, 1),
                        new ServerboundMovePlayerPacket.Pos(arrival, true, false)));
                } else {
                    fixture.send(crossing());
                }
                phase = Phase.RESULT;
                remaining = STAGE_TICKS;
                return true;
            }
            case RESULT -> {
                return attempt.expect == Expect.REJECT ? rejected() : accepted();
            }
        }
        return false;
    }

    private void arrange() {
        switch (attempt.label) {
            case "outside_aperture" -> fixture.shift(5.0D, 0.0D);
            case "no_access" -> source.setOutgoingTraversalsEnabled(false);
            case "same_level_accept" -> fixture.player().setGameMode(GameType.CREATIVE);
            case "no_cost" -> {
                denying = new DenyingCost(fixture.player().getUUID());
                costs = runtime.costs().register(new MinecraftTravelCosts.Registration(denying, "seamless-cost-test", "Wormholes test", 0, () -> true));
            }
            default -> {
            }
        }
    }

    private TravelMessage.TravelCross crossing() {
        ServerPlayer player = fixture.player();
        double eye = player.getEyeHeight();
        Vec3 feet = player.position();
        double plane = source.getOrigin().z();
        double claimed = "wrong_direction".equals(attempt.label) ? feet.z + 0.15D : plane + AFTER;
        return new TravelMessage.TravelCross(begin.token(), begin.generation(), barrier,
            new TravelMessage.TravelPose(feet.x, feet.y, claimed, player.getYRot(), player.getXRot()), new Vec3d(feet.x, feet.y + eye, feet.z),
            new Vec3d(feet.x, feet.y + eye, claimed));
    }

    private Vec3 arrival() {
        ServerPlayer player = fixture.player();
        Vec3 feet = player.position();
        Vec3d previous = new Vec3d(feet.x, feet.y + player.getEyeHeight(), feet.z);
        Vec3d origin = source.getOrigin();
        Vec3d normal = source.getFrame().getNormal().toVector();
        boolean front = (previous.x() - origin.x()) * normal.x() + (previous.y() - origin.y()) * normal.y()
            + (previous.z() - origin.z()) * normal.z() > 0.0D;
        Vec3d claimed = new Vec3d(feet.x, feet.y, origin.z() + AFTER);
        Vec3d look = Angles.direction(player.getYRot(), player.getXRot());
        Vec3d out = new PlaneCrossing(source.getFrame().view(front), origin, claimed, new Vec3d(0, 0, 0), look, front)
            .outPoint(destination.getFrame(), destination.getOrigin());
        return new Vec3(out.x(), out.y(), out.z());
    }

    private TravelMessage.TravelBegin arm() {
        for (TravelMessage message : fixture.travel()) {
            if (message instanceof TravelMessage.TravelBegin armed && armed.sourcePortal().equals(source.getId())) {
                known = armed;
            } else if (message instanceof TravelMessage.TravelCancel cancel && known != null && cancel.token().equals(known.token())
                && (claimed == null || !claimed.token().equals(cancel.token()))) {
                known = null;
            }
        }
        return known;
    }

    private boolean arrivalDelivered() {
        RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
        Vec3 arrival = arrival();
        return route != null && route.opened() && route.stream().delivered(ChunkPos.pack((int) Math.floor(arrival.x) >> 4,
            (int) Math.floor(arrival.z) >> 4));
    }

    private boolean residentPaired() {
        RemoteRoute route = runtime.remoteRoutes().route(fixture.player().getUUID(), source.getId());
        return route != null && route.paired().contains(resident.getId());
    }

    private boolean rejected() throws Exception {
        if (fixture.last(TravelMessage.TravelCancel.class) == null || !fixture.vanillaContains(ClientboundPlayerPositionPacket.class)) {
            return false;
        }
        helper.assertTrue(fixture.last(TravelMessage.TravelAccept.class) == null, attempt.label + " was accepted");
        helper.assertTrue(fixture.player().level() == helper.getLevel(), attempt.label + " moved the player out of the source level");
        helper.assertTrue(fixture.acknowledge(), attempt.label + " sent no vanilla correction");
        restore();
        pass();
        return true;
    }

    private boolean accepted() throws Exception {
        TravelMessage.TravelAccept accept = fixture.last(TravelMessage.TravelAccept.class);
        if (accept == null) {
            helper.assertTrue(fixture.last(TravelMessage.TravelCancel.class) == null, attempt.label + " was rejected: " + fixture.travel());
            return false;
        }
        ServerPlayer player = fixture.player();
        boolean levelChange = attempt.expect == Expect.ACCEPT_LEVEL_CHANGE;
        helper.assertTrue(accept.dimensionChanged() == levelChange, attempt.label + " reported dimensionChanged " + accept.dimensionChanged());
        helper.assertTrue(accept.levelHandle() == begin.levelHandle(), attempt.label + " accepted into handle " + accept.levelHandle());
        helper.assertTrue(player.level() == destinationLevel, attempt.label + " left the player in " + player.level().dimension());
        helper.assertTrue(!fixture.vanillaContains(ClientboundRespawnPacket.class), attempt.label + " sent a respawn packet");
        helper.assertTrue(!fixture.vanillaContains(ClientboundPlayerPositionPacket.class), attempt.label + " sent a teleport position packet");
        helper.assertTrue(player.position().distanceTo(new Vec3(accept.pose().x(), accept.pose().y(), accept.pose().z())) < 1.0E-3D,
            attempt.label + " server pose " + player.position() + " differs from the accepted pose " + accept.pose());
        helper.assertTrue(!runtime.seamlessMoving(player), attempt.label + " left the player marked as moving");
        if (levelChange) {
            TravelMessage.RemoteLevelOpen reopened = fixture.last(TravelMessage.RemoteLevelOpen.class);
            helper.assertTrue(reopened != null && reopened.levelHandle() == accept.levelHandle(), attempt.label + " did not reopen the handle for the return route: " + reopened + " after " + fixture.travel().size()
                + " messages, accept handle " + accept.levelHandle());
            helper.assertTrue(reopened.center().x() == ((int) Math.floor(source.getOrigin().x()) >> 4)
                && reopened.center().z() == ((int) Math.floor(source.getOrigin().z()) >> 4), attempt.label + " centred the return route at "
                + reopened.center() + " instead of the source portal");
            runtime.seamlessEvents(events.delegate);
            helper.assertTrue(events.watched > 0, attempt.label + " fired no chunk watch for the adopted destination window");
            helper.assertTrue(events.unwatched > 0, attempt.label + " fired no chunk unwatch for the departed origin view");
            helper.assertTrue(events.tracked.contains(resident.getId()), attempt.label + " fired no start-tracking for the adopted destination entity");
            events = null;
        }
        if (broken != null) {
            helper.assertTrue(destinationLevel.getBlockState(broken).isAir(), attempt.label
                + " handled the block break sent after the crossing before the crossing itself");
            player.setGameMode(GameType.SURVIVAL);
            broken = null;
        }
        pass();
        return true;
    }

    private void restore() throws Exception {
        source.setOutgoingTraversalsEnabled(true);
        if (costs != null) {
            costs.close();
            costs = null;
        }
    }

    private void pass() throws Exception {
        passed.add(attempt.label);
        if (attempt.index + 1 >= attempts.size()) {
            LOGGER.info("WORMHOLES_GAME_TEST_PASS seamless_validation {}", passed);
            finish(null);
            return;
        }
        next(attempt.index + 1);
    }

    private void finish(Throwable failure) {
        try {
            if (events != null) {
                runtime.seamlessEvents(events.delegate);
            }
            if (resident != null) {
                resident.discard();
            }
            if (costs != null) {
                costs.close();
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

    private enum Phase {
        PREPARE, CROSS, RESULT
    }

    private enum Expect {
        ACCEPT_LEVEL_CHANGE, ACCEPT_SAME_LEVEL, REJECT
    }

    private static final class Attempt {
        private final String label;
        private final Expect expect;
        private int index;

        private Attempt(String label, Expect expect) {
            this.label = label;
            this.expect = expect;
        }
    }

    private static final class RecordingEvents implements SeamlessMove.Events {
        private final SeamlessMove.Events delegate;
        private final List<Integer> tracked = new ArrayList<>();
        private int watched;
        private int unwatched;

        private RecordingEvents(SeamlessMove.Events delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean allowLevelChange(ServerPlayer player, ServerLevel destination) {
            return delegate.allowLevelChange(player, destination);
        }

        @Override
        public void levelChanged(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
            delegate.levelChanged(player, origin, destination);
        }

        @Override
        public void chunkWatched(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
            watched++;
            delegate.chunkWatched(player, level, chunk);
        }

        @Override
        public void chunkUnwatched(SeamlessMove.ChunkLeave leave) {
            unwatched++;
            delegate.chunkUnwatched(leave);
        }

        @Override
        public void entityTracked(ServerPlayer player, Entity entity) {
            tracked.add(entity.getId());
            delegate.entityTracked(player, entity);
        }

        @Override
        public void entityUntracked(ServerPlayer player, Entity entity) {
            delegate.entityUntracked(player, entity);
        }
    }

    private static final class DenyingCost implements MinecraftTraversalCostProvider {
        private final UUID traveler;

        private DenyingCost(UUID traveler) {
            this.traveler = traveler;
        }

        @Override
        public TraversalQuote quote(MinecraftTraversalContext context) {
            return traveler.equals(context.travelerId()) ? TraversalQuote.denied("Seamless test denial") : TraversalQuote.pass();
        }

        @Override
        public TraversalReservation reserve(MinecraftTraversalContext context, TraversalQuote quote) {
            return TraversalReservation.reserved(TraversalReceipt.of("seamless-cost"));
        }

        @Override
        public void commit(TraversalReceipt receipt) {
        }

        @Override
        public void refund(TraversalReceipt receipt, TraversalRefundReason reason) {
        }
    }
}
