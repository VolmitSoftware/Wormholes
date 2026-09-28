package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.access.PortalPermissionKey;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class MinecraftAccessMenu {
    static final int HEADER_ROW = 0;
    static final int ROW_WIDTH = 9;
    static final int MAX_VIEWPORT_HEIGHT = 6;

    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final int PLACARD_POSITION = -4;
    private static final int ADD_POSITION = -1;
    private static final int KEY_POSITION = 0;
    private static final int GROUPS_POSITION = 1;
    private static final int LISTED_POSITION = 2;
    private static final int SHORT_ID_LENGTH = 8;

    private final WormholesModRuntime runtime;

    public MinecraftAccessMenu(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
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

    static int viewportHeight(int roleCount) {
        if (roleCount <= 0) {
            return 1;
        }
        return Math.min(1 + ((roleCount + ROW_WIDTH - 1) / ROW_WIDTH), MAX_VIEWPORT_HEIGHT);
    }

    static Item roleIcon(PortalRole role) {
        return switch (role) {
            case OWNER -> Items.GOLDEN_HELMET;
            case CO_OWNER -> Items.IRON_HELMET;
            case USER -> Items.STAINED_GLASS_PANE.lime();
            case DENIED -> Items.STAINED_GLASS_PANE.red();
        };
    }

    static AddResolution resolveAddition(MinecraftPortal portal, UUID resolvedId) {
        if (resolvedId == null) {
            return AddResolution.NOT_FOUND;
        }
        if (resolvedId.equals(portal.getOwner())) {
            return AddResolution.OWNER;
        }
        return portal.role(resolvedId) == null ? AddResolution.ADD : AddResolution.ALREADY_LISTED;
    }

    public void open(MinecraftPortal portal, ServerPlayer viewer) {
        MinecraftWindow window = new MinecraftWindow(runtime, viewer);
        window.setTitle(MinecraftPortalText.router(runtime, portal, true));
        window.setViewportHeight(viewportHeight(portal.getRoles().size()));
        window.setDecorator(Items.STAINED_GLASS_PANE.gray());
        populate(window, portal, viewer);
        window.setVisible(true);
    }

    private void populate(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer) {
        window.batch(() -> {
            window.clearElements();
            window.setElement(PLACARD_POSITION, HEADER_ROW,
                element(viewer, "access-placard", AccessMessages.MENU_PLACARD, placardArguments(viewer, portal), Items.NAME_TAG));

            MinecraftElement add = element(viewer, "access-add", AccessMessages.MENU_ADD, MessageArgs.empty(), Items.WRITABLE_BOOK);
            add.onLeftClick(clicked -> promptForPlayer(window, portal, viewer));
            window.setElement(ADD_POSITION, HEADER_ROW, add);

            MinecraftElement key = element(viewer, "access-key", AccessMessages.MENU_KEY,
                MinecraftPortalText.arguments("key", portal.getPermissionKey()), Items.TRIPWIRE_HOOK);
            key.onLeftClick(clicked -> promptForKey(window, portal, viewer));
            window.setElement(KEY_POSITION, HEADER_ROW, key);

            MinecraftElement groups = element(viewer, "access-groups", AccessMessages.MENU_GROUPS,
                MinecraftPortalText.arguments("count", portal.getGroups().size()), Items.BOOK);
            groups.onLeftClick(clicked -> promptForGroup(window, portal, viewer));
            groups.onRightClick(clicked -> clearGroups(window, portal, viewer));
            window.setElement(GROUPS_POSITION, HEADER_ROW, groups);

            MinecraftElement listed = element(viewer, "access-listed", AccessMessages.MENU_LISTED,
                MinecraftPortalText.arguments("state", listedLabel(viewer, portal.isListed())),
                portal.isListed() ? Items.ENDER_EYE : Items.ENDER_PEARL);
            listed.onLeftClick(clicked -> toggleListed(window, portal, viewer));
            window.setElement(LISTED_POSITION, HEADER_ROW, listed);

            int index = 0;
            for (Map.Entry<UUID, PortalRole> entry : portal.getRoles().entrySet()) {
                window.setElement(entryPosition(index), entryRow(index),
                    roleElement(window, portal, viewer, entry.getKey(), entry.getValue()));
                index++;
            }
        });
    }

    private MinecraftElement roleElement(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer, UUID listed, PortalRole role) {
        MinecraftElement element = element(viewer, "access-role-" + listed, AccessMessages.MENU_ROLE,
            MinecraftPortalText.arguments("name", playerLabel(viewer, listed), "state", roleLabel(viewer, role)), roleIcon(role));
        element.onLeftClick(clicked -> cycleRole(window, portal, viewer, listed, true));
        element.onRightClick(clicked -> cycleRole(window, portal, viewer, listed, false));
        element.onMiddleClick(clicked -> removeRole(portal, viewer, listed));
        element.onShiftLeftClick(clicked -> removeRole(portal, viewer, listed));
        return element;
    }

    private void cycleRole(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer, UUID listed, boolean forward) {
        PortalRole current = portal.role(listed);
        if (current == null) {
            refresh(window, portal, viewer);
            return;
        }
        PortalRole next = forward ? current.next() : current.previous();
        if (runtime.portals().update(viewer, portal.getId(), target -> target.setRole(listed, next))) {
            notice(viewer, AccessMessages.ROLE_CHANGED, MinecraftPortalText.arguments("name", playerLabel(viewer, listed),
                "state", roleLabel(viewer, next), "portal", portal.getName()));
        }
        refresh(window, portal, viewer);
    }

    private void removeRole(MinecraftPortal portal, ServerPlayer viewer, UUID listed) {
        if (portal.role(listed) != null && runtime.portals().update(viewer, portal.getId(), target -> target.setRole(listed, null))) {
            notice(viewer, AccessMessages.ROLE_REMOVED, MinecraftPortalText.arguments("name", playerLabel(viewer, listed),
                "portal", portal.getName()));
        }
        reopen(portal, viewer);
    }

    private void toggleListed(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer) {
        boolean listed = !portal.isListed();
        if (runtime.portals().update(viewer, portal.getId(), target -> target.setListed(listed))) {
            notice(viewer, AccessMessages.LISTED_CHANGED, MinecraftPortalText.arguments("portal", portal.getName(),
                "state", listedLabel(viewer, portal.isListed())));
        }
        refresh(window, portal, viewer);
    }

    private void clearGroups(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer) {
        if (!portal.getGroups().isEmpty() && runtime.portals().update(viewer, portal.getId(), MinecraftPortal::clearGroups)) {
            notice(viewer, AccessMessages.GROUP_CLEARED, MinecraftPortalText.arguments("portal", portal.getName()));
        }
        refresh(window, portal, viewer);
    }

    private void promptForPlayer(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer) {
        prompt(window, portal, viewer, AccessMessages.PROMPT_PLAYER, input -> {
            String name = input.trim();
            return resolvePlayerId(name).thenAccept(resolved -> addPlayer(portal, viewer, name, resolved));
        });
    }

    private void addPlayer(MinecraftPortal portal, ServerPlayer viewer, String name, UUID resolved) {
        switch (resolveAddition(portal, resolved)) {
            case NOT_FOUND -> notice(viewer, AccessMessages.PLAYER_NOT_FOUND, MinecraftPortalText.arguments("name", name));
            case OWNER -> notice(viewer, AccessMessages.ROLE_OWNER_ALWAYS, MessageArgs.empty());
            case ALREADY_LISTED -> notice(viewer, AccessMessages.ROLE_CHANGED, MinecraftPortalText.arguments("name", playerLabel(viewer, resolved),
                "state", roleLabel(viewer, portal.role(resolved)), "portal", portal.getName()));
            case ADD -> {
                if (runtime.portals().update(viewer, portal.getId(), target -> target.setRole(resolved, PortalRole.USER))) {
                    notice(viewer, AccessMessages.ROLE_CHANGED, MinecraftPortalText.arguments("name", playerLabel(viewer, resolved),
                        "state", roleLabel(viewer, PortalRole.USER), "portal", portal.getName()));
                }
            }
        }
    }

    private void promptForKey(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer) {
        prompt(window, portal, viewer, AccessMessages.PROMPT_KEY, input -> {
            String requested = input.trim();
            switch (setPermissionKey(portal, viewer, requested)) {
                case INVALID -> notice(viewer, AccessMessages.KEY_INVALID, MessageArgs.empty());
                case TAKEN -> notice(viewer, AccessMessages.KEY_TAKEN, MinecraftPortalText.arguments("key", requested));
                case SET, UNCHANGED -> notice(viewer, AccessMessages.KEY_SET,
                    MinecraftPortalText.arguments("portal", portal.getName(), "key", portal.getPermissionKey()));
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private void promptForGroup(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer) {
        prompt(window, portal, viewer, AccessMessages.PROMPT_GROUP, input -> {
            String node = input.trim();
            if (!node.isEmpty() && !portal.getGroups().contains(node)
                && runtime.portals().update(viewer, portal.getId(), target -> target.addGroup(node))) {
                notice(viewer, AccessMessages.GROUP_ADDED, MinecraftPortalText.arguments("portal", portal.getName(), "node", node));
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private KeyResult setPermissionKey(MinecraftPortal portal, ServerPlayer viewer, String requested) {
        if (!PortalPermissionKey.isValid(requested)) {
            return KeyResult.INVALID;
        }
        if (requested.equals(portal.getPermissionKey())) {
            return KeyResult.UNCHANGED;
        }
        for (MinecraftPortal other : runtime.portals().snapshot()) {
            if (!other.getId().equals(portal.getId()) && requested.equals(other.getPermissionKey())) {
                return KeyResult.TAKEN;
            }
        }
        runtime.portals().update(viewer, portal.getId(), target -> target.setPermissionKey(requested));
        return KeyResult.SET;
    }

    private void prompt(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer, TextKey message,
                        Function<String, CompletableFuture<Void>> accept) {
        runtime.schedule(() -> {
            window.close();
            viewer.closeContainer();
            notice(viewer, message, MinecraftPortalText.arguments("cancel", plain(viewer, WormholesMessages.PORTAL_INPUT_CANCEL)));
            runtime.chatInput().await(viewer, input -> {
                if (input == null || isCancelInput(viewer, input)) {
                    reopen(portal, viewer);
                    return;
                }
                accept.apply(input).whenComplete((applied, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Could not apply access input for portal {}", portal.getId(), failure);
                    }
                    reopen(portal, viewer);
                });
            });
        }, 1L);
    }

    private void refresh(MinecraftWindow window, MinecraftPortal portal, ServerPlayer viewer) {
        if (window.getViewportHeight() != viewportHeight(portal.getRoles().size())) {
            reopen(portal, viewer);
            return;
        }
        populate(window, portal, viewer);
        window.updateInventory();
    }

    private void reopen(MinecraftPortal portal, ServerPlayer viewer) {
        runtime.schedule(() -> open(portal, viewer), 1L);
    }

    private CompletableFuture<UUID> resolvePlayerId(String name) {
        MinecraftServer server = runtime.server();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return CompletableFuture.completedFuture(online.getUUID());
        }
        CompletableFuture<UUID> resolved = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> server.services().nameToIdCache().get(name)).whenComplete((identity, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not resolve player {} for portal access", name, failure);
            }
            UUID id = failure == null ? identity.map(NameAndId::id).orElse(null) : null;
            server.execute(() -> {
                if (runtime.running()) {
                    resolved.complete(id);
                }
            });
        });
        return resolved;
    }

    private MessageArgs placardArguments(ServerPlayer viewer, MinecraftPortal portal) {
        return MinecraftPortalText.arguments(
            "portal", portal.getName(),
            "owner", ownerLabel(viewer, portal),
            "count", portal.getRoles().size(),
            "key", portal.getPermissionKey());
    }

    private String ownerLabel(ServerPlayer viewer, MinecraftPortal portal) {
        UUID owner = portal.getOwner();
        return owner == null || owner.equals(portal.getId()) ? plain(viewer, WormholesMessages.LABEL_NONE) : playerLabel(viewer, owner);
    }

    private String playerLabel(ServerPlayer viewer, UUID playerId) {
        MinecraftServer server = runtime.server();
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().name();
        }
        Optional<NameAndId> cached = server.services().nameToIdCache().get(playerId);
        if (cached.isPresent() && cached.get().name() != null && !cached.get().name().isBlank()) {
            return cached.get().name();
        }
        return plain(viewer, WormholesMessages.DOOR_ACCESS_UNKNOWN_PLAYER,
            MinecraftPortalText.arguments("id", playerId.toString().substring(0, SHORT_ID_LENGTH)));
    }

    private static String roleLabel(ServerPlayer viewer, PortalRole role) {
        TextKey key = switch (role) {
            case OWNER -> AccessMessages.LABEL_OWNER;
            case CO_OWNER -> AccessMessages.LABEL_CO_OWNER;
            case USER -> AccessMessages.LABEL_USER;
            case DENIED -> AccessMessages.LABEL_DENIED;
        };
        return plain(viewer, key);
    }

    private static String listedLabel(ServerPlayer viewer, boolean listed) {
        return plain(viewer, listed ? AccessMessages.LABEL_LISTED : AccessMessages.LABEL_UNLISTED);
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

    private static MinecraftElement element(ServerPlayer viewer, String id, LinesKey key, MessageArgs arguments, Item material) {
        MinecraftElement element = new MinecraftElement(id);
        element.setMaterial(material);
        MinecraftLegacyText.apply(viewer, element, key, arguments);
        return element;
    }

    private static void notice(ServerPlayer viewer, TextKey message, MessageArgs arguments) {
        viewer.sendSystemMessage(MinecraftMenuText.text(viewer, message, arguments));
    }

    enum AddResolution {
        NOT_FOUND,
        OWNER,
        ALREADY_LISTED,
        ADD
    }

    private enum KeyResult {
        INVALID,
        UNCHANGED,
        TAKEN,
        SET
    }
}
