package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SeamlessTurningTap {
    private static Scope scope;

    private SeamlessTurningTap() {
    }

    static void open(Minecraft minecraft) {
        close();
        scope = new Scope(Objects.requireNonNull(minecraft.player, "turning player"),
            Objects.requireNonNull(minecraft.level, "turning level"));
        NativeSeamlessInput.begin(minecraft);
    }

    static void close() {
        NativeSeamlessInput.clear();
        scope = null;
    }

    public static void beforeTickCrossing(Minecraft minecraft) {
        observeAfterReturn("after-player-tick", minecraft);
        beginEvaluation(minecraft, Evaluation.TICK);
    }

    public static void beforeRender(Minecraft minecraft) {
        observeAfterReturn("before-render-update", minecraft);
        if (scope == null) {
            NativeSeamlessInput.frame(minecraft);
            return;
        }
        if (beginEvaluation(minecraft, Evaluation.RENDER)) {
            input(minecraft);
        }
    }

    static boolean acceptsInput(Minecraft minecraft) {
        Scope active = scope;
        return active == null || minecraft.player == active.player && minecraft.level == active.source
            && !active.accepted;
    }

    static boolean inputAllowed() {
        return scope != null && scope.input;
    }

    static long interval() {
        return Objects.requireNonNull(scope, "turning capture scope").interval;
    }

    public static void turned(Entity entity, float before) {
        Scope active = scope;
        if (active == null || !active.input || entity != active.player || entity.level() != active.source) {
            return;
        }
        active.turn = new Turn(active.interval, TravelTap.frameIndex(), active.player, active.source, before, entity.getYRot(),
            Mth.wrapDegrees(entity.getYRot() - before));
    }

    public static Attempt entering(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm,
                                   float partial, Vec3 previous, Vec3 eye) {
        Scope active = scope;
        if (active == null) {
            return null;
        }
        PoseSample beforeInput = sample("before-cross-input", player, minecraft.level);
        input(minecraft);
        ClientLevel target = arm.resident() ? WormholesClient.instance().seamlessTravel().residents().level(arm.levelHandle()) : minecraft.level;
        return new Attempt(active.interval, active.evaluation, TravelTap.frameIndex(), player, minecraft.level, target, active.turn, player.getYRot(), arm, partial, previous, eye,
            active.evaluationStart, beforeInput, sample("original-cross-entry", player, minecraft.level));
    }

    public static void returned(Attempt attempt, Minecraft minecraft, Vec3 result) {
        Scope active = scope;
        if (active != null && attempt != null) {
            active.crossings.add(new Crossing(attempt, result != null, minecraft.player, minecraft.level, sample("original-cross-return", minecraft.player, minecraft.level)));
            if (result != null) {
                active.accepted = true;
                NativeSeamlessInput.clear();
            }
        }
    }

    public static void gameTick() {
        Scope active = scope;
        if (active != null && active.accepted && active.firstRender == null) {
            observeAfterReturn("before-player-tick", Minecraft.getInstance());
        }
    }

    static void rendered(Minecraft minecraft) {
        Scope active = scope;
        if (active != null && active.accepted && active.firstRender == null) {
            active.firstRender = sample("first-render-after-return", minecraft.player, minecraft.level);
        }
    }

    static String observations() {
        Scope active = Objects.requireNonNull(scope, "turning capture scope");
        List<String> crossings = new ArrayList<>(active.crossings.size());
        for (Crossing crossing : active.crossings) {
            Attempt attempt = crossing.attempt();
            Turn turn = attempt.turn();
            crossings.add("evaluation=" + attempt.evaluation() + ", id=" + attempt.interval() + ", frame=" + attempt.frame()
                + ", source=" + System.identityHashCode(attempt.source()) + ", target=" + System.identityHashCode(attempt.target())
                + ", accepted=" + crossing.accepted() + ", turn=" + (turn == null ? null : turn.before() + " -> " + turn.after() + " (delta " + turn.delta() + ")")
                + ", evaluationStart=" + attempt.evaluationStart() + ", beforeInput=" + attempt.beforeInput()
                + ", entry=" + attempt.entry() + ", returned=" + crossing.returned());
        }
        return "crossings=" + crossings + ", afterReturn=" + active.afterReturn + ", firstRender=" + active.firstRender;
    }

    static Crossing assertCrossing(int frame, ClientLevel source, int player, String label) {
        Scope active = Objects.requireNonNull(scope, "turning capture scope");
        SeamlessScenario.assertTrue(active.crossings.size() == 1, label + ": expected one original native crossing, recorded " + active.crossings.size());
        Crossing crossing = active.crossings.getFirst();
        Attempt attempt = crossing.attempt();
        Turn turn = attempt.turn();
        SeamlessScenario.assertTrue(crossing.accepted(), label + ": original native crossing rejected the turning input");
        SeamlessScenario.assertTrue(attempt.interval() > 0L && attempt.evaluation() != null && attempt.frame() == frame, label + ": original native crossing did not produce the recorded crossing frame");
        SeamlessScenario.assertTrue(attempt.player() == active.player && System.identityHashCode(attempt.player()) == player
            && attempt.source() == active.source && attempt.source() == source,
            label + ": original native crossing used another source player or level");
        SeamlessScenario.assertTrue(crossing.player() == attempt.player() && crossing.level() == attempt.target(),
            label + ": original native crossing returned another player or destination level");
        SeamlessScenario.assertTrue(turn != null && turn.interval() == attempt.interval() && turn.frame() == attempt.frame() && turn.player() == attempt.player()
            && turn.level() == attempt.source(), label + ": no native mouse turn occurred in the original crossing frame");
        SeamlessScenario.assertTrue(Math.abs(turn.delta()) > 1.0F && Float.floatToIntBits(turn.after()) == Float.floatToIntBits(attempt.yaw()),
            label + ": original native crossing did not use its greater-than-one-degree signed mouse turn");
        PoseSample entry = attempt.entry();
        PoseSample returned = crossing.returned();
        SeamlessScenario.assertTrue(entry != null && entry.frame() == attempt.frame() && entry.player() == player
            && entry.level() == System.identityHashCode(source) && Float.floatToIntBits(entry.yaw()) == Float.floatToIntBits(attempt.yaw())
            && Float.isFinite(entry.pitch()), label + ": original native crossing entry pose does not match its turn, frame, player or source");
        SeamlessScenario.assertTrue(returned != null && returned.frame() == attempt.frame() && returned.player() == player
            && returned.level() == System.identityHashCode(attempt.target()),
            label + ": original native crossing return pose does not match its frame, player or destination");
        return crossing;
    }

    private static boolean beginEvaluation(Minecraft minecraft, Evaluation evaluation) {
        Scope active = scope;
        if (active == null) {
            return false;
        }
        if (minecraft.player != active.player || minecraft.level != active.source) {
            NativeSeamlessInput.clear();
            return false;
        }
        active.turn = null;
        active.interval++;
        active.evaluation = evaluation;
        active.evaluationFrame = TravelTap.frameIndex();
        active.evaluationStart = sample("evaluation-start", minecraft.player, minecraft.level);
        return true;
    }

    private static void observeAfterReturn(String stage, Minecraft minecraft) {
        Scope active = scope;
        if (active != null && active.accepted && active.firstRender == null) {
            active.afterReturn.add(sample(stage, minecraft.player, minecraft.level));
        }
    }

    private static PoseSample sample(String stage, LocalPlayer player, ClientLevel level) {
        return new PoseSample(stage, TravelTap.frameIndex(), TravelTap.gameTickIndex(), System.identityHashCode(player),
            System.identityHashCode(level), player == null ? Float.NaN : player.getYRot(),
            player == null ? Float.NaN : player.yHeadRot, player == null ? Float.NaN : player.yRotO,
            player == null ? Float.NaN : player.yHeadRotO, player == null ? Float.NaN : player.getXRot());
    }

    private static void input(Minecraft minecraft) {
        Scope active = scope;
        if (active == null || minecraft.player != active.player || minecraft.level != active.source) {
            return;
        }
        if (active.evaluation == null || active.evaluationFrame != TravelTap.frameIndex()) {
            return;
        }
        active.input = true;
        try {
            NativeSeamlessInput.frame(minecraft);
        } finally {
            active.input = false;
        }
    }

    public enum Evaluation {
        TICK,
        RENDER
    }

    public record Turn(long interval, int frame, LocalPlayer player, ClientLevel level, float before, float after, float delta) {
    }

    public record Attempt(long interval, Evaluation evaluation, int frame, LocalPlayer player, ClientLevel source, ClientLevel target, Turn turn, float yaw,
                          TravelMessage.TravelBegin arm, float partial, Vec3 previous, Vec3 eye,
                          PoseSample evaluationStart, PoseSample beforeInput, PoseSample entry) {
    }

    public record PoseSample(String stage, int frame, long tick, int player, int level, float yaw, float headYaw,
                             float previousYaw, float previousHeadYaw, float pitch) {
    }

    record Crossing(Attempt attempt, boolean accepted, LocalPlayer player, ClientLevel level, PoseSample returned) {
    }

    private static final class Scope {
        private final LocalPlayer player;
        private final ClientLevel source;
        private final List<Crossing> crossings = new ArrayList<>(1);
        private final List<PoseSample> afterReturn = new ArrayList<>(4);
        private PoseSample evaluationStart;
        private PoseSample firstRender;
        private Turn turn;
        private long interval;
        private Evaluation evaluation;
        private int evaluationFrame = -1;
        private boolean input;
        private boolean accepted;

        private Scope(LocalPlayer player, ClientLevel source) {
            this.player = player;
            this.source = source;
        }
    }
}
