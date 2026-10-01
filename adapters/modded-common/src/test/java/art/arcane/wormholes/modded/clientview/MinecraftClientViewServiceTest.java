package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftProjectionService;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewHandshake;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.render.client.session.ClientViewInbound;
import art.arcane.wormholes.render.client.session.ClientViewOptions;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MinecraftClientViewServiceTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");
    private static final long HELLO_CAPS = ClientViewCapability.of(ClientViewCapability.PLATES, ClientViewCapability.BRICK_CACHE);

    private final List<byte[]> sent = new ArrayList<>();
    private final ServerLevel overworld = mock(ServerLevel.class);
    private final ServerLevel nether = mock(ServerLevel.class);
    private volatile ClientViewOptions options;
    private Connection connection;
    private EmbeddedChannel channel;
    private MinecraftClientViewService service;
    private long tick;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        Executor lanes = Runnable::run;
        options = options(true);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.clientViewOptions()).thenAnswer(ignored -> options);
        when(runtime.projections()).thenReturn(projections);
        when(projections.lanes()).thenReturn(lanes);
        connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        service = new MinecraftClientViewService(runtime);
        service.packets(payload -> {
            sent.add(payload.data());
            return new ClientboundCustomPayloadPacket(payload);
        });
        service.start();
    }

    @After
    public void tearDown() {
        service.close();
        channel.finishAndReleaseAll();
    }

    @Test
    public void changingLevelResetsTheSessionForTheNewDimension() throws ClientViewProtocolException {
        ServerPlayer player = player(overworld);
        negotiate(player);
        int mark = sent.size();

        tick(player);
        tick(player);
        when(player.level()).thenReturn(nether);
        tick(player);
        tick(player);

        assertEquals(List.of(ClientViewMessage.ResetReason.DIMENSION), resets(mark));
    }

    @Test
    public void respawnResetsForRespawnInTheSameLevelAndForDimensionInAnother() throws ClientViewProtocolException {
        ServerPlayer first = player(overworld);
        negotiate(first);
        tick(first);
        int mark = sent.size();

        tick(player(overworld));
        tick(player(nether));

        assertEquals(List.of(ClientViewMessage.ResetReason.RESPAWN, ClientViewMessage.ResetReason.DIMENSION), resets(mark));
    }

    @Test
    public void switchingBackOnOffersTheConnectedClientAgain() throws ClientViewProtocolException {
        ServerPlayer player = player(overworld);
        negotiate(player);

        service.runtimeEnabled(false);
        assertEquals(List.of(ClientViewMessage.ResetReason.DISABLED), resets(0));
        int mark = sent.size();
        service.runtimeEnabled(true);

        assertEquals(List.of(ClientViewMessageType.OFFER), types(mark));
        accept();
        mark = sent.size();
        service.runtimeEnabled(true);
        tick(player);
        assertEquals(List.of(), types(mark));
    }

    @Test
    public void enablingInTheConfigurationOffersClientsThatJoinedWhileItWasOff() throws ClientViewProtocolException {
        ServerPlayer player = player(overworld);
        options = options(false);
        tick(player);
        service.channelRegistered(player);
        assertEquals(List.of(), types(0));

        options = options(true);
        tick(player);

        assertEquals(List.of(ClientViewMessageType.OFFER), types(0));
        accept();
    }

    private void negotiate(ServerPlayer player) throws ClientViewProtocolException {
        int mark = sent.size();
        service.channelRegistered(player);
        assertEquals(List.of(ClientViewMessageType.OFFER), types(mark));
        accept();
    }

    private void accept() throws ClientViewProtocolException {
        ClientViewMessage.Offer offer = (ClientViewMessage.Offer) message(sent.get(sent.size() - 1));
        byte[] hello = ClientViewCodec.encodeC2S(ClientViewHandshake.clientHello(offer, offer.mcDataVersion(), HELLO_CAPS, 512 * 1024, 256, 0L,
            "fabric"));
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, service.receive(connection, hello));
    }

    private void tick(ServerPlayer player) {
        service.tick(++tick, List.of(player), List.of());
    }

    private ServerPlayer player(ServerLevel level) {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class, withSettings().extraInterfaces(ServerConnectionAccess.class));
        when(((ServerConnectionAccess) listener).wormholesConnection()).thenReturn(connection);
        player.connection = listener;
        when(player.getUUID()).thenReturn(ALEX);
        when(player.getGameProfile()).thenReturn(new GameProfile(ALEX, "Alex"));
        when(player.level()).thenReturn(level);
        return player;
    }

    private List<ClientViewMessage.ResetReason> resets(int from) throws ClientViewProtocolException {
        List<ClientViewMessage.ResetReason> reasons = new ArrayList<>();
        for (int i = from; i < sent.size(); i++) {
            if (message(sent.get(i)) instanceof ClientViewMessage.SessionReset reset) {
                reasons.add(reset.reason());
            }
        }
        return reasons;
    }

    private List<ClientViewMessageType> types(int from) throws ClientViewProtocolException {
        List<ClientViewMessageType> types = new ArrayList<>();
        for (int i = from; i < sent.size(); i++) {
            types.add(message(sent.get(i)).type());
        }
        return types;
    }

    private static ClientViewMessage message(byte[] payload) throws ClientViewProtocolException {
        return ClientViewCodec.decodeS2C(payload, ClientViewCapability.ALL).message();
    }

    private static ClientViewOptions options(boolean enabled) {
        return new ClientViewOptions(enabled, false, 100, 512 * 1024, 8, true, true, true, true, false, true, true, true, 5);
    }
}
