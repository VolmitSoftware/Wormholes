package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.door.DoorAccessRecord;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.door.DoorAccessEdit;
import art.arcane.wormholes.menu.door.DoorAccessMenuView;
import art.arcane.wormholes.modded.MinecraftDoorService;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftDoorAccessMenuHostTest extends MinecraftTestBase {
    private static final UUID DOOR = UUID.fromString("d0d00000-0000-4000-8000-0000000000aa");
    private static final UUID OWNER = UUID.fromString("d0d00000-0000-4000-8000-0000000000ab");
    private static final UUID LISTED = UUID.fromString("d0d00000-0000-4000-8000-0000000000ac");

    private final List<Runnable> serverTasks = new ArrayList<>();

    private MinecraftDoorService doors;
    private MenuService menus;
    private ServerPlayer player;
    private MenuViewer viewer;
    private MinecraftDoorAccessMenuHost host;

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        doors = mock(MinecraftDoorService.class);
        menus = mock(MenuService.class);
        DoorStateService state = mock(DoorStateService.class);
        player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        doAnswer(invocation -> serverTasks.add(invocation.getArgument(0))).when(server).execute(any(Runnable.class));
        when(runtime.server()).thenReturn(server);
        when(runtime.doors()).thenReturn(doors);
        when(runtime.menus()).thenReturn(menus);
        when(doors.enabled()).thenReturn(true);
        when(doors.state()).thenReturn(state);
        PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 1, 64, 1),
            new DoorItemIdentity(DOOR, DoorKind.PERSONAL, DoorForm.DOOR, null, null, null), DoorOpenState.CLOSED, DoorProjectionState.ON);
        when(state.accessRecord(DOOR)).thenReturn(Optional.of(DoorAccessRecord.unrestricted(DOOR, OWNER)
            .withPlayerState(LISTED, DoorAccessState.BLACKLIST)));
        when(state.findEndpointByItem(any(UUID.class))).thenAnswer(invocation -> DOOR.equals(invocation.getArgument(0))
            ? Optional.of(endpoint) : Optional.empty());
        when(player.getUUID()).thenReturn(OWNER);
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftDoorAccessMenuHost(runtime);
    }

    @Test
    public void theViewJoinsTheEndpointWithItsRecordAndDoorItem() {
        DoorAccessMenuView view = host.door(DOOR).orElseThrow();

        assertEquals(DoorKind.PERSONAL, view.kind());
        assertEquals(DoorOpenState.CLOSED, view.openState());
        assertEquals(DoorProjectionState.ON, view.projection());
        assertEquals("minecraft:dark_oak_door", view.placardItemId());
        assertEquals(DoorAccessState.BLACKLIST, view.record().stateOf(LISTED));
        assertFalse(host.door(UUID.randomUUID()).isPresent());
    }

    @Test
    public void aDisabledDoorServiceShowsNoDoor() {
        when(doors.enabled()).thenReturn(false);

        assertFalse(host.door(DOOR).isPresent());
    }

    @Test
    public void changedDoorsRefreshTheirOpenWindows() {
        when(doors.updateOpenState(player, DOOR, DoorOpenState.OPEN)).thenReturn(CompletableFuture.completedFuture(true));
        when(doors.updateAccessState(player, DOOR, new MinecraftDoorService.AccessChange(LISTED, DoorAccessState.WHITELIST)))
            .thenReturn(CompletableFuture.completedFuture(false));

        CompletableFuture<Boolean> opened = host.edit(viewer, DOOR, new DoorAccessEdit.OpenState(DoorOpenState.OPEN));
        CompletableFuture<Boolean> listed = host.edit(viewer, DOOR, new DoorAccessEdit.PlayerState(LISTED, DoorAccessState.WHITELIST));
        runServerTasks();

        assertTrue(opened.join());
        assertFalse(listed.join());

        verify(menus).invalidate(new MenuScope.Door(DOOR));
    }

    @Test
    public void aFailedSaveFailsTheEditFuture() {
        IOException failure = new IOException("disk full");
        when(doors.removeAccessPlayer(player, DOOR, LISTED)).thenReturn(CompletableFuture.failedFuture(failure));

        CompletableFuture<Boolean> edited = host.edit(viewer, DOOR, new DoorAccessEdit.RemovePlayer(LISTED));
        runServerTasks();
        CompletionException thrown = assertThrows(CompletionException.class, edited::join);

        assertSame(failure, thrown.getCause());
        verify(menus, never()).invalidate(any());
    }

    @Test
    public void aFailedSaveCompletesOnTheServerThread() {
        CompletableFuture<Boolean> saving = new CompletableFuture<>();
        when(doors.addAccessPlayer(player, DOOR, LISTED)).thenReturn(saving);
        CompletableFuture<Boolean> edited = host.edit(viewer, DOOR, new DoorAccessEdit.AddPlayer(LISTED));

        saving.completeExceptionally(new IOException("disk full"));

        assertFalse(edited.isDone());
        runServerTasks();
        assertTrue(edited.isCompletedExceptionally());
    }

    @Test
    public void managementAndProjectionFollowTheDoorService() {
        when(doors.canManage(player, DOOR)).thenReturn(true);
        when(doors.projectionEnabled()).thenReturn(true);

        assertTrue(host.canManage(viewer, DOOR));
        assertTrue(host.projectionEnabled());
    }

    private void runServerTasks() {
        List<Runnable> pending = List.copyOf(serverTasks);
        serverTasks.clear();
        pending.forEach(Runnable::run);
    }
}
