package art.arcane.wormholes.door;

import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

final class DoorAccessMenu {
    static final int HEADER_ROW = 0;
    static final int ROW_WIDTH = 9;
    static final int MAX_VIEWPORT_HEIGHT = 6;
    private static final int PLACARD_POSITION = -1;
    private static final int ADD_POSITION = 1;
    private static final int SHORT_ID_LENGTH = 8;
    private static final String ADMINISTRATOR_NODE = "wormholes.admin";

    private final DimensionalDoorManager manager;

    DoorAccessMenu(DimensionalDoorManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
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

    /** Entries fill left to right under the header row. */
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

    /** The window is exactly as tall as the header plus the rows the list actually needs. */
    static int viewportHeight(int listedCount) {
        if (listedCount <= 0) {
            return 1;
        }
        int rows = 1 + ((listedCount + ROW_WIDTH - 1) / ROW_WIDTH);
        return Math.min(rows, MAX_VIEWPORT_HEIGHT);
    }

    static Material stateIcon(DoorAccessState state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case NEUTRAL -> Material.BLACK_STAINED_GLASS_PANE;
            case WHITELIST -> Material.GREEN_STAINED_GLASS_PANE;
            case BLACKLIST -> Material.RED_STAINED_GLASS_PANE;
        };
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

    void open(Player player, PlacedDoorEndpoint endpoint) {
        Player viewer = Objects.requireNonNull(player, "player");
        PlacedDoorEndpoint door = Objects.requireNonNull(endpoint, "endpoint");
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            return;
        }
        Wormholes.v("QA_EVT {\"event\":\"door_access_menu_open\",\"status\":\"info\",\"details\":\"open\",\"context\":{\"item\":\""
            + door.identity().itemId() + "\",\"listed\":" + record.players().size()
            + ",\"whitelisted\":" + countState(record, DoorAccessState.WHITELIST)
            + ",\"blacklisted\":" + countState(record, DoorAccessState.BLACKLIST) + "}}");
        UIWindow window = new UIWindow(Wormholes.instance, viewer);
        window.setTitle(Wormholes.text().legacy(
            WormholesMessages.DOOR_MENU_ACCESS_TITLE,
            arguments("kind", kindLabel(door.identity().kind()))));
        window.setResolution(WindowResolution.W9_H6);
        window.setViewportHeight(viewportHeight(record.players().size()));
        window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
        populate(window, viewer, door, record);
        window.setVisible(true);
    }

    private void populate(UIWindow window, Player viewer, PlacedDoorEndpoint door, DoorAccessRecord record) {
        window.batch(() -> {
            window.clearElements();
            window.setElement(PLACARD_POSITION, HEADER_ROW, placardElement(door, record));
            window.setElement(ADD_POSITION, HEADER_ROW, addPlayerElement(window, viewer, door));
            List<UUID> listed = record.listedPlayers();
            for (int index = 0; index < listed.size(); index++) {
                UUID playerId = listed.get(index);
                window.setElement(
                    entryPosition(index),
                    entryRow(index),
                    entryElement(window, viewer, door, playerId, record.stateOf(playerId)));
            }
        });
    }

    private UIElement placardElement(PlacedDoorEndpoint door, DoorAccessRecord record) {
        return localizedElement(
            "door-access-placard",
            WormholesMessages.DOOR_MENU_ACCESS_PLACARD,
            arguments(
                "kind", kindLabel(door.identity().kind()),
                "owner", playerLabel(record.ownerId()),
                "whitelisted", countState(record, DoorAccessState.WHITELIST),
                "blacklisted", countState(record, DoorAccessState.BLACKLIST),
                "count", record.players().size()),
            DoorItemService.defaultMaterial(door.identity().kind()));
    }

    private UIElement addPlayerElement(UIWindow window, Player viewer, PlacedDoorEndpoint door) {
        UIElement element = localizedElement(
            "door-access-add",
            WormholesMessages.DOOR_MENU_ACCESS_ADD_PLAYER,
            MessageArgs.empty(),
            Material.WRITABLE_BOOK);
        element.onLeftClick(clicked -> promptForPlayer(window, viewer, door));
        return element;
    }

    private UIElement entryElement(
        UIWindow window,
        Player viewer,
        PlacedDoorEndpoint door,
        UUID listed,
        DoorAccessState state
    ) {
        UIElement element = localizedElement(
            "door-access-" + listed,
            WormholesMessages.DOOR_MENU_ACCESS_ENTRY,
            arguments("name", playerLabel(listed), "state", stateLabel(state)),
            stateIcon(state));
        element.onLeftClick(clicked -> applyState(window, viewer, door, listed, DoorAccessState.WHITELIST));
        element.onRightClick(clicked -> applyState(window, viewer, door, listed, DoorAccessState.BLACKLIST));
        // vanilla only sends a middle click in creative, so shift-left has to remove too
        element.onMiddleClick(clicked -> removeListedPlayer(window, viewer, door, listed));
        element.onShiftLeftClick(clicked -> removeListedPlayer(window, viewer, door, listed));
        return element;
    }

    private void applyState(
        UIWindow window,
        Player viewer,
        PlacedDoorEndpoint door,
        UUID listed,
        DoorAccessState state
    ) {
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
        if (!apply(viewer, () -> manager.applyAccessState(door.identity().itemId(), listed, state))) {
            return;
        }
        Wormholes.v("QA_EVT {\"event\":\"door_access_menu_apply\",\"status\":\"info\",\"details\":\"state\",\"context\":{\"item\":\""
            + door.identity().itemId() + "\",\"player\":\"" + listed + "\",\"state\":\"" + state + "\"}}");
        notice(viewer, WormholesMessages.DOOR_ACCESS_STATE_CHANGED,
            arguments("name", playerLabel(listed), "state", stateLabel(state)));
        refresh(window, viewer, door);
    }

    private void promptForPlayer(UIWindow window, Player viewer, PlacedDoorEndpoint door) {
        if (manageableRecord(viewer, door) == null) {
            closeWindow(window, viewer);
            return;
        }
        FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> {
            window.close();
            viewer.closeInventory();
            notice(viewer, WormholesMessages.DOOR_ACCESS_PROMPT_PLAYER,
                arguments("cancel", Wormholes.text().plain(WormholesMessages.PORTAL_INPUT_CANCEL)));
            Wormholes.awaitChatInput(viewer, input -> acceptPlayerName(viewer, door, input));
        });
    }

    private void acceptPlayerName(Player viewer, PlacedDoorEndpoint door, String input) {
        if (input == null || isCancelInput(input)) {
            reopen(viewer, door);
            return;
        }
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            return;
        }
        String name = input.trim();
        UUID resolved = resolvePlayerId(name);
        switch (resolveAddition(record, resolved)) {
            case NOT_FOUND -> notice(viewer, WormholesMessages.DOOR_ACCESS_PLAYER_NOT_FOUND, arguments("name", name));
            case OWNER -> notice(viewer, WormholesMessages.DOOR_ACCESS_OWNER_ALWAYS, MessageArgs.empty());
            case ALREADY_LISTED ->
                notice(viewer, WormholesMessages.DOOR_ACCESS_ALREADY_LISTED, arguments("name", playerLabel(resolved)));
            case ADD -> addListedPlayer(viewer, door, resolved);
        }
        reopen(viewer, door);
    }

    private void addListedPlayer(Player viewer, PlacedDoorEndpoint door, UUID playerId) {
        if (!apply(viewer, () -> manager.addAccessPlayer(door.identity().itemId(), playerId))) {
            return;
        }
        Wormholes.v("QA_EVT {\"event\":\"door_access_menu_apply\",\"status\":\"info\",\"details\":\"add\",\"context\":{\"item\":\""
            + door.identity().itemId() + "\",\"player\":\"" + playerId + "\",\"state\":\"" + DoorAccessState.NEUTRAL + "\"}}");
        notice(viewer, WormholesMessages.DOOR_ACCESS_ADDED, arguments("name", playerLabel(playerId)));
    }

    private void removeListedPlayer(UIWindow window, Player viewer, PlacedDoorEndpoint door, UUID listed) {
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            closeWindow(window, viewer);
            return;
        }
        if (!record.isListed(listed)) {
            refresh(window, viewer, door);
            return;
        }
        if (!apply(viewer, () -> manager.removeAccessPlayer(door.identity().itemId(), listed))) {
            return;
        }
        Wormholes.v("QA_EVT {\"event\":\"door_access_menu_apply\",\"status\":\"info\",\"details\":\"remove\",\"context\":{\"item\":\""
            + door.identity().itemId() + "\",\"player\":\"" + listed + "\"}}");
        notice(viewer, WormholesMessages.DOOR_ACCESS_REMOVED, arguments("name", playerLabel(listed)));
        refresh(window, viewer, door);
    }

    /** The window height tracks the list, so a size change has to reopen instead of repaint. */
    private void refresh(UIWindow window, Player viewer, PlacedDoorEndpoint door) {
        DoorAccessRecord record = manager.accessRecord(door.identity().itemId()).orElse(null);
        if (record == null) {
            closeWindow(window, viewer);
            return;
        }
        if (window.getViewportHeight() != viewportHeight(record.players().size())) {
            closeWindow(window, viewer);
            reopen(viewer, door);
            return;
        }
        populate(window, viewer, door, record);
        window.updateInventory();
    }

    private void reopen(Player viewer, PlacedDoorEndpoint door) {
        FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> open(viewer, door));
    }

    private void closeWindow(UIWindow window, Player viewer) {
        FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> {
            window.close();
            viewer.closeInventory();
        });
    }

    private DoorAccessRecord manageableRecord(Player viewer, PlacedDoorEndpoint door) {
        DoorAccessRecord record = manager.accessRecord(door.identity().itemId()).orElse(null);
        if (record == null) {
            notice(viewer, WormholesMessages.DOOR_ACCESS_UNAVAILABLE, MessageArgs.empty());
            return null;
        }
        boolean administrator = viewer.isOp() || viewer.hasPermission(ADMINISTRATOR_NODE);
        if (!DoorAccessPolicy.canManage(record, viewer.getUniqueId(), administrator)) {
            notice(viewer, WormholesMessages.DOOR_ACCESS_EDIT_DENIED, MessageArgs.empty());
            return null;
        }
        return record;
    }

    private boolean apply(Player viewer, DoorAccessMutation mutation) {
        try {
            return mutation.apply();
        } catch (IOException | RuntimeException ex) {
            Wormholes.instance.getLogger().log(Level.SEVERE, "Could not save a dimensional-door access change", ex);
            notice(viewer, WormholesMessages.DOOR_ACCESS_SAVE_FAILED, MessageArgs.empty());
            return false;
        }
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

    private static UUID resolvePlayerId(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = WormholesPlatform.offlinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    private static String playerLabel(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        return resolveDisplayName(Bukkit.getOfflinePlayer(playerId).getName(), unknownLabel(playerId));
    }

    private static String unknownLabel(UUID playerId) {
        return Wormholes.text().plain(
            WormholesMessages.DOOR_ACCESS_UNKNOWN_PLAYER,
            arguments("id", shortId(playerId)));
    }

    private static String kindLabel(DoorKind kind) {
        TextKey key = switch (kind) {
            case PAIR -> WormholesMessages.DOOR_ACCESS_KIND_PAIR;
            case PERSONAL -> WormholesMessages.DOOR_ACCESS_KIND_PERSONAL;
            case PUBLIC -> WormholesMessages.DOOR_ACCESS_KIND_PUBLIC;
            case RETURN -> WormholesMessages.DOOR_ACCESS_KIND_RETURN;
        };
        return Wormholes.text().plain(key);
    }

    private static String stateLabel(DoorAccessState state) {
        TextKey key = switch (state) {
            case NEUTRAL -> WormholesMessages.DOOR_ACCESS_LABEL_NEUTRAL;
            case WHITELIST -> WormholesMessages.DOOR_ACCESS_LABEL_WHITELIST;
            case BLACKLIST -> WormholesMessages.DOOR_ACCESS_LABEL_BLACKLIST;
        };
        return Wormholes.text().plain(key);
    }

    private static boolean isCancelInput(String input) {
        return input.trim().equalsIgnoreCase(Wormholes.text().plain(WormholesMessages.PORTAL_INPUT_CANCEL));
    }

    /**
     * Chat, not the HUD: every one of these lands while the access window is open or is reopened in
     * the same tick, and Minecraft does not draw the action bar behind a container screen.
     */
    private static void notice(Player viewer, TextKey message, MessageArgs messageArguments) {
        WormholesAudience.sendMessage(viewer, Wormholes.text().component(message, messageArguments));
    }

    private static UIElement localizedElement(String id, LinesKey key, MessageArgs messageArguments, Material material) {
        UIElement element = new UIElement(id);
        element.setMaterial(new MaterialBlock(material));
        Wormholes.text().apply(element, key, messageArguments);
        return element;
    }

    private static MessageArgs arguments(Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Localization arguments require name-value pairs");
        }
        MessageArgument[] messageArguments = new MessageArgument[nameValuePairs.length / 2];
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            messageArguments[index / 2] = MessageArgument.untrusted(
                (String) nameValuePairs[index],
                nameValuePairs[index + 1]);
        }
        return WormholesLocalization.args(messageArguments);
    }

    enum AddResolution {
        NOT_FOUND,
        OWNER,
        ALREADY_LISTED,
        ADD
    }

    @FunctionalInterface
    private interface DoorAccessMutation {
        boolean apply() throws IOException;
    }
}
