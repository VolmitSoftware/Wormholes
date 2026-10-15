package art.arcane.wormholes.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EndReturnPortalClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String VARIANT = "wormholes.clientgametest.variant";

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientViewTestConfig.enableSeamless(true);
        String filter = System.getProperty(VARIANT, "").trim();
        List<String> failures = new ArrayList<>();
        for (PortalPairScene.Orientation orientation : PortalPairScene.Orientation.values()) {
            for (PortalPairScene.Side start : List.of(PortalPairScene.Side.REMOTE, PortalPairScene.Side.LOCAL)) {
                String label = "end-return-" + orientation.name().toLowerCase(Locale.ROOT) + "-from-"
                    + (start == PortalPairScene.Side.REMOTE ? "end" : "overworld");
                if (!label.contains(filter)) {
                    continue;
                }
                try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setAllowCommands(true)).create()) {
                    FabricConnection connection = FabricConnection.of(world);
                    EndReturnPortal.run(new FabricSeamlessClient(context, connection), new FabricSeamlessServer(world.getServer(), connection),
                        label, orientation, start);
                } catch (AssertionError failure) {
                    LOGGER.error("WORMHOLES_END_RETURN_FAIL {}", label, failure);
                    failures.add(failure.getMessage());
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " End return variants failed:\n" + String.join("\n", failures));
        }
    }
}
