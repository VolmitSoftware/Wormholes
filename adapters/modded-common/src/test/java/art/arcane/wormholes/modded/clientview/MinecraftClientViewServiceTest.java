package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.seamless.RemoteRoutes;

import art.arcane.wormholes.modded.MinecraftTestSettings;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.MinecraftProjectionService;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamHandshake;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamMessageType;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamInbound;
import art.arcane.optics.stream.ViewStreamOptions;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MinecraftClientViewServiceTest extends MinecraftTestBase {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");
    private static final long HELLO_CAPS = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE);

    private final List<byte[]> sent = new ArrayList<>();
    private final ServerLevel overworld = mock(ServerLevel.class);
    private final ServerLevel nether = mock(ServerLevel.class);
    private volatile ViewStreamOptions options;
    private Connection connection;
    private EmbeddedChannel channel;
    private MinecraftClientViewService service;
    private long tick;

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        Executor lanes = Runnable::run;
        options = options(true);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(MinecraftTestSettings.defaults());
        when(configuration.clientViewOptions()).thenAnswer(ignored -> options);
        when(runtime.projections()).thenReturn(projections);
        when(runtime.remoteRoutes()).thenReturn(mock(RemoteRoutes.class));
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
    public void nativeGreetingAdvertisesAndNegotiatesLocalMesh() throws ViewStreamProtocolException {
        service.channelRegistered(player(overworld));
        ViewStreamMessage.Offer offer = (ViewStreamMessage.Offer) message(sent.get(sent.size() - 1));
        assertTrue(ViewStreamCapability.LOCAL_MESH.in(offer.serverCaps()));
        assertTrue(ViewStreamCapability.ENTITY_SELF.in(offer.serverCaps()));
        assertTrue((offer.serverCaps() & ClientViewExtensions.PREPARED_TRAVEL) != 0L);
        assertTrue((offer.serverCaps() & ClientViewExtensions.PREPARED_TRAVEL_CACHE) != 0L);

        byte[] hello = MinecraftClientViewExtensions.CODEC.encodeC2S(ViewStreamHandshake.clientHello(offer, offer.mcDataVersion(),
            ViewStreamCapability.ALL, 512 * 1024, 256, 0L, "fabric"));
        assertEquals(ViewStreamInbound.HELLO_ACCEPTED, service.receive(connection, hello));
        ViewStreamMessage.Accept accept = (ViewStreamMessage.Accept) message(sent.get(sent.size() - 1));
        assertTrue(ViewStreamCapability.LOCAL_MESH.in(accept.caps()));
        assertTrue(ViewStreamCapability.ENTITY_SELF.in(accept.caps()));
        assertTrue(ViewStreamCapability.MESH_RENDER.in(accept.caps()));
        assertTrue((accept.caps() & ClientViewExtensions.PREPARED_TRAVEL) != 0L);
        assertTrue((accept.caps() & ClientViewExtensions.PREPARED_TRAVEL_CACHE) != 0L);
    }

    @Test
    public void changingLevelResetsTheSessionForTheNewDimension() throws ViewStreamProtocolException {
        ServerPlayer player = player(overworld);
        negotiate(player);
        int mark = sent.size();

        tick(player);
        tick(player);
        when(player.level()).thenReturn(nether);
        tick(player);
        tick(player);

        assertEquals(List.of(ViewStreamMessage.ResetReason.DIMENSION), resets(mark));
    }

    @Test
    public void respawnResetsForRespawnInTheSameLevelAndForDimensionInAnother() throws ViewStreamProtocolException {
        ServerPlayer first = player(overworld);
        negotiate(first);
        tick(first);
        int mark = sent.size();

        tick(player(overworld));
        tick(player(nether));

        assertEquals(List.of(ViewStreamMessage.ResetReason.RESPAWN, ViewStreamMessage.ResetReason.DIMENSION), resets(mark));
    }

    @Test
    public void switchingBackOnOffersTheConnectedClientAgain() throws ViewStreamProtocolException {
        ServerPlayer player = player(overworld);
        negotiate(player);

        service.runtimeEnabled(false);
        assertEquals(List.of(ViewStreamMessage.ResetReason.DISABLED), resets(0));
        int mark = sent.size();
        service.runtimeEnabled(true);

        assertEquals(List.of(ViewStreamMessageType.OFFER), types(mark));
        accept();
        mark = sent.size();
        service.runtimeEnabled(true);
        tick(player);
        assertEquals(List.of(), types(mark));
    }

    @Test
    public void enablingInTheConfigurationOffersClientsThatJoinedWhileItWasOff() throws ViewStreamProtocolException {
        ServerPlayer player = player(overworld);
        options = options(false);
        tick(player);
        service.channelRegistered(player);
        assertEquals(List.of(), types(0));

        options = options(true);
        tick(player);

        assertEquals(List.of(ViewStreamMessageType.OFFER), types(0));
        accept();
    }

    private void negotiate(ServerPlayer player) throws ViewStreamProtocolException {
        int mark = sent.size();
        service.channelRegistered(player);
        assertEquals(List.of(ViewStreamMessageType.OFFER), types(mark));
        accept();
    }

    private void accept() throws ViewStreamProtocolException {
        ViewStreamMessage.Offer offer = (ViewStreamMessage.Offer) message(sent.get(sent.size() - 1));
        byte[] hello = MinecraftClientViewExtensions.CODEC.encodeC2S(ViewStreamHandshake.clientHello(offer, offer.mcDataVersion(), HELLO_CAPS, 512 * 1024, 256, 0L,
            "fabric"));
        assertEquals(ViewStreamInbound.HELLO_ACCEPTED, service.receive(connection, hello));
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

    private List<ViewStreamMessage.ResetReason> resets(int from) throws ViewStreamProtocolException {
        List<ViewStreamMessage.ResetReason> reasons = new ArrayList<>();
        for (int i = from; i < sent.size(); i++) {
            if (message(sent.get(i)) instanceof ViewStreamMessage.SessionReset reset) {
                reasons.add(reset.reason());
            }
        }
        return reasons;
    }

    private List<ViewStreamMessageType> types(int from) throws ViewStreamProtocolException {
        List<ViewStreamMessageType> types = new ArrayList<>();
        for (int i = from; i < sent.size(); i++) {
            types.add(message(sent.get(i)) instanceof ViewStreamMessage.Projection projection ? projection.type() : null);
        }
        return types;
    }

    private static ViewStreamMessage message(byte[] payload) throws ViewStreamProtocolException {
        return MinecraftClientViewExtensions.CODEC.decodeS2C(payload, ViewStreamCapability.ALL).message();
    }

    private static ViewStreamOptions options(boolean enabled) {
        return new ViewStreamOptions(enabled, false, 100, 512 * 1024, 8, true, true, true, true, false, true, true, true, 5, ViewStreamCapability.NONE);
    }
}
