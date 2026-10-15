package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class PortalRemovalProjectionClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String VARIANT = "wormholes.clientgametest.variant";

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        String filter = System.getProperty(VARIANT, "").trim();
        List<String> failures = new ArrayList<>();
        for (Variant variant : List.of(
            new Variant("portal-removal-same-dimension-menu", (client, server, label) -> PortalRemovalProjection.viewer(client, server, label,
                Level.OVERWORLD, PortalPairScene.Side.LOCAL, PortalRemovalProjection.Method.MENU)),
            new Variant("portal-removal-end-viewer-menu", (client, server, label) -> PortalRemovalProjection.viewer(client, server, label,
                Level.END, PortalPairScene.Side.LOCAL, PortalRemovalProjection.Method.MENU)),
            new Variant("portal-removal-end-partner-command", (client, server, label) -> PortalRemovalProjection.viewer(client, server, label,
                Level.END, PortalPairScene.Side.REMOTE, PortalRemovalProjection.Method.COMMAND)),
            new Variant("portal-removal-after-end-crossing-menu", (client, server, label) -> PortalRemovalProjection.afterCrossing(client, server,
                label, true)),
            new Variant("portal-removal-one-way-after-end-crossing-menu", (client, server, label) -> PortalRemovalProjection.afterCrossing(client,
                server, label, false)))) {
            if (!variant.label().contains(filter)) {
                continue;
            }
            try {
                run(context, variant);
            } catch (AssertionError failure) {
                LOGGER.error("WORMHOLES_PORTAL_REMOVAL_FAIL {}", variant.label(), failure);
                failures.add(failure.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " portal removal variants failed:\n" + String.join("\n", failures));
        }
    }

    private static void run(ClientGameTestContext context, Variant variant) {
        TestWorldSave save;
        PortalRemovalProjection.Removal removal;
        try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
            FabricConnection connection = FabricConnection.of(world);
            FabricSeamlessClient client = new FabricSeamlessClient(context, connection);
            removal = variant.scenario().run(client, new FabricSeamlessServer(world.getServer(), connection), variant.label());
            client.restoreDefaultGameOptions();
            save = world.getWorldSave();
        }
        try (TestSingleplayerContext world = save.open()) {
            FabricConnection connection = FabricConnection.of(world);
            PortalRemovalProjection.assertReloaded(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(world.getServer(), connection),
                removal);
        }
        LOGGER.info("WORMHOLES_PORTAL_REMOVAL_PASS {}", variant.label());
    }

    private interface Scenario {
        PortalRemovalProjection.Removal run(SeamlessClient client, SeamlessServer server, String label);
    }

    private record Variant(String label, Scenario scenario) {
    }
}
