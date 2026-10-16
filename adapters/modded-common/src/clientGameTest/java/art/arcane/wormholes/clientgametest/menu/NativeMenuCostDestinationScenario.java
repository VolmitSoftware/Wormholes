package art.arcane.wormholes.clientgametest.menu;

import art.arcane.wormholes.clientgametest.QaReplies;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.destination.DestinationRoutes;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.modded.MinecraftItemEncoding;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

public final class NativeMenuCostDestinationScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String DENIED = "Only the portal owner or an administrator can edit this portal.";
    private static final String COST_INVALID = "That item could not be saved as a travel cost.";
    private static final String COST_SET = "Travel now requires Diamond.";
    private static final String SOURCE_NAME = "Harbor Source";
    private static final String TARGET_NAME = "Alpha Target";
    private static final String FILLER_PREFIX = "Filler ";
    private static final String NEAREST_FILLER = "Filler 10";
    private static final String FIRST_FILLER = "Filler 01";
    private static final String SELECT_SCREEN = "destination.select";
    private static final String TRAVEL_COST = "travelCost";
    private static final int NEGOTIATION_TICKS = 200;
    private static final int TIMEOUT_TICKS = 200;
    private static final int PORTAL_WIDTH = 2;
    private static final int PORTAL_HEIGHT = 3;
    private static final int FILLERS = 46;
    private static final int FILLER_COLUMNS = 16;
    private static final int PER_PAGE = 45;
    private static final double SLIDER_TARGET = 64.0D;

    private final MenuScreenDriver driver;
    private final NativeMenuShots shots;
    private final NativeMenuServer server;
    private final List<UUID> created = new ArrayList<>(FILLERS + 2);
    private UUID sourceId;
    private UUID targetId;

    private NativeMenuCostDestinationScenario(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        this.driver = driver;
        this.shots = shots;
        this.server = server;
    }

    public static void run(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        new NativeMenuCostDestinationScenario(driver, shots, server).run();
    }

    private void run() {
        driver.input().waitFor(minecraft -> ClientMenus.session().version() >= 1, NEGOTIATION_TICKS);
        created.addAll(server.compute(NativeMenuCostDestinationScenario::createPortals));
        sourceId = created.get(0);
        targetId = created.get(1);
        try {
            cost();
            extensions();
            destination();
            selection();
            revoked();
        } finally {
            server.run((minecraftServer, player) -> cleanup(minecraftServer, player));
        }
        LOGGER.info("WORMHOLES_NATIVE_MENU_COST_DESTINATION_PASS screenshots={}", shots.captured().size());
    }

    private void cost() {
        open(PortalRoutes.home(sourceId), PortalRoutes.HOME);
        driver.click("portal-settings");
        awaitScreen(PortalRoutes.SETTINGS_MAIN);
        driver.click("travel-cost");
        awaitScreen(PortalRoutes.COST_MAIN);
        hold(ItemStack.EMPTY);
        answer(() -> driver.click("travel-cost-vanilla"));
        check(driver.feedback().filter(COST_INVALID::equals).isPresent(), "an empty hand answered " + driver.feedback());
        check(read(portal -> price(portal).isEmpty()), "an empty hand stored a travel cost");
        hold(new ItemStack(Items.DIAMOND, 5));
        act(() -> driver.click("travel-cost-vanilla"));
        awaitPortal("the held item cost", portal -> "VANILLA".equals(price(portal).get("type")) && quantity(portal) == 1);
        check(server.compute((minecraftServer, player) -> MinecraftItemEncoding.decode(String.valueOf(price(source(minecraftServer)).get("item")),
            minecraftServer.registryAccess()).is(Items.DIAMOND)), "the held item cost is not a diamond");
        check(driver.feedback().filter(COST_SET::equals).isPresent(), "the held item cost answered " + driver.feedback());
        act(() -> driver.increment("travel-cost-quantity", false));
        awaitPortal("the quantity increment", portal -> quantity(portal) == 2);
        act(() -> driver.increment("travel-cost-quantity", true));
        awaitPortal("the quantity large increment", portal -> quantity(portal) == 10);
        double[] reached = new double[1];
        act(() -> reached[0] = driver.setSlider("travel-cost-quantity", SLIDER_TARGET));
        int slid = (int) Math.round(reached[0]);
        awaitPortal("the quantity slider", portal -> quantity(portal) == slid);
        check(driver.value("travel-cost-quantity").equals(Integer.toString(slid)), "the quantity row shows " + driver.value("travel-cost-quantity"));
        shots.capture(PortalRoutes.COST_MAIN);
        act(() -> driver.click("travel-cost-free"));
        awaitPortal("the free mode", portal -> price(portal).isEmpty());
        driver.back();
        awaitScreen(PortalRoutes.SETTINGS_MAIN);
    }

    private void extensions() {
        driver.click("extensions");
        awaitScreen(PortalRoutes.EXTENSIONS_MAIN);
        shots.capture(PortalRoutes.EXTENSIONS_MAIN);
        for (String entry : List.of("access", "rules", "nexus-network", "transit", "fidelity")) {
            check(driver.read(screen -> screen.rowFor(entry).isPresent()), "More settings has no " + entry + " entry");
        }
        driver.back();
        awaitScreen(PortalRoutes.SETTINGS_MAIN);
        driver.back();
        awaitScreen(PortalRoutes.HOME);
    }

    private void destination() {
        driver.click("set-destination");
        awaitScreen(PortalRoutes.DESTINATION_PICKER);
        MenuPaging paging = driver.read(screen -> screen.snapshot().paging());
        check(paging != null && paging.page() == 0 && paging.pageCount() == 2, "the picker paging is " + paging);
        check(label("portal-0").contains(TARGET_NAME) && label("portal-1").contains(NEAREST_FILLER),
            "the smart order starts with " + label("portal-0") + ", " + label("portal-1"));
        shots.capture(PortalRoutes.DESTINATION_PICKER);
        act(driver::next);
        check(driver.read(screen -> screen.snapshot().paging().page()) == 1, "the next page did not open");
        check(portalRows() == FILLERS + 1 - PER_PAGE, "the second page lists " + portalRows() + " portals");
        act(driver::previous);
        check(driver.read(screen -> screen.snapshot().paging().page()) == 0, "the previous page did not open");
        act(() -> driver.click("destination-sort"));
        check(label("portal-0").contains(TARGET_NAME) && label("portal-1").contains(FIRST_FILLER),
            "the name order starts with " + label("portal-0") + ", " + label("portal-1"));
        driver.click(rowNamed(TARGET_NAME));
        driver.awaitClosed();
        awaitPortal("the destination link", portal -> targetId.equals(portal.getDestinationId()));
        open(PortalRoutes.destinationPicker(sourceId), PortalRoutes.DESTINATION_PICKER);
        String linked = rowNamed(TARGET_NAME);
        check(linked.equals("portal-0"), "the linked portal is not first: " + linked);
        driver.click(linked);
        driver.awaitClosed();
        awaitPortal("the destination unlink", portal -> portal.getDestinationId() == null);
    }

    private void selection() {
        open(DestinationRoutes.select(), SELECT_SCREEN);
        MenuPaging paging = driver.read(screen -> screen.snapshot().paging());
        check(paging != null && paging.pageCount() == 2, "the selection paging is " + paging);
        shots.capture(SELECT_SCREEN);
        driver.click(rowNamed(TARGET_NAME));
        awaitScreen(PortalRoutes.HOME);
        String context = driver.read(screen -> screen.snapshot().context());
        check(context.contains(TARGET_NAME), "the selection opened " + context);
        driver.escape();
        driver.awaitClosed();
    }

    private void revoked() {
        hold(new ItemStack(Items.DIAMOND, 5));
        open(PortalRoutes.cost(sourceId), PortalRoutes.COST_MAIN);
        act(() -> driver.click("travel-cost-vanilla"));
        awaitPortal("the held item cost before the revocation", portal -> "VANILLA".equals(price(portal).get("type")));
        QaReplies.clearRecent();
        UUID stranger = UUID.randomUUID();
        server.run((minecraftServer, player) -> check(runtime(minecraftServer).portals().update(player, sourceId, portal -> portal.setOwner(stranger)),
            "the owner transfer was refused"));
        boolean sent = driver.input().computeOnClient(minecraft -> ClientMenus.session().act("travel-cost-free", MenuInteraction.PRIMARY, null));
        driver.awaitClosed();
        check(read(portal -> "VANILLA".equals(price(portal).get("type"))), "a player who lost manage rights cleared the travel cost (sent=" + sent + ")");
        for (int tick = 0; tick < TIMEOUT_TICKS && !QaReplies.recent().contains(DENIED); tick++) {
            driver.input().waitTicks(1);
        }
        check(QaReplies.recent().contains(DENIED), "the revoked player saw no denial notice: " + QaReplies.recent());
    }

    private void open(MenuRoute route, String screenId) {
        server.run((minecraftServer, player) -> runtime(minecraftServer).menus().open(player.getUUID(), route));
        awaitScreen(screenId);
    }

    private void hold(ItemStack stack) {
        server.run((minecraftServer, player) -> player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy()));
        driver.input().waitTicks(2);
    }

    private void act(Runnable action) {
        long revision = revision();
        action.run();
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> screen.snapshot().revision() > revision && !screen.feedbackLine().pending())
            .orElse(false), TIMEOUT_TICKS);
        driver.input().waitTicks(1);
    }

    private void answer(Runnable action) {
        action.run();
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> !screen.feedbackLine().pending() && screen.feedbackLine().message().isPresent())
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
            if (server.compute((minecraftServer, player) -> condition.test(source(minecraftServer)))) {
                return;
            }
            driver.input().waitTicks(1);
        }
        throw new AssertionError(description + " never reached the server portal");
    }

    private String label(String elementId) {
        return driver.read(screen -> screen.snapshot().element(elementId).map(element -> MenuText.stripColors(element.label())).orElse(""));
    }

    private int portalRows() {
        return driver.read(screen -> {
            int rows = 0;
            for (MenuElement element : screen.snapshot().elements()) {
                if (element.id().startsWith("portal-")) {
                    rows++;
                }
            }
            return rows;
        });
    }

    private String rowNamed(String name) {
        return driver.read(screen -> {
            for (MenuElement element : screen.snapshot().elements()) {
                if (element.id().startsWith("portal-") && MenuText.stripColors(element.label()).contains(name)) {
                    return element.id();
                }
            }
            throw new AssertionError("no portal row names " + name);
        });
    }

    private long revision() {
        return driver.read(screen -> screen.snapshot().revision());
    }

    private boolean read(Predicate<MinecraftPortal> condition) {
        return server.compute((minecraftServer, player) -> condition.test(source(minecraftServer)));
    }

    private MinecraftPortal source(MinecraftServer minecraftServer) {
        return Objects.requireNonNull(runtime(minecraftServer).portals().get(sourceId), "the cost test portal is gone");
    }

    private static List<UUID> createPortals(MinecraftServer minecraftServer, ServerPlayer player) {
        WormholesModRuntime runtime = runtime(minecraftServer);
        ServerLevel level = minecraftServer.overworld();
        BlockPos origin = player.blockPosition();
        List<UUID> portals = new ArrayList<>(FILLERS + 2);
        portals.add(create(runtime, level, player, origin.offset(3, 0, 4), SOURCE_NAME, ProjectionMode.ON));
        portals.add(create(runtime, level, player, origin.offset(-3, 0, 4), TARGET_NAME, ProjectionMode.ON));
        for (int index = 0; index < FILLERS; index++) {
            BlockPos base = origin.offset(-24 + (index % FILLER_COLUMNS) * 3, 12 + (index / FILLER_COLUMNS) * 5, 12);
            portals.add(create(runtime, level, player, base, FILLER_PREFIX + String.format("%02d", index + 1), ProjectionMode.OFF));
        }
        return portals;
    }

    private static UUID create(WormholesModRuntime runtime, ServerLevel level, ServerPlayer player, BlockPos base, String name, ProjectionMode projection) {
        List<BlockPos> cells = new ArrayList<>(PORTAL_WIDTH * PORTAL_HEIGHT);
        for (int x = 0; x < PORTAL_WIDTH; x++) {
            for (int y = 0; y < PORTAL_HEIGHT; y++) {
                cells.add(base.offset(x, y, 0));
            }
        }
        MinecraftPortal portal = runtime.portals().create(player.getUUID(), level, cells, PortalType.PORTAL, new Vec3(0, 0, -1));
        check(portal != null, "the portal " + name + " was refused");
        check(runtime.portals().update(player, portal.getId(), configured -> {
            configured.setName(name);
            configured.setProjectionMode(projection);
        }), "the portal " + name + " could not be configured");
        return portal.getId();
    }

    private void cleanup(MinecraftServer minecraftServer, ServerPlayer player) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        for (UUID id : created) {
            runtime(minecraftServer).portals().remove(id);
        }
    }

    private static Map<?, ?> price(MinecraftPortal portal) {
        return portal.setting(TRAVEL_COST) instanceof Map<?, ?> price ? price : Map.of();
    }

    private static int quantity(MinecraftPortal portal) {
        return price(portal).get("quantity") instanceof Number number ? number.intValue() : 0;
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
