package art.arcane.wormholes.clientgametest;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
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
import java.util.function.Function;
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

final class EndPortalFloor {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final Site FLOOR = new Site("floor-enter", new BlockPos(40, 70, 40), 0);
    private static final Site ELEVATED = new Site("elevated-enter", new BlockPos(40, 70, 88), 2);
    private static final String RETURN_PHASE = "end-return";
    private static final String EXIT_PHASE = "end-exit";
    private static final String DIRECTORY = "end-portal-floor";
    private static final String NONE = "none";
    private static final float WALK_PITCH = 40.0F;
    private static final float LOOK_UP_PITCH = -60.0F;
    private static final double FRAME_TOP = 0.8125D;
    private static final double CROSSING_JUMP = 4.0D;
    private static final int PLACE_TIMEOUT_TICKS = 400;
    private static final int CONVERSION_TIMEOUT_TICKS = 600;
    private static final int ARM_TIMEOUT_TICKS = 600;
    private static final int HOLD_TICKS = 60;
    private static final int ENTER_TIMEOUT_TICKS = 200;
    private static final int LAND_TIMEOUT_TICKS = 200;
    private static final long LAND_TIMEOUT_MILLIS = 20_000L;
    private static final int DRAGON_TIMEOUT_TICKS = 1200;
    private static final int EXIT_TIMEOUT_TICKS = 400;
    private static final int VIEW_TIMEOUT_TICKS = 300;
    private static final int ABSENCE_TICKS = 40;
    private static final int RETURN_JUMP_TIMEOUT_TICKS = 160;
    private static final int EXIT_WALK_TIMEOUT_TICKS = 600;
    private static final int PERCH_DEPTH = 3;
    private static final int RIM_DISTANCE = 3;
    private static final int[][] RIM_DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private EndPortalFloor() {
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
        try {
            Entry entry = enter(client, server, playerId, FLOOR, label, phases, failures);
            boolean elevatedTried = false;
            if (entry == null) {
                entry = enter(client, server, playerId, ELEVATED, label, phases, failures);
                elevatedTried = true;
            }
            if (entry == null) {
                JsonObject skipped = new JsonObject();
                phases.add(skipped);
                skipped.addProperty("phase", RETURN_PHASE);
                fail(skipped, failures, RETURN_PHASE + ": the End was never reached through the floor or elevated End portal, so its arrival portal could not be tried");
            } else {
                returnThroughArrival(client, server, playerId, entry, label, phases, failures);
            }
            exitThroughFountain(client, server, playerId, label, phases, failures);
            if (!elevatedTried) {
                enter(client, server, playerId, ELEVATED, label, phases, failures);
            }
        } finally {
            client.restoreDefaultGameOptions();
            JsonArray failed = new JsonArray();
            for (String failure : failures) {
                failed.add(failure);
            }
            evidence.add("failures", failed);
            write(directory.resolve(label + ".json"), evidence);
        }
        LOGGER.info("WORMHOLES_END_PORTAL_FLOOR_RESULT fixture={} failures={} evidence={}", label, failures.size(), directory.resolve(label + ".json"));
        SeamlessScenario.assertTrue(failures.isEmpty(), label + ": " + String.join("; ", failures));
        LOGGER.info("WORMHOLES_END_PORTAL_FLOOR_PASS fixture={}", label);
    }

    private static Entry enter(SeamlessClient client, SeamlessServer server, UUID playerId, Site site, String label, JsonArray phases,
                               List<String> failures) {
        JsonObject phase = new JsonObject();
        phases.add(phase);
        phase.addProperty("phase", site.phase());
        phase.addProperty("portalMinimum", site.minimum().toShortString());
        phase.addProperty("airBelowPortal", site.drop());
        Vec3 stand = site.stand();
        Vec3 placed = server.computeOnServer(minecraftServer -> build(minecraftServer, playerId, site));
        phase.addProperty("placed", vector(placed));
        if (ticksUntil(client, minecraft -> at(minecraft, Level.OVERWORLD, stand), PLACE_TIMEOUT_TICKS) < 0) {
            fail(phase, failures, site.phase() + ": the player never stood on the End portal frame ring at " + stand + ": "
                + client.computeOnClient(EndPortalFloor::describeClient));
            return null;
        }
        client.waitForChunksDownload();
        EyeUse eye = server.computeOnServer(minecraftServer -> insertLastEye(minecraftServer, playerId, site));
        phase.addProperty("eye", eye.detail());
        LOGGER.info("[{}] {} eye of ender inserted: {}", label, site.phase(), eye.detail());
        if (!eye.consumed() || !eye.formed()) {
            fail(phase, failures, site.phase() + ": the eye of ender did not complete the vanilla End portal: " + eye.detail());
            return null;
        }
        Polled<Conversion> converted = poll(client, server, minecraftServer -> conversion(minecraftServer, site), CONVERSION_TIMEOUT_TICKS);
        if (converted.value() == null) {
            fail(phase, failures, site.phase() + ": the completed End portal was not converted into a Wormholes End portal within "
                + CONVERSION_TIMEOUT_TICKS + " ticks: " + server.computeOnServer(minecraftServer -> siteState(minecraftServer, site)));
            return null;
        }
        Conversion conversion = converted.value();
        phase.addProperty("conversionTicks", converted.ticks());
        phase.addProperty("conversion", conversion.describe());
        LOGGER.info("[{}] {} converted after {} ticks: {}", label, site.phase(), converted.ticks(), conversion.describe());
        int armed = ticksUntil(client, minecraft -> armed(conversion.source()), ARM_TIMEOUT_TICKS);
        client.lookAt(180.0F, WALK_PITCH);
        client.waitTicks(2);
        phase.addProperty("armedTicks", armed);
        String sourceView = client.computeOnClient(minecraft -> viewState(minecraft, conversion.openings()));
        phase.addProperty("viewBeforeWalk", sourceView);
        phase.addProperty("travelBeforeWalk", client.computeOnClient(EndPortalFloor::travelState));
        screenshot(client, label + "-" + site.phase() + "-approach");
        Walk walk = move(client, server, playerId, conversion.source(), Level.END, Motion.WALK, ENTER_TIMEOUT_TICKS);
        record(phase, walk);
        screenshot(client, label + "-" + site.phase() + (walk.arrived() >= 0 ? "-arrived" : "-stuck"));
        LOGGER.info("[{}] {} walk: arrived at tick {}, {}", label, site.phase(), walk.arrived(), walk.summary(conversion.planeY()));
        if (walk.arrived() < 0) {
            fail(phase, failures, site.phase() + ": walked off the frame ring into the converted End portal (plane y="
                + format(conversion.planeY()) + ", block under the portal " + conversion.floor() + ", seamless armed after " + armed
                + " ticks) but was still outside the End " + ENTER_TIMEOUT_TICKS + " ticks later; " + walk.summary(conversion.planeY()));
            return null;
        }
        boolean clean = crossedOnContact(phase, failures, site, conversion, walk);
        Landing landing = land(client, server, playerId, conversion.arrivalOrigin().y());
        double disagreement = landing.sample().client().feet().distanceTo(landing.sample().server().feet());
        phase.addProperty("landingTicks", landing.ticks());
        phase.addProperty("landingMillis", landing.millis());
        phase.addProperty("landing", landing.sample().toString());
        if (landing.ticks() < 0) {
            clean = fail(phase, failures, site.phase() + ": after entering the End the client and server did not both report the player on the ground "
                + "under the arrival portal (plane y=" + format(conversion.arrivalOrigin().y()) + ") within " + LAND_TIMEOUT_TICKS + " ticks and "
                + LAND_TIMEOUT_MILLIS + " ms: " + landing.sample());
        } else if (disagreement > 1.0D) {
            clean = fail(phase, failures, site.phase() + ": after landing on the ground under the End arrival portal (plane y="
                + format(conversion.arrivalOrigin().y()) + ") the client and server positions disagree by " + format(disagreement) + " blocks: "
                + landing.sample());
        }
        if (clean) {
            phase.addProperty("passed", true);
            LOGGER.info("WORMHOLES_END_PORTAL_FLOOR_PHASE_PASS {} {} landed after {} ticks and {} ms {}", label, site.phase(), landing.ticks(),
                landing.millis(), landing.sample());
        }
        return new Entry(conversion, landing.sample());
    }

    private static boolean crossedOnContact(JsonObject phase, List<String> failures, Site site, Conversion conversion, Walk walk) {
        Sample crossed = walk.serverArrival(Level.END.identifier().toString());
        Sample departure = walk.samples().get(Math.max(0, crossed.tick() - 1));
        double lowestFeet = walk.lowestServerFeet(Level.OVERWORLD.identifier().toString());
        double plane = conversion.arrivalOrigin().y();
        ArrivalPlacementTap.Placement placement = walk.placement();
        ArrivalPlacementTap.Arrival arrival = walk.arrival();
        phase.addProperty("crossingTick", crossed.tick());
        phase.addProperty("departure", departure.toString());
        phase.addProperty("lowestServerFeetBeforeCrossing", lowestFeet);
        phase.addProperty("sampledServerFeetFromArrivalPlane", crossed.server().feet().y - plane);
        boolean clean = true;
        if (lowestFeet < site.minimum().getY()) {
            clean = fail(phase, failures, site.phase() + ": the server let the player sink to feet y " + format(lowestFeet)
                + " below the End portal cells at y " + site.minimum().getY() + " before sending it at tick " + crossed.tick() + "; "
                + walk.summary(conversion.planeY()));
        }
        if (placement == null) {
            clean = fail(phase, failures, site.phase() + ": the server never placed the player in the End through its seamless move; first End sample "
                + crossed);
        } else {
            double placedFeet = placement.feet().y - plane;
            phase.addProperty("serverPlacementFromArrivalPlane", placedFeet);
            phase.addProperty("serverTicksFromPlacementToSample", crossed.server().tick() - placement.serverTick());
            if (!belowPlane(placedFeet)) {
                clean = fail(phase, failures, site.phase() + ": the server placed the player with feet " + format(placedFeet) + " from the End arrival "
                    + "plane y=" + format(plane) + " instead of within one block below it: " + placement + "; first End sample " + crossed);
            }
        }
        if (arrival == null) {
            clean = fail(phase, failures, site.phase() + ": the client never crossed into the End through a seamless crossing; first End sample "
                + crossed);
        } else {
            double arrivedFeet = arrival.feet().y - plane;
            phase.addProperty("clientArrivalFromArrivalPlane", arrivedFeet);
            if (!belowPlane(arrivedFeet)) {
                clean = fail(phase, failures, site.phase() + ": the client arrived with feet " + format(arrivedFeet) + " from the End arrival plane y="
                    + format(plane) + " instead of within one block below it: " + arrival + "; first End sample " + crossed);
            }
        }
        if (walk.respawns() > 0 || walk.positions() > 0 || walk.loading() || walk.replaced()) {
            clean = fail(phase, failures, site.phase() + ": entering the End portal was not seamless: respawn packets " + walk.respawns()
                + ", position packets " + walk.positions() + ", loading screen " + walk.loading() + ", player replaced " + walk.replaced()
                + "; " + walk.summary(conversion.planeY()));
        }
        return clean;
    }

    private static void returnThroughArrival(SeamlessClient client, SeamlessServer server, UUID playerId, Entry entry, String label,
                                             JsonArray phases, List<String> failures) {
        JsonObject phase = new JsonObject();
        phases.add(phase);
        phase.addProperty("phase", RETURN_PHASE);
        Conversion conversion = entry.conversion();
        Vec3d plane = conversion.arrivalOrigin();
        Vec3 relative = entry.landing().server().feet().subtract(plane.x(), plane.y(), plane.z());
        phase.addProperty("arrivalPortal", conversion.describeArrival());
        phase.addProperty("arrival", entry.landing().toString());
        phase.addProperty("arrivalRelativeToPortal", vector(relative));
        phase.addProperty("arrivalEyeBelowPlane", plane.y() - entry.landing().client().eye());
        client.lookAt(0.0F, LOOK_UP_PITCH);
        int arrivalRoute = ticksUntil(client, minecraft -> returnRoute(minecraft, conversion) != null, ABSENCE_TICKS);
        String routeAtArrival = client.computeOnClient(minecraft -> routeState(minecraft, conversion));
        phase.addProperty("returnTicksAtArrival", arrivalRoute);
        phase.addProperty("returnAtArrival", routeAtArrival);
        String streamsAtArrival = client.computeOnClient(minecraft -> streamState(minecraft, conversion));
        phase.addProperty("streamsAtArrival", streamsAtArrival);
        phase.addProperty("travelAtArrival", client.computeOnClient(EndPortalFloor::travelState));
        LOGGER.info("[{}] {} arrival {} is {} from the arrival portal {}; way back after {} of {} ticks: {}", label, RETURN_PHASE,
            entry.landing(), vector(relative), conversion.describeArrival(), arrivalRoute, ABSENCE_TICKS, routeAtArrival);
        screenshot(client, label + "-" + RETURN_PHASE + "-arrival");
        boolean clean = true;
        if (arrivalRoute >= 0) {
            clean = fail(phase, failures, RETURN_PHASE + ": from the End landing spot " + format(plane.y() - entry.landing().client().eye())
                + " blocks below the receive-only End arrival portal (" + conversion.describeArrivalFlags() + "), the client offered a way back "
                + "through it after " + arrivalRoute + " ticks: " + routeAtArrival);
        }
        Vec3 perch = server.computeOnServer(minecraftServer -> perch(minecraftServer, playerId, conversion));
        phase.addProperty("perch", vector(perch));
        if (ticksUntil(client, minecraft -> at(minecraft, Level.END, perch), PLACE_TIMEOUT_TICKS) < 0) {
            fail(phase, failures, RETURN_PHASE + ": the player never stood on the perch under the End arrival portal at " + perch + ": "
                + client.computeOnClient(EndPortalFloor::describeClient));
            return;
        }
        client.lookAt(0.0F, LOOK_UP_PITCH);
        int perchRoute = ticksUntil(client, minecraft -> returnRoute(minecraft, conversion) != null, ABSENCE_TICKS);
        String routeAtPerch = client.computeOnClient(minecraft -> routeState(minecraft, conversion));
        phase.addProperty("returnTicksAtPerch", perchRoute);
        phase.addProperty("returnAtPerch", routeAtPerch);
        String streamsAtPerch = client.computeOnClient(minecraft -> streamState(minecraft, conversion));
        phase.addProperty("streamsAtPerch", streamsAtPerch);
        phase.addProperty("travelAtPerch", client.computeOnClient(EndPortalFloor::travelState));
        screenshot(client, label + "-" + RETURN_PHASE + "-perch");
        if (perchRoute >= 0) {
            clean = fail(phase, failures, RETURN_PHASE + ": standing " + PERCH_DEPTH + " blocks under the receive-only End arrival portal, the "
                + "client offered a way back through it after " + perchRoute + " ticks: " + routeAtPerch);
        }
        Walk jump = move(client, server, playerId, conversion.arrival(), Level.OVERWORLD, Motion.JUMP, RETURN_JUMP_TIMEOUT_TICKS);
        int attempts = client.computeOnClient(minecraft -> TravelTap.crossingAttempts());
        String routeAfterJump = client.computeOnClient(minecraft -> routeState(minecraft, conversion));
        server.computeOnServer(minecraftServer -> unperch(minecraftServer, conversion));
        record(phase, jump);
        phase.addProperty("highestEye", jump.highestEye());
        phase.addProperty("crossingAttempts", attempts);
        phase.addProperty("returnAfterJump", routeAfterJump);
        screenshot(client, label + "-" + RETURN_PHASE + "-jump");
        String outsideEnd = jump.outside(Level.END.identifier().toString());
        LOGGER.info("[{}] {} jump: highest eye {} against plane y {}, client crossing attempts {}, way back after the jump {}, left the End {}, {}",
            label, RETURN_PHASE, format(jump.highestEye()), format(plane.y()), attempts, routeAfterJump, outsideEnd == null ? "never" : outsideEnd,
            jump.summary(plane.y()));
        if (jump.highestEye() <= plane.y()) {
            clean = fail(phase, failures, RETURN_PHASE + ": jumping from the perch never lifted the eye through the End arrival plane y="
                + format(plane.y()) + " (highest eye y " + format(jump.highestEye()) + "), so the one-way arrival was not tried; "
                + jump.summary(plane.y()));
        }
        if (outsideEnd != null) {
            clean = fail(phase, failures, RETURN_PHASE + ": jumping up through the receive-only End arrival portal left the End: " + outsideEnd
                + "; " + jump.summary(plane.y()));
        }
        if (attempts > 0 || jump.positions() > 0 || jump.respawns() > 0 || jump.loading() || jump.replaced()) {
            clean = fail(phase, failures, RETURN_PHASE + ": jumping up through the receive-only End arrival portal was treated as a crossing: "
                + "client crossing attempts " + attempts + ", position packets " + jump.positions() + ", respawn packets " + jump.respawns()
                + ", loading screen " + jump.loading() + ", player replaced " + jump.replaced() + "; " + jump.summary(plane.y()));
        }
        if (!routeAfterJump.equals(NONE)) {
            clean = fail(phase, failures, RETURN_PHASE + ": after jumping through the receive-only End arrival portal the client offered a "
                + "way back through it: " + routeAfterJump);
        }
        if (!clean) {
            return;
        }
        phase.addProperty("passed", true);
        LOGGER.info("WORMHOLES_END_PORTAL_FLOOR_PHASE_PASS {} {} stayed in the End {}", label, RETURN_PHASE, jump.samples().getLast());
    }

    private static void exitThroughFountain(SeamlessClient client, SeamlessServer server, UUID playerId, String label, JsonArray phases,
                                            List<String> failures) {
        JsonObject phase = new JsonObject();
        phases.add(phase);
        phase.addProperty("phase", EXIT_PHASE);
        String placement = server.computeOnServer(minecraftServer -> enterEnd(minecraftServer, playerId));
        phase.addProperty("placement", placement);
        if (ticksUntil(client, minecraft -> minecraft.player != null && minecraft.level != null && minecraft.level.dimension().equals(Level.END),
            PLACE_TIMEOUT_TICKS) < 0) {
            fail(phase, failures, EXIT_PHASE + ": the player could not be placed in the End: " + client.computeOnClient(EndPortalFloor::describeClient));
            return;
        }
        client.waitTicks(2);
        int settled = ticksUntil(client, minecraft -> minecraft.player != null && minecraft.player.onGround(), LAND_TIMEOUT_TICKS);
        Sample arrival = sample(client, server, playerId, null, settled);
        Polled<String> activation = poll(client, server, EndPortalFloor::activateExit, DRAGON_TIMEOUT_TICKS);
        if (activation.value() == null) {
            fail(phase, failures, EXIT_PHASE + ": no ender dragon appeared within " + DRAGON_TIMEOUT_TICKS
                + " ticks to open the End exit portal: " + server.computeOnServer(EndPortalFloor::fountainState));
            return;
        }
        phase.addProperty("activation", activation.value());
        LOGGER.info("[{}] {} exit activation after {} ticks: {}", label, EXIT_PHASE, activation.ticks(), activation.value());
        Polled<Exit> registered = poll(client, server, EndPortalFloor::exit, EXIT_TIMEOUT_TICKS);
        if (registered.value() == null) {
            fail(phase, failures, EXIT_PHASE + ": the active End exit portal was not registered as a Wormholes End exit within "
                + EXIT_TIMEOUT_TICKS + " ticks: " + server.computeOnServer(EndPortalFloor::fountainState));
            return;
        }
        Exit exit = registered.value();
        Vec3 relative = arrival.server().feet().subtract(exit.origin().x(), exit.origin().y(), exit.origin().z());
        phase.addProperty("exit", exit.describe());
        phase.addProperty("start", arrival.toString());
        phase.addProperty("startRelativeToExit", vector(relative));
        int startView = ticksUntil(client, minecraft -> view(minecraft, exit.openings()) != null, VIEW_TIMEOUT_TICKS);
        String viewAtStart = client.computeOnClient(minecraft -> viewState(minecraft, exit.openings()));
        phase.addProperty("viewTicksAtStart", startView);
        phase.addProperty("viewAtStart", viewAtStart);
        LOGGER.info("[{}] {} exit {} registered after {} ticks; start {} is {} from the exit; view after {} ticks: {}", label,
            EXIT_PHASE, exit.describe(), registered.ticks(), arrival, vector(relative), startView, viewAtStart);
        screenshot(client, label + "-" + EXIT_PHASE + "-start");
        Approach approach = server.computeOnServer(minecraftServer -> approach(minecraftServer, playerId, exit));
        if (approach == null) {
            fail(phase, failures, EXIT_PHASE + ": the active End exit portal has no bedrock rim to walk in from: "
                + server.computeOnServer(EndPortalFloor::fountainState));
            return;
        }
        phase.addProperty("rim", approach.rim().toShortString());
        if (ticksUntil(client, minecraft -> at(minecraft, Level.END, approach.stand()), PLACE_TIMEOUT_TICKS) < 0) {
            fail(phase, failures, EXIT_PHASE + ": the player never stood on the exit portal rim at " + approach.stand() + ": "
                + client.computeOnClient(EndPortalFloor::describeClient));
            return;
        }
        client.lookAt(approach.yaw(), WALK_PITCH);
        int rimView = ticksUntil(client, minecraft -> view(minecraft, exit.openings()) != null, VIEW_TIMEOUT_TICKS);
        String viewAtRim = client.computeOnClient(minecraft -> viewState(minecraft, exit.openings()));
        phase.addProperty("viewTicksAtRim", rimView);
        phase.addProperty("viewAtRim", viewAtRim);
        phase.addProperty("travelBeforeWalk", client.computeOnClient(EndPortalFloor::travelState));
        screenshot(client, label + "-" + EXIT_PHASE + "-rim");
        Walk walk = move(client, server, playerId, exit.id(), Level.OVERWORLD, Motion.WALK, EXIT_WALK_TIMEOUT_TICKS);
        record(phase, walk);
        screenshot(client, label + "-" + EXIT_PHASE + (walk.arrived() >= 0 ? "-arrived" : "-stuck"));
        LOGGER.info("[{}] {} walk: arrived at tick {}, credits at tick {}, {}", label, EXIT_PHASE, walk.arrived(), walk.credits(),
            walk.summary(exit.origin().y()));
        if (walk.arrived() < 0) {
            fail(phase, failures, EXIT_PHASE + ": walked off the bedrock rim into the active End exit portal (plane y="
                + format(exit.origin().y()) + ", view at rim " + viewAtRim + ") but was still outside the Overworld "
                + EXIT_WALK_TIMEOUT_TICKS + " ticks later; " + walk.summary(exit.origin().y()));
            return;
        }
        phase.addProperty("passed", true);
        LOGGER.info("WORMHOLES_END_PORTAL_FLOOR_PHASE_PASS {} {} arrived {}", label, EXIT_PHASE, walk.samples().getLast());
    }

    private static boolean belowPlane(double feetFromPlane) {
        return feetFromPlane < 0.0D && feetFromPlane >= -1.0D;
    }

    private static Landing land(SeamlessClient client, SeamlessServer server, UUID playerId, double planeY) {
        long started = System.nanoTime();
        int tick = 0;
        Sample sample = sample(client, server, playerId, null, tick);
        while (!grounded(sample, planeY)) {
            if (tick >= LAND_TIMEOUT_TICKS && elapsedMillis(started) >= LAND_TIMEOUT_MILLIS) {
                return new Landing(sample, -1, elapsedMillis(started));
            }
            client.waitTicks(1);
            tick++;
            sample = sample(client, server, playerId, null, tick);
        }
        return new Landing(sample, tick, elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static boolean grounded(Sample sample, double planeY) {
        String end = Level.END.identifier().toString();
        return sample.client().level().equals(end) && sample.server().level().equals(end) && sample.client().ground() && sample.server().ground()
            && sample.client().feet().y <= planeY - 2.0D && sample.server().feet().y <= planeY - 2.0D;
    }

    private static Walk move(SeamlessClient client, SeamlessServer server, UUID playerId, UUID portal, ResourceKey<Level> target, Motion motion,
                             int timeoutTicks) {
        client.runOnClient(minecraft -> {
            TravelTap.reset();
            ArrivalPlacementTap.reset();
        });
        int identity = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player));
        String destination = target.identifier().toString();
        List<Sample> samples = new ArrayList<>(timeoutTicks + 1);
        samples.add(sample(client, server, playerId, portal, 0));
        int arrived = -1;
        int credits = -1;
        press(client, motion, true);
        try {
            for (int tick = 1; tick <= timeoutTicks && arrived < 0; tick++) {
                if (tick == HOLD_TICKS) {
                    press(client, motion, false);
                }
                client.waitTicks(1);
                Sample sample = sample(client, server, playerId, portal, tick);
                samples.add(sample);
                if (credits < 0 && client.computeOnClient(EndPortalFloor::closeCredits)) {
                    credits = tick;
                }
                if (sample.client().level().equals(destination) && sample.server().level().equals(destination)) {
                    arrived = tick;
                }
            }
        } finally {
            press(client, motion, false);
        }
        boolean replaced = client.computeOnClient(minecraft -> System.identityHashCode(minecraft.player)) != identity;
        return new Walk(samples, arrived, credits, TravelTap.crossingFrame(CROSSING_JUMP), TravelTap.respawns(), TravelTap.positions(),
            TravelTap.loadingScreenShown(), replaced, ArrivalPlacementTap.placement(destination), ArrivalPlacementTap.arrival(destination));
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
        phase.addProperty("creditsTick", walk.credits());
        phase.addProperty("crossingFrame", walk.crossingFrame());
        phase.addProperty("respawnPackets", walk.respawns());
        phase.addProperty("positionPackets", walk.positions());
        phase.addProperty("loadingScreen", walk.loading());
        phase.addProperty("playerReplaced", walk.replaced());
        phase.addProperty("serverPlacement", walk.placement() == null ? NONE : walk.placement().toString());
        phase.addProperty("clientArrival", walk.arrival() == null ? NONE : walk.arrival().toString());
        JsonArray samples = new JsonArray();
        for (Sample sample : walk.samples()) {
            samples.add(sample.toString());
        }
        phase.add("samples", samples);
    }

    private static Sample sample(SeamlessClient client, SeamlessServer server, UUID playerId, UUID portal, int tick) {
        ClientSample local = client.computeOnClient(EndPortalFloor::clientSample);
        ServerSample remote = server.computeOnServer(minecraftServer -> serverSample(minecraftServer, playerId, portal));
        return new Sample(tick, local, remote);
    }

    private static ClientSample clientSample(Minecraft minecraft) {
        Screen screen = ClientGui.screen(minecraft);
        String screenName = screen == null ? "none" : screen.getClass().getSimpleName();
        if (minecraft.player == null || minecraft.level == null) {
            return new ClientSample("absent", Vec3.ZERO, 0.0D, false, 0.0D, screenName, false);
        }
        ClientSeamlessTravel travel = WormholesClient.instance() == null ? null : WormholesClient.instance().seamlessTravel();
        return new ClientSample(minecraft.level.dimension().identifier().toString(), minecraft.player.position(), minecraft.player.getEyeY(),
            minecraft.player.onGround(), minecraft.player.getDeltaMovement().y, screenName, travel != null && travel.armed());
    }

    private static ServerSample serverSample(MinecraftServer server, UUID playerId, UUID portal) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return new ServerSample("absent", Vec3.ZERO, false, false, server.getTickCount());
        }
        boolean eye = portal != null && runtime(server).clientViews().seamlessEye(playerId, portal);
        return new ServerSample(player.level().dimension().identifier().toString(), player.position(), player.onGround(), eye, server.getTickCount());
    }

    private static Vec3 build(MinecraftServer server, UUID playerId, Site site) {
        ServerPlayer player = player(server, playerId);
        ServerLevel level = server.overworld();
        BlockPos minimum = site.minimum();
        fill(level, minimum.offset(-6, -1 - site.drop(), -6), 15, 1, 15, Blocks.STONE.defaultBlockState());
        fill(level, minimum.offset(-6, -site.drop(), -6), 15, 8 + site.drop(), 15, Blocks.AIR.defaultBlockState());
        for (int n = 0; n < 3; n++) {
            frame(level, minimum.offset(n, 0, -1), Direction.SOUTH, true, site.drop());
            frame(level, minimum.offset(n, 0, 3), Direction.NORTH, true, site.drop());
            frame(level, minimum.offset(-1, 0, n), Direction.EAST, true, site.drop());
            frame(level, minimum.offset(3, 0, n), Direction.WEST, n != 1, site.drop());
        }
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        Vec3 stand = site.stand();
        player.teleportTo(level, stand.x, stand.y, stand.z, Set.of(), 180.0F, WALK_PITCH, false);
        player.setDeltaMovement(Vec3.ZERO);
        return player.position();
    }

    private static void frame(ServerLevel level, BlockPos position, Direction facing, boolean eye, int drop) {
        for (int below = 1; below <= drop; below++) {
            level.setBlockAndUpdate(position.below(below), Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(position, Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.FACING, facing)
            .setValue(EndPortalFrameBlock.HAS_EYE, eye));
    }

    private static EyeUse insertLastEye(MinecraftServer server, UUID playerId, Site site) {
        ServerPlayer player = player(server, playerId);
        ServerLevel level = server.overworld();
        BlockPos last = site.lastFrame();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_EYE));
        InteractionResult result = player.gameMode.useItemOn(player, level, player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND,
            new BlockHitResult(new Vec3(last.getX() + 0.5D, last.getY() + FRAME_TOP, last.getZ() + 0.5D), Direction.UP, last, false));
        BlockState frame = level.getBlockState(last);
        boolean formed = level.getBlockState(site.minimum()).is(Blocks.END_PORTAL) || runtime(server).portals().at(level, site.minimum()) != null;
        return new EyeUse(result.consumesAction(), formed, "result=" + result + " frame=" + frame + " formed=" + formed
            + " interior=" + level.getBlockState(site.minimum()) + " floor=" + level.getBlockState(site.minimum().below())
            + " actor=" + player.position() + " gameMode=" + player.gameMode() + " replace="
            + runtime(server).configuration().settings().getMain().replaceNetherAndEndPortals);
    }

    private static Conversion conversion(MinecraftServer server, Site site) {
        WormholesModRuntime runtime = runtime(server);
        ServerLevel level = server.overworld();
        MinecraftPortal source = runtime.portals().at(level, site.minimum());
        if (source == null || source.getDimensionalKind() != DimensionalPortalKind.END_SOURCE || source.getDestinationId() == null) {
            return null;
        }
        MinecraftPortal arrival = runtime.portals().get(source.getDestinationId());
        if (arrival == null) {
            return null;
        }
        List<Vec3d> openings = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                openings.add(new Vec3d(site.minimum().getX() + x + 0.5D, site.minimum().getY() + 0.5D, site.minimum().getZ() + z + 0.5D));
            }
        }
        List<Vec3d> arrivalOpenings = new ArrayList<>(9);
        int minimumX = Integer.MAX_VALUE;
        int minimumY = Integer.MAX_VALUE;
        int minimumZ = Integer.MAX_VALUE;
        for (Vec3d cell : arrival.getBuiltGeometry().getBlockPositions()) {
            minimumX = Math.min(minimumX, (int) cell.x());
            minimumY = Math.min(minimumY, (int) cell.y());
            minimumZ = Math.min(minimumZ, (int) cell.z());
            arrivalOpenings.add(new Vec3d(cell.x() + 0.5D, cell.y() + 0.5D, cell.z() + 0.5D));
        }
        String arrivalFlags = "kind " + arrival.getDimensionalKind() + ", outgoing traversals " + arrival.isOutgoingTraversalsEnabled()
            + ", incoming traversals " + arrival.isIncomingTraversalsEnabled() + ", projection " + arrival.getProjectionMode()
            + ", destination " + arrival.getDestinationId();
        return new Conversion(source.getId(), source.getOrigin(), String.valueOf(source.getFrame().getNormal()), level.getBlockState(site.minimum()).toString(),
            level.getBlockState(site.minimum().below()).toString(), openings, arrival.getId(), arrival.getOrigin(), arrival.getWorldKey(),
            new BlockPos(minimumX, minimumY, minimumZ), arrivalOpenings, String.valueOf(arrival.getFrame().getNormal()), arrivalFlags);
    }

    private static String siteState(MinecraftServer server, Site site) {
        ServerLevel level = server.overworld();
        MinecraftPortal portal = runtime(server).portals().at(level, site.minimum());
        StringBuilder state = new StringBuilder("interior=").append(level.getBlockState(site.minimum()))
            .append(" floor=").append(level.getBlockState(site.minimum().below()))
            .append(" lastFrame=").append(level.getBlockState(site.lastFrame()))
            .append(" portal=").append(portal == null ? "none" : portal.getDimensionalKind() + " destination " + portal.getDestinationId());
        ServerLevel end = server.getLevel(Level.END);
        state.append(" endLevel=").append(end != null);
        return state.toString();
    }

    private static Vec3 perch(MinecraftServer server, UUID playerId, Conversion conversion) {
        ServerLevel end = Objects.requireNonNull(server.getLevel(Level.END), "the End level is not loaded");
        BlockPos minimum = conversion.arrivalMinimum();
        BlockPos floor = minimum.below(PERCH_DEPTH);
        fill(end, floor.offset(-1, 0, -1), 5, 1, 5, Blocks.STONE.defaultBlockState());
        fill(end, floor.offset(-1, 1, -1), 5, PERCH_DEPTH - 1, 5, Blocks.AIR.defaultBlockState());
        ServerPlayer player = player(server, playerId);
        Vec3 stand = new Vec3(minimum.getX() + 1.5D, floor.getY() + 1.0D, minimum.getZ() + 1.5D);
        player.teleportTo(end, stand.x, stand.y, stand.z, Set.of(), 0.0F, LOOK_UP_PITCH, false);
        player.setDeltaMovement(Vec3.ZERO);
        return stand;
    }

    private static BlockPos unperch(MinecraftServer server, Conversion conversion) {
        ServerLevel end = Objects.requireNonNull(server.getLevel(Level.END), "the End level is not loaded");
        BlockPos floor = conversion.arrivalMinimum().below(PERCH_DEPTH);
        fill(end, floor.offset(-1, 0, -1), 5, 1, 5, Blocks.AIR.defaultBlockState());
        return floor;
    }

    private static String enterEnd(MinecraftServer server, UUID playerId) {
        ServerPlayer player = player(server, playerId);
        if (player.level().dimension().equals(Level.END)) {
            return "already in the End at " + vector(player.position());
        }
        ServerLevel end = Objects.requireNonNull(server.getLevel(Level.END), "the End level is not loaded");
        end.getChunk(0, 0);
        int surface = end.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8);
        player.teleportTo(end, 8.5D, surface, 8.5D, Set.of(), 0.0F, 0.0F, false);
        player.setDeltaMovement(Vec3.ZERO);
        return "placed in the End at 8.5," + surface + ",8.5 from " + vector(player.position());
    }

    private static String activateExit(MinecraftServer server) {
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) {
            return "the End level is not loaded";
        }
        if (exit(server) != null) {
            return "the End exit portal was already registered";
        }
        List<? extends EnderDragon> dragons = end.getDragons();
        if (dragons.isEmpty()) {
            return null;
        }
        EnderDragon dragon = dragons.getFirst();
        Vec3 position = dragon.position();
        dragon.kill(end);
        return "killed ender dragon " + dragon.getUUID() + " at " + position + " previouslyKilled="
            + (end.getDragonFight() != null && end.getDragonFight().hasPreviouslyKilledDragon());
    }

    private static Exit exit(MinecraftServer server) {
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) {
            return null;
        }
        String world = end.dimension().identifier().toString();
        for (MinecraftPortal portal : runtime(server).portals().snapshot()) {
            if (portal.getDimensionalKind() != DimensionalPortalKind.END_EXIT || !portal.getWorldKey().equals(world)) {
                continue;
            }
            int minimumX = Integer.MAX_VALUE;
            int maximumX = Integer.MIN_VALUE;
            int minimumZ = Integer.MAX_VALUE;
            int maximumZ = Integer.MIN_VALUE;
            int y = 0;
            List<Vec3d> openings = new ArrayList<>();
            for (Vec3d cell : portal.getBuiltGeometry().getBlockPositions()) {
                minimumX = Math.min(minimumX, (int) cell.x());
                maximumX = Math.max(maximumX, (int) cell.x());
                minimumZ = Math.min(minimumZ, (int) cell.z());
                maximumZ = Math.max(maximumZ, (int) cell.z());
                y = (int) cell.y();
                openings.add(new Vec3d(cell.x() + 0.5D, cell.y() + 0.5D, cell.z() + 0.5D));
            }
            return new Exit(portal.getId(), portal.getOrigin(), y, (minimumX + maximumX) / 2, (minimumZ + maximumZ) / 2, openings,
                portal.getProjectionMode().name(), portal.isOutgoingTraversalsEnabled());
        }
        return null;
    }

    private static Approach approach(MinecraftServer server, UUID playerId, Exit exit) {
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) {
            return null;
        }
        for (int[] direction : RIM_DIRECTIONS) {
            BlockPos rim = new BlockPos(exit.centerX() + direction[0] * RIM_DISTANCE, exit.y(), exit.centerZ() + direction[1] * RIM_DISTANCE);
            BlockPos inner = new BlockPos(exit.centerX() + direction[0] * (RIM_DISTANCE - 1), exit.y(), exit.centerZ() + direction[1] * (RIM_DISTANCE - 1));
            if (!end.getBlockState(rim).is(Blocks.BEDROCK) || !end.getBlockState(rim.above()).isAir() || !end.getBlockState(rim.above(2)).isAir()
                || !end.getBlockState(inner).is(Blocks.END_PORTAL)) {
                continue;
            }
            Vec3 stand = new Vec3(rim.getX() + 0.5D, rim.getY() + 1.0D, rim.getZ() + 0.5D);
            float yaw = (float) Math.toDegrees(Math.atan2(direction[0], -direction[1]));
            ServerPlayer player = player(server, playerId);
            player.teleportTo(end, stand.x, stand.y, stand.z, Set.of(), yaw, WALK_PITCH, false);
            player.setDeltaMovement(Vec3.ZERO);
            return new Approach(rim, stand, yaw);
        }
        return null;
    }

    private static String fountainState(MinecraftServer server) {
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) {
            return "the End level is not loaded";
        }
        int surface = end.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 1, 0);
        int portals = 0;
        int bedrock = 0;
        for (BlockPos position : BlockPos.betweenClosed(-3, surface - 8, -3, 3, surface + 8, 3)) {
            BlockState state = end.getBlockState(position);
            if (state.is(Blocks.END_PORTAL)) {
                portals++;
            } else if (state.is(Blocks.BEDROCK)) {
                bedrock++;
            }
        }
        return "surface=" + surface + " endPortalBlocks=" + portals + " bedrockBlocks=" + bedrock + " dragons=" + end.getDragons().size()
            + " previouslyKilled=" + (end.getDragonFight() != null && end.getDragonFight().hasPreviouslyKilledDragon())
            + " chunks=" + end.hasChunk(0, 0) + " replace=" + runtime(server).configuration().settings().getMain().replaceNetherAndEndPortals;
    }

    private static ServerPlayer player(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        SeamlessScenario.assertTrue(player != null, "the End portal fixture player " + playerId + " is not connected");
        return player;
    }

    private static boolean at(Minecraft minecraft, ResourceKey<Level> level, Vec3 position) {
        return minecraft.player != null && minecraft.level != null && minecraft.level.dimension().equals(level)
            && minecraft.player.position().distanceTo(position) < 0.25D;
    }

    private static boolean armed(UUID source) {
        WormholesClient wormholes = WormholesClient.instance();
        return wormholes != null && wormholes.seamlessTravel().armed(source) && !wormholes.seamlessTravel().pending();
    }

    private static String travelState(Minecraft minecraft) {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null) {
            return "no client";
        }
        ClientSeamlessTravel travel = wormholes.seamlessTravel();
        return "armed=" + travel.armed() + " pending=" + travel.pending() + " unprepared=" + travel.unprepared()
            + " portals=" + wormholes.session().portals().size() + " views=" + wormholes.portalViews().current().size();
    }

    private static String view(Minecraft minecraft, List<Vec3d> openings) {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null || minecraft.level == null) {
            return null;
        }
        for (PortalView view : wormholes.portalViews().current()) {
            if (view.source() != minecraft.level) {
                continue;
            }
            for (Vec3d opening : openings) {
                if (view.surface().area().contains(opening)) {
                    return view.kind() + " to " + (view.destination() == null ? "none" : view.destination().dimension().identifier());
                }
            }
        }
        return null;
    }

    private static String returnRoute(Minecraft minecraft, Conversion conversion) {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null || minecraft.player == null || minecraft.level == null) {
            return "client not in a level";
        }
        List<String> found = new ArrayList<>(4);
        String view = view(minecraft, conversion.arrivalOpenings());
        if (view != null) {
            found.add("view " + view);
        }
        StraddleTracker.Straddle straddle = StraddleTracker.straddle(minecraft.player);
        if (straddle != null) {
            found.add("straddle into " + straddle.destination().dimension().identifier());
        }
        if (wormholes.seamlessTravel().armed(conversion.arrival())) {
            found.add("seamless arm");
        }
        ClientViewSession session = wormholes.session();
        for (ClientPortal portal : streams(session, conversion)) {
            int key = portal.portalKey();
            if (session.environment(key) != null || session.localDestinationWorld(key) != null || portal.plate() != null) {
                found.add(stream(session, portal, minecraft));
            }
        }
        return found.isEmpty() ? null : String.join(", ", found);
    }

    private static String streamState(Minecraft minecraft, Conversion conversion) {
        WormholesClient wormholes = WormholesClient.instance();
        if (wormholes == null || minecraft.level == null) {
            return "client not in a level";
        }
        List<String> described = new ArrayList<>(2);
        for (ClientPortal portal : streams(wormholes.session(), conversion)) {
            described.add(stream(wormholes.session(), portal, minecraft));
        }
        return described.isEmpty() ? NONE : String.join(", ", described);
    }

    private static List<ClientPortal> streams(ClientViewSession session, Conversion conversion) {
        List<ClientPortal> overlapping = new ArrayList<>(2);
        for (ClientPortal portal : session.portals().values()) {
            for (Vec3d opening : conversion.arrivalOpenings()) {
                if (portal.geometry().containsPoint(opening.x(), opening.y(), opening.z())) {
                    overlapping.add(portal);
                    break;
                }
            }
        }
        return overlapping;
    }

    private static String stream(ClientViewSession session, ClientPortal portal, Minecraft minecraft) {
        int key = portal.portalKey();
        ApertureDescriptor geometry = portal.geometry();
        EnvironmentState environment = session.environment(key);
        return "streamed portal " + key + " (root source " + session.rootSourceWorld(key) + ", this level " + session.matchesSource(key, minecraft.level)
            + ", destination " + (environment == null ? "none" : environment.world().dimensionKey()) + ", local destination "
            + session.localDestinationWorld(key) + ", origin " + geometry.originX() + "," + geometry.originY() + "," + geometry.originZ()
            + " facing " + geometry.facingDirection() + " size " + geometry.apertureWidth() + "x" + geometry.apertureHeight() + ", ready " + portal.ready()
            + ", plate " + (portal.plate() != null) + ")";
    }

    private static String routeState(Minecraft minecraft, Conversion conversion) {
        String route = returnRoute(minecraft, conversion);
        return route == null ? NONE : route;
    }

    private static String viewState(Minecraft minecraft, List<Vec3d> openings) {
        String view = view(minecraft, openings);
        return view == null ? "none" : view;
    }

    private static boolean closeCredits(Minecraft minecraft) {
        if (!(ClientGui.screen(minecraft) instanceof WinScreen credits)) {
            return false;
        }
        credits.onClose();
        return true;
    }

    private static String describeClient(Minecraft minecraft) {
        return clientSample(minecraft) + " " + travelState(minecraft);
    }

    private static <T> Polled<T> poll(SeamlessClient client, SeamlessServer server, Function<MinecraftServer, T> query, int timeoutTicks) {
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            T value = server.computeOnServer(query);
            if (value != null) {
                return new Polled<>(value, tick);
            }
            client.waitTicks(1);
        }
        return new Polled<>(null, -1);
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

    private static boolean fail(JsonObject phase, List<String> failures, String failure) {
        LOGGER.error("WORMHOLES_END_PORTAL_FLOOR_PHASE_FAIL {}", failure);
        phase.addProperty("passed", false);
        phase.addProperty("failure", failure);
        failures.add(failure);
        return false;
    }

    private static void screenshot(SeamlessClient client, String name) {
        client.runOnClient(minecraft -> Screenshot.grab(minecraft.gameDirectory, DIRECTORY + "/" + name + ".png", ClientRenderAccess.mainTarget(minecraft), 1,
            message -> LOGGER.info("End portal floor capture {}: {}", name, message.getString())));
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not create End portal floor evidence directory " + directory, failure);
        }
    }

    private static void write(Path file, JsonObject evidence) {
        try {
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write End portal floor evidence " + file, failure);
        }
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

    private static String vector(Vec3 vector) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", vector.x, vector.y, vector.z);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private record Site(String phase, BlockPos minimum, int drop) {
        Vec3 stand() {
            return new Vec3(minimum.getX() + 1.5D, minimum.getY() + 1.0D, minimum.getZ() + 3.5D);
        }

        BlockPos lastFrame() {
            return minimum.offset(3, 0, 1);
        }
    }

    private record EyeUse(boolean consumed, boolean formed, String detail) {
    }

    private record Entry(Conversion conversion, Sample landing) {
    }

    private record Landing(Sample sample, int ticks, long millis) {
    }

    private record Conversion(UUID source, Vec3d sourceOrigin, String normal, String interior, String floor, List<Vec3d> openings, UUID arrival,
                              Vec3d arrivalOrigin, String arrivalLevel, BlockPos arrivalMinimum, List<Vec3d> arrivalOpenings, String arrivalNormal,
                              String arrivalFlags) {
        double planeY() {
            return sourceOrigin.y();
        }

        String describe() {
            return "source " + source + " plane " + sourceOrigin + " normal " + normal + " interior " + interior + " floor " + floor
                + "; " + describeArrival();
        }

        String describeArrival() {
            return "arrival " + arrival + " in " + arrivalLevel + " plane " + arrivalOrigin + " normal " + arrivalNormal + " cells from "
                + arrivalMinimum.toShortString() + ", " + arrivalFlags;
        }

        String describeArrivalFlags() {
            return arrivalFlags;
        }
    }

    private record Exit(UUID id, Vec3d origin, int y, int centerX, int centerZ, List<Vec3d> openings, String projection, boolean outgoing) {
        String describe() {
            return "exit " + id + " plane " + origin + " cells " + openings.size() + " center " + centerX + "," + y + "," + centerZ
                + " projection " + projection + " outgoing " + outgoing;
        }
    }

    private record Approach(BlockPos rim, Vec3 stand, float yaw) {
    }

    private record Polled<T>(T value, int ticks) {
    }

    private enum Motion {
        WALK,
        JUMP
    }

    private record ClientSample(String level, Vec3 feet, double eye, boolean ground, double velocity, String screen, boolean armed) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "client %s %.3f,%.3f,%.3f eye %.3f ground %s vy %.3f screen %s armed %s", level, feet.x, feet.y,
                feet.z, eye, ground, velocity, screen, armed);
        }
    }

    private record ServerSample(String level, Vec3 feet, boolean ground, boolean eyeDetection, int tick) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "server %s %.3f,%.3f,%.3f ground %s eyeDetection %s tick %d", level, feet.x, feet.y, feet.z, ground,
                eyeDetection, tick);
        }
    }

    private record Sample(int tick, ClientSample client, ServerSample server) {
        @Override
        public String toString() {
            return "t=" + tick + " " + client + " | " + server;
        }
    }

    private record Walk(List<Sample> samples, int arrived, int credits, int crossingFrame, int respawns, int positions, boolean loading,
                        boolean replaced, ArrivalPlacementTap.Placement placement, ArrivalPlacementTap.Arrival arrival) {
        double highestEye() {
            double highest = -Double.MAX_VALUE;
            for (Sample sample : samples) {
                highest = Math.max(highest, sample.client().eye());
            }
            return highest;
        }

        String outside(String level) {
            for (Sample sample : samples) {
                if (!sample.client().level().equals(level) || !sample.server().level().equals(level)) {
                    return sample.toString();
                }
            }
            return null;
        }

        String summary(double planeY) {
            double lowestFeet = Double.MAX_VALUE;
            double lowestEye = Double.MAX_VALUE;
            double lowestServerFeet = Double.MAX_VALUE;
            boolean eyeDetection = false;
            boolean armed = false;
            for (Sample sample : samples) {
                if (arrived >= 0 && sample.tick() >= arrived) {
                    break;
                }
                lowestFeet = Math.min(lowestFeet, sample.client().feet().y);
                lowestEye = Math.min(lowestEye, sample.client().eye());
                lowestServerFeet = Math.min(lowestServerFeet, sample.server().feet().y);
                eyeDetection |= sample.server().eyeDetection();
                armed |= sample.client().armed();
            }
            return String.format(Locale.ROOT, "lowest client feet y %.3f and eye y %.3f against plane y %.3f, lowest server feet y %.3f, "
                    + "client armed %s, server eye detection %s, credits tick %d, crossing frame %d, respawn packets %d, position packets %d, "
                    + "loading screen %s, player replaced %s, first %s, last %s", lowestFeet, lowestEye, planeY, lowestServerFeet, armed,
                eyeDetection, credits, crossingFrame, respawns, positions, loading, replaced, samples.getFirst(), samples.getLast());
        }

        Sample serverArrival(String level) {
            for (Sample sample : samples) {
                if (sample.server().level().equals(level)) {
                    return sample;
                }
            }
            return null;
        }

        double lowestServerFeet(String level) {
            double lowest = Double.MAX_VALUE;
            for (Sample sample : samples) {
                if (sample.server().level().equals(level)) {
                    lowest = Math.min(lowest, sample.server().feet().y);
                }
            }
            return lowest;
        }
    }
}
