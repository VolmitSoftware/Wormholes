package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.localization.DoorViewMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class MinecraftDoorAccessMenu {
    static final int HEADER_ROW = 0;
    static final int ROW_WIDTH = 9;
    static final int MAX_VIEWPORT_HEIGHT = 6;
    static final int PLACARD_POSITION = -1;
    static final int OPEN_STATE_POSITION = 0;
    static final int ADD_POSITION = 1;
    static final int PROJECTION_POSITION = 2;
    private static final int SHORT_ID_LENGTH = 8;
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final WormholesModRuntime runtime;
    private final MinecraftDoorService doors;

    MinecraftDoorAccessMenu(WormholesModRuntime runtime, MinecraftDoorService doors) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.doors = Objects.requireNonNull(doors, "doors");
    }

    static AddResolution resolveAddition(DoorAccessRecord record, UUID resolvedId) {
        Objects.requireNonNull(record, "record");
        if (resolvedId == null) {
            return AddResolution.NOT_FOUND;
        }
        if (record.ownerId().equals(resolvedId)) {
            return AddResolution.OWNER;
        }
        if (record.isListed(resolvedId)) {
            return AddResolution.ALREADY_LISTED;
        }
        return AddResolution.ADD;
    }

    static int entryRow(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("entry index cannot be negative");
        }
        return HEADER_ROW + 1 + (index / ROW_WIDTH);
    }

    static int entryPosition(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("entry index cannot be negative");
        }
        return (index % ROW_WIDTH) - (ROW_WIDTH - 1) / 2;
    }

    static int viewportHeight(int listedCount) {
        if (listedCount <= 0) {
            return 1;
        }
        int rows = 1 + ((listedCount + ROW_WIDTH - 1) / ROW_WIDTH);
        return Math.min(rows, MAX_VIEWPORT_HEIGHT);
    }

    static Item stateIcon(DoorAccessState state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case NEUTRAL -> Items.STAINED_GLASS_PANE.black();
            case WHITELIST -> Items.STAINED_GLASS_PANE.green();
            case BLACKLIST -> Items.STAINED_GLASS_PANE.red();
        };
    }

    static Item openStateIcon(DoorOpenState state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case OPEN -> Items.DYE.lime();
            case CLOSED -> Items.DYE.gray();
        };
    }

    static DoorOpenState nextOpenState(DoorOpenState state) {
        return Objects.requireNonNull(state, "state").flipped();
    }

    static Item projectionIcon(DoorProjectionState state) {
        Objects.requireNonNull(state, "state");
        return Items.SPYGLASS;
    }

    static DoorProjectionState nextProjectionState(DoorProjectionState state) {
        return Objects.requireNonNull(state, "state").next();
    }

    static String resolveDisplayName(String knownName, String fallback) {
        Objects.requireNonNull(fallback, "fallback");
        if (knownName == null || knownName.isBlank()) {
            return fallback;
        }
        return knownName;
    }

    static String shortId(UUID playerId) {
        return Objects.requireNonNull(playerId, "playerId").toString().substring(0, SHORT_ID_LENGTH);
    }

    void open(ServerPlayer player, PlacedDoorEndpoint endpoint) {
        ServerPlayer viewer = Objects.requireNonNull(player, "player");
        PlacedDoorEndpoint requested = Objects.requireNonNull(endpoint, "endpoint");
        PlacedDoorEndpoint door = endpoint(requested.identity().itemId());
        if (door == null) {
            notice(viewer, WormholesMessages.DOOR_ACCESS_UNAVAILABLE, MessageArgs.empty());
            return;
        }
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            return;
        }
        MinecraftWindow window = new MinecraftWindow(runtime, viewer);
        window.setTitle(MinecraftLegacyText.text(viewer, WormholesMessages.DOOR_MENU_ACCESS_TITLE,
            MinecraftPortalText.arguments("kind", kindLabel(viewer, door.identity().kind()))));
        window.setViewportHeight(viewportHeight(record.players().size()));
        window.setDecorator(Items.STAINED_GLASS_PANE.gray());
        populate(window, viewer, door, record);
        window.open();
    }

    private void populate(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door, DoorAccessRecord record) {
        window.batch(() -> {
            window.clearElements();
            window.setElement(PLACARD_POSITION, HEADER_ROW, placardElement(viewer, door, record));
            window.setElement(OPEN_STATE_POSITION, HEADER_ROW, openStateElement(window, viewer, door));
            window.setElement(ADD_POSITION, HEADER_ROW, addPlayerElement(window, viewer, door));
            window.setElement(PROJECTION_POSITION, HEADER_ROW, projectionElement(window, viewer, door));
            List<UUID> listed = record.listedPlayers();
            for (int index = 0; index < listed.size(); index++) {
                UUID playerId = listed.get(index);
                window.setElement(entryPosition(index), entryRow(index),
                    entryElement(window, viewer, door, playerId, record.stateOf(playerId)));
            }
        });
    }

    private MinecraftElement placardElement(ServerPlayer viewer, PlacedDoorEndpoint door, DoorAccessRecord record) {
        return localizedElement(viewer, "door-access-placard", WormholesMessages.DOOR_MENU_ACCESS_PLACARD,
            MinecraftPortalText.arguments(
                "kind", kindLabel(viewer, door.identity().kind()),
                "owner", playerLabel(viewer, record.ownerId()),
                "whitelisted", countState(record, DoorAccessState.WHITELIST),
                "blacklisted", countState(record, DoorAccessState.BLACKLIST),
                "count", record.players().size()),
            MinecraftDoorItems.material(door.identity()));
    }

    private MinecraftElement addPlayerElement(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door) {
        MinecraftElement element = localizedElement(viewer, "door-access-add", WormholesMessages.DOOR_MENU_ACCESS_ADD_PLAYER,
            MessageArgs.empty(), Items.WRITABLE_BOOK);
        element.onLeftClick(clicked -> promptForPlayer(window, viewer, door));
        return element;
    }

    private MinecraftElement openStateElement(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door) {
        DoorOpenState state = door.openState();
        MinecraftElement element = localizedElement(viewer, "door-access-open-state", WormholesMessages.DOOR_MENU_ACCESS_OPEN_STATE,
            MinecraftPortalText.arguments(
                "state", openStateLabel(viewer, state),
                "next", openStateLabel(viewer, nextOpenState(state))),
            openStateIcon(state));
        element.onLeftClick(clicked -> toggleOpenState(window, viewer, door));
        return element;
    }

    private MinecraftElement projectionElement(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door) {
        DoorProjectionState state = door.projection();
        MinecraftElement element = localizedElement(viewer, "door-access-projection", DoorViewMessages.MENU_PROJECTION,
            MinecraftPortalText.arguments("state", projectionLabel(viewer, state)), projectionIcon(state));
        element.onLeftClick(clicked -> cycleProjection(window, viewer, door));
        return element;
    }

    private MinecraftElement entryElement(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door, UUID listed,
                                          DoorAccessState state) {
        MinecraftElement element = localizedElement(viewer, "door-access-" + listed, WormholesMessages.DOOR_MENU_ACCESS_ENTRY,
            MinecraftPortalText.arguments("name", playerLabel(viewer, listed), "state", stateLabel(viewer, state)),
            stateIcon(state));
        element.onLeftClick(clicked -> applyState(window, viewer, door, listed, DoorAccessState.WHITELIST));
        element.onRightClick(clicked -> applyState(window, viewer, door, listed, DoorAccessState.BLACKLIST));
        element.onMiddleClick(clicked -> removeListedPlayer(window, viewer, door, listed));
        element.onShiftLeftClick(clicked -> removeListedPlayer(window, viewer, door, listed));
        return element;
    }

    private void applyState(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door, UUID listed, DoorAccessState state) {
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            closeWindow(window, viewer);
            return;
        }
        if (!record.isListed(listed)) {
            refresh(window, viewer, door);
            return;
        }
        if (record.stateOf(listed) == state) {
            return;
        }
        UUID itemId = door.identity().itemId();
        apply(viewer, doors.updateAccessState(viewer, itemId, new MinecraftDoorService.AccessChange(listed, state)), changed -> {
            if (!changed) {
                return;
            }
            notice(viewer, WormholesMessages.DOOR_ACCESS_STATE_CHANGED,
                MinecraftPortalText.arguments("name", playerLabel(viewer, listed), "state", stateLabel(viewer, state)));
            refresh(window, viewer, door);
        });
    }

    private void toggleOpenState(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door) {
        PlacedDoorEndpoint current = endpoint(door.identity().itemId());
        if (current == null || manageableRecord(viewer, current) == null) {
            closeWindow(window, viewer);
            return;
        }
        DoorOpenState state = nextOpenState(current.openState());
        apply(viewer, doors.updateOpenState(viewer, current.identity().itemId(), state), changed -> refresh(window, viewer, current));
    }

    private void cycleProjection(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door) {
        PlacedDoorEndpoint current = endpoint(door.identity().itemId());
        if (current == null || manageableRecord(viewer, current) == null) {
            closeWindow(window, viewer);
            return;
        }
        if (!doors.projectionEnabled()) {
            notice(viewer, DoorViewMessages.DISABLED, MessageArgs.empty());
            return;
        }
        DoorProjectionState state = nextProjectionState(current.projection());
        apply(viewer, doors.updateProjection(viewer, current.identity().itemId(), state), changed -> {
            if (changed) {
                notice(viewer, projectionNotice(state), MessageArgs.empty());
            }
            refresh(window, viewer, current);
        });
    }

    private void promptForPlayer(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door) {
        if (manageableRecord(viewer, door) == null) {
            closeWindow(window, viewer);
            return;
        }
        runtime.schedule(() -> {
            window.close();
            viewer.closeContainer();
            notice(viewer, WormholesMessages.DOOR_ACCESS_PROMPT_PLAYER,
                MinecraftPortalText.arguments("cancel", plain(viewer, WormholesMessages.PORTAL_INPUT_CANCEL)));
            runtime.chatInput().await(viewer, input -> acceptPlayerName(viewer, door, input));
        }, 1L);
    }

    private void acceptPlayerName(ServerPlayer viewer, PlacedDoorEndpoint door, String input) {
        if (input == null || isCancelInput(viewer, input)) {
            reopen(viewer, door);
            return;
        }
        if (manageableRecord(viewer, door) == null) {
            return;
        }
        String name = input.trim();
        resolvePlayerId(name).whenComplete((resolved, failure) -> {
            if (failure != null) {
                LOGGER.warn("Could not resolve player {} for dimensional-door access", name, failure);
            }
            runtime.schedule(() -> addResolvedPlayer(viewer, door, name, failure == null ? resolved : null), 1L);
        });
    }

    private void addResolvedPlayer(ServerPlayer viewer, PlacedDoorEndpoint door, String name, UUID resolved) {
        if (viewer.hasDisconnected()) {
            return;
        }
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            return;
        }
        switch (resolveAddition(record, resolved)) {
            case NOT_FOUND -> notice(viewer, WormholesMessages.DOOR_ACCESS_PLAYER_NOT_FOUND, MinecraftPortalText.arguments("name", name));
            case OWNER -> notice(viewer, WormholesMessages.DOOR_ACCESS_OWNER_ALWAYS, MessageArgs.empty());
            case ALREADY_LISTED -> notice(viewer, WormholesMessages.DOOR_ACCESS_ALREADY_LISTED,
                MinecraftPortalText.arguments("name", playerLabel(viewer, resolved)));
            case ADD -> {
                addListedPlayer(viewer, door, resolved);
                return;
            }
        }
        reopen(viewer, door);
    }

    private void addListedPlayer(ServerPlayer viewer, PlacedDoorEndpoint door, UUID playerId) {
        apply(viewer, doors.addAccessPlayer(viewer, door.identity().itemId(), playerId), changed -> {
            if (changed) {
                notice(viewer, WormholesMessages.DOOR_ACCESS_ADDED, MinecraftPortalText.arguments("name", playerLabel(viewer, playerId)));
            }
            reopen(viewer, door);
        });
    }

    private void removeListedPlayer(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door, UUID listed) {
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            closeWindow(window, viewer);
            return;
        }
        if (!record.isListed(listed)) {
            refresh(window, viewer, door);
            return;
        }
        apply(viewer, doors.removeAccessPlayer(viewer, door.identity().itemId(), listed), changed -> {
            if (!changed) {
                return;
            }
            notice(viewer, WormholesMessages.DOOR_ACCESS_REMOVED, MinecraftPortalText.arguments("name", playerLabel(viewer, listed)));
            refresh(window, viewer, door);
        });
    }

    private void refresh(MinecraftWindow window, ServerPlayer viewer, PlacedDoorEndpoint door) {
        if (!window.isVisible()) {
            return;
        }
        PlacedDoorEndpoint current = endpoint(door.identity().itemId());
        DoorAccessRecord record = current == null ? null : accessRecord(current.identity().itemId());
        if (current == null || record == null) {
            closeWindow(window, viewer);
            return;
        }
        if (window.getViewportHeight() != viewportHeight(record.players().size())) {
            closeWindow(window, viewer);
            reopen(viewer, current);
            return;
        }
        populate(window, viewer, current, record);
        window.updateInventory();
    }

    private void reopen(ServerPlayer viewer, PlacedDoorEndpoint door) {
        runtime.schedule(() -> open(viewer, door), 1L);
    }

    private void closeWindow(MinecraftWindow window, ServerPlayer viewer) {
        runtime.schedule(() -> {
            window.close();
            viewer.closeContainer();
        }, 1L);
    }

    private DoorAccessRecord manageableRecord(ServerPlayer viewer, PlacedDoorEndpoint door) {
        UUID itemId = door.identity().itemId();
        DoorAccessRecord record = accessRecord(itemId);
        if (record == null) {
            notice(viewer, WormholesMessages.DOOR_ACCESS_UNAVAILABLE, MessageArgs.empty());
            return null;
        }
        if (!doors.canManage(viewer, itemId)) {
            notice(viewer, WormholesMessages.DOOR_ACCESS_EDIT_DENIED, MessageArgs.empty());
            return null;
        }
        return record;
    }

    private void apply(ServerPlayer viewer, CompletableFuture<Boolean> mutation, Consumer<Boolean> completed) {
        mutation.whenComplete((changed, failure) -> runtime.schedule(() -> {
            if (viewer.hasDisconnected()) {
                return;
            }
            if (failure != null) {
                notice(viewer, WormholesMessages.DOOR_ACCESS_SAVE_FAILED, MessageArgs.empty());
                completed.accept(false);
                return;
            }
            completed.accept(Boolean.TRUE.equals(changed));
        }, 1L));
    }

    private PlacedDoorEndpoint endpoint(UUID itemId) {
        return doors.enabled() ? doors.state().findEndpointByItem(itemId).orElse(null) : null;
    }

    private DoorAccessRecord accessRecord(UUID itemId) {
        return doors.enabled() ? doors.state().accessRecord(itemId).orElse(null) : null;
    }

    private CompletableFuture<UUID> resolvePlayerId(String name) {
        MinecraftServer server = runtime.server();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return CompletableFuture.completedFuture(online.getUUID());
        }
        UserNameToIdResolver cache = server.services().nameToIdCache();
        return CompletableFuture.supplyAsync(() -> cache.get(name).map(NameAndId::id).orElse(null),
            task -> Thread.ofVirtual().name("Wormholes-door-player-lookup").start(task));
    }

    private String playerLabel(ServerPlayer viewer, UUID playerId) {
        MinecraftServer server = runtime.server();
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().name();
        }
        String cached = server.services().nameToIdCache().get(playerId).map(NameAndId::name).orElse(null);
        return resolveDisplayName(cached, unknownLabel(viewer, playerId));
    }

    private static int countState(DoorAccessRecord record, DoorAccessState state) {
        int total = 0;
        for (DoorAccessState listed : record.players().values()) {
            if (listed == state) {
                total++;
            }
        }
        return total;
    }

    private static String unknownLabel(ServerPlayer viewer, UUID playerId) {
        return plain(viewer, WormholesMessages.DOOR_ACCESS_UNKNOWN_PLAYER, MinecraftPortalText.arguments("id", shortId(playerId)));
    }

    private static String kindLabel(ServerPlayer viewer, DoorKind kind) {
        TextKey key = switch (kind) {
            case PAIR -> WormholesMessages.DOOR_ACCESS_KIND_PAIR;
            case PERSONAL -> WormholesMessages.DOOR_ACCESS_KIND_PERSONAL;
            case PUBLIC -> WormholesMessages.DOOR_ACCESS_KIND_PUBLIC;
            case RETURN -> WormholesMessages.DOOR_ACCESS_KIND_RETURN;
        };
        return plain(viewer, key);
    }

    private static String stateLabel(ServerPlayer viewer, DoorAccessState state) {
        TextKey key = switch (state) {
            case NEUTRAL -> WormholesMessages.DOOR_ACCESS_LABEL_NEUTRAL;
            case WHITELIST -> WormholesMessages.DOOR_ACCESS_LABEL_WHITELIST;
            case BLACKLIST -> WormholesMessages.DOOR_ACCESS_LABEL_BLACKLIST;
        };
        return plain(viewer, key);
    }

    private static String openStateLabel(ServerPlayer viewer, DoorOpenState state) {
        TextKey key = switch (Objects.requireNonNull(state, "state")) {
            case OPEN -> WormholesMessages.DOOR_OPEN_STATE_OPEN;
            case CLOSED -> WormholesMessages.DOOR_OPEN_STATE_CLOSED;
        };
        return plain(viewer, key);
    }

    private static String projectionLabel(ServerPlayer viewer, DoorProjectionState state) {
        TextKey key = switch (state) {
            case INHERIT -> DoorViewMessages.STATE_INHERIT;
            case ON -> DoorViewMessages.STATE_ON;
            case OFF -> DoorViewMessages.STATE_OFF;
        };
        return plain(viewer, key);
    }

    private static TextKey projectionNotice(DoorProjectionState state) {
        return switch (state) {
            case INHERIT -> DoorViewMessages.TOGGLE_INHERIT;
            case ON -> DoorViewMessages.TOGGLE_ON;
            case OFF -> DoorViewMessages.TOGGLE_OFF;
        };
    }

    private static boolean isCancelInput(ServerPlayer viewer, String input) {
        return input.trim().equalsIgnoreCase(plain(viewer, WormholesMessages.PORTAL_INPUT_CANCEL));
    }

    private static String plain(ServerPlayer viewer, TextKey key) {
        return plain(viewer, key, MessageArgs.empty());
    }

    private static String plain(ServerPlayer viewer, TextKey key, MessageArgs arguments) {
        return MinecraftMenuText.text(viewer, key, arguments).getString();
    }

    private static void notice(ServerPlayer viewer, TextKey message, MessageArgs arguments) {
        viewer.sendSystemMessage(MinecraftMenuText.text(viewer, message, arguments));
    }

    private static MinecraftElement localizedElement(ServerPlayer viewer, String id, LinesKey key, MessageArgs arguments, Item material) {
        MinecraftElement element = new MinecraftElement(id);
        element.setMaterial(material);
        MinecraftLegacyText.apply(viewer, element, key, arguments);
        return element;
    }

    enum AddResolution {
        NOT_FOUND,
        OWNER,
        ALREADY_LISTED,
        ADD
    }
}
