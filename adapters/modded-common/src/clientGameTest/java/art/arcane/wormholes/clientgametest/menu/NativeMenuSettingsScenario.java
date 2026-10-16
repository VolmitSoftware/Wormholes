package art.arcane.wormholes.clientgametest.menu;

import art.arcane.wormholes.clientgametest.QaReplies;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.cosmetics.CosmeticsRoutes;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.settings.SettingsRoutes;
import art.arcane.wormholes.modded.MinecraftAccessService;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

public final class NativeMenuSettingsScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String DENIED = "Only the portal owner or an administrator can edit this portal.";
    private static final String ADMIN_NODE = "wormholes.admin";
    private static final int NEGOTIATION_TICKS = 200;
    private static final int TIMEOUT_TICKS = 200;
    private static final int PORTAL_WIDTH = 2;
    private static final int PORTAL_HEIGHT = 3;
    private static final int LIGHT_BLUE = 0x3AB3DA;

    private final MenuScreenDriver driver;
    private final NativeMenuShots shots;
    private final NativeMenuServer server;
    private UUID portalId;
    private AutoCloseable adminDenial;

    private NativeMenuSettingsScenario(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        this.driver = driver;
        this.shots = shots;
        this.server = server;
    }

    public static void run(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        new NativeMenuSettingsScenario(driver, shots, server).run();
    }

    private void run() {
        driver.input().waitFor(minecraft -> ClientMenus.session().version() >= 1, NEGOTIATION_TICKS);
        portalId = server.compute(NativeMenuSettingsScenario::createPortal);
        try {
            home();
            settings();
            advanced();
            blackout();
            ambient();
            skin();
            revoked();
        } finally {
            server.run((minecraftServer, player) -> cleanup(minecraftServer));
        }
        LOGGER.info("WORMHOLES_NATIVE_MENU_SETTINGS_PASS screenshots={}", shots.captured().size());
    }

    private void home() {
        server.run((minecraftServer, player) -> runtime(minecraftServer).menus().open(player.getUUID(), PortalRoutes.home(portalId)));
        awaitScreen(PortalRoutes.HOME);
        act(() -> driver.typeAndApply("set-name", "Harbor Arch"));
        awaitPortal("the Apply rename", portal -> portal.getName().equals("Harbor Arch"));
        driver.click("portal-settings");
        awaitScreen(PortalRoutes.SETTINGS_MAIN);
    }

    private void settings() {
        shots.capture(PortalRoutes.SETTINGS_MAIN);
        boolean synced = read(MinecraftPortal::isSettingsSyncEnabled);
        act(() -> driver.toggle("settings-sync"));
        awaitPortal("the settings sync toggle", portal -> portal.isSettingsSyncEnabled() != synced);
        check(driver.feedback().isPresent(), "the settings sync toggle showed no feedback");
        act(() -> driver.click("permission-mode"));
        awaitPortal("the access mode cycle", portal -> portal.getPermissionMode() == PortalPermissionMode.WHITELIST);
        driver.clickSide("network-view-quality", MenuInteraction.TERTIARY);
        awaitScreen(SettingsRoutes.ADVANCED);
    }

    private void advanced() {
        shots.capture(SettingsRoutes.ADVANCED);
        int depth = read(MinecraftPortal::getNetworkViewDepth);
        act(() -> driver.increment("network-view-depth", false));
        awaitPortal("the capture radius increment", portal -> portal.getNetworkViewDepth() == depth + 4);
        act(() -> driver.decrement("network-view-depth", true));
        awaitPortal("the capture radius large decrement", portal -> portal.getNetworkViewDepth() == Math.max(1, depth + 4 - 16));
        double[] reached = new double[1];
        act(() -> reached[0] = driver.setSlider("network-view-depth", 64.0D));
        int slid = (int) Math.round(reached[0]);
        awaitPortal("the capture radius slider", portal -> portal.getNetworkViewDepth() == slid);
        check(driver.value("network-view-depth").equals(Integer.toString(slid)), "the capture radius row shows " + driver.value("network-view-depth"));
        act(() -> driver.typeAndApply("network-view-fallback", "minecraft:stone"));
        awaitPortal("the fallback block Apply", portal -> portal.getNetworkViewFallbackBlock().equals("minecraft:stone"));
        driver.back();
        awaitScreen(PortalRoutes.SETTINGS_MAIN);
    }

    private void blackout() {
        driver.clickSide("blackout-background", MenuInteraction.SECONDARY);
        awaitScreen(CosmeticsRoutes.BLACKOUT);
        shots.capture(CosmeticsRoutes.BLACKOUT);
        act(() -> driver.select("blackout-color-red"));
        awaitPortal("the blackout colour pick", portal -> portal.getBlackoutColor() == BlackoutColor.RED);
        driver.back();
        awaitScreen(PortalRoutes.SETTINGS_MAIN);
    }

    private void ambient() {
        driver.clickSide("ambient-particles", MenuInteraction.SECONDARY);
        awaitScreen(CosmeticsRoutes.AMBIENT);
        shots.capture(CosmeticsRoutes.AMBIENT);
        act(() -> driver.select("ambient-color-light_blue"));
        awaitPortal("the ambient dye pick", portal -> portal.getAmbientColor() == LIGHT_BLUE);
        check(driver.value("ambient-red").equals(Integer.toString((LIGHT_BLUE >> 16) & 0xFF)), "the red channel shows " + driver.value("ambient-red"));
        driver.back();
        awaitScreen(PortalRoutes.SETTINGS_MAIN);
    }

    private void skin() {
        driver.click("surface-skin");
        awaitScreen(CosmeticsRoutes.SKIN);
        shots.capture(CosmeticsRoutes.SKIN);
        adminDenial = server.compute((minecraftServer, player) -> denyAdministrator(minecraftServer, player.getUUID()));
        long revision = revision();
        driver.click("surface-skin-glass");
        driver.input().waitFor(minecraft -> NativeMenuListener.current().map(screen -> !screen.feedbackLine().pending()).orElse(false), TIMEOUT_TICKS);
        check(driver.feedback().filter(DENIED::equals).isPresent(), "a manager without the surface skin node saw " + driver.feedback());
        check(revision() == revision, "the refused skin repainted the screen");
        check(read(MinecraftPortal::getSurfaceSkin).isEmpty(), "the refused glass skin reached the portal");
        driver.back();
        awaitScreen(PortalRoutes.SETTINGS_MAIN);
    }

    private void revoked() {
        boolean synced = read(MinecraftPortal::isSettingsSyncEnabled);
        QaReplies.clearRecent();
        UUID stranger = UUID.randomUUID();
        server.run((minecraftServer, player) -> check(runtime(minecraftServer).portals().update(player, portalId, portal -> portal.setOwner(stranger)),
            "the owner transfer was refused"));
        boolean sent = driver.input().computeOnClient(minecraft -> ClientMenus.session().act("settings-sync", MenuInteraction.PRIMARY, null));
        driver.awaitClosed();
        check(read(MinecraftPortal::isSettingsSyncEnabled) == synced, "a player who lost manage rights changed settings sync (sent=" + sent + ")");
        for (int tick = 0; tick < TIMEOUT_TICKS && !QaReplies.recent().contains(DENIED); tick++) {
            driver.input().waitTicks(1);
        }
        check(QaReplies.recent().contains(DENIED), "the revoked player saw no denial notice: " + QaReplies.recent());
    }

    private void act(Runnable action) {
        long revision = revision();
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

    private void awaitPortal(String description, Predicate<MinecraftPortal> condition) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (server.compute((minecraftServer, player) -> condition.test(portal(minecraftServer)))) {
                return;
            }
            driver.input().waitTicks(1);
        }
        throw new AssertionError(description + " never reached the server portal");
    }

    private <T> T read(Function<MinecraftPortal, T> reader) {
        return server.compute((minecraftServer, player) -> reader.apply(portal(minecraftServer)));
    }

    private long revision() {
        return driver.read(screen -> screen.snapshot().revision());
    }

    private MinecraftPortal portal(MinecraftServer minecraftServer) {
        return Objects.requireNonNull(runtime(minecraftServer).portals().get(portalId), "the settings test portal is gone");
    }

    private void cleanup(MinecraftServer minecraftServer) {
        if (adminDenial != null) {
            try {
                adminDenial.close();
            } catch (Exception failure) {
                throw new IllegalStateException("the administrator denial could not be removed", failure);
            }
        }
        runtime(minecraftServer).portals().remove(portalId);
    }

    private static UUID createPortal(MinecraftServer minecraftServer, ServerPlayer player) {
        ServerLevel level = minecraftServer.overworld();
        BlockPos base = player.blockPosition().offset(3, 0, 4);
        for (int x = -1; x <= PORTAL_WIDTH; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= PORTAL_HEIGHT; y++) {
                    level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        List<BlockPos> cells = new ArrayList<>(PORTAL_WIDTH * PORTAL_HEIGHT);
        for (int x = 0; x < PORTAL_WIDTH; x++) {
            for (int y = 0; y < PORTAL_HEIGHT; y++) {
                cells.add(base.offset(x, y, 0));
            }
        }
        MinecraftPortal portal = runtime(minecraftServer).portals().create(player.getUUID(), level, cells, PortalType.PORTAL, new Vec3(0, 0, -1));
        check(portal != null, "the settings test portal was refused");
        return portal.getId();
    }

    private static AutoCloseable denyAdministrator(MinecraftServer minecraftServer, UUID playerId) {
        return runtime(minecraftServer).access().register((candidate, node) -> candidate.getUUID().equals(playerId) && node.equals(ADMIN_NODE)
            ? MinecraftAccessService.Decision.DENY
            : MinecraftAccessService.Decision.UNSET);
    }

    private static WormholesModRuntime runtime(MinecraftServer minecraftServer) {
        return Objects.requireNonNull(WormholesModRuntime.forServer(minecraftServer), "Wormholes runtime is not running on this server");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
