package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

final class SeamlessSameDimension {
    private static final BlockPos FAR_SOURCE = new BlockPos(0, 70, 20);
    private static final BlockPos FAR_DESTINATION = new BlockPos(0, 70, 420);
    private static final BlockPos NEAR_SOURCE = new BlockPos(48, 70, 20);
    private static final BlockPos NEAR_DESTINATION = new BlockPos(48, 70, 44);
    private static final BlockPos TURNING_SOURCE = new BlockPos(-48, 70, 20);
    private static final BlockPos TURNING_DESTINATION = new BlockPos(-48, 70, 420);

    private SeamlessSameDimension() {
    }

    static void seamless(SeamlessClient client, SeamlessServer server, String label) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        cross(client, server, new Pass(FAR_SOURCE, FAR_DESTINATION, label + "-far", false));
        cross(client, server, new Pass(NEAR_SOURCE, NEAR_DESTINATION, label + "-near", false));
        cross(client, server, new Pass(TURNING_SOURCE, TURNING_DESTINATION, label + "-turning", true));
    }

    private static void cross(SeamlessClient client, SeamlessServer server, Pass pass) {
        SeamlessScenario.Route route = server.build(spec(pass.source(), pass.destination()));
        server.approach(route);
        SeamlessScenario.awaitPrepared(client);
        SeamlessScenario.Crossing crossing = pass.turning() ? SeamlessScenario.walkThroughTurning(client, pass.label())
            : SeamlessScenario.walkThrough(client, pass.label());
        SeamlessScenario.assertSeamlessTravel(client, route, crossing, SeamlessScenario.RETURN_VIEW_TICKS);
        SeamlessScenario.turnBack(client, route);
        SeamlessScenario.Crossing inbound = SeamlessScenario.walkThrough(client, pass.label() + "-return");
        SeamlessScenario.assertSeamlessReturn(client, route, inbound, SeamlessScenario.RETURN_VIEW_TICKS);
        SeamlessScenario.finish(client, server, route);
    }

    private static SeamlessScenario.RouteSpec spec(BlockPos source, BlockPos destination) {
        return new SeamlessScenario.RouteSpec(Level.OVERWORLD, source, Level.OVERWORLD, destination, OrientationPolicy.FRAME, false);
    }

    private record Pass(BlockPos source, BlockPos destination, String label, boolean turning) {
    }
}
