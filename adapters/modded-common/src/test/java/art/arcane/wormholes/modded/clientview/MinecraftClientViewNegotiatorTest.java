package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewHandshake;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.ClientViewTransport;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.session.ClientViewInbound;
import art.arcane.wormholes.render.client.session.ClientViewOptions;
import art.arcane.wormholes.render.client.session.ClientViewPlatform;
import art.arcane.wormholes.render.client.session.ClientViewPortalAccess;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import art.arcane.wormholes.render.client.session.ClientViewSessionRegistry;
import art.arcane.wormholes.render.client.session.ClientViewSessionState;
import art.arcane.wormholes.render.plate.ViewPlate;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MinecraftClientViewNegotiatorTest extends MinecraftTestBase {
    private static final int DATA_VERSION = 4711;
    private static final long GRACE_NANOS = 100_000_000L;

    private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
    private final UUID id = UUID.nameUUIDFromBytes("negotiator".getBytes());
    private Recording transport;
    private ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> registry;
    private MinecraftClientViewNegotiator negotiator;

    @Before
    public void setUp() {
        open(options(true));
    }

    @Test
    public void configurationTaskOffersAndCompletesOnHello() throws ClientViewProtocolException {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        ConfigurationTask task = negotiator.configurationTask(id, "Alex", connection, () -> true);
        assertNotNull(task);
        assertEquals(MinecraftClientViewNegotiator.TASK_TYPE, task.type());
        task.start(packet -> {
            throw new AssertionError("the task writes through the ClientView transport");
        });
        ClientViewMessage.Offer offer = (ClientViewMessage.Offer) transport.message(0);
        assertEquals(DATA_VERSION, offer.mcDataVersion());
        assertEquals(0L, offer.zeroCopyNonce());
        assertTrue(ClientViewCapability.CONFIG_PHASE.in(offer.serverCaps()));
        assertTrue(ClientViewCapability.PLATES.in(offer.serverCaps()));
        assertTrue(ClientViewCapability.DEST_LIGHT.in(offer.serverCaps()));
        assertTrue(ClientViewCapability.ENTITY_FRAMES.in(offer.serverCaps()));
        assertTrue(ClientViewCapability.FX_EMITTERS.in(offer.serverCaps()));
        assertTrue(ClientViewCapability.ATMOSPHERE.in(offer.serverCaps()));
        assertFalse(task.tick());
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, negotiator.receive(connection, hello(offer)));
        ClientViewMessage.Accept accept = (ClientViewMessage.Accept) transport.message(1);
        assertEquals(0L, accept.caps() & ~MinecraftClientViewService.PLATFORM_CAPS);
        assertFalse(ClientViewCapability.ZERO_COPY.in(accept.caps()));
        assertTrue(task.tick());
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = negotiator.session(id);
        assertEquals(ClientViewSessionState.CLIENT_VIEW, session.state());
        assertSame(connection, session.player().connection());
        assertEquals("Alex", session.player().name());
        assertTrue(session.player().offered());
    }

    @Test
    public void clientsWithoutTheChannelNeverReceiveAnOffer() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        ConfigurationTask task = negotiator.configurationTask(id, "Alex", connection, () -> false);
        task.start(packet -> {
        });
        assertTrue(task.tick());
        assertEquals(0, transport.sent.size());
        assertNull(negotiator.session(id));
        assertEquals(0, negotiator.peers());
    }

    @Test
    public void disabledOrPlayOnlyServersAddNoConfigurationTask() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        registry.runtimeEnabled(false);
        assertNull(negotiator.configurationTask(id, "Alex", connection, () -> true));
        assertFalse(negotiator.offerPlay(id, "Alex", connection));
        open(new ClientViewOptions(true, false, 100, 512 * 1024, 8, true, true, true, true, false, true, true, true, 5));
        assertNull(negotiator.configurationTask(id, "Alex", connection, () -> true));
        assertTrue(negotiator.offerPlay(id, "Alex", connection));
        ClientViewMessage.Offer offer = (ClientViewMessage.Offer) transport.message(0);
        assertFalse(ClientViewCapability.CONFIG_PHASE.in(offer.serverCaps()));
    }

    @Test
    public void silentClientsFallBackToVanillaAndLateHelloSwitches() throws ClientViewProtocolException {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        ConfigurationTask task = negotiator.configurationTask(id, "Alex", connection, () -> true);
        task.start(packet -> {
        });
        assertFalse(task.tick());
        clock.addAndGet(GRACE_NANOS + 1_000_000L);
        assertTrue(task.tick());
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = negotiator.session(id);
        assertEquals(ClientViewSessionState.VANILLA, session.state());
        assertFalse(session.holdsVanilla());
        ClientViewMessage.Offer offer = (ClientViewMessage.Offer) transport.message(0);
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, negotiator.receive(connection, hello(offer)));
        assertEquals(ClientViewSessionState.CLIENT_VIEW, session.state());
        assertEquals(1L, session.stats().lateSwitches());
    }

    @Test
    public void playFallbackOffersOnceAndIgnoresStrangers() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        assertTrue(negotiator.offerPlay(id, "Alex", connection));
        assertTrue(negotiator.session(id).holdsVanilla());
        assertFalse(negotiator.offerPlay(id, "Alex", connection));
        assertEquals(1, transport.sent.size());
        assertEquals(ClientViewInbound.IGNORED, negotiator.receive(new Connection(PacketFlow.SERVERBOUND), new byte[] {1}));
    }

    @Test
    public void secondLoginNeverReplacesALiveSession() {
        Connection first = new Connection(PacketFlow.SERVERBOUND);
        Connection second = new Connection(PacketFlow.SERVERBOUND);
        assertTrue(negotiator.offerPlay(id, "Alex", first));
        ConfigurationTask task = negotiator.configurationTask(id, "Alex", second, () -> true);
        task.start(packet -> {
        });
        assertTrue(task.tick());
        assertSame(first, negotiator.session(id).player().connection());
        assertEquals(1, transport.sent.size());
    }

    @Test
    public void reofferReachesClientsSeenWhileOffAndSessionsTheKillSwitchEnded() throws ClientViewProtocolException {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        registry.runtimeEnabled(false);
        assertFalse(negotiator.offerPlay(id, "Alex", connection));
        assertEquals(0, negotiator.reoffer());
        assertEquals(0, transport.sent.size());
        assertNull(negotiator.session(id));

        registry.runtimeEnabled(true);
        assertEquals(1, negotiator.reoffer());
        ClientViewMessage.Offer offer = (ClientViewMessage.Offer) transport.message(transport.sent.size() - 1);
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, negotiator.receive(connection, hello(offer)));
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> first = negotiator.session(id);
        assertEquals(ClientViewSessionState.CLIENT_VIEW, first.state());
        assertEquals(0, negotiator.reoffer());

        registry.runtimeEnabled(false);
        assertEquals(ClientViewSessionState.VANILLA, first.state());
        registry.runtimeEnabled(true);
        assertEquals(1, negotiator.reoffer());
        ClientViewMessage.Offer again = (ClientViewMessage.Offer) transport.message(transport.sent.size() - 1);
        assertEquals(ClientViewInbound.HELLO_ACCEPTED, negotiator.receive(connection, hello(again)));
        assertEquals(ClientViewSessionState.CLIENT_VIEW, negotiator.session(id).state());
        assertSame(connection, negotiator.session(id).player().connection());
    }

    @Test
    public void closedConnectionsArePrunedAndDisconnectsForgetTheirOwnSession() {
        Connection closed = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(closed);
        assertTrue(negotiator.offerPlay(id, "Alex", closed));
        assertEquals(0, negotiator.prune());
        channel.close();
        assertEquals(1, negotiator.prune());
        assertNull(negotiator.session(id));
        Connection live = new Connection(PacketFlow.SERVERBOUND);
        assertTrue(negotiator.offerPlay(id, "Alex", live));
        negotiator.disconnected(id, new Connection(PacketFlow.SERVERBOUND));
        assertNotNull(negotiator.session(id));
        negotiator.disconnected(id, live);
        assertNull(negotiator.session(id));
        assertEquals(0, negotiator.peers());
        channel.finishAndReleaseAll();
    }

    private byte[] hello(ClientViewMessage.Offer offer) throws ClientViewProtocolException {
        clock.addAndGet(60_000_000L);
        return ClientViewCodec.encodeC2S(ClientViewHandshake.clientHello(offer, DATA_VERSION, ClientViewCapability.ALL, 512 * 1024, 256, 0L, "fabric"));
    }

    private void open(ClientViewOptions options) {
        transport = new Recording();
        ClientViewPlatform<MinecraftClientViewPeer, BlockState> platform = new ClientViewPlatform<>(transport, new EmptyPortals(), null, null, null,
            Runnable::run, BlockStateParser::serialize, DATA_VERSION, MinecraftClientViewService.PLATFORM_CAPS, clock::get, null);
        registry = new ClientViewSessionRegistry<>(platform, options);
        negotiator = new MinecraftClientViewNegotiator(registry);
    }

    private static ClientViewOptions options(boolean enabled) {
        return new ClientViewOptions(enabled, true, (int) (GRACE_NANOS / 1_000_000L), 512 * 1024, 8, true, true, true, true, false, true, true,
            true, 5);
    }

    private static final class Recording implements ClientViewTransport<MinecraftClientViewPeer> {
        private final List<byte[]> sent = new ArrayList<>();

        @Override
        public void send(MinecraftClientViewPeer player, byte[] payload) {
            sent.add(payload);
        }

        @Override
        public void flush(MinecraftClientViewPeer player) {
        }

        private ClientViewMessage message(int index) {
            try {
                return ClientViewCodec.decodeS2C(sent.get(index), ClientViewCapability.ALL).message();
            } catch (ClientViewProtocolException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class EmptyPortals implements ClientViewPortalAccess<MinecraftClientViewPeer, BlockState> {
        @Override
        public void interested(MinecraftClientViewPeer observer, List<UUID> out) {
        }

        @Override
        public long geometryRevision(MinecraftClientViewPeer observer, UUID portal) {
            return 0L;
        }

        @Override
        public ClientPortalGeometry geometry(MinecraftClientViewPeer observer, UUID portal, SessionPalette palette) {
            return null;
        }

        @Override
        public ViewPlate<BlockState> plate(MinecraftClientViewPeer observer, UUID portal, boolean firstAttendance) {
            return null;
        }

        @Override
        public boolean refused(MinecraftClientViewPeer observer, UUID portal) {
            return true;
        }

        @Override
        public ViewPlate<BlockState> standbyPlate(MinecraftClientViewPeer observer, UUID portal) {
            return null;
        }

        @Override
        public BrickLightSource lightBaseline(MinecraftClientViewPeer observer, UUID portal, ViewPlate<BlockState> plate) {
            return BrickLightSource.NONE;
        }

        @Override
        public void releaseVanilla(MinecraftClientViewPeer observer, UUID portal) {
        }

        @Override
        public void nested(MinecraftClientViewPeer observer, UUID parent, ClientPortalGeometry parentGeometry, List<UUID> out) {
        }

        @Override
        public long nestedGeometryRevision(MinecraftClientViewPeer observer, UUID parent, UUID child) {
            return 0L;
        }

        @Override
        public ClientPortalGeometry nestedGeometry(MinecraftClientViewPeer observer, UUID parent, UUID child, SessionPalette palette) {
            return null;
        }

        @Override
        public ViewPlate<BlockState> nestedPlate(MinecraftClientViewPeer observer, UUID parent, UUID child) {
            return null;
        }

        @Override
        public void effects(MinecraftClientViewPeer observer, List<UUID> out) {
        }

        @Override
        public long effectGeometryRevision(MinecraftClientViewPeer observer, UUID portal) {
            return 0L;
        }

        @Override
        public ClientPortalGeometry effectGeometry(MinecraftClientViewPeer observer, UUID portal, SessionPalette palette) {
            return null;
        }
    }
}
