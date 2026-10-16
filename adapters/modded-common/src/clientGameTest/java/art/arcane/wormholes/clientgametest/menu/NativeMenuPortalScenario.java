package art.arcane.wormholes.clientgametest.menu;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.modded.MinecraftAccessService;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import com.mojang.blaze3d.platform.InputConstants;
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

public final class NativeMenuPortalScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String HOME_NAME = "Harbor Home";
    private static final String GATE_NAME = "Gate Alpha";
    private static final String PEER_NAME = "Gate Beta";
    private static final String PROJECTION_LABEL = "Projection";
    private static final float AIM_UP_PITCH = -75.0F;
    private static final int NEGOTIATION_TICKS = 200;
    private static final int TIMEOUT_TICKS = 200;
    private static final int LOOK_SYNC_TICKS = 3;
    private static final int PORTAL_WIDTH = 2;
    private static final int PORTAL_HEIGHT = 3;

    private final MenuScreenDriver driver;
    private final NativeMenuShots shots;
    private final NativeMenuServer server;
    private UUID homeId;
    private UUID gateId;
    private UUID peerId;
    private AutoCloseable typeGrant;

    private NativeMenuPortalScenario(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        this.driver = driver;
        this.shots = shots;
        this.server = server;
    }

    public static void run(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        new NativeMenuPortalScenario(driver, shots, server).run();
    }

    private void run() {
        driver.input().waitFor(minecraft -> ClientMenus.session().version() >= 1, NEGOTIATION_TICKS);
        homeId = server.compute((minecraftServer, player) -> createPortal(minecraftServer, player, new BlockPos(3, 0, 4), PortalType.PORTAL, HOME_NAME));
        gateId = server.compute((minecraftServer, player) -> createPortal(minecraftServer, player, new BlockPos(-4, 0, 4), PortalType.GATEWAY, GATE_NAME));
        peerId = server.compute((minecraftServer, player) -> createPortal(minecraftServer, player, new BlockPos(-4, 0, -5), PortalType.GATEWAY, PEER_NAME));
        typeGrant = server.compute(NativeMenuPortalScenario::grantWormholeType);
        try {
            home();
            mode();
            orientation();
            gateway();
        } finally {
            driver.input().runOnClient(minecraft -> minecraft.player.setXRot(0.0F));
            server.run((minecraftServer, player) -> cleanup(minecraftServer));
        }
        LOGGER.info("WORMHOLES_NATIVE_MENU_PORTAL_PASS screenshots={}", shots.captured().size());
    }

    private void home() {
        open(PortalRoutes.home(homeId), PortalRoutes.HOME);
        check(driver.label("toggle-projections").equals(PROJECTION_LABEL), "the projection row reads " + driver.label("toggle-projections"));
        shots.capture(PortalRoutes.HOME);
        act(() -> driver.toggle("toggle-projections"));
        awaitPortal(homeId, "the projection toggle", portal -> portal.getProjectionMode() == ProjectionMode.OFF);
        check(driver.label("toggle-projections").equals(PROJECTION_LABEL), "the toggled projection row reads " + driver.label("toggle-projections"));
        act(() -> driver.toggle("toggle-projections"));
        awaitPortal(homeId, "the projection restore", portal -> portal.getProjectionMode() == ProjectionMode.ON);
    }

    private void mode() {
        driver.click("set-mode");
        awaitScreen(PortalRoutes.MODE);
        shots.capture(PortalRoutes.MODE);
        driver.click("mode-wormhole");
        driver.awaitClosed();
        awaitPortal(homeId, "the wormhole mode pick", portal -> portal.getType() == PortalType.WORMHOLE);
    }

    private void orientation() {
        open(PortalRoutes.home(homeId), PortalRoutes.HOME);
        driver.click("portal-orientation");
        awaitScreen(PortalRoutes.ORIENTATION);
        shots.capture(PortalRoutes.ORIENTATION);
        Frame before = read(homeId, MinecraftPortal::getFrame);
        driver.click("rotate-clockwise");
        awaitScreen(PortalRoutes.HOME);
        awaitPortal(homeId, "the clockwise rotation", portal -> !portal.getFrame().equals(before));
        driver.click("portal-orientation");
        awaitScreen(PortalRoutes.ORIENTATION);
        driver.click("set-direction");
        driver.awaitClosed();
        driver.input().runOnClient(minecraft -> minecraft.player.setXRot(AIM_UP_PITCH));
        driver.input().waitTicks(LOOK_SYNC_TICKS);
        driver.input().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        awaitScreen(PortalRoutes.ORIENTATION);
        awaitPortal(homeId, "the aimed direction", portal -> portal.getFrame().getNormal() == Face.U);
        driver.input().runOnClient(minecraft -> minecraft.player.setXRot(0.0F));
        driver.escape();
        awaitScreen(PortalRoutes.HOME);
        driver.escape();
        driver.awaitClosed();
    }

    private void gateway() {
        open(PortalRoutes.home(gateId), PortalRoutes.HOME);
        driver.click("set-destination");
        awaitScreen(PortalRoutes.GATEWAY);
        check(!placard().contains(PEER_NAME), "the unpaired gateway placard reads " + placard());
        driver.click("choose-gateway-destination");
        awaitScreen(PortalRoutes.DESTINATION_PICKER);
        driver.click(rowNamed(PEER_NAME));
        driver.awaitClosed();
        awaitPortal(gateId, "the gateway pair", portal -> peerId.equals(portal.getDestinationId()));
        open(PortalRoutes.gateway(gateId), PortalRoutes.GATEWAY);
        check(placard().contains(PEER_NAME), "the paired gateway placard reads " + placard());
        shots.capture(PortalRoutes.GATEWAY);
        driver.escape();
        driver.awaitClosed();
    }

    private String placard() {
        return driver.read(screen -> screen.snapshot().element("gateway-pair-placard")
            .map(element -> MenuText.stripColors(String.join(" ", element.description())))
            .orElse(""));
    }

    private String rowNamed(String name) {
        return driver.read(screen -> {
            for (MenuElement element : screen.snapshot().elements()) {
                if (element.id().startsWith("portal-") && MenuText.stripColors(element.label()).contains(name)) {
                    return element.id();
                }
            }
            throw new AssertionError("the destination picker has no row for " + name);
        });
    }

    private void open(MenuRoute route, String screenId) {
        server.run((minecraftServer, player) -> runtime(minecraftServer).menus().open(player.getUUID(), route));
        awaitScreen(screenId);
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

    private void awaitPortal(UUID portalId, String description, Predicate<MinecraftPortal> condition) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (server.compute((minecraftServer, player) -> condition.test(portal(minecraftServer, portalId)))) {
                return;
            }
            driver.input().waitTicks(1);
        }
        throw new AssertionError(description + " never reached the server portal");
    }

    private <T> T read(UUID portalId, Function<MinecraftPortal, T> reader) {
        return server.compute((minecraftServer, player) -> reader.apply(portal(minecraftServer, portalId)));
    }

    private void cleanup(MinecraftServer minecraftServer) {
        if (typeGrant != null) {
            try {
                typeGrant.close();
            } catch (Exception failure) {
                throw new IllegalStateException("the portal type grant could not be removed", failure);
            }
        }
        for (UUID id : List.of(homeId, gateId, peerId)) {
            runtime(minecraftServer).portals().remove(id);
        }
    }

    private static UUID createPortal(MinecraftServer minecraftServer, ServerPlayer player, BlockPos offset, PortalType type, String name) {
        WormholesModRuntime runtime = runtime(minecraftServer);
        ServerLevel level = minecraftServer.overworld();
        BlockPos base = player.blockPosition().offset(offset);
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
        MinecraftPortal portal = runtime.portals().create(player.getUUID(), level, cells, type, new Vec3(0, 0, -1));
        check(portal != null, "the portal " + name + " was refused");
        check(runtime.portals().update(player, portal.getId(), configured -> configured.setName(name)), "the portal " + name + " could not be named");
        return portal.getId();
    }

    private static AutoCloseable grantWormholeType(MinecraftServer minecraftServer, ServerPlayer player) {
        String node = PortalType.WORMHOLE.permission();
        return runtime(minecraftServer).access().register((candidate, requested) -> candidate.getUUID().equals(player.getUUID())
            && requested.equals(node) ? MinecraftAccessService.Decision.ALLOW : MinecraftAccessService.Decision.UNSET);
    }

    private static MinecraftPortal portal(MinecraftServer minecraftServer, UUID portalId) {
        return Objects.requireNonNull(runtime(minecraftServer).portals().get(portalId), "the portal test portal " + portalId + " is gone");
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
