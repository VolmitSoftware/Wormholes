package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocol;

class BukkitMenuTransportTest {
    private static final byte[] ACCEPT = {(byte) MenuCodec.S2C_ACCEPT, 1};
    private static final byte[] OPEN = {(byte) MenuCodec.S2C_OPEN, 0};

    private final UUID viewerId = UUID.randomUUID();
    private final Set<String> listening = new HashSet<>();
    private Plugin plugin;
    private Messenger messenger;
    private PluginManager pluginManager;
    private Player player;
    private MenuServiceApi service;
    private BukkitChestRenderer chestRenderer;
    private BukkitMenuTransport transport;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        messenger = mock(Messenger.class);
        pluginManager = mock(PluginManager.class);
        player = mock(Player.class);
        service = mock(MenuServiceApi.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getMessenger()).thenReturn(messenger);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getPlayer(viewerId)).thenReturn(player);
        when(player.getUniqueId()).thenReturn(viewerId);
        when(player.isOnline()).thenReturn(true);
        when(player.getListeningPluginChannels()).thenAnswer(invocation -> Set.copyOf(listening));
        chestRenderer = mock(BukkitChestRenderer.class);
        transport = new BukkitMenuTransport(plugin, chestRenderer);
    }

    @Test
    void channelIsTheMenuProtocolChannel() {
        assertEquals("wormholes:menu", MenuProtocol.CHANNEL);
    }

    @Test
    void startRegistersBothDirectionsAndStopUnregistersThem() {
        transport.start(service);

        verify(messenger).registerOutgoingPluginChannel(plugin, "wormholes:menu");
        verify(messenger).registerIncomingPluginChannel(plugin, "wormholes:menu", transport);
        verify(pluginManager).registerEvents(transport, plugin);

        transport.stop();

        verify(messenger).unregisterIncomingPluginChannel(plugin, "wormholes:menu", transport);
        verify(messenger).unregisterOutgoingPluginChannel(plugin, "wormholes:menu");
    }

    @Test
    void incomingFramesOnTheMenuChannelReachTheService() {
        transport.start(service);
        byte[] frame = {32, 1};

        transport.onPluginMessageReceived("wormholes:menu", player, frame);
        transport.onPluginMessageReceived("wormholes:v11", player, frame);

        verify(service).receive(viewerId, frame);
        verify(service, times(1)).receive(any(UUID.class), any(byte[].class));
    }

    @Test
    void framesGoOutOnlyOnceTheClientListensOnTheChannel() {
        transport.start(service);

        assertFalse(transport.send(viewerId, OPEN));
        verify(player, never()).sendPluginMessage(any(Plugin.class), anyString(), any(byte[].class));

        listening.add(MenuProtocol.CHANNEL);

        assertTrue(transport.send(viewerId, OPEN));
        verify(player).sendPluginMessage(plugin, MenuProtocol.CHANNEL, OPEN);
    }

    @Test
    void acceptIsHeldUntilTheChannelRegistrationArrives() {
        transport.start(service);

        assertTrue(transport.send(viewerId, ACCEPT));
        verify(player, never()).sendPluginMessage(any(Plugin.class), anyString(), any(byte[].class));

        transport.on(new PlayerRegisterChannelEvent(player, "wormholes:v11"));
        verify(player, never()).sendPluginMessage(any(Plugin.class), anyString(), any(byte[].class));

        transport.on(new PlayerRegisterChannelEvent(player, MenuProtocol.CHANNEL));
        transport.on(new PlayerRegisterChannelEvent(player, MenuProtocol.CHANNEL));

        verify(player).sendPluginMessage(plugin, MenuProtocol.CHANNEL, ACCEPT);
    }

    @Test
    void quitClearsTheHeldAcceptAndTheMenuState() {
        transport.start(service);
        transport.send(viewerId, ACCEPT);

        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        transport.on(quit);
        transport.on(new PlayerRegisterChannelEvent(player, MenuProtocol.CHANNEL));

        verify(service).quit(viewerId);
        verify(chestRenderer).disconnected(viewerId);
        verify(player, never()).sendPluginMessage(any(Plugin.class), anyString(), any(byte[].class));
    }

    @Test
    void offlineViewersReceiveNothing() {
        transport.start(service);
        listening.add(MenuProtocol.CHANNEL);
        when(player.isOnline()).thenReturn(false);

        assertFalse(transport.send(viewerId, ACCEPT));
        assertFalse(transport.send(UUID.randomUUID(), OPEN));
    }
}
