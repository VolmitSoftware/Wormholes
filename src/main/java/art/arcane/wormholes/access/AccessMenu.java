package art.arcane.wormholes.access;

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
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-portal access window: roles, allowed groups, the stable permission key, and the public
 * directory flag. Layout follows the Dimensional Door access menu so both feel the same.
 */
public final class AccessMenu {
    static final int HEADER_ROW = 0;
    static final int ROW_WIDTH = 9;
    static final int MAX_VIEWPORT_HEIGHT = 6;

    private static final int PLACARD_POSITION = -4;
    private static final int ADD_POSITION = -1;
    private static final int KEY_POSITION = 0;
    private static final int GROUPS_POSITION = 1;
    private static final int LISTED_POSITION = 2;
    private static final int SHORT_ID_LENGTH = 8;

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

    /** The window is exactly as tall as the header plus the rows the roles actually need. */
    static int viewportHeight(int roleCount) {
        if (roleCount <= 0) {
            return 1;
        }
        return Math.min(1 + ((roleCount + ROW_WIDTH - 1) / ROW_WIDTH), MAX_VIEWPORT_HEIGHT);
    }

    static Material roleIcon(PortalRole role) {
        return switch (role) {
            case OWNER -> Material.GOLDEN_HELMET;
            case CO_OWNER -> Material.IRON_HELMET;
            case USER -> Material.LIME_STAINED_GLASS_PANE;
            case DENIED -> Material.RED_STAINED_GLASS_PANE;
        };
    }

    static AddResolution resolveAddition(LocalPortal portal, AccessPortalExtension access, UUID resolvedId) {
        if (resolvedId == null) {
            return AddResolution.NOT_FOUND;
        }
        if (resolvedId.equals(portal.getOwner())) {
            return AddResolution.OWNER;
        }
        return access.role(resolvedId) == null ? AddResolution.ADD : AddResolution.ALREADY_LISTED;
    }

    static String roleLabel(PortalRole role) {
        TextKey key = switch (role) {
            case OWNER -> AccessMessages.LABEL_OWNER;
            case CO_OWNER -> AccessMessages.LABEL_CO_OWNER;
            case USER -> AccessMessages.LABEL_USER;
            case DENIED -> AccessMessages.LABEL_DENIED;
        };
        return Wormholes.text().plain(key);
    }

    static String listedLabel(boolean listed) {
        return Wormholes.text().plain(listed ? AccessMessages.LABEL_LISTED : AccessMessages.LABEL_UNLISTED);
    }

    static String playerLabel(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        String cached = Bukkit.getOfflinePlayer(playerId).getName();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return Wormholes.text().plain(WormholesMessages.DOOR_ACCESS_UNKNOWN_PLAYER,
            arguments("id", playerId.toString().substring(0, SHORT_ID_LENGTH)));
    }

    static MessageArgs placardArguments(LocalPortal portal, AccessPortalExtension access) {
        return arguments(
            "portal", portal.getName(),
            "owner", ownerLabel(portal),
            "count", access.roles().size(),
            "key", access.permissionKey());
    }

    public void open(LocalPortal portal, Player viewer) {
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        if (access == null) {
            return;
        }
        UIWindow window = new UIWindow(Wormholes.instance, viewer);
        window.setTitle(portal.getRouter(true));
        window.setResolution(WindowResolution.W9_H6);
        window.setViewportHeight(viewportHeight(access.roles().size()));
        window.setDecorator(new UIPaneDecorator(Material.GRAY_STAINED_GLASS_PANE));
        populate(window, portal, access, viewer);
        window.setVisible(true);
    }

    private void populate(UIWindow window, LocalPortal portal, AccessPortalExtension access, Player viewer) {
        window.batch(() -> {
            window.clearElements();
            window.setElement(PLACARD_POSITION, HEADER_ROW,
                element("access-placard", AccessMessages.MENU_PLACARD, placardArguments(portal, access), Material.NAME_TAG));

            UIElement add = element("access-add", AccessMessages.MENU_ADD, MessageArgs.empty(), Material.WRITABLE_BOOK);
            add.onLeftClick(clicked -> promptForPlayer(window, portal, viewer));
            window.setElement(ADD_POSITION, HEADER_ROW, add);

            UIElement key = element("access-key", AccessMessages.MENU_KEY,
                arguments("key", access.permissionKey()), Material.TRIPWIRE_HOOK);
            key.onLeftClick(clicked -> promptForKey(window, portal, viewer));
            window.setElement(KEY_POSITION, HEADER_ROW, key);

            UIElement groups = element("access-groups", AccessMessages.MENU_GROUPS,
                arguments("count", access.groups().size()), Material.BOOK);
            groups.onLeftClick(clicked -> promptForGroup(window, portal, viewer));
            groups.onRightClick(clicked -> clearGroups(window, portal, access, viewer));
            window.setElement(GROUPS_POSITION, HEADER_ROW, groups);

            UIElement listed = element("access-listed", AccessMessages.MENU_LISTED,
                arguments("state", listedLabel(access.listed())), access.listed() ? Material.ENDER_EYE : Material.ENDER_PEARL);
            listed.onLeftClick(clicked -> toggleListed(window, portal, access, viewer));
            window.setElement(LISTED_POSITION, HEADER_ROW, listed);

            int index = 0;
            for (Map.Entry<UUID, PortalRole> entry : access.roles().entrySet()) {
                window.setElement(entryPosition(index), entryRow(index),
                    roleElement(window, portal, access, viewer, entry.getKey(), entry.getValue()));
                index++;
            }
        });
    }

    private UIElement roleElement(UIWindow window, LocalPortal portal, AccessPortalExtension access, Player viewer,
                                  UUID listed, PortalRole role) {
        UIElement element = element("access-role-" + listed, AccessMessages.MENU_ROLE,
            arguments("name", playerLabel(listed), "state", roleLabel(role)), roleIcon(role));
        element.onLeftClick(clicked -> cycleRole(window, portal, access, viewer, listed, true));
        element.onRightClick(clicked -> cycleRole(window, portal, access, viewer, listed, false));
        // vanilla only sends a middle click in creative, so shift-left has to remove too
        element.onMiddleClick(clicked -> removeRole(portal, access, viewer, listed));
        element.onShiftLeftClick(clicked -> removeRole(portal, access, viewer, listed));
        return element;
    }

    private void cycleRole(UIWindow window, LocalPortal portal, AccessPortalExtension access, Player viewer,
                           UUID listed, boolean forward) {
        PortalRole current = access.role(listed);
        if (current == null) {
            refresh(window, portal, viewer);
            return;
        }
        PortalRole next = forward ? current.next() : current.previous();
        access.setRole(listed, next);
        notice(viewer, AccessMessages.ROLE_CHANGED,
            arguments("name", playerLabel(listed), "state", roleLabel(next), "portal", portal.getName()));
        refresh(window, portal, viewer);
    }

    private void removeRole(LocalPortal portal, AccessPortalExtension access, Player viewer, UUID listed) {
        if (access.removeRole(listed)) {
            notice(viewer, AccessMessages.ROLE_REMOVED,
                arguments("name", playerLabel(listed), "portal", portal.getName()));
        }
        reopen(portal, viewer);
    }

    private void toggleListed(UIWindow window, LocalPortal portal, AccessPortalExtension access, Player viewer) {
        access.setListed(!access.listed());
        notice(viewer, AccessMessages.LISTED_CHANGED,
            arguments("portal", portal.getName(), "state", listedLabel(access.listed())));
        refresh(window, portal, viewer);
    }

    private void clearGroups(UIWindow window, LocalPortal portal, AccessPortalExtension access, Player viewer) {
        if (access.clearGroups()) {
            notice(viewer, AccessMessages.GROUP_CLEARED, arguments("portal", portal.getName()));
        }
        refresh(window, portal, viewer);
    }

    private void promptForPlayer(UIWindow window, LocalPortal portal, Player viewer) {
        prompt(window, portal, viewer, AccessMessages.PROMPT_PLAYER, input -> {
            AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
            if (access == null) {
                return;
            }
            UUID resolved = resolvePlayerId(input.trim());
            switch (resolveAddition(portal, access, resolved)) {
                case NOT_FOUND -> notice(viewer, AccessMessages.PLAYER_NOT_FOUND, arguments("name", input.trim()));
                case OWNER -> notice(viewer, AccessMessages.ROLE_OWNER_ALWAYS, MessageArgs.empty());
                case ALREADY_LISTED -> notice(viewer, AccessMessages.ROLE_CHANGED,
                    arguments("name", playerLabel(resolved), "state", roleLabel(access.role(resolved)), "portal", portal.getName()));
                case ADD -> {
                    access.setRole(resolved, PortalRole.USER);
                    notice(viewer, AccessMessages.ROLE_CHANGED, arguments("name", playerLabel(resolved),
                        "state", roleLabel(PortalRole.USER), "portal", portal.getName()));
                }
            }
        });
    }

    private void promptForKey(UIWindow window, LocalPortal portal, Player viewer) {
        prompt(window, portal, viewer, AccessMessages.PROMPT_KEY, input -> {
            AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
            if (access == null) {
                return;
            }
            String requested = input.trim();
            switch (access.setPermissionKey(requested)) {
                case INVALID -> notice(viewer, AccessMessages.KEY_INVALID, MessageArgs.empty());
                case TAKEN -> notice(viewer, AccessMessages.KEY_TAKEN, arguments("key", requested));
                case SET, UNCHANGED -> notice(viewer, AccessMessages.KEY_SET,
                    arguments("portal", portal.getName(), "key", access.permissionKey()));
            }
        });
    }

    private void promptForGroup(UIWindow window, LocalPortal portal, Player viewer) {
        prompt(window, portal, viewer, AccessMessages.PROMPT_GROUP, input -> {
            AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
            if (access != null && access.addGroup(input.trim())) {
                notice(viewer, AccessMessages.GROUP_ADDED, arguments("portal", portal.getName(), "node", input.trim()));
            }
        });
    }

    private void prompt(UIWindow window, LocalPortal portal, Player viewer, TextKey message, Consumer<String> accept) {
        FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> {
            window.close();
            viewer.closeInventory();
            notice(viewer, message, arguments("cancel", Wormholes.text().plain(WormholesMessages.PORTAL_INPUT_CANCEL)));
            Wormholes.awaitChatInput(viewer, input -> {
                if (input != null && !isCancelInput(input)) {
                    accept.accept(input);
                }
                reopen(portal, viewer);
            });
        });
    }

    private void refresh(UIWindow window, LocalPortal portal, Player viewer) {
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        if (access == null) {
            return;
        }
        if (window.getViewportHeight() != viewportHeight(access.roles().size())) {
            reopen(portal, viewer);
            return;
        }
        populate(window, portal, access, viewer);
        window.updateInventory();
    }

    private void reopen(LocalPortal portal, Player viewer) {
        FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> open(portal, viewer));
    }

    private static String ownerLabel(LocalPortal portal) {
        UUID owner = portal.getOwner();
        return owner == null || owner.equals(portal.getId())
            ? Wormholes.text().plain(WormholesMessages.LABEL_NONE)
            : playerLabel(owner);
    }

    private static UUID resolvePlayerId(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = WormholesPlatform.offlinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    private static boolean isCancelInput(String input) {
        return input.trim().equalsIgnoreCase(Wormholes.text().plain(WormholesMessages.PORTAL_INPUT_CANCEL));
    }

    private static UIElement element(String id, LinesKey key, MessageArgs messageArguments, Material material) {
        UIElement element = new UIElement(id);
        element.setMaterial(new MaterialBlock(material));
        Wormholes.text().apply(element, key, messageArguments);
        return element;
    }

    /** Chat, not the HUD: the access window is open or reopening in the same tick. */
    private static void notice(Player viewer, TextKey message, MessageArgs messageArguments) {
        WormholesAudience.sendMessage(viewer, Wormholes.text().component(viewer, message, messageArguments));
    }

    static MessageArgs arguments(Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Localization arguments require name-value pairs");
        }
        MessageArgument[] messageArguments = new MessageArgument[nameValuePairs.length / 2];
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            messageArguments[index / 2] = MessageArgument.untrusted((String) nameValuePairs[index], nameValuePairs[index + 1]);
        }
        return WormholesLocalization.args(messageArguments);
    }

    enum AddResolution {
        NOT_FOUND,
        OWNER,
        ALREADY_LISTED,
        ADD
    }
}
