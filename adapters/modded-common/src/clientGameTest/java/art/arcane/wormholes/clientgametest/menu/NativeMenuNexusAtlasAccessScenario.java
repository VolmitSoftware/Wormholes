package art.arcane.wormholes.clientgametest.menu;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.clientgametest.QaReplies;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.config.toml.DoorsConfig;
import art.arcane.wormholes.door.DoorAccessPolicy;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.access.AccessRoutes;
import art.arcane.wormholes.menu.atlas.AtlasRoutes;
import art.arcane.wormholes.menu.door.DoorRoutes;
import art.arcane.wormholes.menu.nexus.NexusRoutes;
import art.arcane.wormholes.modded.MinecraftAccessService;
import art.arcane.wormholes.modded.MinecraftDoorItems;
import art.arcane.wormholes.modded.MinecraftNexus;
import art.arcane.wormholes.modded.MinecraftNexusValues;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.MenuRow;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

public final class NativeMenuNexusAtlasAccessScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String DENIED = "Only the portal owner or an administrator can edit this portal.";
    private static final String DOOR_DENIED = "Only the door owner or an administrator can edit this door.";
    private static final String DIAL_DENIED = "Portal access denied";
    private static final String ATLAS_DENIED = "You do not have permission to open the atlas.";
    private static final String INVALID_KEY = "not a key!";
    private static final String ALPHA_NAME = "Arch Alpha";
    private static final String BETA_NAME = "Arch Beta";
    private static final String FILLER_PREFIX = "Filler ";
    private static final String LOBBY = "Lobby";
    private static final String HARBOR = "Harbor Net";
    private static final String BETA_ADDRESS = "AAAA";
    private static final String GUEST_NAME = "Astra";
    private static final String ACCESS_KEY = "harbor-key";
    private static final String NETWORK_UNJOINED = "nexus.network.unjoined";
    private static final String NETWORK_JOINED = "nexus.network.joined";
    private static final Pattern ATLAS_ROW = Pattern.compile("atlas-\\d+");
    private static final Pattern DIAL_ROW = Pattern.compile("dial-\\d+");
    private static final int NEGOTIATION_TICKS = 200;
    private static final int TIMEOUT_TICKS = 200;
    private static final int PORTAL_WIDTH = 2;
    private static final int PORTAL_HEIGHT = 3;
    private static final int FILLERS = 46;
    private static final int FILLER_COLUMNS = 16;

    private final MenuScreenDriver driver;
    private final NativeMenuShots shots;
    private final NativeMenuServer server;
    private final List<UUID> created = new ArrayList<>(FILLERS + 2);
    private final UUID guestId = UUID.nameUUIDFromBytes(GUEST_NAME.getBytes(StandardCharsets.UTF_8));
    private UUID alphaId;
    private UUID betaId;
    private UUID doorId;

    private NativeMenuNexusAtlasAccessScenario(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        this.driver = driver;
        this.shots = shots;
        this.server = server;
    }

    public static void run(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        new NativeMenuNexusAtlasAccessScenario(driver, shots, server).run();
    }

    private void run() {
        driver.input().waitFor(minecraft -> ClientMenus.session().version() >= 1, NEGOTIATION_TICKS);
        created.addAll(server.compute(NativeMenuNexusAtlasAccessScenario::createPortals));
        alphaId = created.get(0);
        betaId = created.get(1);
        boolean discovery = server.compute((minecraftServer, player) -> atlasConfig(minecraftServer).discoveryRequired);
        boolean projection = server.compute((minecraftServer, player) -> doorsConfig(minecraftServer).projectionEnabled);
        server.run((minecraftServer, player) -> {
            atlasConfig(minecraftServer).discoveryRequired = false;
            doorsConfig(minecraftServer).projectionEnabled = true;
            minecraftServer.services().nameToIdCache().add(new NameAndId(guestId, GUEST_NAME));
        });
        try {
            network();
            dial();
            dialDenied();
            atlas();
            atlasDenied();
            access();
            door();
            revoked();
        } finally {
            server.run((minecraftServer, player) -> {
                atlasConfig(minecraftServer).discoveryRequired = discovery;
                doorsConfig(minecraftServer).projectionEnabled = projection;
                cleanup(minecraftServer, player);
            });
        }
        LOGGER.info("WORMHOLES_NATIVE_MENU_NEXUS_ATLAS_ACCESS_PASS screenshots={}", shots.captured().size());
    }

    private void network() {
        open(NexusRoutes.network(alphaId), NexusRoutes.NETWORK);
        awaitElement("nexus-create");
        shots.capture(NETWORK_UNJOINED);
        act(() -> driver.typeAndApply("nexus-create", HARBOR));
        awaitServer("the created network", (minecraftServer, player) -> {
            PortalNetwork network = runtime(minecraftServer).nexus().networks().byName(HARBOR);
            return network != null && network.id().equals(networkOf(minecraftServer, alphaId));
        });
        awaitElement("nexus-leave");
        driver.click("nexus-leave");
        driver.awaitConfirmationArmed();
        act(driver::confirm);
        awaitServer("leaving the network", (minecraftServer, player) -> networkOf(minecraftServer, alphaId) == null);
        awaitElement("nexus-join");
        act(() -> driver.typeAndApply("nexus-join", LOBBY));
        awaitServer("joining by name", (minecraftServer, player) -> {
            PortalNetwork lobby = runtime(minecraftServer).nexus().networks().byName(LOBBY);
            return lobby != null && lobby.id().equals(networkOf(minecraftServer, alphaId)) && lobby.member(alphaId) != null;
        });
        awaitElement("nexus-dial");
        check(driver.read(screen -> screen.snapshot().context()).contains(LOBBY), "the joined network screen names "
            + driver.read(screen -> screen.snapshot().context()));
        shots.capture(NETWORK_JOINED);
    }

    private void dial() {
        driver.click("nexus-dial");
        awaitScreen(NexusRoutes.DIAL);
        MenuPaging paging = driver.read(screen -> screen.snapshot().paging());
        check(paging != null && paging.page() == 0 && paging.pageCount() == 2, "the dial paging is " + paging);
        check(rows(DIAL_ROW) == 45, "the first dial page lists " + rows(DIAL_ROW) + " addresses");
        shots.capture(NexusRoutes.DIAL);
        act(driver::next);
        check(driver.read(screen -> screen.snapshot().paging().page()) == 1, "the next dial page did not open");
        check(rows(DIAL_ROW) == FILLERS + 1 - 45, "the second dial page lists " + rows(DIAL_ROW) + " addresses");
        if (!hasRowNamed(DIAL_ROW, BETA_NAME)) {
            act(driver::previous);
        }
        driver.click(rowNamed(DIAL_ROW, BETA_NAME));
        driver.awaitClosed();
        awaitServer("the dialed destination", (minecraftServer, player) -> betaId.equals(portal(minecraftServer, alphaId).getDestinationId()));
    }

    private void dialDenied() {
        open(NexusRoutes.dial(alphaId), NexusRoutes.DIAL);
        UUID destination = server.compute((minecraftServer, player) -> portal(minecraftServer, alphaId).getDestinationId());
        UUID stranger = UUID.randomUUID();
        server.run((minecraftServer, player) -> {
            MinecraftPortal alpha = portal(minecraftServer, alphaId);
            alpha.setOwner(stranger);
            alpha.setRole(player.getUUID(), PortalRole.DENIED);
        });
        QaReplies.clearRecent();
        driver.click(firstRow(DIAL_ROW));
        driver.awaitClosed();
        awaitReceived(DIAL_DENIED, "the denied dialer saw no denial notice");
        check(server.compute((minecraftServer, player) -> Objects.equals(destination, portal(minecraftServer, alphaId).getDestinationId())),
            "a denied dialer changed the destination");
        server.run((minecraftServer, player) -> {
            MinecraftPortal alpha = portal(minecraftServer, alphaId);
            alpha.setRole(player.getUUID(), null);
            alpha.setOwner(player.getUUID());
        });
    }

    private void atlasDenied() {
        open(AtlasRoutes.list(AtlasModel.Filter.ALL), AtlasRoutes.LIST);
        AutoCloseable denial = server.compute((minecraftServer, player) -> runtime(minecraftServer).access().register((candidate, node) ->
            candidate.getUUID().equals(player.getUUID()) && node.equals(AtlasRoutes.PERMISSION)
                ? MinecraftAccessService.Decision.DENY : MinecraftAccessService.Decision.UNSET));
        try {
            QaReplies.clearRecent();
            driver.click("atlas-sort");
            driver.awaitClosed();
            awaitReceived(ATLAS_DENIED, "the player without the atlas permission saw no denial notice");
        } finally {
            server.run((minecraftServer, player) -> close(denial));
        }
    }

    private void atlas() {
        server.run((minecraftServer, player) -> minecraftServer.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "atlas"));
        awaitScreen(AtlasRoutes.LIST);
        MenuPaging paging = driver.read(screen -> screen.snapshot().paging());
        check(paging != null && paging.pageCount() == 2, "the atlas paging is " + paging);
        act(driver::next);
        check(driver.read(screen -> screen.snapshot().paging().page()) == 1, "the next atlas page did not open");
        act(driver::previous);
        act(() -> driver.click("atlas-sort"));
        check(label("atlas-0").contains(ALPHA_NAME) && label("atlas-1").contains(BETA_NAME),
            "the name order starts with " + label("atlas-0") + ", " + label("atlas-1"));
        act(() -> driver.clickSide(rowNamed(ATLAS_ROW, BETA_NAME), MenuInteraction.SECONDARY));
        awaitServer("the atlas favourite", (minecraftServer, player) -> atlasState(minecraftServer, player).isFavorite(betaId));
        act(() -> driver.clickSide(rowNamed(ATLAS_ROW, BETA_NAME), MenuInteraction.TERTIARY));
        awaitServer("the atlas guide", (minecraftServer, player) -> betaId.equals(atlasState(minecraftServer, player).guideTarget()));
        awaitElement("atlas-guide");
        shots.capture(AtlasRoutes.LIST);
        act(() -> driver.click("atlas-guide"));
        awaitServer("the cleared guide", (minecraftServer, player) -> atlasState(minecraftServer, player).guideTarget() == null);
        driver.escape();
        driver.awaitClosed();
    }

    private void access() {
        open(AccessRoutes.roles(alphaId), AccessRoutes.ROLES);
        act(() -> driver.typeAndApply("access-key", ACCESS_KEY));
        awaitServer("the permission key", (minecraftServer, player) -> ACCESS_KEY.equals(portal(minecraftServer, alphaId).getPermissionKey()));
        act(() -> driver.typeAndApply("access-key", INVALID_KEY));
        awaitTyped("access-key", INVALID_KEY);
        check(server.compute((minecraftServer, player) -> ACCESS_KEY.equals(portal(minecraftServer, alphaId).getPermissionKey())),
            "an invalid permission key replaced " + ACCESS_KEY);
        act(() -> driver.click("access-listed"));
        awaitServer("the directory flag", (minecraftServer, player) -> !portal(minecraftServer, alphaId).isListed());
        act(() -> driver.typeAndApply("access-add", GUEST_NAME));
        awaitServer("the added player", (minecraftServer, player) -> portal(minecraftServer, alphaId).role(guestId) == PortalRole.USER);
        awaitTyped("access-add", "");
        String role = "access-role-" + guestId;
        awaitElement(role);
        shots.capture(AccessRoutes.ROLES);
        act(() -> driver.click(role));
        awaitServer("the cycled role", (minecraftServer, player) -> portal(minecraftServer, alphaId).role(guestId) == PortalRole.USER.next());
        act(() -> driver.clickSide(role, MenuInteraction.TERTIARY));
        awaitServer("the removed role", (minecraftServer, player) -> portal(minecraftServer, alphaId).role(guestId) == null);
        driver.escape();
        driver.awaitClosed();
    }

    private void door() {
        AutoCloseable placement = server.compute((minecraftServer, player) -> allowDoorPlacement(minecraftServer, player.getUUID()));
        try {
            doorId = server.compute(NativeMenuNexusAtlasAccessScenario::placeDoor);
            awaitServer("the placed door", (minecraftServer, player) -> endpoint(minecraftServer).isPresent()
                && MinecraftDoorItems.identity(player.getMainHandItem()).isEmpty());
            check(server.compute((minecraftServer, player) -> endpoint(minecraftServer).isPresent()
                && runtime(minecraftServer).doors().state().accessRecord(doorId).isPresent()), "the placed door was rolled back");
        } finally {
            server.run((minecraftServer, player) -> {
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                close(placement);
            });
        }
        open(DoorRoutes.access(doorId), DoorRoutes.ACCESS);
        DoorOpenState original = server.compute((minecraftServer, player) -> endpoint(minecraftServer).orElseThrow().openState());
        act(() -> driver.click("door-access-open-state"));
        awaitServer("the door open state", (minecraftServer, player) -> endpoint(minecraftServer).orElseThrow().openState() == original.flipped());
        act(() -> driver.click("door-access-projection"));
        awaitServer("the door projection", (minecraftServer, player) -> endpoint(minecraftServer).orElseThrow().projection() == DoorProjectionState.ON);
        act(() -> driver.typeAndApply("door-access-add", GUEST_NAME));
        awaitServer("the listed door player", (minecraftServer, player) -> listed(minecraftServer) == DoorAccessState.NEUTRAL);
        String entry = "door-access-" + guestId;
        awaitElement(entry);
        act(() -> driver.clickSide(entry, MenuInteraction.PRIMARY));
        awaitServer("the whitelisted door player", (minecraftServer, player) -> listed(minecraftServer) == DoorAccessState.WHITELIST);
        shots.capture(DoorRoutes.ACCESS);
        act(() -> driver.clickSide(entry, MenuInteraction.SECONDARY));
        awaitServer("the blacklisted door player", (minecraftServer, player) -> listed(minecraftServer) == DoorAccessState.BLACKLIST);
        act(() -> driver.clickSide(entry, MenuInteraction.TERTIARY));
        awaitServer("the removed door player", (minecraftServer, player) -> listed(minecraftServer) == null);
        DoorOpenState current = server.compute((minecraftServer, player) -> endpoint(minecraftServer).orElseThrow().openState());
        server.run((minecraftServer, player) -> transferDoor(minecraftServer, UUID.randomUUID()));
        QaReplies.clearRecent();
        driver.click("door-access-open-state");
        driver.awaitClosed();
        awaitReceived(DOOR_DENIED, "the player who lost the door saw no denial notice");
        check(server.compute((minecraftServer, player) -> endpoint(minecraftServer).orElseThrow().openState()) == current,
            "a player who lost the door changed its open state");
    }

    private void transferDoor(MinecraftServer minecraftServer, UUID owner) {
        DoorStateService state = runtime(minecraftServer).doors().state();
        PlacedDoorEndpoint placed = state.findEndpointByItem(doorId).orElseThrow();
        try {
            state.removeAccessRecord(doorId);
            state.removeEndpoint(placed.position());
            check(state.registerEndpoint(placed, owner), "the door could not be registered for its new owner");
        } catch (IOException failure) {
            throw new UncheckedIOException("the door owner change could not be stored", failure);
        }
    }

    private void revoked() {
        open(AccessRoutes.roles(alphaId), AccessRoutes.ROLES);
        boolean listed = server.compute((minecraftServer, player) -> portal(minecraftServer, alphaId).isListed());
        QaReplies.clearRecent();
        UUID stranger = UUID.randomUUID();
        server.run((minecraftServer, player) -> check(runtime(minecraftServer).portals().update(player, alphaId, portal -> portal.setOwner(stranger)),
            "the owner transfer was refused"));
        boolean sent = driver.input().computeOnClient(minecraft -> ClientMenus.session().act("access-listed", MenuInteraction.PRIMARY, null));
        driver.awaitClosed();
        check(server.compute((minecraftServer, player) -> portal(minecraftServer, alphaId).isListed()) == listed,
            "a player who lost manage rights changed the directory flag (sent=" + sent + ")");
        for (int tick = 0; tick < TIMEOUT_TICKS && !QaReplies.recent().contains(DENIED); tick++) {
            driver.input().waitTicks(1);
        }
        check(QaReplies.recent().contains(DENIED), "the revoked player saw no denial notice: " + QaReplies.recent());
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

    private void awaitElement(String elementId) {
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> screen.snapshot().element(elementId).isPresent())
            .orElse(false), TIMEOUT_TICKS);
        driver.input().waitTicks(1);
    }

    private void awaitTyped(String elementId, String text) {
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .flatMap(screen -> screen.rowFor(elementId))
            .flatMap(MenuRow::textField)
            .map(field -> field.getValue().equals(text))
            .orElse(false), TIMEOUT_TICKS);
    }

    private void awaitReceived(String text, String failure) {
        for (int tick = 0; tick < TIMEOUT_TICKS && !received(text); tick++) {
            driver.input().waitTicks(1);
        }
        check(received(text), failure + ": " + QaReplies.recent());
    }

    private static boolean received(String text) {
        return QaReplies.recent().stream().anyMatch(line -> line.contains(text));
    }

    private String firstRow(Pattern pattern) {
        return driver.read(screen -> {
            for (MenuElement element : screen.snapshot().elements()) {
                if (pattern.matcher(element.id()).matches()) {
                    return element.id();
                }
            }
            throw new AssertionError("no row matches " + pattern);
        });
    }

    private void awaitServer(String description, BiFunction<MinecraftServer, ServerPlayer, Boolean> condition) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (server.compute(condition)) {
                return;
            }
            driver.input().waitTicks(1);
        }
        throw new AssertionError(description + " never reached the server");
    }

    private String label(String elementId) {
        return driver.read(screen -> screen.snapshot().element(elementId).map(element -> MenuText.stripColors(element.label())).orElse(""));
    }

    private int rows(Pattern pattern) {
        return driver.read(screen -> {
            int count = 0;
            for (MenuElement element : screen.snapshot().elements()) {
                if (pattern.matcher(element.id()).matches()) {
                    count++;
                }
            }
            return count;
        });
    }

    private String rowNamed(Pattern pattern, String name) {
        return driver.read(screen -> {
            for (MenuElement element : screen.snapshot().elements()) {
                if (pattern.matcher(element.id()).matches() && MenuText.stripColors(element.label()).contains(name)) {
                    return element.id();
                }
            }
            throw new AssertionError("no row names " + name);
        });
    }

    private boolean hasRowNamed(Pattern pattern, String name) {
        return driver.read(screen -> {
            for (MenuElement element : screen.snapshot().elements()) {
                if (pattern.matcher(element.id()).matches() && MenuText.stripColors(element.label()).contains(name)) {
                    return true;
                }
            }
            return false;
        });
    }

    private Optional<PlacedDoorEndpoint> endpoint(MinecraftServer minecraftServer) {
        return runtime(minecraftServer).doors().state().findEndpointByItem(doorId);
    }

    private DoorAccessState listed(MinecraftServer minecraftServer) {
        return runtime(minecraftServer).doors().state().accessRecord(doorId).orElseThrow().stateOf(guestId);
    }

    private static List<UUID> createPortals(MinecraftServer minecraftServer, ServerPlayer player) {
        WormholesModRuntime runtime = runtime(minecraftServer);
        ServerLevel level = minecraftServer.overworld();
        BlockPos origin = player.blockPosition();
        List<UUID> portals = new ArrayList<>(FILLERS + 2);
        portals.add(create(runtime, level, player, origin.offset(3, 0, 4), ALPHA_NAME));
        portals.add(create(runtime, level, player, origin.offset(-3, 0, 4), BETA_NAME));
        for (int index = 0; index < FILLERS; index++) {
            BlockPos base = origin.offset(-24 + (index % FILLER_COLUMNS) * 3, 12 + (index / FILLER_COLUMNS) * 5, 12);
            portals.add(create(runtime, level, player, base, FILLER_PREFIX + String.format("%02d", index + 1)));
        }
        MinecraftNexus nexus = runtime.nexus();
        PortalNetwork lobby = nexus.create(player, LOBBY);
        nexus.join(player, lobby, runtime.portals().get(portals.get(1)), BETA_ADDRESS);
        for (int index = 2; index < portals.size(); index++) {
            nexus.join(player, nexus.networks().byId(lobby.id()), runtime.portals().get(portals.get(index)), null);
        }
        return portals;
    }

    private static UUID create(WormholesModRuntime runtime, ServerLevel level, ServerPlayer player, BlockPos base, String name) {
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
            configured.setProjectionMode(ProjectionMode.OFF);
        }), "the portal " + name + " could not be configured");
        return portal.getId();
    }

    private static UUID placeDoor(MinecraftServer minecraftServer, ServerPlayer player) {
        ServerLevel level = minecraftServer.overworld();
        BlockPos target = player.blockPosition().offset(0, 0, -3);
        BlockPos support = target.below();
        level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(target.above(), Blocks.AIR.defaultBlockState());
        DoorItemIdentity identity = DoorItemIdentity.newPersonal(DoorForm.DOOR);
        player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftDoorItems.door(identity));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
        check(runtime(minecraftServer).useBlock(player, InteractionHand.MAIN_HAND, hit), "the personal door placement was not consumed");
        return identity.itemId();
    }

    private static AutoCloseable allowDoorPlacement(MinecraftServer minecraftServer, UUID playerId) {
        return runtime(minecraftServer).access().register((candidate, node) -> candidate.getUUID().equals(playerId)
            && node.equals(DoorAccessPolicy.PLACE_NODE) ? MinecraftAccessService.Decision.ALLOW : MinecraftAccessService.Decision.UNSET);
    }

    private static void close(AutoCloseable registration) {
        try {
            registration.close();
        } catch (Exception failure) {
            throw new IllegalStateException("the door placement permission could not be removed", failure);
        }
    }

    private void cleanup(MinecraftServer minecraftServer, ServerPlayer player) {
        WormholesModRuntime runtime = runtime(minecraftServer);
        for (UUID id : created) {
            runtime.portals().remove(id);
        }
    }

    private static UUID networkOf(MinecraftServer minecraftServer, UUID portalId) {
        return MinecraftNexusValues.networkId(portal(minecraftServer, portalId));
    }

    private static AtlasPlayerState atlasState(MinecraftServer minecraftServer, ServerPlayer player) {
        return Objects.requireNonNull(runtime(minecraftServer).atlas().state(player), "the atlas state is not loaded");
    }

    private static AtlasConfig atlasConfig(MinecraftServer minecraftServer) {
        return runtime(minecraftServer).configuration().settings().getAtlas();
    }

    private static DoorsConfig doorsConfig(MinecraftServer minecraftServer) {
        return runtime(minecraftServer).configuration().settings().getDoors();
    }

    private static MinecraftPortal portal(MinecraftServer minecraftServer, UUID portalId) {
        return Objects.requireNonNull(runtime(minecraftServer).portals().get(portalId), "the test portal " + portalId + " is gone");
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
