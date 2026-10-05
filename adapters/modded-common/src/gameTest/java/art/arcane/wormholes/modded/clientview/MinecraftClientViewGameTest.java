package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.MinecraftClientProfiles;
import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.BrickCodec;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewHandshake;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.session.ClientViewInbound;
import art.arcane.wormholes.render.client.session.ClientViewOptions;
import art.arcane.wormholes.render.client.session.ClientViewPlatform;
import art.arcane.wormholes.render.client.session.ClientViewServerSession;
import art.arcane.wormholes.render.client.session.ClientViewSessionRegistry;
import art.arcane.wormholes.render.client.session.ClientViewSessionState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinecraftClientViewGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");
    private static final int STREAM_TICKS = 200;
    private static final int GRACE_MILLIS = 100;
    private static final long STREAM_CAPS = ClientViewCapability.of(ClientViewCapability.PLATES, ClientViewCapability.CONFIG_PHASE);

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final ClientViewSessionRegistry<MinecraftClientViewPeer, BlockState> registry;
    private final MinecraftClientViewNegotiator negotiator;
    private final MinecraftClientViewPortalAccess portals;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<MinecraftPortal> created = new ArrayList<>();
    private final Map<BlockPos, BlockState> physical = new HashMap<>();
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private MinecraftGameTestPlayer player;

    private MinecraftClientViewGameTest(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
        this.portals = new MinecraftClientViewPortalAccess(runtime);
        ClientViewPlatform<MinecraftClientViewPeer, BlockState> platform = new ClientViewPlatform<>(new MinecraftClientViewTransport(), portals,
            null, null, null, Runnable::run, BlockStateParser::serialize, dataVersion(), MinecraftClientViewService.PLATFORM_CAPS,
            System::nanoTime, (message, failure) -> LOGGER.warn(message, failure));
        this.registry = new ClientViewSessionRegistry<>(platform, new ClientViewOptions(true, true, GRACE_MILLIS, 512 * 1024, 0, false,
            false, false, false, false, true, true, true, 5));
        this.negotiator = new MinecraftClientViewNegotiator(registry);
    }

    public static CompletableFuture<Boolean> negotiation(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftClientViewGameTest test = new MinecraftClientViewGameTest(helper, runtime);
        try {
            test.negotiate();
            LOGGER.info("WORMHOLES_GAME_TEST_PASS clientview_negotiation configuration_accept vanilla_brand_no_wait decline_data_version absent_channel play_fallback");
            test.finish(null);
        } catch (Throwable failure) {
            test.finish(failure);
        }
        return test.result;
    }

    public static CompletableFuture<Boolean> stream(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftClientViewGameTest test = new MinecraftClientViewGameTest(helper, runtime);
        try {
            test.startStream();
        } catch (Throwable failure) {
            test.finish(failure);
        }
        return test.result;
    }

    private void negotiate() throws ClientViewProtocolException {
        Connection accepted = mockConnection();
        MinecraftClientProfiles.brand(accepted, "fabric");
        UUID acceptedId = UUID.randomUUID();
        ConfigurationTask task = negotiator.configurationTask(acceptedId, "cv-accept", accepted, () -> true);
        helper.assertTrue(task != null, "Enabled ClientView added no configuration task");
        task.start(packet -> {
        });
        ClientViewMessage.Offer offer = (ClientViewMessage.Offer) single(accepted, ClientViewMessageType.OFFER);
        helper.assertTrue(ClientViewCapability.CONFIG_PHASE.in(offer.serverCaps()), "Configuration OFFER lacks CONFIG_PHASE");
        helper.assertTrue(offer.mcDataVersion() == dataVersion(), "OFFER carried data version " + offer.mcDataVersion());
        helper.assertTrue(!task.tick(), "Modded-brand task finished before the HELLO grace");
        helper.assertTrue(negotiator.receive(accepted, hello(offer, dataVersion(), STREAM_CAPS)) == ClientViewInbound.HELLO_ACCEPTED,
            "HELLO was not accepted");
        ClientViewMessage.Accept accept = (ClientViewMessage.Accept) single(accepted, ClientViewMessageType.ACCEPT);
        helper.assertTrue(accept.caps() == (STREAM_CAPS & MinecraftClientViewService.PLATFORM_CAPS), "ACCEPT caps were " + accept.caps());
        helper.assertTrue(task.tick(), "Configuration task stayed open after ACCEPT");
        helper.assertTrue(negotiator.session(acceptedId).state() == ClientViewSessionState.CLIENT_VIEW, "Session did not reach CLIENT_VIEW");

        Connection vanilla = mockConnection();
        MinecraftClientProfiles.brand(vanilla, "vanilla");
        UUID vanillaId = UUID.randomUUID();
        ConfigurationTask vanillaTask = negotiator.configurationTask(vanillaId, "cv-vanilla", vanilla, () -> true);
        vanillaTask.start(packet -> {
        });
        single(vanilla, ClientViewMessageType.OFFER);
        helper.assertTrue(vanillaTask.tick(), "Vanilla-brand task waited for a HELLO");
        helper.assertTrue(negotiator.session(vanillaId).state() == ClientViewSessionState.VANILLA, "Vanilla brand did not stay vanilla");

        Connection mismatched = mockConnection();
        UUID mismatchedId = UUID.randomUUID();
        ConfigurationTask mismatchedTask = negotiator.configurationTask(mismatchedId, "cv-mismatch", mismatched, () -> true);
        mismatchedTask.start(packet -> {
        });
        ClientViewMessage.Offer mismatchedOffer = (ClientViewMessage.Offer) single(mismatched, ClientViewMessageType.OFFER);
        helper.assertTrue(negotiator.receive(mismatched, hello(mismatchedOffer, dataVersion() + 1, STREAM_CAPS)) == ClientViewInbound.HELLO_DECLINED,
            "Mismatched data version was not declined");
        ClientViewMessage.Decline decline = (ClientViewMessage.Decline) single(mismatched, ClientViewMessageType.DECLINE);
        helper.assertTrue(decline.reason() == ClientViewMessage.DeclineReason.DATA_VERSION_MISMATCH, "DECLINE reason was " + decline.reason());
        helper.assertTrue(mismatchedTask.tick(), "Declined task stayed open");

        Connection absent = mockConnection();
        ConfigurationTask absentTask = negotiator.configurationTask(UUID.randomUUID(), "cv-absent", absent, () -> false);
        absentTask.start(packet -> {
        });
        helper.assertTrue(absentTask.tick() && frames(absent).isEmpty(), "A client without the channel received ClientView frames");

        Connection play = mockConnection();
        UUID playId = UUID.randomUUID();
        helper.assertTrue(negotiator.offerPlay(playId, "cv-play", play), "Play-phase fallback did not offer");
        ClientViewMessage.Offer playOffer = (ClientViewMessage.Offer) single(play, ClientViewMessageType.OFFER);
        helper.assertTrue(!ClientViewCapability.CONFIG_PHASE.in(playOffer.serverCaps()), "Play-phase OFFER claimed CONFIG_PHASE");
        helper.assertTrue(negotiator.session(playId).holdsVanilla(), "Play-phase session did not hold the vanilla projector");
        helper.assertTrue(!negotiator.offerPlay(playId, "cv-play", play), "Play-phase fallback offered twice");
    }

    private void startStream() throws ClientViewProtocolException {
        player = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "cv-stream");
        clear(new BlockPos(0, 2, 0), new BlockPos(8, 7, 12));
        clear(new BlockPos(36, 2, 0), new BlockPos(46, 7, 12));
        MinecraftPortal source = portal(2, 6);
        source.setBlackoutBackground(true);
        MinecraftPortal destination = portal(40, 6);
        helper.assertTrue(runtime.portals().link(player.player(), source.getId(), destination.getId()), "ClientView fixture did not link portals");
        BlockState marker = Blocks.GOLD_BLOCK.defaultBlockState();
        mark(new BlockPos(41, 3, 3), marker);
        mark(new BlockPos(41, 3, 9), marker);
        GeometryVector origin = source.getOrigin();
        player.player().setPos(new Vec3(origin.x(), origin.y() - player.player().getEyeHeight(), origin.z() - 3.0D));
        player.player().setYRot(0.0F);
        player.player().setXRot(0.0F);
        Connection connection = mockConnection();
        UUID id = player.player().getUUID();
        helper.assertTrue(negotiator.offerPlay(id, "cv-stream", connection), "Stream fixture did not offer");
        ClientViewMessage.Offer offer = (ClientViewMessage.Offer) single(connection, ClientViewMessageType.OFFER);
        helper.assertTrue(negotiator.receive(connection, hello(offer, dataVersion(), STREAM_CAPS)) == ClientViewInbound.HELLO_ACCEPTED,
            "Stream HELLO was not accepted");
        single(connection, ClientViewMessageType.ACCEPT);
        ClientViewServerSession<MinecraftClientViewPeer, BlockState> session = negotiator.session(id);
        session.player().attach(player.player(), new MinecraftProjectorPortalAccess(runtime));
        Stream stream = new Stream(session, connection, source, marker);
        helper.runAfterDelay(1, stream::step);
    }

    private MinecraftPortal portal(int x, int z) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int dx = 0; dx < 3; dx++) {
            for (int y = 2; y < 5; y++) {
                cells.add(helper.absolutePos(new BlockPos(x + dx, y, z)));
            }
        }
        MinecraftPortal portal = runtime.portals().create(player.player().getUUID(), helper.getLevel(), cells, PortalType.PORTAL, new Vec3(0, 0, -1));
        created.add(portal);
        portal.setAmbientStyle(AmbientParticleStyle.OFF);
        portal.setNetworkViewDepth(8);
        portal.setNetworkViewLateralPad(8);
        return portal;
    }

    private void clear(BlockPos from, BlockPos to) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = from.getX(); x <= to.getX(); x++) {
            for (int y = from.getY(); y <= to.getY(); y++) {
                for (int z = from.getZ(); z <= to.getZ(); z++) {
                    mark(new BlockPos(x, y, z), air);
                }
            }
        }
    }

    private void mark(BlockPos relative, BlockState state) {
        BlockPos position = helper.absolutePos(relative);
        ServerLevel level = helper.getLevel();
        physical.putIfAbsent(position, level.getBlockState(position));
        level.setBlockAndUpdate(position, state);
    }

    private Connection mockConnection() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        channels.add(new EmbeddedChannel(connection));
        return connection;
    }

    private ClientViewMessage single(Connection connection, ClientViewMessageType type) throws ClientViewProtocolException {
        List<ClientViewMessage> frames = frames(connection);
        helper.assertTrue(frames.size() == 1 && frames.get(0).type() == type, "Expected one " + type + " frame but read " + types(frames));
        return frames.get(0);
    }

    private List<ClientViewMessage> frames(Connection connection) throws ClientViewProtocolException {
        EmbeddedChannel channel = channel(connection);
        channel.runPendingTasks();
        List<ClientViewMessage> frames = new ArrayList<>();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            if (message instanceof ClientboundCustomPayloadPacket packet && packet.payload() instanceof ClientViewPayload payload) {
                frames.add(ClientViewCodec.decodeS2C(payload.data(), STREAM_CAPS & MinecraftClientViewService.PLATFORM_CAPS).message());
            }
        }
        return frames;
    }

    private EmbeddedChannel channel(Connection connection) {
        for (EmbeddedChannel channel : channels) {
            if (channel.pipeline().get(Connection.class) == connection) {
                return channel;
            }
        }
        throw new IllegalStateException("No mock channel for " + connection);
    }

    private void finish(Throwable failure) {
        try {
            registry.shutdown();
            for (EmbeddedChannel channel : channels) {
                channel.finishAndReleaseAll();
            }
            for (Map.Entry<BlockPos, BlockState> entry : physical.entrySet()) {
                helper.getLevel().setBlockAndUpdate(entry.getKey(), entry.getValue());
            }
            if (player != null) {
                for (MinecraftPortal portal : created) {
                    runtime.portals().remove(player.player(), portal.getId());
                }
                player.close();
            }
        } catch (Throwable cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure == null) {
            result.complete(true);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private static byte[] hello(ClientViewMessage.Offer offer, int dataVersion, long caps) throws ClientViewProtocolException {
        return ClientViewCodec.encodeC2S(ClientViewHandshake.clientHello(offer, dataVersion, caps, 512 * 1024, 256, 0L, "fabric"));
    }

    private static int dataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private static List<ClientViewMessageType> types(List<ClientViewMessage> frames) {
        List<ClientViewMessageType> types = new ArrayList<>(frames.size());
        for (ClientViewMessage frame : frames) {
            types.add(frame.type());
        }
        return types;
    }

    private final class Stream {
        private final ClientViewServerSession<MinecraftClientViewPeer, BlockState> session;
        private final Connection connection;
        private final MinecraftPortal source;
        private final String marker;
        private final List<ClientViewMessage> received = new ArrayList<>();
        private final Map<Integer, String> palette = new HashMap<>();
        private long tick;
        private int remaining = STREAM_TICKS;

        private Stream(ClientViewServerSession<MinecraftClientViewPeer, BlockState> session, Connection connection, MinecraftPortal source,
                       BlockState marker) {
            this.session = session;
            this.connection = connection;
            this.source = source;
            this.marker = BlockStateParser.serialize(marker);
        }

        private void step() {
            try {
                portals.frame(runtime.portals().snapshot());
                session.tick(++tick);
                for (ClientViewMessage message : frames(connection)) {
                    received.add(message);
                    if (message instanceof ClientViewMessage.Palette entries) {
                        for (ClientViewMessage.PaletteEntry entry : entries.entries()) {
                            palette.put(entry.id(), entry.state());
                        }
                    }
                }
                if (index(ClientViewMessageType.PLATE_END) < 0) {
                    helper.assertTrue(--remaining > 0, "No complete plate arrived in " + STREAM_TICKS + " ticks; received " + summary());
                    helper.runAfterDelay(1, this::step);
                    return;
                }
                verify();
                LOGGER.info("WORMHOLES_GAME_TEST_PASS clientview_stream frames={} order={}", received.size(), summary());
                finish(null);
            } catch (Throwable failure) {
                finish(failure);
            }
        }

        private void verify() {
            int portal = index(ClientViewMessageType.PORTAL);
            int begin = index(ClientViewMessageType.PLATE_BEGIN);
            int bricks = index(ClientViewMessageType.PLATE_BRICKS);
            int end = index(ClientViewMessageType.PLATE_END);
            int firstPalette = index(ClientViewMessageType.PALETTE);
            helper.assertTrue(firstPalette >= 0 && firstPalette < portal, "PALETTE did not precede PORTAL: " + summary());
            helper.assertTrue(portal < begin && begin < bricks && bricks < end, "Plate frames arrived out of order: " + summary());
            ClientViewMessage.Portal announced = (ClientViewMessage.Portal) received.get(portal);
            GeometryVector origin = source.getOrigin();
            helper.assertTrue(announced.geometry().valid(), "PORTAL geometry is invalid");
            helper.assertTrue(Math.abs(announced.geometry().originZ() - Math.floor(origin.z())) < 1.0D, "PORTAL origin is not the local aperture");
            helper.assertTrue(announced.geometry().blackoutPolicy() == ClientPortalGeometry.BLACKOUT_SHELL, "Plate stream did not announce its configured blackout shell");
            helper.assertTrue(palette.containsKey(announced.geometry().blackoutState()), "Blackout state was not in the palette before PORTAL");
            ClientViewMessage.PlateBricks plate = (ClientViewMessage.PlateBricks) received.get(bricks);
            boolean sawMarker = false;
            for (Brick brick : plate.bricks()) {
                int[] cells = BrickCodec.unpack(brick);
                for (int cell : cells) {
                    helper.assertTrue(cell < 3 || palette.containsKey(cell), "Brick referenced palette id " + cell + " before PALETTE");
                    sawMarker |= marker.equals(palette.get(cell));
                }
            }
            helper.assertTrue(sawMarker, "The destination marker never reached the plate bricks");
            helper.assertTrue(session.stats().framesSent() >= received.size(), "Session counters missed frames");
        }

        private int index(ClientViewMessageType type) {
            for (int i = 0; i < received.size(); i++) {
                if (received.get(i).type() == type) {
                    return i;
                }
            }
            return -1;
        }

        private String summary() {
            return types(received).toString();
        }
    }
}
