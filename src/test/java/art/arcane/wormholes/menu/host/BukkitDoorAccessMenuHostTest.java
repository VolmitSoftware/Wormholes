package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.door.DoorAccessEdit;
import art.arcane.wormholes.menu.door.DoorAccessMenuView;

class BukkitDoorAccessMenuHostTest {
    private final UUID doorId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID listed = UUID.randomUUID();
    private final List<LogRecord> logged = new ArrayList<>();
    private MenuService previousMenus;
    private MenuService menus;
    private DimensionalDoorManager manager;
    private PlacedDoorEndpoint endpoint;
    private MenuViewer owner;
    private MenuViewer stranger;
    private BukkitDoorAccessMenuHost host;

    @BeforeEach
    void setUp() {
        previousMenus = Wormholes.menus;
        menus = mock(MenuService.class);
        Wormholes.menus = menus;
        manager = mock(DimensionalDoorManager.class);
        endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 1, 64, 1),
            new DoorItemIdentity(doorId, DoorKind.PERSONAL, DoorForm.DOOR, null, null, null), DoorOpenState.OPEN, DoorProjectionState.INHERIT);
        when(manager.endpoint(doorId)).thenReturn(Optional.of(endpoint));
        when(manager.accessRecord(doorId)).thenReturn(Optional.of(DoorAccessRecord.unrestricted(doorId, ownerId)
            .withPlayerState(listed, DoorAccessState.WHITELIST)));
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getServer()).thenReturn(mock(Server.class));
        owner = viewer(ownerId);
        stranger = viewer(UUID.randomUUID());
        host = new BukkitDoorAccessMenuHost(plugin, () -> manager);
    }

    @AfterEach
    void tearDown() {
        Wormholes.menus = previousMenus;
    }

    @Test
    void theViewJoinsTheEndpointWithItsAccessRecord() {
        DoorAccessMenuView view = host.door(doorId).orElseThrow();

        assertEquals(DoorKind.PERSONAL, view.kind());
        assertEquals(DoorOpenState.OPEN, view.openState());
        assertEquals(DoorProjectionState.INHERIT, view.projection());
        assertEquals(DoorAccessState.WHITELIST, view.record().stateOf(listed));
        assertTrue(view.placardItemId().startsWith("minecraft:"));
        assertTrue(host.door(UUID.randomUUID()).isEmpty());
    }

    @Test
    void onlyTheOwnerManagesTheDoor() {
        assertTrue(host.canManage(owner, doorId));
        assertFalse(host.canManage(stranger, doorId));
        assertFalse(host.canManage(owner, UUID.randomUUID()));
    }

    @Test
    void openStateEditsPassTheCurrentEndpointAndRefreshOpenWindows() throws IOException {
        when(manager.applyOpenState(endpoint, DoorOpenState.CLOSED)).thenReturn(true);

        CompletableFuture<Boolean> saved = host.edit(owner, doorId, new DoorAccessEdit.OpenState(DoorOpenState.CLOSED));

        assertTrue(saved.join());
        verify(menus).invalidate(new MenuScope.Door(doorId));
    }

    @Test
    void listEditsGoThroughTheManager() throws IOException {
        UUID added = UUID.randomUUID();
        when(manager.addAccessPlayer(doorId, added)).thenReturn(true);
        when(manager.applyAccessState(doorId, listed, DoorAccessState.BLACKLIST)).thenReturn(true);

        assertTrue(host.edit(owner, doorId, new DoorAccessEdit.AddPlayer(added)).join());
        assertTrue(host.edit(owner, doorId, new DoorAccessEdit.PlayerState(listed, DoorAccessState.BLACKLIST)).join());
        assertFalse(host.edit(owner, doorId, new DoorAccessEdit.RemovePlayer(UUID.randomUUID())).join());
    }

    @Test
    void anUnchangedDoorRefreshesNothing() throws IOException {
        assertFalse(host.edit(owner, doorId, new DoorAccessEdit.Projection(DoorProjectionState.ON)).join());

        verify(manager).applyProjectionState(endpoint, DoorProjectionState.ON);
        verify(menus, never()).invalidate(any());
    }

    @Test
    void aFailedSaveFailsTheFutureAndIsLoggedWithItsStack() throws IOException {
        IOException failure = new IOException("disk full");
        when(manager.removeAccessPlayer(doorId, listed)).thenThrow(failure);

        CompletableFuture<Boolean> saved = host.edit(owner, doorId, new DoorAccessEdit.RemovePlayer(listed));
        Throwable thrown = assertThrows(CompletionException.class, saved::join);
        host.saveFailed(doorId, thrown.getCause());

        assertSame(failure, thrown.getCause());
        assertEquals(1, logged.size());
        assertEquals(Level.SEVERE, logged.getFirst().getLevel());
        assertSame(failure, logged.getFirst().getThrown());
    }

    @Test
    void withoutTheDoorManagerNothingIsShownOrSaved() {
        manager = null;

        assertTrue(host.door(doorId).isEmpty());
        assertFalse(host.canManage(owner, doorId));
        assertFalse(host.edit(owner, doorId, new DoorAccessEdit.AddPlayer(listed)).join());
    }

    private static MenuViewer viewer(UUID playerId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        return new BukkitMenuViewer(player);
    }
}
