package art.arcane.wormholes.clientgametest;

import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.WormholesBuild;
import art.arcane.wormholes.menu.protocol.MenuProtocol;
import art.arcane.wormholes.modded.client.ClientViewDebugEntry;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.WormholesDebugLines;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;

public final class DebugScreenStatsClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final BlockPos SOURCE_MIN = new BlockPos(0, 70, 20);
    private static final BlockPos DESTINATION_MIN = new BlockPos(0, 70, 220);
    private static final int TIMEOUT_TICKS = 400;
    private static final int RENDER_TICKS = 10;
    private static final Pattern PORTALS = Pattern.compile("Portals: armed [1-9]\\d*, drawn [1-9]\\d*, streams \\d+");
    private static final Pattern TRAVEL = Pattern.compile("Travel: armed, residents [1-9]\\d*");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            FabricSeamlessClient client = new FabricSeamlessClient(context, FabricConnection.of(singleplayer));
            FabricSeamlessServer server = new FabricSeamlessServer(singleplayer.getServer(), FabricConnection.of(singleplayer));
            SeamlessScenario.join(client);
            SeamlessScenario.assertSeamlessNegotiated(client);
            SeamlessScenario.Route route = server.build(new SeamlessScenario.RouteSpec(Level.OVERWORLD, SOURCE_MIN, Level.NETHER, DESTINATION_MIN,
                OrientationPolicy.FRAME, false));
            server.approach(route);
            SeamlessScenario.awaitPrepared(client);
            client.waitFor(DebugScreenStatsClientGameTest::settled, TIMEOUT_TICKS);
            String platform = server.computeOnServer(minecraft -> minecraft.getServerModName() + " " + minecraft.getServerVersion());
            boolean nativeScreens = server.computeOnServer(minecraft -> runtime(minecraft).configuration().settings().getMenus().nativeScreens);
            client.runOnClient(minecraft -> {
                minecraft.debugEntries.setOverlayVisible(true);
                minecraft.debugEntries.setStatus(ClientViewDebugEntry.DETAIL_ID, DebugScreenEntryStatus.IN_OVERLAY);
            });
            client.waitTicks(RENDER_TICKS);
            List<String> summary = client.computeOnClient(minecraft -> plain(capture(minecraft, ClientViewDebugEntry.STATUS_ID)));
            List<String> detail = client.computeOnClient(minecraft -> plain(capture(minecraft, ClientViewDebugEntry.DETAIL_ID)));
            boolean enabled = client.computeOnClient(minecraft -> minecraft.debugEntries.isCurrentlyEnabled(ClientViewDebugEntry.STATUS_ID));
            Path screenshot = context.takeScreenshot("wormholes-f3-stats");
            LOGGER.info("WORMHOLES_F3_STATS summary={} detail={} screenshot={}", summary, detail, screenshot.toAbsolutePath());
            SeamlessScenario.assertTrue(enabled, "the Wormholes status entry is not enabled on the F3 screen");
            SeamlessScenario.assertTrue(summary.size() == 4, "expected four Wormholes summary lines without a mismatch: " + summary);
            assertEquals("Wormholes: Connected | " + WormholesBuild.version() + " " + platform, summary.get(0));
            assertEquals("Protocols: view v" + ViewStreamLimits.WIRE_VERSION + ", travel on, menus v" + MenuProtocol.VERSION
                + (nativeScreens ? " native" : " chest"), summary.get(1));
            SeamlessScenario.assertTrue(PORTALS.matcher(summary.get(2)).matches(), "unexpected Wormholes portals line: " + summary.get(2));
            SeamlessScenario.assertTrue(TRAVEL.matcher(summary.get(3)).matches(), "unexpected Wormholes travel line: " + summary.get(3));
            SeamlessScenario.assertTrue(detail.size() >= 4 && detail.getFirst().startsWith("ClientView: CLIENT_VIEW ")
                && detail.stream().anyMatch(line -> line.startsWith("Stream: ")) && detail.stream().anyMatch(line -> line.startsWith("Render: "))
                && detail.stream().anyMatch(line -> line.startsWith("stencil=on ")), "unexpected Wormholes detail lines: " + detail);
            for (String line : summary) {
                SeamlessScenario.assertTrue(line.length() <= WormholesDebugLines.MAX_WIDTH, "F3 line is wider than the column: " + line);
            }
            for (String line : detail) {
                SeamlessScenario.assertTrue(line.length() <= WormholesDebugLines.MAX_WIDTH, "F3 detail line is wider than the column: " + line);
            }
            client.runOnClient(minecraft -> {
                minecraft.debugEntries.setStatus(ClientViewDebugEntry.DETAIL_ID, WormholesClient.instance().config().showDebugOverlay
                    ? DebugScreenEntryStatus.IN_OVERLAY : DebugScreenEntryStatus.NEVER);
                minecraft.debugEntries.setOverlayVisible(false);
            });
            SeamlessScenario.finish(client, server, route);
            LOGGER.info("WORMHOLES_F3_STATS_PASS {}", summary);
        }
    }

    private static boolean settled(Minecraft minecraft) {
        List<String> summary = plain(capture(minecraft, ClientViewDebugEntry.STATUS_ID));
        return ClientMenus.version() > 0 && summary.size() == 4 && PORTALS.matcher(summary.get(2)).matches()
            && TRAVEL.matcher(summary.get(3)).matches();
    }

    private static List<String> capture(Minecraft minecraft, Identifier id) {
        LineCapture capture = new LineCapture();
        DebugScreenEntries.getEntry(id).display(capture, minecraft.level, null, null);
        return capture.lines;
    }

    private static List<String> plain(List<String> lines) {
        List<String> plain = new ArrayList<>(lines.size());
        for (String line : lines) {
            plain.add(ChatFormatting.stripFormatting(line));
        }
        return plain;
    }

    private static void assertEquals(String expected, String actual) {
        SeamlessScenario.assertTrue(expected.equals(actual), "expected F3 line '" + expected + "' but was '" + actual + "'");
    }

    private static final class LineCapture implements DebugScreenDisplayer {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void addPriorityLine(String line) {
            lines.add(line);
        }

        @Override
        public void addLine(String line) {
            lines.add(line);
        }

        @Override
        public void addToGroup(Identifier group, Collection<String> groupLines) {
            lines.addAll(groupLines);
        }

        @Override
        public void addToGroup(Identifier group, String line) {
            lines.add(line);
        }
    }
}
