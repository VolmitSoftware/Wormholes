package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class CrossingBench {
    static final String ENABLED = "wormholes.clientgametest.crossbench";
    private static final int ACCEPT_TIMEOUT_TICKS = 100;
    private static final int ARM_TIMEOUT_TICKS = 600;
    private static final int SETTLE_TICKS = Integer.getInteger("wormholes.crossbench.settleTicks", 60);
    private static final int WARM_TRIPS = Integer.getInteger("wormholes.crossbench.warmTrips", 2);
    private static final int RAPID_TRIPS = Integer.getInteger("wormholes.crossbench.rapidTrips", 3);
    private static final List<RouteSetup> SETUPS = List.of(
        new RouteSetup("nether", Level.OVERWORLD, new BlockPos(0, 70, 20), Level.NETHER, new BlockPos(0, 70, 220)),
        new RouteSetup("end", Level.OVERWORLD, new BlockPos(96, 70, 20), Level.END, new BlockPos(96, 70, 420)),
        new RouteSetup("same", Level.OVERWORLD, new BlockPos(-96, 70, 20), Level.OVERWORLD, new BlockPos(-96, 70, 1620)));

    private CrossingBench() {
    }

    static List<String> routes() {
        List<String> selected = List.of(System.getProperty("wormholes.crossbench.routes", "nether,end,same").split(","));
        List<String> routes = new ArrayList<>(SETUPS.size());
        for (RouteSetup setup : SETUPS) {
            if (selected.contains(setup.name())) {
                routes.add(setup.name());
            }
        }
        return routes;
    }

    static void run(SeamlessClient client, SeamlessServer server, String label, String routeName) {
        RouteSetup setup = SETUPS.stream().filter(candidate -> candidate.name().equals(routeName)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown crossing bench route " + routeName));
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        CrossingBenchReport report = new CrossingBenchReport(label);
        route(client, server, setup, report);
        report.finish();
    }

    private static void route(SeamlessClient client, SeamlessServer server, RouteSetup setup, CrossingBenchReport report) {
        SeamlessScenario.Route route = server.build(new SeamlessScenario.RouteSpec(setup.source(), setup.sourceMin(), setup.destination(),
            setup.destinationMin(), OrientationPolicy.FRAME, false));
        try {
            server.approach(route);
            SeamlessScenario.awaitPrepared(client);
            report.add(measure(client, route, new Leg(setup.name(), "out", 1, Phase.COLD), false));
            SeamlessScenario.turnBack(client, route);
            report.add(measure(client, route, new Leg(setup.name(), "back", 1, Phase.COLD), false));
            for (int trip = 2; trip <= WARM_TRIPS + 1; trip++) {
                SeamlessScenario.turnAround(client, route.outbound().exit().sourceOrigin());
                SeamlessScenario.awaitPrepared(client);
                report.add(measure(client, route, new Leg(setup.name(), "out", trip, Phase.WARM), false));
                SeamlessScenario.turnAround(client, route.inbound().exit().sourceOrigin());
                SeamlessScenario.awaitPrepared(client);
                report.add(measure(client, route, new Leg(setup.name(), "back", trip, Phase.WARM), false));
            }
            SeamlessScenario.turnAround(client, route.outbound().exit().sourceOrigin());
            SeamlessScenario.awaitPrepared(client);
            for (int trip = 1; trip <= RAPID_TRIPS; trip++) {
                report.add(measure(client, route, new Leg(setup.name(), "out", trip, Phase.RAPID), true));
                report.add(measure(client, route, new Leg(setup.name(), "back", trip, Phase.RAPID), true));
            }
        } finally {
            SeamlessScenario.finish(client, server, route);
        }
    }

    private static CrossingBenchReport.Sample measure(SeamlessClient client, SeamlessScenario.Route route, Leg leg, boolean rapid) {
        String label = leg.label();
        boolean outbound = leg.direction().equals("out");
        BlockPos returnPortal = outbound ? route.destinationMin() : route.sourceMin();
        UUID entry = outbound ? route.source() : route.destination();
        client.waitFor(minecraft -> WormholesClient.instance().seamlessTravel().armed(entry) && !WormholesClient.instance().seamlessTravel().pending(),
            ARM_TIMEOUT_TICKS);
        client.runOnClient(minecraft -> CrossingBenchTap.open());
        CrossingBenchTap.Recording recording;
        int returnViewTicks = -1;
        boolean seamless;
        try {
            SeamlessScenario.Crossing crossing = walk(client, label);
            client.waitFor(minecraft -> !WormholesClient.instance().seamlessTravel().pending(), ACCEPT_TIMEOUT_TICKS);
            ClientLevel source = crossing.source();
            if (rapid) {
                SeamlessScenario.turnAround(client, (outbound ? route.inbound() : route.outbound()).exit().sourceOrigin());
            } else {
                for (int tick = 0; tick < SETTLE_TICKS; tick++) {
                    if (returnViewTicks < 0 && client.computeOnClient(minecraft -> SeamlessScenario.returnViewReady(minecraft, source, returnPortal))) {
                        returnViewTicks = tick;
                    }
                    client.waitTicks(1);
                }
            }
            seamless = client.computeOnClient(CrossingBench::seamless);
        } finally {
            recording = client.computeOnClient(minecraft -> CrossingBenchTap.close());
        }
        SeamlessScenario.assertTrue(seamless, label + ": the crossing was not seamless (" + TravelTap.respawns() + " respawns, loading screen "
            + TravelTap.loadingScreenShown() + ")");
        return CrossingBenchReport.analyze(leg, recording, returnViewTicks);
    }

    private static SeamlessScenario.Crossing walk(SeamlessClient client, String label) {
        String before = client.computeOnClient(CrossingBench::state);
        try {
            return SeamlessScenario.walkThrough(client, label);
        } catch (AssertionError failure) {
            throw new AssertionError(label + ": the player never crossed; before " + before + "; after " + client.computeOnClient(CrossingBench::state), failure);
        }
    }

    private static String state(Minecraft minecraft) {
        StringBuilder state = new StringBuilder();
        state.append(minecraft.level.dimension().identifier()).append(' ').append(minecraft.player.position()).append(" yaw ")
            .append(minecraft.player.getYRot()).append(" pending ").append(WormholesClient.instance().seamlessTravel().pending())
            .append(" chunk loaded ").append(minecraft.level.hasChunkAt(minecraft.player.blockPosition()))
            .append(" connection level ").append(minecraft.getConnection().getLevel() == minecraft.level)
            .append(" level ").append(System.identityHashCode(minecraft.level)).append(" positions ").append(TravelTap.positions())
            .append(" forward ").append(minecraft.options.keyUp.isDown()).append(" input ").append(minecraft.player.input.keyPresses)
            .append(" screen ").append(ClientGui.screen(minecraft)).append(" loaded ").append(minecraft.getConnection().hasClientLoaded())
            .append(" client straddle ").append(straddle(StraddleTracker.straddle(minecraft.player)));
        if (minecraft.getSingleplayerServer() != null) {
            ServerPlayer server = minecraft.getSingleplayerServer().getPlayerList().getPlayer(minecraft.player.getUUID());
            state.append(" server straddle ").append(server == null ? "no player" : straddle(StraddleTracker.straddle(server)))
                .append(" server position ").append(server == null ? null : server.position());
        }
        for (TravelMessage.TravelBegin arm : WormholesClient.instance().seamlessTravel().arms()) {
            state.append(", arm ").append(arm.sourcePortal()).append(' ').append(arm.sourceWorld()).append(" origin ")
                .append(arm.sourceGeometry().originX()).append(',').append(arm.sourceGeometry().originY()).append(',')
                .append(arm.sourceGeometry().originZ()).append(" front ").append(arm.sourceGeometry().frontSide());
        }
        return state.toString();
    }

    private static String straddle(StraddleTracker.Straddle straddle) {
        return straddle == null ? "none" : straddle.origin() + " front " + straddle.frontSide() + " slab " + straddle.slab() + " to "
            + (straddle.destination() == null ? null : straddle.destination().dimension().identifier());
    }

    private static boolean seamless(Minecraft minecraft) {
        return TravelTap.respawns() == 0 && !TravelTap.loadingScreenShown() && !TravelTap.clientUnloaded();
    }

    enum Phase {
        COLD,
        WARM,
        RAPID;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Leg(String route, String direction, int trip, Phase phase) {
        String label() {
            return route + "-" + direction + "-" + phase.key() + "-" + trip;
        }
    }

    private record RouteSetup(String name, ResourceKey<Level> source, BlockPos sourceMin, ResourceKey<Level> destination, BlockPos destinationMin) {
    }
}
