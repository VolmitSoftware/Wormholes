package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

final class SeamlessCrossDimension {
    private static final BlockPos SOURCE_MIN = new BlockPos(0, 70, 20);
    private static final BlockPos DESTINATION_MIN = new BlockPos(0, 70, 220);

    private SeamlessCrossDimension() {
    }

    static void prepared(SeamlessClient client, SeamlessServer server, String label) {
        SeamlessScenario.join(client);
        SeamlessScenario.Route route = server.build(spec(OrientationPolicy.FRAME));
        server.approach(route);
        SeamlessScenario.awaitPrepared(client);
        SeamlessScenario.Crossing crossing = SeamlessScenario.walkThrough(client, label);
        SeamlessScenario.assertPreparedTravel(client, crossing, true);
        SeamlessScenario.finish(client, server, route);
    }

    static void seamless(SeamlessClient client, SeamlessServer server, String label, OrientationPolicy orientation) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        SeamlessScenario.Route route = server.build(spec(orientation));
        server.approach(route);
        SeamlessScenario.awaitPrepared(client);
        SeamlessScenario.Crossing outbound = SeamlessScenario.walkThrough(client, label);
        SeamlessScenario.assertSeamlessTravel(client, route, outbound);
        SeamlessScenario.turnBack(client, route);
        SeamlessScenario.Crossing inbound = SeamlessScenario.walkThrough(client, label + "-return");
        SeamlessScenario.assertSeamlessReturn(client, route, inbound);
        SeamlessScenario.finish(client, server, route);
    }

    private static SeamlessScenario.RouteSpec spec(OrientationPolicy orientation) {
        return new SeamlessScenario.RouteSpec(Level.OVERWORLD, SOURCE_MIN, Level.NETHER, DESTINATION_MIN, orientation);
    }
}
