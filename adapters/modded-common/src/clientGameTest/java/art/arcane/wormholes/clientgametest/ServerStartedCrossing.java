package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.mixin.SeamlessListenerAccess;
import art.arcane.wormholes.transit.OrientationPolicy;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
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
import java.util.UUID;

final class ServerStartedCrossing {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String DIRECTORY = "server-started-crossing";
    private static final String SERVER_CROSSING = "server";
    private static final BlockPos CROSS_SOURCE = new BlockPos(0, 70, 20);
    private static final BlockPos CROSS_DESTINATION = new BlockPos(0, 70, 220);
    private static final BlockPos SAME_SOURCE = new BlockPos(64, 70, 20);
    private static final BlockPos SAME_DESTINATION = new BlockPos(64, 70, 420);
    private static final int HOLD_TICKS = 3;
    private static final int CROSS_TIMEOUT_TICKS = 200;
    private static final int WALK_AFTER_TICKS = 10;
    private static final int SETTLE_TICKS = 20;
    private static final double AGREEMENT = 1.0D;
    private static final double SETTLED_AGREEMENT = 0.1D;
    private static final double SOURCE_RADIUS = 8.0D;

    private ServerStartedCrossing() {
    }

    static void run(SeamlessClient client, SeamlessServer server, String label) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        UUID playerId = client.computeOnClient(minecraft -> minecraft.player.getUUID());
        Path directory = client.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots").resolve(DIRECTORY));
        createDirectories(directory);
        JsonObject evidence = new JsonObject();
        evidence.addProperty("label", label);
        evidence.addProperty("holdTicks", HOLD_TICKS);
        JsonArray phases = new JsonArray();
        evidence.add("phases", phases);
        List<String> failures = new ArrayList<>();
        try {
            phase(client, server, playerId, "cross-dimension", spec(Level.NETHER, CROSS_SOURCE, CROSS_DESTINATION), phases, failures);
            phase(client, server, playerId, "same-dimension", spec(Level.OVERWORLD, SAME_SOURCE, SAME_DESTINATION), phases, failures);
        } finally {
            client.releaseForward();
            ServerStartedCrossingTap.reset();
            client.restoreDefaultGameOptions();
            JsonArray failed = new JsonArray();
            for (String failure : failures) {
                failed.add(failure);
            }
            evidence.add("failures", failed);
            write(directory.resolve(label + ".json"), evidence);
        }
        LOGGER.info("WORMHOLES_SERVER_STARTED_CROSSING_RESULT fixture={} failures={} evidence={}", label, failures.size(),
            directory.resolve(label + ".json"));
        SeamlessScenario.assertTrue(failures.isEmpty(), label + ": " + String.join("; ", failures));
        LOGGER.info("WORMHOLES_SERVER_STARTED_CROSSING_PASS fixture={}", label);
    }

    private static void phase(SeamlessClient client, SeamlessServer server, UUID playerId, String name, SeamlessScenario.RouteSpec spec,
                              JsonArray phases, List<String> failures) {
        JsonObject phase = new JsonObject();
        phases.add(phase);
        phase.addProperty("phase", name);
        SeamlessScenario.Route route = server.build(spec);
        try {
            server.computeOnServer(minecraftServer -> survival(minecraftServer, playerId));
            server.approach(route);
            SeamlessScenario.awaitPrepared(client);
            Walk walk = walk(client, server, playerId, route, spec.destinationLevel().identifier().toString());
            Vec3d origin = route.outbound().exit().sourceOrigin();
            Vec3 source = new Vec3(origin.x(), origin.y(), origin.z());
            record(phase, walk, source);
            LOGGER.info("[{}] server-started crossing: placed at tick {}, released {} held packets at tick {}, client crossed at tick {}, {}", name,
                walk.placed(), walk.released(), walk.releasedAt(), walk.arrived(), walk.summary(source));
            if (check(phase, failures, name, walk, source)) {
                phase.addProperty("passed", true);
                LOGGER.info("WORMHOLES_SERVER_STARTED_CROSSING_PHASE_PASS {} placed at tick {}", name, walk.placed());
            }
        } finally {
            client.releaseForward();
            server.computeOnServer(minecraftServer -> ServerStartedCrossingTap.release());
            ServerStartedCrossingTap.reset();
            server.remove(route);
        }
    }

    private static boolean check(JsonObject phase, List<String> failures, String name, Walk walk, Vec3 source) {
        if (walk.placed() < 0 || walk.placement() == null) {
            return fail(phase, failures, name + ": the server never started the crossing after the client claim was withheld; " + walk.summary(source));
        }
        boolean clean = true;
        if (walk.suppressed() == 0) {
            clean = fail(phase, failures, name + ": the client never tried to claim the crossing, so the crossing was not left to the server");
        }
        if (walk.arrival() == null || !SERVER_CROSSING.equals(walk.arrival().crossing())) {
            return fail(phase, failures, name + ": the client never applied the server-started crossing: " + (walk.arrival() == null ? "none"
                : walk.arrival()) + "; " + walk.summary(source));
        }
        if (walk.stale(source).isEmpty()) {
            clean = fail(phase, failures, name + ": no move the client sent from the source world reached the server after it started the crossing, "
                + "so no move was in flight; " + walk.summary(source));
        }
        String applied = walk.appliedAtSource(source);
        if (applied != null) {
            clean = fail(phase, failures, name + ": the server applied a move the client sent from the source world before it learned of the "
                + "crossing and put the player at source-world coordinates in the destination: " + applied);
        }
        String apart = walk.apart();
        if (apart != null) {
            clean = fail(phase, failures, name + ": the client and the server disagree in the destination by more than " + format(AGREEMENT)
                + " blocks: " + apart);
        }
        Sample last = walk.samples().getLast();
        if (!last.client().level().equals(last.server().level()) || last.client().feet().distanceTo(last.server().feet()) > SETTLED_AGREEMENT) {
            clean = fail(phase, failures, name + ": after the player stopped the client and the server still disagree by more than "
                + format(SETTLED_AGREEMENT) + " blocks: " + last);
        }
        if (walk.positions() > 0 || walk.respawns() > 0 || walk.accepts() > 0 || walk.rollbacks() > 0 || walk.loading() || walk.replaced()) {
            clean = fail(phase, failures, name + ": the crossing needed a correction: position packets " + walk.positions() + ", respawn packets "
                + walk.respawns() + ", teleport acceptances " + walk.accepts() + ", client rollbacks " + walk.rollbacks() + ", loading screen "
                + walk.loading() + ", player replaced " + walk.replaced());
        }
        if (walk.awaiting()) {
            clean = fail(phase, failures, name + ": the server still waits for a position from the client after the crossing settled");
        }
        return clean;
    }

    private static Walk walk(SeamlessClient client, SeamlessServer server, UUID playerId, SeamlessScenario.Route route, String destination) {
        client.runOnClient(minecraft -> {
            TravelTap.reset();
            ArrivalPlacementTap.reset();
        });
        ServerStartedCrossingTap.reset();
        ServerStartedCrossingTap.record(playerId);
        ServerStartedCrossingTap.suppressClaims(route.source());
        ServerStartedCrossingTap.holdNextMove(playerId);
        int identity = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        List<Sample> samples = new ArrayList<>(CROSS_TIMEOUT_TICKS + 1);
        samples.add(sample(client, server, playerId, 0, destination));
        int placed = -1;
        int releasedAt = -1;
        int arrived = -1;
        int stopped = -1;
        client.holdForward();
        try {
            for (int tick = 1; tick <= CROSS_TIMEOUT_TICKS; tick++) {
                client.waitTicks(1);
                if (placed < 0 && ServerStartedCrossingTap.holdStarted() >= 0) {
                    placed = tick;
                }
                if (placed >= 0 && releasedAt < 0 && tick >= placed + HOLD_TICKS) {
                    server.computeOnServer(minecraftServer -> ServerStartedCrossingTap.release());
                    ServerStartedCrossingTap.allowClaims();
                    releasedAt = tick;
                }
                Sample sample = sample(client, server, playerId, tick, destination);
                samples.add(sample);
                if (arrived < 0 && sample.client().crossed()) {
                    arrived = tick;
                }
                if (arrived >= 0 && stopped < 0 && tick >= arrived + WALK_AFTER_TICKS) {
                    client.releaseForward();
                    stopped = tick;
                }
                if (stopped >= 0 && tick >= stopped + SETTLE_TICKS) {
                    break;
                }
            }
        } finally {
            client.releaseForward();
            server.computeOnServer(minecraftServer -> ServerStartedCrossingTap.release());
            ServerStartedCrossingTap.allowClaims();
            ServerStartedCrossingTap.record(null);
        }
        boolean replaced = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player)) != identity;
        boolean awaiting = server.computeOnServer(minecraftServer -> awaiting(minecraftServer, playerId));
        return new Walk(samples, placed, releasedAt, arrived, ServerStartedCrossingTap.released(), ServerStartedCrossingTap.suppressedClaims(),
            ServerStartedCrossingTap.moves(), ServerStartedCrossingTap.movesBeforeHold(), TravelTap.respawns(), TravelTap.positions(),
            TravelTap.accepts(), TravelTap.rollbacks(), TravelTap.loadingScreenShown(), replaced, awaiting, ArrivalPlacementTap.placement(destination),
            client.computeOnClient(minecraft -> ArrivalPlacementTap.arrival(destination)), TravelTap.events());
    }

    private static Sample sample(SeamlessClient client, SeamlessServer server, UUID playerId, int tick, String destination) {
        ClientSample local = client.computeOnClient(minecraft -> clientSample(minecraft, destination));
        ServerSample remote = server.computeOnServer(minecraftServer -> serverSample(minecraftServer, playerId));
        return new Sample(tick, local, remote);
    }

    private static ClientSample clientSample(Minecraft minecraft, String destination) {
        if (minecraft.player == null || minecraft.level == null) {
            return new ClientSample("absent", Vec3.ZERO, false, false, false);
        }
        return new ClientSample(minecraft.level.dimension().identifier().toString(), minecraft.player.position(), minecraft.player.onGround(),
            minecraft.level.hasChunk(minecraft.player.getBlockX() >> 4, minecraft.player.getBlockZ() >> 4), ArrivalPlacementTap.arrival(destination) != null);
    }

    private static ServerSample serverSample(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return new ServerSample("absent", Vec3.ZERO, false, server.getTickCount());
        }
        return new ServerSample(player.level().dimension().identifier().toString(), player.position(), player.onGround(), server.getTickCount());
    }

    private static boolean survival(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        SeamlessScenario.assertTrue(player != null, "the server-started crossing fixture player " + playerId + " is not connected");
        player.setGameMode(GameType.SURVIVAL);
        return true;
    }

    private static boolean awaiting(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player != null && ((SeamlessListenerAccess) player.connection).wormholesAwaitingPosition() != null;
    }

    private static SeamlessScenario.RouteSpec spec(ResourceKey<Level> destination, BlockPos sourceMin, BlockPos destinationMin) {
        return new SeamlessScenario.RouteSpec(Level.OVERWORLD, sourceMin, destination, destinationMin, OrientationPolicy.FRAME, false);
    }

    private static void record(JsonObject phase, Walk walk, Vec3 source) {
        phase.addProperty("placedTick", walk.placed());
        phase.addProperty("releasedTick", walk.releasedAt());
        phase.addProperty("releasedPackets", walk.released());
        phase.addProperty("clientCrossedTick", walk.arrived());
        phase.addProperty("suppressedClaims", walk.suppressed());
        phase.addProperty("respawnPackets", walk.respawns());
        phase.addProperty("positionPackets", walk.positions());
        phase.addProperty("teleportAcceptances", walk.accepts());
        phase.addProperty("clientRollbacks", walk.rollbacks());
        phase.addProperty("loadingScreen", walk.loading());
        phase.addProperty("playerReplaced", walk.replaced());
        phase.addProperty("awaitingAfter", walk.awaiting());
        phase.addProperty("sourcePortal", vector(source));
        phase.addProperty("serverMove", walk.placement() == null ? "none" : walk.placement().toString());
        phase.addProperty("clientCrossing", walk.arrival() == null ? "none" : walk.arrival().toString());
        phase.addProperty("packets", walk.events());
        JsonArray stale = new JsonArray();
        for (ServerStartedCrossingTap.Move move : walk.stale(source)) {
            stale.add(move.toString());
        }
        phase.add("staleMoves", stale);
        JsonArray moves = new JsonArray();
        for (ServerStartedCrossingTap.Move move : walk.moves()) {
            moves.add(move.toString());
        }
        phase.add("serverMoves", moves);
        JsonArray samples = new JsonArray();
        for (Sample sample : walk.samples()) {
            samples.add(sample.toString());
        }
        phase.add("samples", samples);
    }

    private static boolean fail(JsonObject phase, List<String> failures, String failure) {
        LOGGER.error("WORMHOLES_SERVER_STARTED_CROSSING_PHASE_FAIL {}", failure);
        phase.addProperty("passed", false);
        phase.addProperty("failure", failure);
        failures.add(failure);
        return false;
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not create server-started crossing evidence directory " + directory, failure);
        }
    }

    private static void write(Path file, JsonObject evidence) {
        try {
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write server-started crossing evidence " + file, failure);
        }
    }

    private static String vector(Vec3 vector) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", vector.x, vector.y, vector.z);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private record ClientSample(String level, Vec3 feet, boolean ground, boolean loaded, boolean crossed) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "client %s %s ground %s chunk %s crossed %s", level, vector(feet), ground, loaded, crossed);
        }
    }

    private record ServerSample(String level, Vec3 feet, boolean ground, int tick) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "server %s %s ground %s tick %d", level, vector(feet), ground, tick);
        }
    }

    private record Sample(int tick, ClientSample client, ServerSample server) {
        @Override
        public String toString() {
            return "t=" + tick + " " + client + " | " + server;
        }
    }

    private record Walk(List<Sample> samples, int placed, int releasedAt, int arrived, int released, int suppressed,
                        List<ServerStartedCrossingTap.Move> moves, int movesBeforeHold, int respawns, int positions, int accepts, int rollbacks,
                        boolean loading, boolean replaced, boolean awaiting, ArrivalPlacementTap.Placement placement,
                        ArrivalPlacementTap.Arrival arrival, String events) {
        List<ServerStartedCrossingTap.Move> afterHold() {
            return movesBeforeHold < 0 ? List.of() : moves.subList(Math.min(movesBeforeHold, moves.size()), moves.size());
        }

        List<ServerStartedCrossingTap.Move> stale(Vec3 source) {
            List<ServerStartedCrossingTap.Move> stale = new ArrayList<>();
            for (ServerStartedCrossingTap.Move move : afterHold()) {
                if (move.requested() != null && move.requested().distanceTo(source) <= SOURCE_RADIUS) {
                    stale.add(move);
                }
            }
            return stale;
        }

        String appliedAtSource(Vec3 source) {
            String destination = placement.level();
            for (ServerStartedCrossingTap.Move move : afterHold()) {
                if (move.levelAfter().equals(destination) && move.after().distanceTo(source) <= SOURCE_RADIUS) {
                    return move.toString();
                }
            }
            for (Sample sample : samples) {
                if (sample.tick() > placed && sample.server().level().equals(destination) && sample.server().feet().distanceTo(source) <= SOURCE_RADIUS) {
                    return sample.toString();
                }
            }
            return null;
        }

        String apart() {
            for (Sample sample : samples) {
                if (arrived < 0 || sample.tick() < arrived) {
                    continue;
                }
                if (!sample.client().level().equals(sample.server().level()) || sample.client().feet().distanceTo(sample.server().feet()) > AGREEMENT) {
                    return sample.toString();
                }
            }
            return null;
        }

        String summary(Vec3 source) {
            return String.format(Locale.ROOT, "suppressed claims %d, stale moves %d, position packets %d, respawn packets %d, teleport acceptances %d, "
                    + "rollbacks %d, server move %s, client crossing %s, first %s, last %s", suppressed, stale(source).size(), positions,
                respawns, accepts, rollbacks, placement == null ? "none" : placement, arrival == null ? "none" : arrival, samples.getFirst(),
                samples.getLast());
        }
    }
}
