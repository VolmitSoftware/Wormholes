package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.menu.MenuScreenDriver;
import art.arcane.wormholes.clientgametest.menu.NativeMenuShots;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.ClientScreens;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.modded.client.menu.NativeMenuScreen;
import art.arcane.wormholes.portal.ProjectionMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

final class NativeMenuDriverPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String PROJECTION = "toggle-projections";
    private static final List<String> HOME_ELEMENTS = List.of("portal-placard", "set-destination", PROJECTION, "portal-settings", "set-name",
        "portal-orientation", "set-mode", "destroy");
    private static final List<String> HOME_WIDGETS = List.of("set-destination", PROJECTION, "portal-settings", "set-name", "portal-orientation",
        "set-mode", "destroy");
    private static final int NEGOTIATION_TICKS = 200;
    private static final int TIMEOUT_TICKS = 200;

    private NativeMenuDriverPass() {
    }

    static void home(DriverClient client, CommandSeamlessServer server, String label) {
        client.waitFor(minecraft -> ClientMenus.session().version() >= 1, NEGOTIATION_TICKS);
        PortalPairScene.Pair pair = server.buildPortalPair(new PortalPairScene.Spec(PortalPairScene.Orientation.VERTICAL, Level.OVERWORLD,
            PortalPairScene.Side.LOCAL, PortalPairScene.Link.LINK_AND_RETURN));
        MenuScreenDriver driver = new MenuScreenDriver(new DriverNativeMenuInput(client));
        Path directory = client.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots").resolve("native-menus"));
        NativeMenuShots shots = new NativeMenuShots(driver, directory);
        try {
            client.runOnClient(minecraft -> minecraft.getConnection().sendCommand("wormholes edit " + pair.local()));
            client.waitFor(minecraft -> NativeMenuListener.current().map(screen -> screen.snapshot().screenId().equals(PortalRoutes.HOME)).orElse(false),
                TIMEOUT_TICKS);
            client.waitTicks(1);
            assertHome(client, driver);
            shots.capture(PortalRoutes.HOME);
            ProjectionMode before = server.projection(pair.local());
            long revision = driver.read(screen -> screen.snapshot().revision());
            driver.toggle(PROJECTION);
            client.waitFor(minecraft -> NativeMenuListener.current()
                .map(screen -> screen.snapshot().revision() > revision && !screen.feedbackLine().pending())
                .orElse(false), TIMEOUT_TICKS);
            ProjectionMode after = server.projection(pair.local());
            assertTrue(after == before.next(), label + ": the native projection toggle left the server at " + after + " (was " + before + ")");
            boolean shown = driver.read(screen -> projectionShown(screen.snapshot()));
            assertTrue(shown == (after == ProjectionMode.ON), label + ": the repainted toggle shows " + shown + " for server projection " + after);
            shots.capture(PortalRoutes.HOME + "-toggled");
            driver.escape();
            driver.awaitClosed();
            LOGGER.info("WORMHOLES_NATIVE_MENU_DRIVER_PASS fixture={} projection={}->{} screenshots={}", label, before, after, shots.captured().size());
        } finally {
            server.removePortalPair(pair);
        }
    }

    static ProjectionMode projection(MinecraftServer server, UUID portalId) {
        MinecraftPortal portal = Objects.requireNonNull(runtime(server).portals().get(portalId), "portal " + portalId);
        return portal.getProjectionMode();
    }

    private static void assertHome(DriverClient client, MenuScreenDriver driver) {
        String other = client.computeOnClient(minecraft -> ClientScreens.current() instanceof NativeMenuScreen ? "" : String.valueOf(ClientScreens.current()));
        assertTrue(other.isEmpty(), "Home opened " + other + " instead of a native screen");
        List<String> missing = driver.read(NativeMenuDriverPass::missingElements);
        assertTrue(missing.isEmpty(), "the native Home screen is missing " + missing);
    }

    private static List<String> missingElements(NativeMenuScreen screen) {
        List<String> missing = new ArrayList<>();
        for (String id : HOME_ELEMENTS) {
            if (screen.snapshot().element(id).isEmpty()) {
                missing.add(id);
            }
        }
        for (String id : HOME_WIDGETS) {
            if (screen.widgetFor(id).isEmpty()) {
                missing.add(id + " widget");
            }
        }
        return missing;
    }

    private static boolean projectionShown(MenuScreen snapshot) {
        MenuElement element = snapshot.element(PROJECTION).orElseThrow(() -> new AssertionError("the repainted Home lost " + PROJECTION));
        return element instanceof ToggleElement toggle && toggle.value();
    }
}
