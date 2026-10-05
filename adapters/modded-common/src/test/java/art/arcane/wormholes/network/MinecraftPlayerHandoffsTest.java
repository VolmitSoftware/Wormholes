package art.arcane.wormholes.network;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.MinecraftNetworkService;
import art.arcane.wormholes.network.mesh.DrainMode;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import com.mojang.authlib.GameProfile;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanList;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class MinecraftPlayerHandoffsTest extends MinecraftTestBase {
    private MinecraftServer server;
    private WormholesModRuntime runtime;
    private NetworkManager network;
    private MinecraftEntityTransfers entities;
    private MinecraftPlayerHandoffs handoffs;
    private PlayerList players;
    private UserBanList bans;
    private NameAndId identity;
    private WireMessage.HandoffRequest request;

    @Before
    public void setUp() {
        runtime = mock(WormholesModRuntime.class);
        network = mock(NetworkManager.class);
        MinecraftNetworkService service = mock(MinecraftNetworkService.class);
        entities = mock(MinecraftEntityTransfers.class);
        when(runtime.network()).thenReturn(service);
        when(service.entityTransfers()).thenReturn(entities);
        server = mock(MinecraftServer.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = mock(WormholesSettings.class);
        players = mock(PlayerList.class);
        bans = mock(UserBanList.class);
        when(runtime.server()).thenReturn(server);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        when(settings.getMain()).thenReturn(new MainConfig());
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());
        when(players.getBans()).thenReturn(bans);
        when(players.getMaxPlayers()).thenReturn(10);
        when(network.drain()).thenReturn(mock(DrainMode.class));
        NetworkConfig config = new NetworkConfig();
        config.autoAcceptTransfers = true;
        when(network.activeConfig()).thenReturn(config);
        when(network.send(any(), any())).thenReturn(true);
        identity = NameAndId.createOffline("HandoffFixture");
        request = new WireMessage.HandoffRequest(UUID.randomUUID(), identity.id(), identity.name(), null, true, false, false, null);
        handoffs = new MinecraftPlayerHandoffs(runtime, network);
    }

    @Test
    public void directDispatchWaitsForValidatedEndpointAndAuthenticAck() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = "destination";
        peer.publicHost = "127.0.0.1";
        peer.publicPort = 25565;
        when(network.getPeer(peer.name)).thenReturn(peer);
        when(network.isPeerReady(peer.name)).thenReturn(true);
        GameEndpoint endpoint = new GameEndpoint("127.0.0.1", 25565);
        when(network.playerEndpoint(eq(peer.name), any())).thenReturn(endpoint);
        CompletableFuture<EndpointValidation> validation = new CompletableFuture<>();
        when(network.validatePlayerEndpoint(peer.name, endpoint)).thenReturn(validation);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(identity.id());
        when(player.getGameProfile()).thenReturn(new GameProfile(identity.id(), identity.name()));
        when(player.createCommandSourceStack()).thenReturn(Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS));
        ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class,
            withSettings().extraInterfaces(ServerConnectionAccess.class));
        Connection connection = mock(Connection.class);
        when(((ServerConnectionAccess) listener).wormholesConnection()).thenReturn(connection);
        when(connection.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 40000));
        player.connection = listener;
        assertTrue(handoffs.begin(player, peer.name, null, null, null));
        verify(network, never()).send(eq(peer.name), any());
        validation.complete(new EndpointValidation(EndpointValidation.State.VERIFIED, "fixture"));
        ArgumentCaptor<WireMessage> outgoing = ArgumentCaptor.forClass(WireMessage.class);
        verify(network).send(eq(peer.name), outgoing.capture());
        assertTrue(outgoing.getValue() instanceof WireMessage.HandoffRequest);
        WireMessage.HandoffRequest transfer = (WireMessage.HandoffRequest) outgoing.getValue();
        handoffs.receive("other-peer", new WireMessage.HandoffAck(transfer.transferId()));
        verify(listener, never()).send(any(Packet.class));
        handoffs.receive(peer.name, new WireMessage.HandoffAck(transfer.transferId()));
        handoffs.receive(peer.name, new WireMessage.HandoffAck(transfer.transferId()));
        verify(listener).send(new ClientboundTransferPacket(endpoint.host(), endpoint.port()));
        assertTrue(handoffs.locked(identity.id()));
        verify(entities).playerDispatched(identity.id());
        clearInvocations(entities);
        WireMessage.HandoffResult receipt = new WireMessage.HandoffResult(transfer.transferId(), identity.id(), true, "arrived");
        handoffs.receive("other-peer", receipt);
        verifyNoInteractions(entities);
        handoffs.receive(peer.name, receipt);
        handoffs.receive(peer.name, receipt);
        verify(entities).receive(peer.name, receipt);
        assertFalse(handoffs.locked(identity.id()));
    }

    @Test
    public void cancelIsBoundToPeerAndReplayCannotReserveAgain() {
        handoffs.receive("source", request);
        assertTrue(handoffs.hasAdmission(identity.id()));
        verify(network).send("source", new WireMessage.HandoffAck(request.transferId()));
        handoffs.receive("unrelated", new WireMessage.HandoffCancel(request.transferId(), identity.id()));
        assertTrue(handoffs.hasAdmission(identity.id()));
        handoffs.receive("source", new WireMessage.HandoffCancel(request.transferId(), identity.id()));
        assertFalse(handoffs.hasAdmission(identity.id()));
        clearInvocations(network);
        handoffs.receive("source", request);
        assertFalse(handoffs.hasAdmission(identity.id()));
        ArgumentCaptor<WireMessage> sent = ArgumentCaptor.forClass(WireMessage.class);
        verify(network).send(eq("source"), sent.capture());
        assertTrue(sent.getValue() instanceof WireMessage.HandoffDeny denial && denial.reason().equals("handoff cancelled"));
    }

    @Test
    public void whitelistBanAndAuthenticationRemainAdmissionGates() {
        when(players.isUsingWhitelist()).thenReturn(true);
        handoffs.receive("source", request);
        assertFalse(handoffs.hasAdmission(identity.id()));
        ArgumentCaptor<WireMessage> sent = ArgumentCaptor.forClass(WireMessage.class);
        verify(network).send(eq("source"), sent.capture());
        assertTrue(sent.getValue() instanceof WireMessage.HandoffDeny denial && denial.reason().equals("player is not whitelisted"));
        handoffs.close();
        handoffs = new MinecraftPlayerHandoffs(runtime, network);
        when(players.isUsingWhitelist()).thenReturn(false);
        when(bans.isBanned(identity)).thenReturn(true);
        clearInvocations(network);
        handoffs.receive("source", request);
        verify(network).send(eq("source"), sent.capture());
        assertTrue(sent.getValue() instanceof WireMessage.HandoffDeny denial && denial.reason().equals("player is banned"));
        handoffs.close();
        handoffs = new MinecraftPlayerHandoffs(runtime, network);
        when(bans.isBanned(identity)).thenReturn(false);
        clearInvocations(network);
        handoffs.receive("source", new WireMessage.HandoffRequest(UUID.randomUUID(), UUID.randomUUID(), identity.name(), null, true, false, false, null));
        verify(network).send(eq("source"), sent.capture());
        assertTrue(sent.getValue() instanceof WireMessage.HandoffDeny denial && denial.reason().contains("forwarded player identity"));
    }

    @Test
    public void reservedCapacityDeniesAnotherPlayerBeforeTheirTransfer() {
        when(players.getMaxPlayers()).thenReturn(1);
        handoffs.receive("source", request);
        NameAndId other = NameAndId.createOffline("SecondFixture");
        assertFalse(handoffs.reservedCapacityFull(identity));
        assertTrue(handoffs.reservedCapacityFull(other));
        WireMessage.HandoffRequest second = new WireMessage.HandoffRequest(UUID.randomUUID(), other.id(), other.name(), null, true, false, false, null);
        clearInvocations(network);
        handoffs.receive("source", second);
        assertTrue(handoffs.hasAdmission(identity.id()));
        assertFalse(handoffs.hasAdmission(other.id()));
        ArgumentCaptor<WireMessage> sent = ArgumentCaptor.forClass(WireMessage.class);
        verify(network).send(eq("source"), sent.capture());
        assertTrue(sent.getValue() instanceof WireMessage.HandoffDeny denial && denial.reason().equals("destination server is full"));
    }

    @Test
    public void successfulJoinRecordsReceiptOnlyForAdmittingPeer() {
        handoffs.receive("source", request);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(identity.id());
        when(players.getPlayer(identity.id())).thenReturn(player);
        when(runtime.schedule(any(), anyLong())).thenReturn(true);
        handoffs.joined(player);
        ArgumentCaptor<Runnable> placement = ArgumentCaptor.forClass(Runnable.class);
        verify(runtime).schedule(placement.capture(), eq(1L));
        placement.getValue().run();
        assertFalse(handoffs.hasAdmission(identity.id()));
        verify(network).send("source", new WireMessage.HandoffResult(request.transferId(), identity.id(), true, "server join completed"));
        clearInvocations(network);
        handoffs.receive("unrelated", new WireMessage.HandoffStatus(request.transferId(), identity.id()));
        verifyNoInteractions(network);
        handoffs.receive("source", new WireMessage.HandoffStatus(request.transferId(), identity.id()));
        verify(network).send("source", new WireMessage.HandoffResult(request.transferId(), identity.id(), true, "server join completed"));
    }
}
