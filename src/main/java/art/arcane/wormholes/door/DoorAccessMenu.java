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
import art.arcane.wormholes.service.WormholesHud;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

final class DoorAccessMenu {
    private static final int HEADER_ROW = 0;
    private static final int LIST_START_SLOT = 9;
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
        if (record.players().contains(resolvedId)) {
            return AddResolution.ALREADY_LISTED;
        }
        return AddResolution.ADD;
    }

    static RemoveResolution resolveRemoval(DoorAccessRecord record, UUID playerId) {
        Objects.requireNonNull(record, "record");
        if (playerId == null || !record.players().contains(playerId)) {
            return RemoveResolution.NOT_LISTED;
        }
        return RemoveResolution.REMOVE;
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

    static Material modeIcon(DoorAccessMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case UNRESTRICTED -> Material.LEATHER_HELMET;
            case WHITELIST -> Material.GOLDEN_HELMET;
            case BLACKLIST -> Material.IRON_HELMET;
        };
    }

    static boolean modeGlint(DoorAccessMode mode) {
        return Objects.requireNonNull(mode, "mode") == DoorAccessMode.WHITELIST;
    }

    void open(Player player, PlacedDoorEndpoint endpoint) {
        Player viewer = Objects.requireNonNull(player, "player");
        PlacedDoorEndpoint door = Objects.requireNonNull(endpoint, "endpoint");
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            return;
        }
        Wormholes.v("QA_EVT {\"event\":\"door_access_menu_open\",\"status\":\"info\",\"details\":\"open\",\"context\":{\"item\":\""
            + door.identity().itemId() + "\",\"mode\":\"" + record.mode() + "\",\"listed\":" + record.players().size() + "}}");
        UIWindow window = new UIWindow(Wormholes.instance, viewer);
        window.setTitle(Wormholes.text().legacy(
            WormholesMessages.DOOR_MENU_ACCESS_TITLE,
            arguments("kind", kindLabel(door.identity().kind()))));
        window.setResolution(WindowResolution.W9_H6);
        window.setViewportHeight(3);
        window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
        populate(window, viewer, door, record);
        window.setVisible(true);
    }

    private void populate(UIWindow window, Player viewer, PlacedDoorEndpoint door, DoorAccessRecord record) {
        window.batch(() -> {
            window.clearElements();
            window.setElement(-2, HEADER_ROW, placardElement(door, record));
            window.setElement(0, HEADER_ROW, modeElement(window, viewer, door, record));
            window.setElement(2, HEADER_ROW, addPlayerElement(window, viewer, door));
            int slot = LIST_START_SLOT;
            for (UUID listed : record.players()) {
                window.setElement(
                    window.getPosition(slot),
                    window.getRow(slot),
                    entryElement(window, viewer, door, listed));
                slot++;
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
                "mode", modeLabel(record.mode()),
                "count", record.players().size()),
            DoorItemService.defaultMaterial(door.identity().kind()));
    }

    private UIElement modeElement(UIWindow window, Player viewer, PlacedDoorEndpoint door, DoorAccessRecord record) {
        UIElement element = localizedElement(
            "door-access-mode",
            WormholesMessages.DOOR_MENU_ACCESS_MODE,
            arguments("mode", modeLabel(record.mode()), "description", modeDescription(record.mode())),
            modeIcon(record.mode()));
        element.setEnchanted(modeGlint(record.mode()));
        element.onLeftClick(clicked -> cycleMode(window, viewer, door));
        return element;
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

    private UIElement entryElement(UIWindow window, Player viewer, PlacedDoorEndpoint door, UUID listed) {
        UIElement element = localizedElement(
            "door-access-" + listed,
            WormholesMessages.DOOR_MENU_ACCESS_ENTRY,
            arguments("name", playerLabel(listed)),
            Material.PLAYER_HEAD);
        element.setBaseItemStack(playerHead(listed));
        element.onLeftClick(clicked -> removeListedPlayer(window, viewer, door, listed));
        return element;
    }

    private void cycleMode(UIWindow window, Player viewer, PlacedDoorEndpoint door) {
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            closeWindow(window, viewer);
            return;
        }
        DoorAccessMode next = record.mode().next();
        if (!apply(viewer, () -> manager.applyAccessMode(door.identity().itemId(), next))) {
            return;
        }
        Wormholes.v("QA_EVT {\"event\":\"door_access_menu_apply\",\"status\":\"info\",\"details\":\"mode\",\"context\":{\"item\":\""
            + door.identity().itemId() + "\",\"mode\":\"" + next + "\"}}");
        notice(viewer, WormholesMessages.DOOR_ACCESS_MODE_CHANGED, arguments("mode", modeLabel(next)));
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
            WormholesAudience.sendMessage(viewer, Wormholes.text().component(
                WormholesMessages.DOOR_ACCESS_PROMPT_PLAYER,
                arguments("cancel", Wormholes.text().plain(WormholesMessages.PORTAL_INPUT_CANCEL))));
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
            + door.identity().itemId() + "\",\"player\":\"" + playerId + "\"}}");
        notice(viewer, WormholesMessages.DOOR_ACCESS_ADDED, arguments("name", playerLabel(playerId)));
    }

    private void removeListedPlayer(UIWindow window, Player viewer, PlacedDoorEndpoint door, UUID listed) {
        DoorAccessRecord record = manageableRecord(viewer, door);
        if (record == null) {
            closeWindow(window, viewer);
            return;
        }
        if (resolveRemoval(record, listed) == RemoveResolution.NOT_LISTED) {
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

    private void refresh(UIWindow window, Player viewer, PlacedDoorEndpoint door) {
        DoorAccessRecord record = manager.accessRecord(door.identity().itemId()).orElse(null);
        if (record == null) {
            closeWindow(window, viewer);
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

    private static ItemStack playerHead(UUID playerId) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof SkullMeta skull)) {
            return stack;
        }
        skull.setOwningPlayer(Bukkit.getOfflinePlayer(playerId));
        stack.setItemMeta(skull);
        return stack;
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

    private static String modeLabel(DoorAccessMode mode) {
        TextKey key = switch (mode) {
            case UNRESTRICTED -> WormholesMessages.DOOR_ACCESS_LABEL_UNRESTRICTED;
            case WHITELIST -> WormholesMessages.DOOR_ACCESS_LABEL_WHITELIST;
            case BLACKLIST -> WormholesMessages.DOOR_ACCESS_LABEL_BLACKLIST;
        };
        return Wormholes.text().plain(key);
    }

    private static String modeDescription(DoorAccessMode mode) {
        TextKey key = switch (mode) {
            case UNRESTRICTED -> WormholesMessages.DOOR_ACCESS_DESCRIPTION_UNRESTRICTED;
            case WHITELIST -> WormholesMessages.DOOR_ACCESS_DESCRIPTION_WHITELIST;
            case BLACKLIST -> WormholesMessages.DOOR_ACCESS_DESCRIPTION_BLACKLIST;
        };
        return Wormholes.text().plain(key);
    }

    private static boolean isCancelInput(String input) {
        return input.trim().equalsIgnoreCase(Wormholes.text().plain(WormholesMessages.PORTAL_INPUT_CANCEL));
    }

    private static void notice(Player viewer, TextKey message, MessageArgs messageArguments) {
        WormholesHud.notice(viewer, Wormholes.text().component(message, messageArguments));
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

    enum RemoveResolution {
        NOT_LISTED,
        REMOVE
    }

    @FunctionalInterface
    private interface DoorAccessMutation {
        boolean apply() throws IOException;
    }
}
