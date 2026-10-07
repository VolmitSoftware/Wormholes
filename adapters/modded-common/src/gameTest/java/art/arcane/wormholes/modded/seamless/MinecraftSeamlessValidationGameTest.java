package art.arcane.wormholes.modded.seamless;

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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
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
    private static final int COOLDOWN_TICKS = 10;
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
    private long barrier;
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
        attempts.add(new Attempt("cooldown", Expect.NO_PREPARATION));
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
        fixture.forget();
        begin = null;
        phase = attempt.expect == Expect.NO_PREPARATION ? Phase.OBSERVE : Phase.PREPARE;
        remaining = attempt.expect == Expect.NO_PREPARATION ? COOLDOWN_TICKS : STAGE_TICKS;
    }

    private void open(boolean nether) throws Exception {
        ServerLevel level = helper.getLevel();
        fixture = SeamlessGameFixture.connect(runtime, level, nether ? "seamless-nether" : "seamless-reject");
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, nether ? 4 : 12));
        source = fixture.portal(level, base);
        destinationLevel = nether ? runtime.server().getLevel(Level.NETHER) : level;
        helper.assertTrue(destinationLevel != null, "The seamless fixture needs a nether level");
        BlockPos target = nether ? new BlockPos(base.getX(), 70, base.getZ()) : base.offset(400, 0, 0);
        destination = fixture.portal(destinationLevel, target);
        helper.assertTrue(fixture.link(source, destination), "Seamless fixture did not link portals");
        fixture.stand(source, BEFORE);
        fixture.negotiate();
    }

    private void step() {
        try {
            fixture.pump();
            if (!advance()) {
                helper.assertTrue(--remaining > 0 || phase == Phase.OBSERVE, "Seamless attempt " + attempt.label + " timed out in "
                    + phase + "; travel " + fixture.travel());
                if (phase == Phase.OBSERVE && remaining <= 0) {
                    pass();
                    return;
                }
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
                TravelMessage.TravelBegin latest = fixture.last(TravelMessage.TravelBegin.class);
                TravelMessage.TravelEnd end = fixture.last(TravelMessage.TravelEnd.class);
                if (latest == null || !latest.seamless() || end == null || !end.token().equals(latest.token())) {
                    return false;
                }
                helper.assertTrue(latest.resident(), attempt.label + " prepared a far route without a resident level");
                begin = latest;
                barrier = end.contentRevision();
                fixture.send(new TravelMessage.TravelReady(latest.token(), latest.generation(), barrier));
                phase = Phase.CROSS;
                return true;
            }
            case CROSS -> {
                arrange();
                fixture.forget();
                fixture.send(crossing());
                phase = Phase.RESULT;
                remaining = STAGE_TICKS;
                return true;
            }
            case RESULT -> {
                return attempt.expect == Expect.REJECT ? rejected() : accepted();
            }
            case OBSERVE -> {
                TravelMessage.TravelBegin latest = fixture.last(TravelMessage.TravelBegin.class);
                helper.assertTrue(latest == null, "A seamless preparation was offered during the arrival cooldown");
                return false;
            }
        }
        return false;
    }

    private void arrange() {
        switch (attempt.label) {
            case "outside_aperture" -> fixture.shift(5.0D, 0.0D);
            case "no_access" -> source.setOutgoingTraversalsEnabled(false);
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
        PREPARE, CROSS, RESULT, OBSERVE
    }

    private enum Expect {
        ACCEPT_LEVEL_CHANGE, ACCEPT_SAME_LEVEL, REJECT, NO_PREPARATION
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
