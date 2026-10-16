package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftMenuTransportTest extends MinecraftTestBase {
    private static final UUID ALEX = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");
    private static final UUID OFFLINE = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000002");

    @Test
    public void receiveOnTheServerThreadForwardsTheFrameToTheMenuService() {
        Fixture fixture = fixture(true);
        byte[] frame = {32, 1};

        fixture.transport().receive(fixture.player(), frame);

        verify(fixture.menus()).receive(ALEX, frame);
    }

    @Test
    public void receiveOffTheServerThreadRunsOnTheServerThread() {
        Fixture fixture = fixture(false);
        byte[] frame = {34, 0, 0, 0, 0, 0, 0, 0, 0};

        fixture.transport().receive(fixture.player(), frame);

        verify(fixture.menus(), never()).receive(any(), any());
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.captor();
        verify(fixture.server()).execute(tasks.capture());
        when(fixture.server().isSameThread()).thenReturn(true);
        tasks.getValue().run();
        verify(fixture.menus()).receive(ALEX, frame);
    }

    @Test
    public void oversizedFramesDisconnectThePlayerInsteadOfReachingTheMenuService() {
        Fixture fixture = fixture(true);

        fixture.transport().receive(fixture.player(), new byte[MenuLimits.MAX_C2S_BYTES + 1]);

        verify(fixture.menus(), never()).receive(any(), any());
        verify(fixture.player().connection).disconnect(any(Component.class));
    }

    @Test
    public void framesAtTheClientLimitReachTheMenuService() {
        Fixture fixture = fixture(true);
        byte[] frame = new byte[MenuLimits.MAX_C2S_BYTES];

        fixture.transport().receive(fixture.player(), frame);

        verify(fixture.menus()).receive(ALEX, frame);
        verify(fixture.player().connection, never()).disconnect(any(Component.class));
    }

    @Test
    public void deferredReceiveIsDroppedWhenThePlayerDisconnectedBeforeTheHop() {
        Fixture fixture = fixture(false);

        fixture.transport().receive(fixture.player(), new byte[] {32, 1});
        when(fixture.player().hasDisconnected()).thenReturn(true);
        runDeferred(fixture);

        verify(fixture.menus(), never()).receive(any(), any());
    }

    @Test
    public void deferredReceiveIsDroppedWhenANewerConnectionReplacedThePlayer() {
        Fixture fixture = fixture(false);
        ServerPlayer newer = mock(ServerPlayer.class);
        when(newer.getUUID()).thenReturn(ALEX);

        fixture.transport().receive(fixture.player(), new byte[] {32, 1});
        when(fixture.players().getPlayer(ALEX)).thenReturn(newer);
        runDeferred(fixture);

        verify(fixture.menus(), never()).receive(any(), any());
    }

    @Test
    public void receiveWhileStoppedIsDropped() {
        Fixture fixture = fixture(true);
        when(fixture.runtime().running()).thenReturn(false);

        fixture.transport().receive(fixture.player(), new byte[] {32, 1});

        verify(fixture.menus(), never()).receive(any(), any());
    }

    @Test
    public void sendDeliversTheFrameAsAMenuPayloadToTheViewer() {
        Fixture fixture = fixture(true);
        byte[] frame = {1, 1};

        assertTrue(fixture.transport().send(ALEX, frame));

        ArgumentCaptor<Packet<?>> packets = ArgumentCaptor.captor();
        verify(fixture.player().connection).send(packets.capture());
        ClientboundCustomPayloadPacket packet = (ClientboundCustomPayloadPacket) packets.getValue();
        assertArrayEquals(frame, ((MenuPayload) packet.payload()).data());
    }

    @Test
    public void sendToAnOfflineViewerFails() {
        Fixture fixture = fixture(true);

        assertFalse(fixture.transport().send(OFFLINE, new byte[] {1, 1}));
    }

    @Test
    public void sendWhileStoppedFails() {
        Fixture fixture = fixture(true);
        when(fixture.runtime().running()).thenReturn(false);

        assertFalse(fixture.transport().send(ALEX, new byte[] {1, 1}));
        verify(fixture.player().connection, never()).send(any(Packet.class));
    }

    @Test
    public void loaderPacketFactoryBuildsOutboundPackets() {
        Fixture fixture = fixture(true);
        Packet<?> loaderPacket = mock(Packet.class);
        byte[] frame = {4};
        MenuPayload[] built = new MenuPayload[1];
        fixture.transport().packets(payload -> {
            built[0] = payload;
            return loaderPacket;
        });

        assertTrue(fixture.transport().send(ALEX, frame));

        ArgumentCaptor<Packet<?>> packets = ArgumentCaptor.captor();
        verify(fixture.player().connection).send(packets.capture());
        assertSame(loaderPacket, packets.getValue());
        assertArrayEquals(frame, built[0].data());
    }

    private static void runDeferred(Fixture fixture) {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.captor();
        verify(fixture.server()).execute(tasks.capture());
        when(fixture.server().isSameThread()).thenReturn(true);
        tasks.getValue().run();
    }

    private static Fixture fixture(boolean serverThread) {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        ServerPlayer player = mock(ServerPlayer.class);
        MenuService menus = mock(MenuService.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        when(player.getUUID()).thenReturn(ALEX);
        when(player.getGameProfile()).thenReturn(new GameProfile(ALEX, "Alex"));
        when(runtime.running()).thenReturn(true);
        when(runtime.server()).thenReturn(server);
        when(runtime.menus()).thenReturn(menus);
        when(server.isSameThread()).thenReturn(serverThread);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(ALEX)).thenReturn(player);
        return new Fixture(new MinecraftMenuTransport(runtime), runtime, server, players, player, menus);
    }

    private record Fixture(MinecraftMenuTransport transport, WormholesModRuntime runtime, MinecraftServer server, PlayerList players,
                           ServerPlayer player, MenuService menus) {
    }
}
