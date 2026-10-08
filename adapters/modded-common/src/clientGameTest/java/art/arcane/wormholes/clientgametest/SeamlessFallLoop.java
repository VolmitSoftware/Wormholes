package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

final class SeamlessFallLoop {
    static final BlockPos PLAYER_FLOOR = new BlockPos(100, 80, 100);
    static final BlockPos ENTITY_FLOOR = new BlockPos(112, 80, 100);
    static final int SHAFT = 20;
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int ARM_TIMEOUT_TICKS = 400;
    private static final int LOOP_TICKS = 240;
    private static final int SETTLE_TICKS = 20;
    private static final int MIN_CROSSINGS = 15;
    private static final double TERMINAL_SPEED = 3.5D;
    private static final double MAX_FALL_SPEED = 3.95D;
    private static final double JUMP = SHAFT * 0.5D;
    private static final double STEP_TOLERANCE = 1.0E-3D;
    private static final double FLOOR_PLANE = ENTITY_FLOOR.getY() + 0.5D;
    private static final double CEILING_PLANE = ENTITY_FLOOR.getY() + SHAFT + 0.5D;
    private static final double PLANE_REACH = 4.25D;
    private static final double PLANE_SLACK = 0.25D;
    private static final double HITCH = 0.5D;
    private static final double FALL_OUT_DEPTH = 6.0D;

    private SeamlessFallLoop() {
    }

    static void run(SeamlessClient client, SeamlessServer server, String label) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        Loop loop = server.buildFallLoop();
        List<UUID> fallers = loop.fallers();
        client.waitForChunksDownload();
        List<Sample> player = new ArrayList<>(LOOP_TICKS);
        List<List<Observation>> observed = new ArrayList<>(fallers.size());
        for (int index = 0; index < fallers.size(); index++) {
            observed.add(new ArrayList<>(LOOP_TICKS));
        }
        try (TickStepper stepper = client.lockstep()) {
            await(stepper, SeamlessScenario::ready, label + ": the fall loop portals did not arm");
            for (int tick = 0; tick < SETTLE_TICKS; tick++) {
                stepper.step(minecraft -> Boolean.TRUE);
            }
            await(stepper, minecraft -> visible(minecraft, fallers), label + ": the fall loop entities were not visible");
            client.runOnClient(minecraft -> TravelTap.reset());
            for (int tick = 0; tick < LOOP_TICKS; tick++) {
                Snapshot snapshot = stepper.step(minecraft -> snapshot(minecraft, fallers));
                player.add(snapshot.player());
                for (int index = 0; index < fallers.size(); index++) {
                    observed.get(index).add(snapshot.entities().get(index));
                }
            }
        }
        List<String> failures = new ArrayList<>();
        assertPlayer(label, player, failures);
        for (int index = 0; index < fallers.size(); index++) {
            assertEntity(label + "-entity-" + index, observed.get(index), failures);
        }
        client.restoreDefaultGameOptions();
        server.removeFallLoop(loop);
        SeamlessScenario.assertTrue(failures.isEmpty(), label + ": " + failures);
    }

    static Loop build(MinecraftServer server, ServerPlayer player) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        List<UUID> portals = new ArrayList<>(4);
        portals.addAll(shaft(runtime, level, player, PLAYER_FLOOR, true));
        portals.addAll(shaft(runtime, level, player, ENTITY_FLOOR, false));
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(level, PLAYER_FLOOR.getX() + 1.5D, PLAYER_FLOOR.getY() + SHAFT - 6.0D, PLAYER_FLOOR.getZ() + 1.5D, Set.of(), 0.0F, 30.0F,
            false);
        player.setDeltaMovement(Vec3.ZERO);
        List<UUID> fallers = new ArrayList<>(3);
        double top = ENTITY_FLOOR.getY() + SHAFT - 4.0D;
        ItemEntity item = new ItemEntity(level, ENTITY_FLOOR.getX() + 0.5D, top, ENTITY_FLOOR.getZ() + 0.5D, new ItemStack(Items.DIAMOND));
        item.setDeltaMovement(Vec3.ZERO);
        item.setNeverPickUp();
        item.setUnlimitedLifetime();
        level.addFreshEntity(item);
        fallers.add(item.getUUID());
        ArmorStand stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        stand.snapTo(ENTITY_FLOOR.getX() + 2.5D, top, ENTITY_FLOOR.getZ() + 2.5D, 0.0F, 0.0F);
        level.addFreshEntity(stand);
        fallers.add(stand.getUUID());
        Entity pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        pig.snapTo(ENTITY_FLOOR.getX() + 0.5D, top - 6.0D, ENTITY_FLOOR.getZ() + 2.5D, 0.0F, 0.0F);
        level.addFreshEntity(pig);
        fallers.add(pig.getUUID());
        return new Loop(portals, fallers);
    }

    static void remove(MinecraftServer server, ServerPlayer player, Loop loop) {
        WormholesModRuntime runtime = runtime(server);
        for (UUID portal : loop.portals()) {
            runtime.portals().remove(player, portal);
        }
    }

    private static List<UUID> shaft(WormholesModRuntime runtime, ServerLevel level, ServerPlayer player, BlockPos floor, boolean builtFacingTheRoom) {
        for (int x = -2; x <= 4; x++) {
            for (int z = -2; z <= 4; z++) {
                for (int y = -12; y <= SHAFT + 2; y++) {
                    boolean wall = x == -2 || x == 4 || z == -2 || z == 4;
                    level.setBlockAndUpdate(floor.offset(x, y, z), wall ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
        List<BlockPos> bottom = new ArrayList<>(9);
        List<BlockPos> top = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                bottom.add(floor.offset(x, 0, z));
                top.add(floor.offset(x, SHAFT, z));
            }
        }
        MinecraftPortal up = runtime.portals().create(player.getUUID(), level, bottom, PortalType.PORTAL, new Vec3(0, -1, 0));
        MinecraftPortal down = runtime.portals().create(player.getUUID(), level, top, PortalType.PORTAL, new Vec3(0, builtFacingTheRoom ? 1 : -1, 0));
        SeamlessScenario.assertTrue(up != null && down != null, "fall loop portal creation rejected at " + floor);
        if (builtFacingTheRoom) {
            SeamlessScenario.assertTrue(runtime.portals().update(player, down.getId(), portal -> portal.setFrame(portal.getFrame().flipNormal())),
                "ceiling portal face flip rejected");
        }
        SeamlessScenario.assertTrue(runtime.portals().link(player, up.getId(), down.getId()), "floor portal link rejected");
        SeamlessScenario.assertTrue(runtime.portals().link(player, down.getId(), up.getId()), "ceiling portal link rejected");
        return List.of(up.getId(), down.getId());
    }

    private static void await(TickStepper stepper, Predicate<Minecraft> condition, String failure) {
        for (int tick = 0; tick <= ARM_TIMEOUT_TICKS; tick++) {
            if (stepper.step(condition::test)) {
                return;
            }
        }
        throw new AssertionError(failure + " within " + ARM_TIMEOUT_TICKS + " ticks");
    }

    private static Snapshot snapshot(Minecraft minecraft, List<UUID> fallers) {
        List<Observation> entities = new ArrayList<>(fallers.size());
        for (UUID id : fallers) {
            entities.add(observe(minecraft, id));
        }
        return new Snapshot(new Sample(minecraft.player.position(), minecraft.player.getDeltaMovement().y), entities);
    }

    private static boolean visible(Minecraft minecraft, List<UUID> fallers) {
        for (UUID id : fallers) {
            if (observe(minecraft, id) == null) {
                return false;
            }
        }
        return true;
    }

    private static Observation observe(Minecraft minecraft, UUID id) {
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.getUUID().equals(id)) {
                return new Observation(entity.position(), entity.tickCount);
            }
        }
        return null;
    }

    private static void assertPlayer(String label, List<Sample> samples, List<String> failures) {
        int crossings = 0;
        double fastest = 0.0D;
        double lowest = Double.POSITIVE_INFINITY;
        for (int index = 1; index < samples.size(); index++) {
            Sample before = samples.get(index - 1);
            Sample after = samples.get(index);
            double rise = after.position().y - before.position().y;
            if (rise > JUMP) {
                crossings++;
            }
            fastest = Math.max(fastest, -after.velocity());
            lowest = Math.min(lowest, after.position().y);
        }
        List<TravelTap.Frame> frames = TravelTap.frames();
        int snaps = 0;
        String worst = "";
        double worstGap = 0.0D;
        for (int index = 1; index < frames.size(); index++) {
            TravelTap.Frame before = frames.get(index - 1);
            TravelTap.Frame after = frames.get(index);
            double rise = after.camera().y - before.camera().y;
            Vec3 expected = rise > JUMP ? before.camera().add(0.0D, SHAFT, 0.0D) : before.camera();
            double step = Math.max(before.tickSpeed(), after.tickSpeed()) * (after.clock() - before.clock());
            double gap = expected.distanceTo(after.camera());
            if (gap > step + STEP_TOLERANCE + 0.05D) {
                snaps++;
                if (gap > worstGap) {
                    worstGap = gap;
                    worst = "frame " + index + " moved " + String.format("%.3f", gap) + " (step " + String.format("%.3f", step) + ")";
                }
            }
        }
        LOGGER.info("[{}] player loop: {} crossings in {} ticks, fastest fall {} blocks/tick, lowest y {}, {} frame snaps {}; {}", label, crossings,
            samples.size(), String.format("%.3f", fastest), String.format("%.2f", lowest), snaps, worst, TravelTap.events());
        if (crossings < MIN_CROSSINGS) {
            failures.add("player crossed only " + crossings + " times");
        }
        if (fastest < TERMINAL_SPEED) {
            failures.add("player only reached " + String.format("%.3f", fastest) + " blocks/tick");
        }
        if (fastest > MAX_FALL_SPEED) {
            failures.add("player fell at " + String.format("%.3f", fastest) + " blocks/tick, faster than terminal velocity");
        }
        if (lowest < PLAYER_FLOOR.getY() - FALL_OUT_DEPTH) {
            failures.add("player fell out of the loop to y " + String.format("%.2f", lowest));
        }
        if (TravelTap.respawns() > 0 || TravelTap.positions() > 0 || TravelTap.accepts() > 0) {
            failures.add("player loop used respawns " + TravelTap.respawns() + " positions " + TravelTap.positions() + " teleport acks " + TravelTap.accepts());
        }
        if (snaps > 0) {
            failures.add("player camera snapped " + snaps + " times, worst " + worst);
        }
    }

    private static void assertEntity(String label, List<Observation> observations, List<String> failures) {
        int crossings = 0;
        int backwards = 0;
        int missing = 0;
        int early = 0;
        int hitches = 0;
        double previousStep = Double.NaN;
        double fastest = 0.0D;
        double lowest = Double.POSITIVE_INFINITY;
        List<String> breaks = new ArrayList<>();
        Observation last = observations.getFirst();
        for (int index = 1; index < observations.size(); index++) {
            Observation next = observations.get(index);
            if (last == null || next == null) {
                missing++;
                previousStep = Double.NaN;
                last = next;
                breaks.add("tick " + index + " missing");
                continue;
            }
            int ticks = next.tick() - last.tick();
            if (ticks <= 0) {
                continue;
            }
            Vec3 before = last.position();
            Vec3 after = next.position();
            last = next;
            double rise = after.y - before.y;
            double step = (rise > JUMP ? rise - SHAFT : rise) / ticks;
            if (!Double.isNaN(previousStep) && Math.abs(step - previousStep) > HITCH) {
                hitches++;
                breaks.add("tick " + index + " stepped " + String.format("%.3f", step) + " after " + String.format("%.3f", previousStep));
            }
            previousStep = step;
            if (rise > JUMP) {
                crossings++;
                if (before.y - FLOOR_PLANE > PLANE_REACH * ticks || before.y < FLOOR_PLANE - PLANE_SLACK
                    || CEILING_PLANE - after.y > PLANE_REACH * ticks || after.y > CEILING_PLANE + PLANE_SLACK) {
                    early++;
                    breaks.add("tick " + index + " crossed from " + String.format("%.2f", before.y) + " to " + String.format("%.2f", after.y));
                }
            } else if (rise > 0.05D) {
                backwards++;
                breaks.add("tick " + index + " rose " + String.format("%.3f", rise) + " to " + String.format("%.2f", after.y));
            } else {
                fastest = Math.max(fastest, -step);
            }
            lowest = Math.min(lowest, after.y);
        }
        LOGGER.info("[{}] observed loop: {} crossings, fastest {} blocks/tick, {} upward drifts, {} missing ticks, lowest y {} {}", label, crossings,
            String.format("%.3f", fastest), backwards, missing, String.format("%.2f", lowest), breaks);
        if (crossings < MIN_CROSSINGS / 2) {
            failures.add(label + " crossed only " + crossings + " times");
        }
        if (backwards > 0 || missing > 0) {
            failures.add(label + " moved discontinuously: " + backwards + " upward drifts, " + missing + " missing ticks");
        }
        if (hitches > 0) {
            failures.add(label + " stalled or jumped " + hitches + " times");
        }
        if (early > 0) {
            failures.add(label + " crossed away from the portal planes " + early + " times");
        }
        if (lowest < ENTITY_FLOOR.getY() - 4.0D) {
            failures.add(label + " fell out of the loop to y " + String.format("%.2f", lowest));
        }
    }

    record Loop(List<UUID> portals, List<UUID> fallers) {
    }

    private record Sample(Vec3 position, double velocity) {
    }

    private record Observation(Vec3 position, int tick) {
    }

    private record Snapshot(Sample player, List<Observation> entities) {
    }
}
