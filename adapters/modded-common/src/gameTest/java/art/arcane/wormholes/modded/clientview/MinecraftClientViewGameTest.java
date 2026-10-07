package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftClientProfiles;
import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProjectorPortalAccess;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamHandshake;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamMessageType;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.stream.ViewStreamInbound;
import art.arcane.optics.stream.ViewStreamOptions;
import art.arcane.optics.stream.ViewStreamPlatform;
import art.arcane.optics.stream.ViewStreamSession;
import art.arcane.optics.stream.ViewStreamSessionRegistry;
import art.arcane.optics.stream.ViewStreamSessionState;
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
import art.arcane.wormholes.render.client.session.ClientViewTravel;

public final class MinecraftClientViewGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");
    private static final int STREAM_TICKS = 200;
    private static final int GRACE_MILLIS = 100;
    private static final long STREAM_CAPS = ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.CONFIG_PHASE);

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final ViewStreamSessionRegistry<MinecraftClientViewPeer, BlockState> registry;
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
        ViewStreamPlatform<MinecraftClientViewPeer, BlockState> platform = new ViewStreamPlatform<>(new MinecraftClientViewTransport(), portals,
            null, null, null, Runnable::run, BlockStateParser::serialize, dataVersion(), MinecraftClientViewService.PLATFORM_CAPS,
            System::nanoTime, (message, failure) -> LOGGER.warn(message, failure), MinecraftClientViewExtensions.ALL, ClientViewTravel::new);
        this.registry = new ViewStreamSessionRegistry<>(platform, new ViewStreamOptions(true, true, GRACE_MILLIS, 512 * 1024, 0, false,
            false, false, false, false, true, true, true, 5, ViewStreamCapability.NONE));
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

    private void negotiate() throws ViewStreamProtocolException {
        Connection accepted = mockConnection();
        MinecraftClientProfiles.brand(accepted, "fabric");
        UUID acceptedId = UUID.randomUUID();
        ConfigurationTask task = negotiator.configurationTask(acceptedId, "cv-accept", accepted, () -> true);
        helper.assertTrue(task != null, "Enabled ClientView added no configuration task");
        task.start(packet -> {
        });
        ViewStreamMessage.Offer offer = (ViewStreamMessage.Offer) single(accepted, ViewStreamMessageType.OFFER);
        helper.assertTrue(ViewStreamCapability.CONFIG_PHASE.in(offer.serverCaps()), "Configuration OFFER lacks CONFIG_PHASE");
        helper.assertTrue(offer.mcDataVersion() == dataVersion(), "OFFER carried data version " + offer.mcDataVersion());
        helper.assertTrue(!task.tick(), "Modded-brand task finished before the HELLO grace");
        helper.assertTrue(negotiator.receive(accepted, hello(offer, dataVersion(), STREAM_CAPS)) == ViewStreamInbound.HELLO_ACCEPTED,
            "HELLO was not accepted");
        ViewStreamMessage.Accept accept = (ViewStreamMessage.Accept) single(accepted, ViewStreamMessageType.ACCEPT);
        helper.assertTrue(accept.caps() == (STREAM_CAPS & MinecraftClientViewService.PLATFORM_CAPS), "ACCEPT caps were " + accept.caps());
        helper.assertTrue(task.tick(), "Configuration task stayed open after ACCEPT");
        helper.assertTrue(negotiator.session(acceptedId).state() == ViewStreamSessionState.CLIENT_VIEW, "Session did not reach CLIENT_VIEW");

        Connection vanilla = mockConnection();
        MinecraftClientProfiles.brand(vanilla, "vanilla");
        UUID vanillaId = UUID.randomUUID();
        ConfigurationTask vanillaTask = negotiator.configurationTask(vanillaId, "cv-vanilla", vanilla, () -> true);
        vanillaTask.start(packet -> {
        });
        single(vanilla, ViewStreamMessageType.OFFER);
        helper.assertTrue(vanillaTask.tick(), "Vanilla-brand task waited for a HELLO");
        helper.assertTrue(negotiator.session(vanillaId).state() == ViewStreamSessionState.VANILLA, "Vanilla brand did not stay vanilla");

        Connection mismatched = mockConnection();
        UUID mismatchedId = UUID.randomUUID();
        ConfigurationTask mismatchedTask = negotiator.configurationTask(mismatchedId, "cv-mismatch", mismatched, () -> true);
        mismatchedTask.start(packet -> {
        });
        ViewStreamMessage.Offer mismatchedOffer = (ViewStreamMessage.Offer) single(mismatched, ViewStreamMessageType.OFFER);
        helper.assertTrue(negotiator.receive(mismatched, hello(mismatchedOffer, dataVersion() + 1, STREAM_CAPS)) == ViewStreamInbound.HELLO_DECLINED,
            "Mismatched data version was not declined");
        ViewStreamMessage.Decline decline = (ViewStreamMessage.Decline) single(mismatched, ViewStreamMessageType.DECLINE);
        helper.assertTrue(decline.reason() == ViewStreamMessage.DeclineReason.DATA_VERSION_MISMATCH, "DECLINE reason was " + decline.reason());
        helper.assertTrue(mismatchedTask.tick(), "Declined task stayed open");

        Connection absent = mockConnection();
        ConfigurationTask absentTask = negotiator.configurationTask(UUID.randomUUID(), "cv-absent", absent, () -> false);
        absentTask.start(packet -> {
        });
        helper.assertTrue(absentTask.tick() && frames(absent).isEmpty(), "A client without the channel received ClientView frames");

        Connection play = mockConnection();
        UUID playId = UUID.randomUUID();
        helper.assertTrue(negotiator.offerPlay(playId, "cv-play", play), "Play-phase fallback did not offer");
        ViewStreamMessage.Offer playOffer = (ViewStreamMessage.Offer) single(play, ViewStreamMessageType.OFFER);
        helper.assertTrue(!ViewStreamCapability.CONFIG_PHASE.in(playOffer.serverCaps()), "Play-phase OFFER claimed CONFIG_PHASE");
        helper.assertTrue(negotiator.session(playId).holdsVanilla(), "Play-phase session did not hold the vanilla projector");
        helper.assertTrue(!negotiator.offerPlay(playId, "cv-play", play), "Play-phase fallback offered twice");
    }

    private void startStream() throws ViewStreamProtocolException {
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
        Vec3d origin = source.getOrigin();
        player.player().setPos(new Vec3(origin.x(), origin.y() - player.player().getEyeHeight(), origin.z() - 3.0D));
        player.player().setYRot(0.0F);
        player.player().setXRot(0.0F);
        Connection connection = mockConnection();
        UUID id = player.player().getUUID();
        helper.assertTrue(negotiator.offerPlay(id, "cv-stream", connection), "Stream fixture did not offer");
        ViewStreamMessage.Offer offer = (ViewStreamMessage.Offer) single(connection, ViewStreamMessageType.OFFER);
        helper.assertTrue(negotiator.receive(connection, hello(offer, dataVersion(), STREAM_CAPS)) == ViewStreamInbound.HELLO_ACCEPTED,
            "Stream HELLO was not accepted");
        single(connection, ViewStreamMessageType.ACCEPT);
        ViewStreamSession<MinecraftClientViewPeer, BlockState> session = negotiator.session(id);
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

    private ViewStreamMessage single(Connection connection, ViewStreamMessageType type) throws ViewStreamProtocolException {
        List<ViewStreamMessage> frames = frames(connection);
        helper.assertTrue(frames.size() == 1 && frames.get(0).id() == type.id(), "Expected one " + type + " frame but read " + types(frames));
        return frames.get(0);
    }

    private List<ViewStreamMessage> frames(Connection connection) throws ViewStreamProtocolException {
        EmbeddedChannel channel = channel(connection);
        channel.runPendingTasks();
        List<ViewStreamMessage> frames = new ArrayList<>();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            if (message instanceof ClientboundCustomPayloadPacket packet && packet.payload() instanceof ClientViewPayload payload) {
                frames.add(MinecraftClientViewExtensions.CODEC.decodeS2C(payload.data(), STREAM_CAPS & MinecraftClientViewService.PLATFORM_CAPS).message());
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

    private static byte[] hello(ViewStreamMessage.Offer offer, int dataVersion, long caps) throws ViewStreamProtocolException {
        return MinecraftClientViewExtensions.CODEC.encodeC2S(ViewStreamHandshake.clientHello(offer, dataVersion, caps, 512 * 1024, 256, 0L, "fabric"));
    }

    private static int dataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private static List<ViewStreamMessageType> types(List<ViewStreamMessage> frames) {
        List<ViewStreamMessageType> types = new ArrayList<>(frames.size());
        for (ViewStreamMessage frame : frames) {
            types.add(frame instanceof ViewStreamMessage.Projection projection ? projection.type() : null);
        }
        return types;
    }

    private final class Stream {
        private final ViewStreamSession<MinecraftClientViewPeer, BlockState> session;
        private final Connection connection;
        private final MinecraftPortal source;
        private final String marker;
        private final List<ViewStreamMessage> received = new ArrayList<>();
        private final Map<Integer, String> palette = new HashMap<>();
        private long tick;
        private int remaining = STREAM_TICKS;

        private Stream(ViewStreamSession<MinecraftClientViewPeer, BlockState> session, Connection connection, MinecraftPortal source,
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
                for (ViewStreamMessage message : frames(connection)) {
                    received.add(message);
                    if (message instanceof ViewStreamMessage.Palette entries) {
                        for (ViewStreamMessage.PaletteEntry entry : entries.entries()) {
                            palette.put(entry.id(), entry.state());
                        }
                    }
                }
                if (index(ViewStreamMessageType.PLATE_END) < 0) {
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
            int portal = index(ViewStreamMessageType.PORTAL);
            int begin = index(ViewStreamMessageType.PLATE_BEGIN);
            int bricks = index(ViewStreamMessageType.PLATE_BRICKS);
            int end = index(ViewStreamMessageType.PLATE_END);
            int firstPalette = index(ViewStreamMessageType.PALETTE);
            helper.assertTrue(firstPalette >= 0 && firstPalette < portal, "PALETTE did not precede PORTAL: " + summary());
            helper.assertTrue(portal < begin && begin < bricks && bricks < end, "Plate frames arrived out of order: " + summary());
            ViewStreamMessage.Portal announced = (ViewStreamMessage.Portal) received.get(portal);
            Vec3d origin = source.getOrigin();
            helper.assertTrue(announced.geometry().valid(), "PORTAL geometry is invalid");
            helper.assertTrue(Math.abs(announced.geometry().originZ() - Math.floor(origin.z())) < 1.0D, "PORTAL origin is not the local aperture");
            helper.assertTrue(announced.geometry().blackoutPolicy() == ApertureDescriptor.BLACKOUT_SHELL, "Plate stream did not announce its configured blackout shell");
            helper.assertTrue(palette.containsKey(announced.geometry().blackoutState()), "Blackout state was not in the palette before PORTAL");
            ViewStreamMessage.PlateBricks plate = (ViewStreamMessage.PlateBricks) received.get(bricks);
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

        private int index(ViewStreamMessageType type) {
            for (int i = 0; i < received.size(); i++) {
                if (received.get(i).id() == type.id()) {
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
