package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.wormholes.config.toml.ClientViewConfig;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.modded.MinecraftAccessService;
import art.arcane.wormholes.modded.MinecraftLocalization;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.render.client.session.ClientViewOptions;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import art.arcane.wormholes.render.client.session.ClientViewSessionRegistry;
import art.arcane.wormholes.render.client.session.ClientViewSessionState;
import art.arcane.wormholes.render.client.session.ClientViewSessionStats;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftClientViewCommandsTest extends MinecraftTestBase {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");

    private MinecraftClientViewService clientViews;
    private ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> registry;
    private ClientViewServerSession<MinecraftClientViewPeer, BlockState> session;
    private CommandSourceStack admin;
    private CommandSourceStack projectionAdmin;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        clientViews = mock(MinecraftClientViewService.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer alex = mock(ServerPlayer.class);
        registry = mock(ClientViewSessionRegistry.class);
        session = mock(ClientViewServerSession.class);
        admin = mock(CommandSourceStack.class);
        projectionAdmin = mock(CommandSourceStack.class);
        ClientViewConfig config = new ClientViewConfig();
        when(runtime.running()).thenReturn(true);
        when(runtime.access()).thenReturn(access);
        when(runtime.localization()).thenReturn(localization);
        when(runtime.clientViews()).thenReturn(clientViews);
        when(runtime.server()).thenReturn(server);
        when(access.permission(admin, "wormholes.admin")).thenReturn(true);
        when(access.permission(projectionAdmin, "wormholes.admin.projection")).thenReturn(true);
        when(localization.snapshot(null)).thenReturn(LocalizationSnapshot.create(LocalizationCandidate.english(
            WormholesMessages.catalog(), PluralSelector.oneOther())));
        when(clientViews.registry()).thenReturn(registry);
        when(registry.options()).thenReturn(ClientViewOptions.from(config, ClientViewOptions.DEFAULT_INTEREST_GRACE_TICKS));
        when(registry.runtimeEnabled()).thenReturn(true);
        when(registry.stats()).thenReturn(List.of());
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayerByName("Alex")).thenReturn(alex);
        when(alex.getUUID()).thenReturn(ALEX);
        when(alex.getGameProfile()).thenReturn(new GameProfile(ALEX, "Alex"));
        when(session.player()).thenReturn(new MinecraftClientViewPeer(ALEX, "Alex", new Connection(PacketFlow.SERVERBOUND)));
        dispatcher = new CommandDispatcher<>();
        new MinecraftClientViewCommands(runtime).register(dispatcher);
    }

    @Test
    public void statusPrintsTheSharedCatalogRepliesForEverySession() throws CommandSyntaxException {
        when(registry.stats()).thenReturn(List.of(new ClientViewSessionStats(ALEX, 1, ClientViewSessionState.CLIENT_VIEW,
            ClientViewCapability.of(ClientViewCapability.PLATES), 2, 7L, 3072L, 5L, 1, 4L, 12_500L, 900L, 30L, 0L, 0L, 0L, 0L, null)));
        when(registry.session(ALEX)).thenReturn(session);

        assertEquals(1, dispatcher.execute("wormholes clientview status", admin));

        assertEquals(List.of("ClientView runtime on, configured on, 1 sessions",
            "Alex CLIENT_VIEW | caps plates portals 2 frames 7 sent 3.0 KiB unacked 1 rtt 12ms cells 900"), messages(admin));
    }

    @Test
    public void statusWithoutSessionsSaysSo() throws CommandSyntaxException {
        dispatcher.execute("wormholes clientview status", admin);

        assertEquals(List.of("ClientView runtime on, configured on, 0 sessions", "No player has a ClientView session."), messages(admin));
    }

    @Test
    public void onAndOffFlipTheRuntimeSwitchAndReply() throws CommandSyntaxException {
        dispatcher.execute("wormholes clientview off", admin);
        dispatcher.execute("wormholes clientview on", admin);

        verify(clientViews).runtimeEnabled(false);
        verify(clientViews).runtimeEnabled(true);
        assertEquals(List.of("ClientView is off. Every session returned to vanilla projection.",
            "ClientView is offered again. Configured: on."), messages(admin));
    }

    @Test
    public void resetRestartsOnlyActiveSessionsOfOnlinePlayers() throws CommandSyntaxException {
        when(registry.session(ALEX)).thenReturn(session);
        when(session.state()).thenReturn(ClientViewSessionState.PENDING);
        assertEquals(0, dispatcher.execute("wormholes clientview reset Steve", admin));
        assertEquals(0, dispatcher.execute("wormholes clientview reset Alex", admin));
        verify(session, never()).reset(any());

        when(session.state()).thenReturn(ClientViewSessionState.CLIENT_VIEW);
        assertEquals(1, dispatcher.execute("wormholes clientview reset Alex", admin));

        verify(session).reset(ClientViewMessage.ResetReason.TELEPORT);
        assertEquals(List.of("No online player is named Steve.", "Alex has no active ClientView session.",
            "Restarting the ClientView stream for Alex."), messages(admin));
    }

    @Test
    public void onlyTheAdminNodeReachesTheCommand() {
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("wormholes clientview status", projectionAdmin));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("wormholes clientview off", projectionAdmin));

        verify(clientViews, never()).runtimeEnabled(anyBoolean());
        verify(projectionAdmin, never()).sendSystemMessage(any());
    }

    @Test
    public void theBareCommandNoLongerListsSessions() {
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("wormholes clientview", admin));

        verify(registry, never()).stats();
    }

    private static List<String> messages(CommandSourceStack source) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, atLeastOnce()).sendSystemMessage(captor.capture());
        List<String> lines = new ArrayList<>(captor.getAllValues().size());
        for (Component component : captor.getAllValues()) {
            lines.add(component.getString());
        }
        return lines;
    }
}
