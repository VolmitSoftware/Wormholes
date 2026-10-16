package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.clientgametest.menu.MenuScreenDriver;
import art.arcane.wormholes.clientgametest.menu.NativeMenuShots;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.ClientScreens;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.modded.client.menu.NativeMenuScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class NativeMenuPaperScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String FIXTURE_PERMISSION = "wormholesfixture.use";
    private static final String PROJECTION = "toggle-projections";
    private static final String RENAME = "set-name";
    private static final String SETTINGS = "portal-settings";
    private static final String ACCESS = "permission-mode";
    private static final String DENIED = "Only the portal owner or an administrator can edit this portal.";
    private static final String PORTAL_NAME = "PaperNative";
    private static final String RENAMED = "Paper Native Arch";
    private static final List<String> HOME_ELEMENTS = List.of("portal-placard", "set-destination", PROJECTION, SETTINGS, RENAME,
        "portal-orientation", "set-mode", "destroy");
    private static final List<String> HOME_WIDGETS = List.of("set-destination", PROJECTION, SETTINGS, RENAME, "portal-orientation", "set-mode",
        "destroy");
    private static final List<String> SETTINGS_ELEMENTS = List.of("settings-placard", ACCESS, "travel-direction", "network-view-quality",
        "settings-sync", "activation-range", "render-mode", "travel-cost");
    private static final List<String> SETTINGS_WIDGETS = List.of(ACCESS, "travel-direction", "network-view-quality", "settings-sync",
        "activation-range", "render-mode", "travel-cost");
    private static final int NEGOTIATION_TICKS = 400;
    private static final int TIMEOUT_TICKS = 200;
    private static final int POLL_ATTEMPTS = 40;
    private static final int POLL_INTERVAL_TICKS = 5;

    private final ExternalServerSession session;
    private final MenuScreenDriver driver;
    private final NativeMenuShots shots;
    private final String label;
    private UUID portalId;
    private boolean revoked;

    private NativeMenuPaperScenario(ExternalServerSession session, MenuScreenDriver driver, NativeMenuShots shots, String label) {
        this.session = session;
        this.driver = driver;
        this.shots = shots;
        this.label = label;
    }

    static void run(TestClient client, MenuScreenDriver driver, NativeMenuShots shots, String label) {
        String address = ExternalServerSession.configuredAddress();
        if (address.isEmpty()) {
            LOGGER.info("[{}] skipped without an external server", label);
            return;
        }
        try (ExternalServerSession session = ExternalServerSession.connect(client, address)) {
            new NativeMenuPaperScenario(session, driver, shots, label).run();
        }
    }

    private void run() {
        driver.input().waitFor(minecraft -> ClientMenus.session().version() >= 1, NEGOTIATION_TICKS);
        int clientVersion = driver.input().computeOnClient(minecraft -> ClientMenus.session().version());
        String serverVersion = session.command("whtest native", "menu-native");
        check(serverVersion.equals(Integer.toString(clientVersion)), "the server accepted native menus at " + serverVersion + " but the client runs " + clientVersion);
        LOGGER.info("[{}] native menus negotiated: client version {} accepted by the server at {}", label, clientVersion, serverVersion);
        String permitted = session.command("whtest perm " + FIXTURE_PERMISSION, "perm " + FIXTURE_PERMISSION);
        check(permitted.equals("true"), "the joined player reports " + FIXTURE_PERMISSION + "=" + permitted);
        portalId = UUID.fromString(session.command("whtest portal create " + PORTAL_NAME, "portal-create"));
        try {
            home();
            projection();
            rename();
            settings();
            denied();
        } finally {
            if (revoked) {
                String restored = session.command("whtest portal restore " + portalId, "portal-restore " + portalId);
                LOGGER.info("[{}] restored manage rights: {}", label, restored);
            }
        }
        LOGGER.info("WORMHOLES_NATIVE_MENU_PAPER_PASS portal={} screenshots={}", portalId, shots.captured().size());
    }

    private void home() {
        session.command("whtest menu " + portalId, "menu " + portalId);
        awaitScreen(PortalRoutes.HOME);
        String other = driver.input().computeOnClient(minecraft -> ClientScreens.current() instanceof NativeMenuScreen ? "" : describe(ClientScreens.current()));
        check(other.isEmpty(), "Home opened " + other + " instead of a native screen");
        List<String> missing = driver.read(screen -> missing(screen, HOME_ELEMENTS, HOME_WIDGETS));
        check(missing.isEmpty(), "the native Home screen is missing " + missing);
        shots.capture("paper-home");
    }

    private void projection() {
        String before = field("projection");
        String expected = before.equals("on") ? "off" : "on";
        act(() -> driver.toggle(PROJECTION));
        awaitField("projection", expected, "the native projection toggle");
        boolean shown = driver.read(screen -> projectionShown(screen.snapshot().element(PROJECTION).orElse(null)));
        check(shown == expected.equals("on"), "the repainted toggle shows " + shown + " for server projection " + expected);
        LOGGER.info("[{}] projection {} -> {}", label, before, expected);
        shots.capture("paper-home-toggled");
    }

    private void rename() {
        act(() -> driver.typeAndApply(RENAME, RENAMED));
        awaitField("name", RENAMED, "the native rename");
        LOGGER.info("[{}] renamed {} to {}", label, PORTAL_NAME, RENAMED);
    }

    private void settings() {
        driver.click(SETTINGS);
        awaitScreen(PortalRoutes.SETTINGS_MAIN);
        List<String> missing = driver.read(screen -> missing(screen, SETTINGS_ELEMENTS, SETTINGS_WIDGETS));
        check(missing.isEmpty(), "the native Settings screen is missing " + missing);
        shots.capture("paper-settings");
        String access = field("access");
        act(() -> driver.click(ACCESS));
        String changed = awaitChange("access", access, "the native access cycle");
        LOGGER.info("[{}] access {} -> {}", label, access, changed);
        driver.back();
        awaitScreen(PortalRoutes.HOME);
    }

    private void denied() {
        String projection = field("projection");
        QaReplies.clearRecent();
        revoked = true;
        boolean[] sent = new boolean[1];
        String reply = session.command("whtest portal revoke " + portalId, "portal-revoke " + portalId,
            minecraft -> sent[0] = ClientMenus.session().act(PROJECTION, MenuInteraction.PRIMARY, null));
        check(sent[0], "the client session did not send the projection toggle after the revocation");
        check(reply.endsWith("manage=false"), "the fixture left manage rights after the revocation: " + reply);
        driver.awaitClosed();
        awaitNotice(DENIED);
        String after = field("projection");
        check(after.equals(projection), "a player who lost manage rights changed projection from " + projection + " to " + after);
        Screen current = driver.input().computeOnClient(minecraft -> ClientScreens.current());
        check(!(current instanceof AbstractContainerScreen<?>), "the refused action reopened a chest: " + describe(current));
        LOGGER.info("[{}] revoked ({}), refused toggle kept projection {}, menu closed, notice '{}', screen {}", label, reply, after, DENIED,
            describe(current));
    }

    private void act(Runnable action) {
        long revision = driver.read(screen -> screen.snapshot().revision());
        action.run();
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> screen.snapshot().revision() > revision && !screen.feedbackLine().pending())
            .orElse(false), TIMEOUT_TICKS);
        driver.input().waitTicks(1);
    }

    private void awaitScreen(String screenId) {
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> screen.snapshot().screenId().equals(screenId))
            .orElse(false), TIMEOUT_TICKS);
        driver.input().waitTicks(1);
    }

    private void awaitNotice(String notice) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (noticed(notice)) {
                return;
            }
            driver.input().waitTicks(1);
        }
        throw new AssertionError(label + ": the revoked player saw no '" + notice + "' notice: " + QaReplies.recent());
    }

    private void awaitField(String field, String expected, String description) {
        String value = "";
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            value = field(field);
            if (value.equals(expected)) {
                return;
            }
            driver.input().waitTicks(POLL_INTERVAL_TICKS);
        }
        throw new AssertionError(label + ": " + description + " left " + field + "=" + value + " instead of " + expected);
    }

    private String awaitChange(String field, String before, String description) {
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            String value = field(field);
            if (!value.equals(before)) {
                return value;
            }
            driver.input().waitTicks(POLL_INTERVAL_TICKS);
        }
        throw new AssertionError(label + ": " + description + " left " + field + "=" + before);
    }

    private String field(String field) {
        String value = session.command("whtest portal get " + portalId + " " + field, "portal-get " + portalId + " " + field);
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean noticed(String notice) {
        for (String line : QaReplies.recent()) {
            if (line.contains(notice)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> missing(NativeMenuScreen screen, List<String> elements, List<String> widgets) {
        List<String> missing = new ArrayList<>();
        for (String id : elements) {
            if (screen.snapshot().element(id).isEmpty()) {
                missing.add(id);
            }
        }
        for (String id : widgets) {
            if (screen.widgetFor(id).isEmpty()) {
                missing.add(id + " widget");
            }
        }
        return missing;
    }

    private static boolean projectionShown(MenuElement element) {
        return element instanceof ToggleElement toggle && toggle.value();
    }

    private static String describe(Screen screen) {
        return screen == null ? "no screen" : screen.getClass().getName();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
