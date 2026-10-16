package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

final class EndReturnPortal {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int ARRIVAL_TICKS = 400;
    private static final int ARM_TICKS = 400;
    private static final int CROSS_TICKS = 200;
    private static final int STEP_AWAY_TICKS = 10;
    private static final int SETTLE_TICKS = 40;

    private EndReturnPortal() {
    }

    static void run(SeamlessClient client, SeamlessServer server, String label, PortalPairScene.Orientation orientation,
                    PortalPairScene.Side start) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        PortalPairScene.Pair pair = server.buildPortalPair(new PortalPairScene.Spec(orientation, Level.END, start, PortalPairScene.Link.LINK_AND_RETURN));
        PortalPairScene.Side other = start == PortalPairScene.Side.LOCAL ? PortalPairScene.Side.REMOTE : PortalPairScene.Side.LOCAL;
        try {
            Leg outbound = cross(client, server, pair, start, other, label + " outbound", false);
            LOGGER.info("WORMHOLES_END_RETURN_LEG {}", outbound);
            assertTrue(outbound.travelled(), label + ": the outbound crossing " + outbound.from() + " -> " + outbound.to() + " did not travel: " + outbound);
            Leg inbound = cross(client, server, pair, other, start, label + " return", true);
            LOGGER.info("WORMHOLES_END_RETURN_LEG {}", inbound);
            if (!inbound.travelled() && orientation == PortalPairScene.Orientation.VERTICAL) {
                Leg alternate = cross(client, server, pair, other, start, label + " return from the entry face", false);
                LOGGER.info("WORMHOLES_END_RETURN_LEG {}", alternate);
            }
            assertTrue(inbound.travelled(), label + ": the return crossing " + inbound.from() + " -> " + inbound.to() + " did not travel: " + inbound);
            LOGGER.info("WORMHOLES_END_RETURN_PASS {}", label);
        } finally {
            client.restoreDefaultGameOptions();
            server.removePortalPair(pair);
        }
    }

    private static Leg cross(SeamlessClient client, SeamlessServer server, PortalPairScene.Pair pair, PortalPairScene.Side from,
                             PortalPairScene.Side to, String label, boolean natural) {
        String fromDimension = pair.level(from).identifier().toString();
        String toDimension = pair.level(to).identifier().toString();
        boolean vertical = pair.orientation() == PortalPairScene.Orientation.VERTICAL;
        if (natural && vertical) {
            client.holdForwardFor(STEP_AWAY_TICKS);
            client.waitTicks(SETTLE_TICKS);
            client.lookAt(0.0F, 0.0F);
            int arrived = PortalPairViews.awaitTicks(client, minecraft -> dimension(minecraft).equals(fromDimension), ARRIVAL_TICKS);
            assertTrue(arrived >= 0, label + ": the player never reached " + fromDimension + ": " + client.computeOnClient(PortalPairViews::describe));
            client.waitForChunksDownload();
        } else {
            server.stance(pair, from, PortalPairScene.Stance.ENTRY);
            PortalPairViews.awaitPlaced(client, server, pair.level(from), label);
        }
        int armed = PortalPairViews.awaitTicks(client, minecraft -> WormholesClient.instance().seamlessTravel().armed(pair.portal(from))
            && !WormholesClient.instance().seamlessTravel().pending(), ARM_TICKS);
        boolean view = client.computeOnClient(minecraft -> PortalPairViews.viewAt(minecraft, pair, from));
        String before = client.computeOnClient(PortalPairViews::describe);
        int crossed;
        if (vertical) {
            client.holdForward();
            try {
                crossed = PortalPairViews.awaitTicks(client, minecraft -> dimension(minecraft).equals(toDimension), CROSS_TICKS);
            } finally {
                client.releaseForward();
            }
        } else {
            server.fall();
            crossed = PortalPairViews.awaitTicks(client, minecraft -> dimension(minecraft).equals(toDimension), CROSS_TICKS);
        }
        client.waitTicks(SETTLE_TICKS);
        String serverDimension = server.playerDimension();
        String after = client.computeOnClient(PortalPairViews::describe);
        return new Leg(label, fromDimension, toDimension, armed, view, crossed, serverDimension, before, after);
    }

    private static String dimension(Minecraft minecraft) {
        return minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
    }

    private record Leg(String label, String from, String to, int armedTicks, boolean viewShown, int crossedTicks, String serverDimension,
                       String before, String after) {
        boolean travelled() {
            return crossedTicks >= 0 && to.equals(serverDimension);
        }
    }
}
