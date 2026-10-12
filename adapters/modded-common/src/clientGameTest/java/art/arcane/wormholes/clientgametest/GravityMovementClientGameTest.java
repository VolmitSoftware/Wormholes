package art.arcane.wormholes.clientgametest;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.google.gson.GsonBuilder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

public final class GravityMovementClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int PREPARE_TICKS = 600;
    private static final int SLAB_RADIUS = 24;
    private static final double POSITION_TOLERANCE = 1.0E-4D;
    private static final double VELOCITY_TOLERANCE = 1.0E-5D;
    private static final Controls RELEASED = new Controls(false, false, false, false, false);
    private static final Controls JUMP = new Controls(false, false, true, false, false);
    private static final Controls DESCEND = new Controls(false, false, false, true, false);
    private static final Controls FORWARD = new Controls(true, false, false, false, false);
    private static final Controls RIGHT = new Controls(false, true, false, false, false);
    private static final Controls SPRINT = new Controls(true, false, false, false, true);
    private static final List<GravityFrame> FRAMES = List.of(GravityFrame.WORLD,
        new GravityFrame(AxisPermutation.of(Face.E, Face.D, Face.N)),
        new GravityFrame(AxisPermutation.of(Face.U, Face.W, Face.S)),
        new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S)),
        new GravityFrame(AxisPermutation.of(Face.E, Face.S, Face.D)),
        new GravityFrame(AxisPermutation.of(Face.E, Face.N, Face.U)));

    private GravityMovementClientGameTest() {
    }

    static void run(SeamlessClient client, Server server, Consumer<Controls> input) {
        SeamlessScenario.join(client);
        List<Trace> traces = new ArrayList<>(8);
        List<Pod> pods = server.compute(nativeServer -> build(nativeServer.overworld()));
        try {
            client.runOnClient(minecraft -> {
                hidden(minecraft);
                minecraft.options.autoJump().set(false);
                minecraft.options.bobView().set(false);
                minecraft.options.fovEffectScale().set(0.0D);
                minecraft.options.framerateLimit().set(120);
            });
            Trace baseline = null;
            for (Pod pod : pods) {
                Trace trace = runCase(client, server, input, pod, CameraType.FIRST_PERSON, baseline, traces);
                if (baseline == null) {
                    baseline = trace;
                }
            }
            runCase(client, server, input, pods.getFirst(), CameraType.THIRD_PERSON_BACK, baseline, traces);
            runCase(client, server, input, pods.get(3), CameraType.THIRD_PERSON_BACK, baseline, traces);
            LOGGER.info("WORMHOLES_GRAVITY_MOVEMENT_PASS: six cardinal gravity directions, WORLD trajectory baseline, jump/held jump, creative flight input/toggles, hover, plane movement, drag, sprinting, landing and rear third-person repeats");
        } finally {
            input.accept(RELEASED);
            try {
                save(client, traces);
            } finally {
                client.restoreDefaultGameOptions();
                server.run(nativeServer -> cleanup(nativeServer, server.player(), pods));
            }
        }
    }

    private static Trace runCase(SeamlessClient client, Server server, Consumer<Controls> input, Pod pod,
                                 CameraType camera, Trace baseline, List<Trace> traces) {
        input.accept(RELEASED);
        prepare(client, server, pod, 0.0D, false, camera);
        Trace trace = new Trace(pod.gravity().down(), camera, new ArrayList<>(400));
        traces.add(trace);
        int frames = client.computeOnClient(minecraft -> TravelTap.frameIndex());
        LOGGER.info("Gravity movement begins: down={}, camera={}", trace.down(), camera);
        try (TickStepper stepper = client.lockstep()) {
            phase(client, server, input, stepper, pod, trace, baseline, "stationary", RELEASED, 12);
            phase(client, server, input, stepper, pod, trace, baseline, "jump-press", JUMP, 1);
            phase(client, server, input, stepper, pod, trace, baseline, "jump-flight", RELEASED, 25);
            jump(trace, "jump-press", "jump-flight");
            phase(client, server, input, stepper, pod, trace, baseline, "held-jump", JUMP, 28);
            phase(client, server, input, stepper, pod, trace, baseline, "held-jump-release", RELEASED, 12);
            heldJump(trace);
            toggle(client, server, input, stepper, pod, trace, baseline, "takeoff");
            assertTrue(last(trace).flying(), diagnostic(trace, "Double jump did not enable creative flight"));
            phase(client, server, input, stepper, pod, trace, baseline, "ascent", JUMP, 16);
            vertical(trace, "ascent", true);
            phase(client, server, input, stepper, pod, trace, baseline, "ascent-hover", RELEASED, 16);
            hover(trace, "ascent-hover");
            phase(client, server, input, stepper, pod, trace, baseline, "forward", FORWARD, 8);
            plane(trace, "forward", false);
            phase(client, server, input, stepper, pod, trace, baseline, "forward-drag", RELEASED, 12);
            drag(trace, "forward-drag");
            phase(client, server, input, stepper, pod, trace, baseline, "strafe", RIGHT, 8);
            plane(trace, "strafe", true);
            phase(client, server, input, stepper, pod, trace, baseline, "strafe-drag", RELEASED, 12);
            drag(trace, "strafe-drag");
            phase(client, server, input, stepper, pod, trace, baseline, "sprint", SPRINT, 8);
            sprint(trace);
            phase(client, server, input, stepper, pod, trace, baseline, "sprint-drag", RELEASED, 12);
            drag(trace, "sprint-drag");
            phase(client, server, input, stepper, pod, trace, baseline, "descent", DESCEND, 8);
            vertical(trace, "descent", false);
            phase(client, server, input, stepper, pod, trace, baseline, "descent-hover", RELEASED, 16);
            hover(trace, "descent-hover");
            toggle(client, server, input, stepper, pod, trace, baseline, "flight-off");
            assertTrue(!last(trace).flying(), diagnostic(trace, "Double jump did not disable creative flight"));
            phase(client, server, input, stepper, pod, trace, baseline, "fall-and-land", RELEASED, 40);
            landed(trace);
        } finally {
            input.accept(RELEASED);
        }
        prepare(client, server, pod, 3.0D, true, camera);
        try (TickStepper stepper = client.lockstep()) {
            phase(client, server, input, stepper, pod, trace, baseline, "flight-landing", DESCEND, 24);
            phase(client, server, input, stepper, pod, trace, baseline, "landing-rest", RELEASED, 8);
            landed(trace);
        } finally {
            input.accept(RELEASED);
        }
        client.runOnClient(minecraft -> {
            hidden(minecraft);
            assertTrue(TravelTap.frameIndex() > frames, diagnostic(trace, "Native render frames did not advance"));
        });
        LOGGER.info("Gravity movement complete: down={}, camera={}, samples={}", trace.down(), camera, trace.samples().size());
        return trace;
    }

    private static void phase(SeamlessClient client, Server server, Consumer<Controls> input, TickStepper stepper,
                              Pod pod, Trace trace, Trace baseline, String name, Controls controls, int ticks) {
        input.accept(controls);
        for (int tick = 0; tick < ticks; tick++) {
            int localTick = tick;
            Sample sample = stepper.step(minecraft -> {
                hidden(minecraft);
                assertTrue(pod.gravity().equals(MinecraftGravity.frame(minecraft.player)), "Gravity changed during " + name);
                assertTrue(Math.abs(minecraft.player.getScale() - 1.0F) < 1.0E-6F, "Gravity movement scale must remain 100%");
                return sample(minecraft.player, pod, name, localTick);
            });
            trace.samples().add(sample);
            assertTrue(!sample.suffocating(), diagnostic(trace, "Gravity floor suffocated the player"));
            assertTrue(Math.abs(sample.yaw()) < 0.1F && Math.abs(sample.pitch()) < 0.1F,
                diagnostic(trace, "Input changed the gravity-local look"));
            if (baseline != null) {
                compare(server, pod, trace, baseline.samples().get(trace.samples().size() - 1), sample);
            }
            if (name.equals("stationary")) {
                assertTrue(sample.grounded() && !sample.flying() && sample.position().length() < POSITION_TOLERANCE,
                    diagnostic(trace, "Stationary gravity floor pushed or ungrounded the player"));
            }
        }
        input.accept(RELEASED);
        Sample authoritative = server.compute(nativeServer -> {
            nativeServer.packetProcessor().processQueuedPackets();
            assertTrue(MinecraftGravity.frame(server.player()).equals(pod.gravity()), "Server gravity changed during " + name);
            return sample(server.player(), pod, name, ticks - 1);
        });
        Sample local = last(trace);
        assertTrue(local.flying() == authoritative.flying() && local.grounded() == authoritative.grounded()
            && local.position().distanceTo(authoritative.position()) < 0.15D,
            diagnostic(trace, "Client/server phase disagreement: server=" + authoritative));
        LOGGER.info("Gravity movement phase: down={}, camera={}, phase={}, local={}, server={}", trace.down(), trace.camera(), name, local, authoritative);
    }

    private static void toggle(SeamlessClient client, Server server, Consumer<Controls> input, TickStepper stepper,
                               Pod pod, Trace trace, Trace baseline, String name) {
        phase(client, server, input, stepper, pod, trace, baseline, name + "-first", JUMP, 1);
        phase(client, server, input, stepper, pod, trace, baseline, name + "-release", RELEASED, 2);
        phase(client, server, input, stepper, pod, trace, baseline, name + "-second", JUMP, 1);
        phase(client, server, input, stepper, pod, trace, baseline, name + "-settle", RELEASED, 1);
    }

    private static void compare(Server server, Pod pod, Trace trace, Sample expected, Sample actual) {
        if (!expected.phase().equals(actual.phase()) || expected.tick() != actual.tick()
            || expected.position().distanceTo(actual.position()) > POSITION_TOLERANCE
            || expected.velocity().distanceTo(actual.velocity()) > VELOCITY_TOLERANCE
            || expected.grounded() != actual.grounded() || expected.flying() != actual.flying() || expected.sprinting() != actual.sprinting()) {
            Sample authoritative = server.compute(nativeServer -> sample(server.player(), pod, actual.phase(), actual.tick()));
            throw new AssertionError(diagnostic(trace, "WORLD baseline mismatch: expected=" + expected + "; actual=" + actual + "; server=" + authoritative));
        }
    }

    private static void jump(Trace trace, String pressed, String airborne) {
        List<Sample> samples = inPhase(trace, airborne);
        assertTrue(inPhase(trace, pressed).getFirst().velocity().y > 0.2D,
            diagnostic(trace, "Jump did not impart a normal upward impulse"));
        double apex = 0.0D;
        for (Sample sample : samples) {
            apex = Math.max(apex, sample.position().y);
            assertTrue(!sample.flying(), diagnostic(trace, "A single jump enabled creative flight"));
        }
        assertTrue(apex > 1.0D && apex < 1.6D && samples.getLast().grounded(),
            diagnostic(trace, "Jump height or landing differs from ordinary gravity"));
    }

    private static void heldJump(Trace trace) {
        int takeoffs = 0;
        boolean rising = false;
        for (Sample sample : inPhase(trace, "held-jump")) {
            boolean next = sample.velocity().y > 0.2D;
            if (next && !rising) {
                takeoffs++;
            }
            rising = next;
            assertTrue(!sample.flying(), diagnostic(trace, "Held jump toggled creative flight"));
        }
        assertTrue(takeoffs >= 2, diagnostic(trace, "Held jump did not repeat after landing"));
        landed(trace);
    }

    private static void vertical(Trace trace, String phase, boolean up) {
        List<Sample> samples = inPhase(trace, phase);
        Sample last = samples.getLast();
        assertTrue(last.flying() && (up ? last.velocity().y > 0.1D : last.velocity().y < -0.1D)
            && (up ? last.position().y > samples.getFirst().position().y + 0.5D
                : last.position().y < samples.getFirst().position().y - 0.5D),
            diagnostic(trace, phase + " did not follow the gravity-relative vertical axis"));
    }

    private static void hover(Trace trace, String phase) {
        List<Sample> samples = inPhase(trace, phase);
        Sample before = samples.get(samples.size() - 5);
        Sample after = samples.getLast();
        assertTrue(after.flying() && !after.grounded() && Math.abs(after.position().y - before.position().y) < 0.01D,
            diagnostic(trace, "Released vertical input did not settle into hover"));
    }

    private static void plane(Trace trace, String phase, boolean strafe) {
        List<Sample> samples = inPhase(trace, phase);
        Vec3 movement = samples.getLast().position().subtract(samples.getFirst().position());
        assertTrue(Math.abs(movement.y) < 0.01D && (strafe ? movement.x < -0.1D : movement.z > 0.1D),
            diagnostic(trace, phase + " did not stay in the local locomotion plane"));
    }

    private static void drag(Trace trace, String phase) {
        List<Sample> samples = inPhase(trace, phase);
        double previous = samples.getFirst().velocity().horizontalDistance();
        for (Sample sample : samples) {
            double speed = sample.velocity().horizontalDistance();
            assertTrue(speed <= previous + VELOCITY_TOLERANCE, diagnostic(trace, "Released movement accelerated during " + phase));
            previous = speed;
        }
        assertTrue(previous < samples.getFirst().velocity().horizontalDistance() * 0.5D,
            diagnostic(trace, "Creative movement did not retain ordinary release drag"));
    }

    private static void sprint(Trace trace) {
        Sample sprint = inPhase(trace, "sprint").getLast();
        Sample forward = inPhase(trace, "forward").getLast();
        assertTrue(sprint.sprinting() && sprint.velocity().z > forward.velocity().z * 1.3D,
            diagnostic(trace, "Flight sprint did not increase forward speed"));
    }

    private static void landed(Trace trace) {
        Sample sample = last(trace);
        assertTrue(sample.grounded() && !sample.flying() && Math.abs(sample.position().y) < POSITION_TOLERANCE,
            diagnostic(trace, "Player did not land on the gravity floor with flight disabled"));
    }

    private static List<Sample> inPhase(Trace trace, String name) {
        List<Sample> samples = new ArrayList<>();
        for (Sample sample : trace.samples()) {
            if (sample.phase().equals(name)) {
                samples.add(sample);
            }
        }
        assertTrue(!samples.isEmpty(), "Missing movement phase " + name);
        return samples;
    }

    private static Sample last(Trace trace) {
        return trace.samples().getLast();
    }

    private static String diagnostic(Trace trace, String message) {
        return "Gravity movement down=" + trace.down() + ", camera=" + trace.camera() + ": " + message + "; sample=" + last(trace);
    }

    private static Sample sample(LivingEntity player, Pod pod, String phase, int tick) {
        boolean flying = player instanceof Player actor && actor.getAbilities().flying;
        return new Sample(phase, tick, MinecraftGravity.local(pod.gravity(), player.position().subtract(pod.feet())),
            MinecraftGravity.local(pod.gravity(), player.getDeltaMovement()), player.onGround(), flying, player.isSprinting(),
            player.isInWall(), player.getYRot(), player.getXRot());
    }

    private static void prepare(SeamlessClient client, Server server, Pod pod, double height, boolean flying, CameraType camera) {
        client.runOnClient(minecraft -> minecraft.options.setCameraType(camera));
        server.run(nativeServer -> place(nativeServer, server.player(), pod, 4.0D, true));
        client.waitFor(minecraft -> pod.gravity().equals(MinecraftGravity.frame(minecraft.player))
            && minecraft.player.getAbilities().flying
            && minecraft.player.position().distanceTo(pod.atHeight(4.0D)) < 0.05D, PREPARE_TICKS);
        client.waitForChunksDownload();
        client.waitForChunksRender();
        BlockPos support = BlockPos.containing(pod.atHeight(-0.5D));
        client.waitFor(minecraft -> minecraft.level.hasChunkAt(support) && minecraft.level.getBlockState(support).is(Blocks.STONE), PREPARE_TICKS);
        server.run(nativeServer -> place(nativeServer, server.player(), pod, height, flying));
        client.waitFor(minecraft -> pod.gravity().equals(MinecraftGravity.frame(minecraft.player))
            && minecraft.player.getAbilities().flying == flying && minecraft.player.position().distanceTo(pod.atHeight(height)) < 0.05D
            && (flying || minecraft.player.onGround()), PREPARE_TICKS);
        client.lookAt(0.0F, 0.0F);
        client.waitTicks(12);
        client.runOnClient(minecraft -> {
            hidden(minecraft);
            assertTrue(minecraft.player.position().distanceTo(pod.atHeight(height)) < POSITION_TOLERANCE,
                "Gravity movement setup drifted: " + sample(minecraft.player, pod, "prepare", 12));
        });
    }

    private static void place(MinecraftServer server, ServerPlayer player, Pod pod, double height, boolean flying) {
        player.setGameMode(GameType.CREATIVE);
        MinecraftScaleAccess.scaleAttribute().reset(player);
        MinecraftGravity.set(player, pod.gravity());
        player.getAbilities().setFlyingSpeed(0.05F);
        player.getAbilities().setWalkingSpeed(0.1F);
        player.getAbilities().flying = flying;
        player.setSprinting(false);
        player.onUpdateAbilities();
        Vec3 position = pod.atHeight(height);
        player.setDeltaMovement(Vec3.ZERO);
        player.teleportTo(server.overworld(), position.x, position.y, position.z, Set.of(), 0.0F, 0.0F, false);
    }

    private static List<Pod> build(ServerLevel level) {
        List<Pod> pods = new ArrayList<>(FRAMES.size());
        for (int index = 0; index < FRAMES.size(); index++) {
            Pod pod = new Pod(FRAMES.get(index), new Vec3(400.0D + index * 64.0D, 100.0D, 400.0D));
            pods.add(pod);
            slab(level, pod, true);
        }
        return pods;
    }

    private static void slab(ServerLevel level, Pod pod, boolean stone) {
        for (int x = -SLAB_RADIUS; x <= SLAB_RADIUS; x++) {
            for (int z = -SLAB_RADIUS; z <= SLAB_RADIUS; z++) {
                Vec3 center = pod.origin().add(MinecraftGravity.world(pod.gravity(), new Vec3(x + 0.5D, -0.5D, z + 0.5D)));
                level.setBlockAndUpdate(BlockPos.containing(center), stone ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void cleanup(MinecraftServer server, ServerPlayer player, List<Pod> pods) {
        MinecraftGravity.set(player, GravityFrame.WORLD);
        MinecraftScaleAccess.scaleAttribute().reset(player);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(server.overworld(), 400.5D, 104.0D, 400.5D, Set.of(), 0.0F, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
        for (Pod pod : pods) {
            slab(server.overworld(), pod, false);
        }
    }

    private static void save(SeamlessClient client, List<Trace> traces) {
        Path directory = client.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots/gravity-movement"));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("movement.json"), new GsonBuilder().setPrettyPrinting().create().toJson(traces));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not retain gravity movement evidence at " + directory, failure);
        }
    }

    private static void hidden(Minecraft minecraft) {
        assertTrue(Boolean.parseBoolean(System.getProperty("automator.hidden", "true")) && NativeTestWindow.hiddenAndUnfocused(minecraft)
            && NativeTestWindow.mouseReleased(minecraft), "Gravity movement requires hidden unfocused GPU rendering with the mouse released");
    }

    interface Server {
        void run(Consumer<MinecraftServer> action);

        <T> T compute(Function<MinecraftServer, T> action);

        ServerPlayer player();
    }

    record Controls(boolean forward, boolean right, boolean jump, boolean descend, boolean sprint) {
    }

    private record Pod(GravityFrame gravity, Vec3 origin) {
        Vec3 feet() {
            return origin.add(MinecraftGravity.world(gravity, new Vec3(0.5D, 0.0D, 0.5D)));
        }

        Vec3 atHeight(double height) {
            return feet().add(MinecraftGravity.world(gravity, new Vec3(0.0D, height, 0.0D)));
        }
    }

    private record Sample(String phase, int tick, Vec3 position, Vec3 velocity, boolean grounded, boolean flying,
                          boolean sprinting, boolean suffocating, float yaw, float pitch) {
    }

    private record Trace(Face down, CameraType camera, List<Sample> samples) {
    }
}
