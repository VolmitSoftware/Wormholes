package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.localization.DoorViewMessages;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.localization.WormholesMessages;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MinecraftDoorMenus implements AutoCloseable {
    private static final Map<MinecraftServer, MinecraftDoorMenus> SERVICES = new HashMap<>();
    private static final long PROMPT_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int PAGE_SIZE = 45;

    private final MinecraftDoorService doors;
    private final MinecraftServer server;
    private final ExecutorService profiles = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new HashMap<>();
    private boolean closed;

    MinecraftDoorMenus(WormholesModRuntime runtime, MinecraftDoorService doors) {
        this.doors = doors;
        server = runtime.server();
        SERVICES.put(server, this);
    }

    public static boolean chat(ServerPlayer player, String message) {
        MinecraftDoorMenus menus = SERVICES.get(player.level().getServer());
        return menus != null && menus.acceptChat(player, message);
    }

    public boolean open(ServerPlayer player, UUID itemId) {
        if (!doors.canManage(player, itemId)) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.DOOR_ACCESS_EDIT_DENIED, Map.of()));
            return false;
        }
        new Session(player, itemId).open();
        return true;
    }

    void tick() {
        prompts.entrySet().removeIf(entry -> entry.getValue().expiresAt() < System.nanoTime());
        Iterator<Session> iterator = sessions.values().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (session.player.containerMenu != session.menu) {
                iterator.remove();
            } else if (!session.valid()) {
                iterator.remove();
                session.player.closeContainer();
            }
        }
    }

    void disconnected(ServerPlayer player) {
        sessions.remove(player.getUUID());
        prompts.remove(player.getUUID());
    }

    @Override
    public void close() {
        closed = true;
        SERVICES.remove(server, this);
        for (Session session : sessions.values()) {
            if (session.player.containerMenu == session.menu) {
                session.player.closeContainer();
            }
        }
        sessions.clear();
        prompts.clear();
        profiles.shutdownNow();
    }

    private boolean acceptChat(ServerPlayer player, String message) {
        Prompt prompt = prompts.remove(player.getUUID());
        if (prompt == null) {
            return false;
        }
        Session session = prompt.session();
        if (prompt.expiresAt() < System.nanoTime() || !session.valid()) {
            return true;
        }
        String value = message.trim();
        String cancel = MinecraftMenuText.text(player, WormholesMessages.PORTAL_INPUT_CANCEL, Map.of()).getString();
        if (value.equalsIgnoreCase(cancel)) {
            session.open();
            return true;
        }
        CompletableFuture.supplyAsync(() -> resolve(value), profiles).orTimeout(10, TimeUnit.SECONDS)
            .whenCompleteAsync((profile, failure) -> {
                if (!session.valid()) {
                    return;
                }
                if (failure != null) {
                    LoggerFactory.getLogger("Wormholes").error("Could not resolve player for door access", failure);
                }
                if (failure != null || profile.isEmpty()) {
                    player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.DOOR_ACCESS_PLAYER_NOT_FOUND, Map.of("name", value)));
                    session.open();
                    return;
                }
                UUID listed = profile.get().id();
                DoorAccessRecord record = doors.state().accessRecord(session.itemId).orElseThrow();
                if (record.ownerId().equals(listed)) {
                    player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.DOOR_ACCESS_OWNER_ALWAYS, Map.of()));
                    session.open();
                } else {
                    session.save(doors.addAccessPlayer(player, session.itemId, listed), true);
                }
            }, server);
        return true;
    }

    private Optional<GameProfile> resolve(String value) {
        try {
            return server.services().profileResolver().fetchById(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return server.services().profileResolver().fetchByName(value);
        }
    }

    private String playerName(UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        return player == null ? id.toString() : player.getGameProfile().name();
    }

    private final class Session {
        private final ServerPlayer player;
        private final UUID itemId;
        private MinecraftInventoryMenu menu;
        private int page;
        private boolean saving;

        private Session(ServerPlayer player, UUID itemId) {
            this.player = player;
            this.itemId = itemId;
        }

        private boolean valid() {
            return !closed && !player.hasDisconnected() && doors.canManage(player, itemId);
        }

        private void open() {
            if (!valid()) {
                return;
            }
            PlacedDoorEndpoint endpoint = doors.state().findEndpointByItem(itemId).orElseThrow();
            MinecraftInventoryMenu.open(player, MinecraftMenuText.text(player, WormholesMessages.DOOR_MENU_ACCESS_TITLE,
                Map.of("kind", label(kind(endpoint.identity().kind())))), new MinecraftInventoryMenu.Actions(this::valid, this::render, this::click));
            sessions.put(player.getUUID(), this);
        }

        private void render(MinecraftInventoryMenu active) {
            menu = active;
            PlacedDoorEndpoint endpoint = doors.state().findEndpointByItem(itemId).orElseThrow();
            DoorAccessRecord record = doors.state().accessRecord(itemId).orElseThrow();
            active.set(2, MinecraftMenuText.item(player, Items.SPYGLASS, DoorViewMessages.MENU_PROJECTION,
                Map.of("state", label(projectionLabel(endpoint.projection())))));
            active.set(3, MinecraftMenuText.item(player, MinecraftDoorItems.door(endpoint.identity()).getItem(), WormholesMessages.DOOR_MENU_ACCESS_PLACARD,
                Map.of("kind", label(kind(endpoint.identity().kind())), "owner", playerName(record.ownerId()), "count", record.players().size(),
                    "whitelisted", count(record, DoorAccessState.WHITELIST), "blacklisted", count(record, DoorAccessState.BLACKLIST))));
            active.set(4, MinecraftMenuText.item(player, endpoint.openState() == DoorOpenState.OPEN ? Items.DYE.lime() : Items.DYE.gray(),
                WormholesMessages.DOOR_MENU_ACCESS_OPEN_STATE, Map.of("state", label(openLabel(endpoint.openState())),
                    "next", label(openLabel(endpoint.openState().flipped())))));
            active.set(5, MinecraftMenuText.item(player, Items.WRITABLE_BOOK, WormholesMessages.DOOR_MENU_ACCESS_ADD_PLAYER, Map.of()));
            List<UUID> listed = record.listedPlayers();
            page = Math.min(page, Math.max(0, (listed.size() - 1) / PAGE_SIZE));
            if (page > 0) {
                active.set(0, MinecraftMenuText.item(player, Items.ARROW, WormholesMessages.PORTAL_MENU_DESTINATION_PREVIOUS, Map.of()));
            }
            if ((page + 1) * PAGE_SIZE < listed.size()) {
                active.set(8, MinecraftMenuText.item(player, Items.ARROW, WormholesMessages.PORTAL_MENU_DESTINATION_NEXT, Map.of()));
            }
            for (int index = page * PAGE_SIZE; index < Math.min(listed.size(), (page + 1) * PAGE_SIZE); index++) {
                UUID id = listed.get(index);
                DoorAccessState access = record.stateOf(id);
                active.set(9 + index % PAGE_SIZE, MinecraftMenuText.item(player, switch (access) {
                    case NEUTRAL -> Items.STAINED_GLASS_PANE.black();
                    case WHITELIST -> Items.STAINED_GLASS_PANE.green();
                    case BLACKLIST -> Items.STAINED_GLASS_PANE.red();
                }, WormholesMessages.DOOR_MENU_ACCESS_ENTRY, Map.of("name", playerName(id), "state", label(accessLabel(access)))));
            }
        }

        private void click(MinecraftInventoryMenu.Click click) {
            if (saving || !valid()) {
                return;
            }
            if (click.slot() == 0 && page > 0) {
                page--;
                menu.refresh();
            } else if (click.slot() == 8) {
                page++;
                menu.refresh();
            } else if (click.slot() == 2 && !click.right()) {
                if (!doors.projectionEnabled()) {
                    player.sendSystemMessage(MinecraftMenuText.text(player, DoorViewMessages.DISABLED, Map.of()));
                    return;
                }
                DoorProjectionState next = doors.state().findEndpointByItem(itemId).orElseThrow().projection().next();
                save(doors.updateProjection(player, itemId, next).thenApplyAsync(changed -> {
                    if (changed) {
                        player.sendSystemMessage(MinecraftMenuText.text(player, projectionNotice(next), Map.of()));
                    }
                    return changed;
                }, server), false);
            } else if (click.slot() == 4 && !click.right()) {
                DoorOpenState current = doors.state().findEndpointByItem(itemId).orElseThrow().openState();
                save(doors.updateOpenState(player, itemId, current.flipped()), false);
            } else if (click.slot() == 5 && !click.right()) {
                prompts.put(player.getUUID(), new Prompt(this, System.nanoTime() + PROMPT_NANOS));
                player.closeContainer();
                player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.DOOR_ACCESS_PROMPT_PLAYER,
                    Map.of("cancel", label(WormholesMessages.PORTAL_INPUT_CANCEL))));
            } else if (click.slot() >= 9) {
                List<UUID> listed = doors.state().accessRecord(itemId).orElseThrow().listedPlayers();
                int index = page * PAGE_SIZE + click.slot() - 9;
                if (index >= listed.size()) {
                    return;
                }
                UUID id = listed.get(index);
                if (click.middle() || click.shift() && !click.right()) {
                    save(doors.removeAccessPlayer(player, itemId, id), false);
                } else {
                    save(doors.updateAccessState(player, itemId,
                        new MinecraftDoorService.AccessChange(id, click.right() ? DoorAccessState.BLACKLIST : DoorAccessState.WHITELIST)), false);
                }
            }
        }

        private void save(CompletableFuture<Boolean> operation, boolean reopen) {
            saving = true;
            operation.whenCompleteAsync((changed, failure) -> {
                saving = false;
                if (!valid()) {
                    return;
                }
                if (failure != null) {
                    player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.DOOR_ACCESS_SAVE_FAILED, Map.of()));
                }
                if (reopen) {
                    open();
                } else if (player.containerMenu == menu) {
                    menu.refresh();
                }
            }, server);
        }

        private String label(TextKey key) {
            return MinecraftMenuText.text(player, key, Map.of()).getString();
        }
    }

    private static long count(DoorAccessRecord record, DoorAccessState state) {
        long result = 0;
        for (DoorAccessState entry : record.players().values()) {
            if (entry == state) {
                result++;
            }
        }
        return result;
    }

    private static TextKey kind(DoorKind kind) {
        return switch (kind) {
            case PAIR -> WormholesMessages.DOOR_ACCESS_KIND_PAIR;
            case PERSONAL -> WormholesMessages.DOOR_ACCESS_KIND_PERSONAL;
            case PUBLIC -> WormholesMessages.DOOR_ACCESS_KIND_PUBLIC;
            case RETURN -> WormholesMessages.DOOR_ACCESS_KIND_RETURN;
        };
    }

    private static TextKey openLabel(DoorOpenState state) {
        return state == DoorOpenState.OPEN ? WormholesMessages.DOOR_OPEN_STATE_OPEN : WormholesMessages.DOOR_OPEN_STATE_CLOSED;
    }

    private static TextKey projectionLabel(DoorProjectionState state) {
        return switch (state) {
            case INHERIT -> DoorViewMessages.STATE_INHERIT;
            case ON -> DoorViewMessages.STATE_ON;
            case OFF -> DoorViewMessages.STATE_OFF;
        };
    }

    private static TextKey projectionNotice(DoorProjectionState state) {
        return switch (state) {
            case INHERIT -> DoorViewMessages.TOGGLE_INHERIT;
            case ON -> DoorViewMessages.TOGGLE_ON;
            case OFF -> DoorViewMessages.TOGGLE_OFF;
        };
    }

    private static TextKey accessLabel(DoorAccessState state) {
        return switch (state) {
            case NEUTRAL -> WormholesMessages.DOOR_ACCESS_LABEL_NEUTRAL;
            case WHITELIST -> WormholesMessages.DOOR_ACCESS_LABEL_WHITELIST;
            case BLACKLIST -> WormholesMessages.DOOR_ACCESS_LABEL_BLACKLIST;
        };
    }

    private record Prompt(Session session, long expiresAt) {
    }
}
