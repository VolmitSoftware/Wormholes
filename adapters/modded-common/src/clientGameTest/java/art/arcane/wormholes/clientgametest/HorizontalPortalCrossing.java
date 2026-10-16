package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.portal.PortalType;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

final class HorizontalPortalCrossing {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos FLOOR = new BlockPos(40, 70, 40);
    private static final BlockPos CEILING = new BlockPos(40, 70, 40);
    private static final String DIRECTORY = "horizontal-portal-crossing";
    private static final String FLOOR_PHASE = "floor-enter";
    private static final String CEILING_PHASE = "ceiling-enter";
    private static final String NONE = "none";
    private static final Vec3 RIM = new Vec3(FLOOR.getX() + 1.5D, FLOOR.getY() + 1.0D, FLOOR.getZ() - 1.5D);
    private static final Vec3 UNDER_CEILING = new Vec3(CEILING.getX() + 1.5D, CEILING.getY() - 2.0D, CEILING.getZ() + 1.5D);
    private static final float WALK_YAW = 0.0F;
    private static final float WALK_PITCH = 30.0F;
    private static final float JUMP_PITCH = -60.0F;
    private static final int WALK_TICKS = 14;
    private static final int PLACE_TIMEOUT_TICKS = 400;
    private static final int ARM_TIMEOUT_TICKS = 600;
    private static final int ENTER_TIMEOUT_TICKS = 200;
    private static final int LAND_TIMEOUT_TICKS = 200;
    private static final double AGREEMENT = 1.0D;
    private static final double PLACE_TOLERANCE = 1.0E-3D;
    private static final int SETTLE_TICKS = 5;

    private HorizontalPortalCrossing() {
    }

    static void run(SeamlessClient client, SeamlessServer server, String label) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        UUID playerId = client.computeOnClient(minecraft -> minecraft.player.getUUID());
        Path directory = client.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots").resolve(DIRECTORY));
        createDirectories(directory);
        JsonObject evidence = new JsonObject();
        evidence.addProperty("label", label);
        JsonArray phases = new JsonArray();
        evidence.add("phases", phases);
        List<String> failures = new ArrayList<>();
        Scene scene = server.computeOnServer(minecraftServer -> build(minecraftServer, playerId));
        evidence.addProperty("scene", scene.describe());
        LOGGER.info("[{}] horizontal portal pair {}", label, scene.describe());
        try {
            boolean floorEntered = floorEnter(client, server, playerId, scene, label, phases, failures);
            if (floorEntered) {
                ceilingEnter(client, server, playerId, scene, label, phases, failures);
            } else {
                JsonObject skipped = new JsonObject();
                phases.add(skipped);
                skipped.addProperty("phase", CEILING_PHASE);
                fail(skipped, failures, CEILING_PHASE + ": the Nether ceiling portal was never reached through the floor portal, so the return "
                    + "jump could not be tried");
            }
        } finally {
            client.releaseForward();
            client.releaseJump();
            server.computeOnServer(minecraftServer -> remove(minecraftServer, playerId, scene));
            client.restoreDefaultGameOptions();
            JsonArray failed = new JsonArray();
            for (String failure : failures) {
                failed.add(failure);
            }
            evidence.add("failures", failed);
            write(directory.resolve(label + ".json"), evidence);
        }
        LOGGER.info("WORMHOLES_HORIZONTAL_PORTAL_CROSSING_RESULT fixture={} failures={} evidence={}", label, failures.size(),
            directory.resolve(label + ".json"));
        SeamlessScenario.assertTrue(failures.isEmpty(), label + ": " + String.join("; ", failures));
        LOGGER.info("WORMHOLES_HORIZONTAL_PORTAL_CROSSING_PASS fixture={}", label);
    }

    private static boolean floorEnter(SeamlessClient client, SeamlessServer server, UUID playerId, Scene scene, String label, JsonArray phases,
                                      List<String> failures) {
        JsonObject phase = new JsonObject();
        phases.add(phase);
        phase.addProperty("phase", FLOOR_PHASE);
        server.computeOnServer(minecraftServer -> place(minecraftServer, playerId, Level.OVERWORLD, RIM, WALK_YAW, WALK_PITCH));
        if (ticksUntil(client, minecraft -> at(minecraft, Level.OVERWORLD, RIM), PLACE_TIMEOUT_TICKS) < 0) {
            return fail(phase, failures, FLOOR_PHASE + ": the player never stood on the floor portal rim at " + vector(RIM) + ": "
                + client.computeOnClient(HorizontalPortalCrossing::describeClient));
        }
        client.waitForChunksDownload();
        client.waitTicks(SETTLE_TICKS);
        int armed = ticksUntil(client, minecraft -> armed(scene.floor()), ARM_TIMEOUT_TICKS);
        phase.addProperty("armedTicks", armed);
        phase.addProperty("travelBeforeWalk", client.computeOnClient(HorizontalPortalCrossing::travelState));
        if (armed < 0) {
            return fail(phase, failures, FLOOR_PHASE + ": the floor portal never armed a seamless route within " + ARM_TIMEOUT_TICKS + " ticks: "
                + client.computeOnClient(HorizontalPortalCrossing::describeClient));
        }
        screenshot(client, label + "-" + FLOOR_PHASE + "-rim");
        Walk walk = move(client, server, playerId, Level.NETHER, Motion.WALK, ENTER_TIMEOUT_TICKS);
        record(phase, walk);
        phase.addProperty("lowestClientFeetBeforeCrossing", walk.lowestClientFeet(Level.OVERWORLD));
        phase.addProperty("lowestServerFeetBeforeCrossing", walk.lowestServerFeet(Level.OVERWORLD));
        screenshot(client, label + "-" + FLOOR_PHASE + (walk.arrived() >= 0 ? "-arrived" : "-stuck"));
        LOGGER.info("[{}] {} walk: arrived at tick {}, {}", label, FLOOR_PHASE, walk.arrived(), walk.summary(scene.floorPlane()));
        if (walk.arrived() < 0) {
            return fail(phase, failures, FLOOR_PHASE + ": walked off the rim into the floor portal (plane y=" + format(scene.floorPlane())
                + ", solid block under the opening) but was still outside the Nether " + ENTER_TIMEOUT_TICKS + " ticks later; "
                + walk.summary(scene.floorPlane()));
        }
        boolean clean = seamless(phase, failures, FLOOR_PHASE, walk, scene.floorPlane());
        clean &= placed(client, server, playerId, phase, failures, FLOOR_PHASE, walk, Level.NETHER, scene.ceilingPlane(), false);
        if (clean) {
            phase.addProperty("passed", true);
            LOGGER.info("WORMHOLES_HORIZONTAL_PORTAL_CROSSING_PHASE_PASS {} {} crossed at tick {}", label, FLOOR_PHASE, walk.arrived());
        }
        return true;
    }

    private static void ceilingEnter(SeamlessClient client, SeamlessServer server, UUID playerId, Scene scene, String label, JsonArray phases,
                                     List<String> failures) {
        JsonObject phase = new JsonObject();
        phases.add(phase);
        phase.addProperty("phase", CEILING_PHASE);
        int landed = ticksUntil(client, minecraft -> grounded(minecraft, Level.NETHER), LAND_TIMEOUT_TICKS);
        phase.addProperty("landedTicks", landed);
        phase.addProperty("landing", client.computeOnClient(HorizontalPortalCrossing::describeClient));
        if (landed < 0) {
            fail(phase, failures, CEILING_PHASE + ": after crossing the player did not land on the Nether floor under the ceiling portal within "
                + LAND_TIMEOUT_TICKS + " ticks: " + client.computeOnClient(HorizontalPortalCrossing::describeClient));
            return;
        }
        server.computeOnServer(minecraftServer -> place(minecraftServer, playerId, Level.NETHER, UNDER_CEILING, WALK_YAW, JUMP_PITCH));
        if (ticksUntil(client, minecraft -> at(minecraft, Level.NETHER, UNDER_CEILING), PLACE_TIMEOUT_TICKS) < 0) {
            fail(phase, failures, CEILING_PHASE + ": the player never stood under the ceiling portal at " + vector(UNDER_CEILING) + ": "
                + client.computeOnClient(HorizontalPortalCrossing::describeClient));
            return;
        }
        client.waitTicks(SETTLE_TICKS);
        int armed = ticksUntil(client, minecraft -> armed(scene.ceiling()), ARM_TIMEOUT_TICKS);
        phase.addProperty("armedTicks", armed);
        phase.addProperty("travelBeforeJump", client.computeOnClient(HorizontalPortalCrossing::travelState));
        if (armed < 0) {
            fail(phase, failures, CEILING_PHASE + ": the ceiling portal never armed a seamless route back within " + ARM_TIMEOUT_TICKS + " ticks: "
                + client.computeOnClient(HorizontalPortalCrossing::describeClient));
            return;
        }
        screenshot(client, label + "-" + CEILING_PHASE + "-under");
        Walk jump = move(client, server, playerId, Level.OVERWORLD, Motion.JUMP, ENTER_TIMEOUT_TICKS);
        record(phase, jump);
        phase.addProperty("highestClientEyeBeforeCrossing", jump.highestClientEye(Level.NETHER));
        screenshot(client, label + "-" + CEILING_PHASE + (jump.arrived() >= 0 ? "-arrived" : "-stuck"));
        LOGGER.info("[{}] {} jump: arrived at tick {}, {}", label, CEILING_PHASE, jump.arrived(), jump.summary(scene.ceilingPlane()));
        if (jump.arrived() < 0) {
            fail(phase, failures, CEILING_PHASE + ": jumped into the ceiling portal (plane y=" + format(scene.ceilingPlane())
                + ", solid block above the opening) linked back to the floor portal (plane y=" + format(scene.floorPlane())
                + ", solid block under the opening) but was still outside the Overworld " + ENTER_TIMEOUT_TICKS + " ticks later; "
                + jump.summary(scene.ceilingPlane()));
            return;
        }
        boolean clean = seamless(phase, failures, CEILING_PHASE, jump, scene.ceilingPlane());
        clean &= placed(client, server, playerId, phase, failures, CEILING_PHASE, jump, Level.OVERWORLD, scene.floorPlane(), true);
        if (clean) {
            phase.addProperty("passed", true);
            LOGGER.info("WORMHOLES_HORIZONTAL_PORTAL_CROSSING_PHASE_PASS {} {} crossed at tick {}", label, CEILING_PHASE, jump.arrived());
        }
    }

    private static boolean seamless(JsonObject phase, List<String> failures, String name, Walk walk, double plane) {
        if (walk.respawns() > 0 || walk.positions() > 0 || walk.loading() || walk.replaced() || walk.rollbacks() > 0) {
            return fail(phase, failures, name + ": the crossing was not seamless: respawn packets " + walk.respawns() + ", position packets "
                + walk.positions() + ", loading screen " + walk.loading() + ", player replaced " + walk.replaced() + ", client crossing rollbacks "
                + walk.rollbacks() + "; " + walk.summary(plane));
        }
        return true;
    }

    private static boolean placed(SeamlessClient client, SeamlessServer server, UUID playerId, JsonObject phase, List<String> failures,
                                  String name, Walk walk, ResourceKey<Level> level, double plane, boolean upward) {
        ArrivalPlacementTap.Placement placement = walk.placement();
        ArrivalPlacementTap.Arrival arrival = walk.arrival();
        boolean clean = true;
        if (placement == null) {
            clean = fail(phase, failures, name + ": the server never placed the player in " + level.identifier() + " through its seamless move");
        } else {
            Body body = server.computeOnServer(minecraftServer -> serverBody(minecraftServer, playerId, level, placement.feet()));
            phase.addProperty("serverPlacement", body.describe(plane));
            String problem = body.problem(plane, upward);
            if (problem != null) {
                clean = fail(phase, failures, name + ": the server placed the player " + problem + ": " + body.describe(plane) + "; " + placement);
            }
        }
        if (arrival == null) {
            clean = fail(phase, failures, name + ": the client never applied a seamless crossing into " + level.identifier());
        } else {
            Body body = client.computeOnClient(minecraft -> clientBody(minecraft, level, arrival.feet()));
            phase.addProperty("clientArrival", body.describe(plane));
            String problem = body.problem(plane, upward);
            if (problem != null) {
                clean = fail(phase, failures, name + ": the client arrived " + problem + ": " + body.describe(plane) + "; " + arrival);
            }
        }
        if (placement != null && arrival != null && placement.feet().distanceTo(arrival.feet()) > AGREEMENT) {
            clean = fail(phase, failures, name + ": the client arrival " + vector(arrival.feet()) + " and the server placement "
                + vector(placement.feet()) + " differ by " + format(placement.feet().distanceTo(arrival.feet())) + " blocks");
        }
        return clean;
    }

    private static Scene build(MinecraftServer server, UUID playerId) {
        ServerPlayer player = player(server, playerId);
        ServerLevel overworld = server.overworld();
        ServerLevel nether = Objects.requireNonNull(server.getLevel(Level.NETHER), "the Nether level is not loaded");
        fill(overworld, FLOOR.offset(-6, -3, -6), 15, 3, 15, Blocks.STONE.defaultBlockState());
        fill(overworld, FLOOR.offset(-6, 0, -6), 15, 1, 15, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        fill(overworld, FLOOR.offset(-6, 1, -6), 15, 8, 15, Blocks.AIR.defaultBlockState());
        fill(overworld, FLOOR, 3, 1, 3, Blocks.AIR.defaultBlockState());
        fill(nether, CEILING.offset(-6, -4, -6), 15, 9, 15, Blocks.STONE.defaultBlockState());
        fill(nether, CEILING.offset(-4, -2, -4), 11, 2, 11, Blocks.AIR.defaultBlockState());
        fill(nether, CEILING.offset(-1, 0, -1), 5, 1, 5, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        fill(nether, CEILING, 3, 1, 3, Blocks.AIR.defaultBlockState());
        nether.setBlockAndUpdate(CEILING.offset(-3, -1, 1), Blocks.GLOWSTONE.defaultBlockState());
        WormholesModRuntime runtime = runtime(server);
        MinecraftPortal floor = runtime.portals().create(playerId, overworld, cells(FLOOR), PortalType.PORTAL, new Vec3(0.0D, -1.0D, 1.0D));
        MinecraftPortal ceiling = runtime.portals().create(playerId, nether, cells(CEILING), PortalType.PORTAL, new Vec3(0.0D, 1.0D, 1.0D));
        SeamlessScenario.assertTrue(floor != null && ceiling != null, "the floor or ceiling portal could not be created");
        SeamlessScenario.assertTrue(runtime.portals().link(player, floor.getId(), ceiling.getId()), "the floor portal link was rejected");
        runtime.nexus().pair(player, floor, ceiling, true);
        SeamlessScenario.assertTrue(floor.getId().equals(ceiling.getDestinationId()), "link and return did not link the ceiling portal back");
        player.setGameMode(GameType.CREATIVE);
        return new Scene(floor.getId(), ceiling.getId(), floor.getOrigin().y(), ceiling.getOrigin().y(),
            floor.getFrame().getNormal() + " / " + ceiling.getFrame().getNormal());
    }

    private static boolean remove(MinecraftServer server, UUID playerId, Scene scene) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return false;
        }
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        WormholesModRuntime runtime = runtime(server);
        runtime.portals().remove(player, scene.floor());
        runtime.portals().remove(player, scene.ceiling());
        return true;
    }

    private static boolean place(MinecraftServer server, UUID playerId, ResourceKey<Level> key, Vec3 position, float yaw, float pitch) {
        ServerPlayer player = player(server, playerId);
        ServerLevel level = Objects.requireNonNull(server.getLevel(key), "level " + key);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        player.teleportTo(level, position.x, position.y, position.z, Set.of(), yaw, pitch, false);
        player.setDeltaMovement(Vec3.ZERO);
        return true;
    }

    private static boolean freeze(MinecraftServer server, UUID playerId) {
        ServerPlayer player = player(server, playerId);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        return true;
    }

    private static Walk move(SeamlessClient client, SeamlessServer server, UUID playerId, ResourceKey<Level> target, Motion motion, int timeoutTicks) {
        client.runOnClient(minecraft -> {
            TravelTap.reset();
            ArrivalPlacementTap.reset();
        });
        int identity = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        String destination = target.identifier().toString();
        List<Sample> samples = new ArrayList<>(timeoutTicks + 1);
        samples.add(sample(client, server, playerId, 0));
        int arrived = -1;
        press(client, motion, true);
        try {
            for (int tick = 1; tick <= timeoutTicks && arrived < 0; tick++) {
                if (motion == Motion.WALK && tick == WALK_TICKS) {
                    press(client, motion, false);
                }
                client.waitTicks(1);
                Sample sample = sample(client, server, playerId, tick);
                samples.add(sample);
                if (sample.client().level().equals(destination) && sample.server().level().equals(destination)) {
                    arrived = tick;
                    press(client, motion, false);
                    if (motion == Motion.JUMP) {
                        server.computeOnServer(minecraftServer -> freeze(minecraftServer, playerId));
                    }
                }
            }
        } finally {
            press(client, motion, false);
        }
        boolean replaced = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player)) != identity;
        return new Walk(samples, arrived, TravelTap.respawns(), TravelTap.positions(), TravelTap.rollbacks(), TravelTap.loadingScreenShown(), replaced,
            ArrivalPlacementTap.placement(destination), ArrivalPlacementTap.arrival(destination), TravelTap.events());
    }

    private static void press(SeamlessClient client, Motion motion, boolean down) {
        switch (motion) {
            case WALK -> {
                if (down) {
                    client.holdForward();
                } else {
                    client.releaseForward();
                }
            }
            case JUMP -> {
                if (down) {
                    client.holdJump();
                } else {
                    client.releaseJump();
                }
            }
        }
    }

    private static void record(JsonObject phase, Walk walk) {
        phase.addProperty("arrivedTick", walk.arrived());
        phase.addProperty("respawnPackets", walk.respawns());
        phase.addProperty("positionPackets", walk.positions());
        phase.addProperty("clientRollbacks", walk.rollbacks());
        phase.addProperty("loadingScreen", walk.loading());
        phase.addProperty("playerReplaced", walk.replaced());
        phase.addProperty("serverMove", walk.placement() == null ? NONE : walk.placement().toString());
        phase.addProperty("clientCrossing", walk.arrival() == null ? NONE : walk.arrival().toString());
        JsonArray samples = new JsonArray();
        for (Sample sample : walk.samples()) {
            samples.add(sample.toString());
        }
        phase.add("samples", samples);
    }

    private static Sample sample(SeamlessClient client, SeamlessServer server, UUID playerId, int tick) {
        ClientSample local = client.computeOnClient(HorizontalPortalCrossing::clientSample);
        ServerSample remote = server.computeOnServer(minecraftServer -> serverSample(minecraftServer, playerId));
        return new Sample(tick, local, remote);
    }

    private static ClientSample clientSample(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            return new ClientSample("absent", Vec3.ZERO, 0.0D, false, 0.0D, false, TravelTap.positions());
        }
        ClientSeamlessTravel travel = WormholesClient.instance() == null ? null : WormholesClient.instance().seamlessTravel();
        return new ClientSample(minecraft.level.dimension().identifier().toString(), minecraft.player.position(), minecraft.player.getEyeY(),
            minecraft.player.onGround(), minecraft.player.getDeltaMovement().y, travel != null && travel.armed(), TravelTap.positions());
    }

    private static ServerSample serverSample(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return new ServerSample("absent", Vec3.ZERO, false, server.getTickCount());
        }
        return new ServerSample(player.level().dimension().identifier().toString(), player.position(), player.onGround(), server.getTickCount());
    }

    private static Body serverBody(MinecraftServer server, UUID playerId, ResourceKey<Level> key, Vec3 feet) {
        ServerPlayer player = player(server, playerId);
        ServerLevel level = Objects.requireNonNull(server.getLevel(key), "level " + key);
        return body(level, player, feet);
    }

    private static Body clientBody(Minecraft minecraft, ResourceKey<Level> key, Vec3 feet) {
        if (minecraft.player == null || minecraft.level == null || !minecraft.level.dimension().equals(key)) {
            return new Body(feet, Double.NaN, Double.NaN, false, "the client left " + key.identifier());
        }
        return body(minecraft.level, minecraft.player, feet);
    }

    private static Body body(Level level, Entity entity, Vec3 feet) {
        AABB bounds = entity.getDimensions(entity.getPose()).makeBoundingBox(feet);
        return new Body(feet, entity.getEyeHeight(), bounds.maxY - bounds.minY, level.noCollision(entity, bounds), null);
    }

    private static boolean armed(UUID source) {
        WormholesClient wormholes = WormholesClient.instance();
        return wormholes != null && wormholes.seamlessTravel().armed(source) && !wormholes.seamlessTravel().pending();
    }

    private static boolean at(Minecraft minecraft, ResourceKey<Level> level, Vec3 position) {
        return minecraft.player != null && minecraft.level != null && minecraft.level.dimension().equals(level)
            && minecraft.player.position().distanceTo(position) < PLACE_TOLERANCE;
    }

    private static boolean grounded(Minecraft minecraft, ResourceKey<Level> level) {
        return minecraft.player != null && minecraft.level != null && minecraft.level.dimension().equals(level) && minecraft.player.onGround();
    }

    private static String travelState(Minecraft minecraft) {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null) {
            return "no client";
        }
        ClientSeamlessTravel travel = wormholes.seamlessTravel();
        return "armed=" + travel.armed() + " pending=" + travel.pending() + " unprepared=" + travel.unprepared();
    }

    private static String describeClient(Minecraft minecraft) {
        return clientSample(minecraft) + " " + travelState(minecraft);
    }

    private static int ticksUntil(SeamlessClient client, Predicate<Minecraft> condition, int timeoutTicks) {
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            if (client.computeOnClient(condition::test)) {
                return tick;
            }
            client.waitTicks(1);
        }
        return -1;
    }

    private static ServerPlayer player(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        SeamlessScenario.assertTrue(player != null, "the horizontal portal fixture player " + playerId + " is not connected");
        return player;
    }

    private static List<BlockPos> cells(BlockPos minimum) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                cells.add(minimum.offset(x, 0, z));
            }
        }
        return cells;
    }

    private static void fill(ServerLevel level, BlockPos minimum, int sizeX, int sizeY, int sizeZ, BlockState state) {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    level.setBlockAndUpdate(minimum.offset(x, y, z), state);
                }
            }
        }
    }

    private static boolean fail(JsonObject phase, List<String> failures, String failure) {
        LOGGER.error("WORMHOLES_HORIZONTAL_PORTAL_CROSSING_PHASE_FAIL {}", failure);
        phase.addProperty("passed", false);
        phase.addProperty("failure", failure);
        failures.add(failure);
        return false;
    }

    private static void screenshot(SeamlessClient client, String name) {
        client.runOnClient(minecraft -> Screenshot.grab(minecraft.gameDirectory, DIRECTORY + "/" + name + ".png", ClientRenderAccess.mainTarget(minecraft), 1,
            message -> LOGGER.info("Horizontal portal crossing capture {}: {}", name, message.getString())));
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not create horizontal portal crossing evidence directory " + directory, failure);
        }
    }

    private static void write(Path file, JsonObject evidence) {
        try {
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write horizontal portal crossing evidence " + file, failure);
        }
    }

    private static String vector(Vec3 vector) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", vector.x, vector.y, vector.z);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private enum Motion {
        WALK,
        JUMP
    }

    private record Scene(UUID floor, UUID ceiling, double floorPlane, double ceilingPlane, String normals) {
        String describe() {
            return "floor portal " + floor + " in the Overworld plane y=" + String.format(Locale.ROOT, "%.3f", floorPlane) + ", ceiling portal "
                + ceiling + " in the Nether plane y=" + String.format(Locale.ROOT, "%.3f", ceilingPlane) + ", normals " + normals;
        }
    }

    private record Body(Vec3 feet, double eyeHeight, double height, boolean clear, String missing) {
        String problem(double plane, boolean upward) {
            if (missing != null) {
                return missing;
            }
            double eye = feet.y + eyeHeight;
            double trailing = upward ? feet.y : feet.y + height;
            if (upward ? eye <= plane : eye >= plane) {
                return "with the eye at y " + String.format(Locale.ROOT, "%.3f", eye) + " on the entry side of the arrival plane";
            }
            if (Math.abs(trailing - plane) > 1.0D) {
                return "with its " + (upward ? "feet" : "head") + " " + String.format(Locale.ROOT, "%.3f", trailing - plane)
                    + " blocks from the arrival plane instead of within one block";
            }
            return clear ? null : "inside solid blocks";
        }

        String describe(double plane) {
            return String.format(Locale.ROOT, "feet %.3f,%.3f,%.3f (%.3f from plane y %.3f), eye %.3f, head %.3f, clear %s%s", feet.x, feet.y, feet.z,
                feet.y - plane, plane, feet.y + eyeHeight, feet.y + height, clear, missing == null ? "" : ", " + missing);
        }
    }

    private record ClientSample(String level, Vec3 feet, double eye, boolean ground, double velocity, boolean armed, int positions) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "client %s %.3f,%.3f,%.3f eye %.3f ground %s vy %.3f armed %s positions %d", level, feet.x, feet.y, feet.z,
                eye, ground, velocity, armed, positions);
        }
    }

    private record ServerSample(String level, Vec3 feet, boolean ground, int tick) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "server %s %.3f,%.3f,%.3f ground %s tick %d", level, feet.x, feet.y, feet.z, ground, tick);
        }
    }

    private record Sample(int tick, ClientSample client, ServerSample server) {
        @Override
        public String toString() {
            return "t=" + tick + " " + client + " | " + server;
        }
    }

    private record Walk(List<Sample> samples, int arrived, int respawns, int positions, int rollbacks, boolean loading, boolean replaced,
                        ArrivalPlacementTap.Placement placement, ArrivalPlacementTap.Arrival arrival, String events) {
        double lowestClientFeet(ResourceKey<Level> level) {
            double lowest = Double.MAX_VALUE;
            String name = level.identifier().toString();
            for (Sample sample : samples) {
                if (sample.client().level().equals(name)) {
                    lowest = Math.min(lowest, sample.client().feet().y);
                }
            }
            return lowest;
        }

        double lowestServerFeet(ResourceKey<Level> level) {
            double lowest = Double.MAX_VALUE;
            String name = level.identifier().toString();
            for (Sample sample : samples) {
                if (sample.server().level().equals(name)) {
                    lowest = Math.min(lowest, sample.server().feet().y);
                }
            }
            return lowest;
        }

        double highestClientEye(ResourceKey<Level> level) {
            double highest = -Double.MAX_VALUE;
            String name = level.identifier().toString();
            for (Sample sample : samples) {
                if (sample.client().level().equals(name)) {
                    highest = Math.max(highest, sample.client().eye());
                }
            }
            return highest;
        }

        String summary(double plane) {
            boolean armed = false;
            for (Sample sample : samples) {
                armed |= sample.client().armed();
            }
            return String.format(Locale.ROOT, "plane y %.3f, client armed %s, respawn packets %d, position packets %d, client rollbacks %d, "
                    + "loading screen %s, player replaced %s, server move %s, client crossing %s, packets %s, first %s, last %s", plane, armed, respawns,
                positions, rollbacks, loading, replaced, placement == null ? NONE : placement, arrival == null ? NONE : arrival, events, samples.getFirst(),
                samples.getLast());
        }
    }
}
