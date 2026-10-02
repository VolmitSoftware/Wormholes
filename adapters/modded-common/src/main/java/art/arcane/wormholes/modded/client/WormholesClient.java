package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;

import art.arcane.wormholes.modded.mixin.client.DebugScreenEntriesAccessor;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class WormholesClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final String CONNECTION_MESSAGE_KEY = "wormholes.clientview.connected";
    private static final String CONNECTION_MESSAGE = "Wormholes Connection Established";
    private static volatile WormholesClient instance;

    private final WormholesClientConfig config;
    private final ClientViewStats stats;
    private final ClientMeshViews meshViews = new ClientMeshViews();
    private final ClientReflectionEntity reflections;
    private final ClientViewAnnouncer announcer;
    private final Consumer<byte[]> sender;
    private final int dataVersion;
    private final String brandTag;
    private volatile ClientViewSession session;
    private volatile ClientViewReceiver receiver;
    private ClientViewTick tick;
    private Object attachedLevel;
    private ClientLevelSurface attachedSurface;
    private boolean debugRegistered;
    private boolean debugEnabled;

    private WormholesClient(WormholesClientConfig config, Consumer<byte[]> sender) {
        this.config = Objects.requireNonNull(config, "config");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.stats = new ClientViewStats();
        this.reflections = new ClientReflectionEntity();
        this.announcer = new ClientViewAnnouncer();
        this.dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        this.brandTag = ClientBrandRetriever.getClientModName();
        freshSession();
    }

    public static WormholesClient initialize(Path configDirectory, Consumer<byte[]> sender) {
        WormholesClient client = new WormholesClient(WormholesClientConfig.load(configDirectory), sender);
        instance = client;
        LOGGER.info("Wormholes ClientView client ready (renderer={}, plate budget {} MiB, bulk writes {})",
            client.config.renderer, client.config.maxPlateMemoryMb, client.config.bulkWrite);
        return client;
    }

    public static WormholesClient instance() {
        return instance;
    }

    public static void chunkReplaced(LevelChunk chunk) {
        WormholesClient client = instance;
        if (client != null) {
            client.reapplyChunk(chunk);
        }
    }

    public static void reconfiguring() {
        WormholesClient client = instance;
        if (client != null) {
            client.disconnected();
        }
    }

    public static void chunkBlockEntitiesReplaced(LevelChunk chunk) {
        WormholesClient client = instance;
        if (client != null) {
            client.rebuildBlockEntities(chunk);
        }
    }

    public static void blockChanged(Object level, BlockPos position) {
        WormholesClient client = instance;
        if (client != null) {
            client.tick.blockChanged(level, position.getX(), position.getY(), position.getZ());
        }
    }

    public void receive(byte[] payload, Consumer<byte[]> reply) {
        receiver.receive(payload, reply == null ? sender : reply);
    }

    public void connected() {
        if (session.state() == ClientViewSession.State.VANILLA || session.state() == ClientViewSession.State.DECLINED) {
            return;
        }
        if (attachedLevel != null) {
            detach();
        }
    }

    public void disconnected() {
        reflections.clear(null, null);
        detach();
        freshSession();
    }

    public void tick(Minecraft minecraft) {
        tick.effectsActive(!minecraft.isPaused() && minecraft.isWindowActive());
        ClientLevel level = minecraft.level;
        if (level == null) {
            reflections.clear(null, null);
            if (attachedLevel != null) {
                detach();
            }
            tick.tick(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, System.currentTimeMillis());
            return;
        }
        if (attachedLevel != level) {
            ClientLevelSurface surface = new ClientLevelSurface(level, config.bulkWrite);
            tick.attach(level, surface, new ClientLevelScene(level, minecraft::getConnection));
            attachedLevel = level;
            attachedSurface = surface;
        }
        registerDebugEntry(minecraft);
        LocalPlayer player = minecraft.player;
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 eye = camera.isInitialized() ? camera.position() : player == null ? Vec3.ZERO : player.getEyePosition();
        Vec3 velocity = player == null ? Vec3.ZERO : player.getDeltaMovement();
        tick.tick(eye.x, eye.y, eye.z, velocity.x, velocity.y, velocity.z, System.currentTimeMillis());
        meshViews.update(session, level);
        reflections.tick(level, player, minecraft.getConnection(), session, tick,
            config.selfReflection && session.active() && session.has(ClientViewCapability.CLIENT_MIRROR));
        if (announcer.due(config.connectionMessage, session.active(), session.acceptMessage(), player != null)) {
            player.sendSystemMessage(Component.translatableWithFallback(CONNECTION_MESSAGE_KEY, CONNECTION_MESSAGE)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    public String debugLine() {
        ClientViewSession current = session;
        ClientViewTick state = tick;
        ProjectionOverlay overlay = state == null ? null : state.overlay();
        return "Wormholes ClientView: " + current.state()
            + " portals=" + current.portals().size() + " memoryUnavailable=" + current.unavailableMeshes()
            + " overlay=" + (overlay == null ? 0 : overlay.size())
            + " plates=" + current.memoryMb() + "MiB"
            + " sweep=" + stats.sweepMicrosP50() + "us apply=" + stats.applyMicrosP50() + "us"
            + " frames=" + stats.framesReceived() + " bytes=" + stats.bytesReceived()
            + " unknown=" + current.palette().unknownStates()
            + " entities=" + (state == null || state.entities() == null ? 0 : state.entities().spawned())
            + " fx=" + (state == null || state.fx() == null ? 0 : state.fx().emitters())
            + " " + ClientPortalRenderer.instance().debugLine();
    }

    public WormholesClientConfig config() {
        return config;
    }

    public ClientViewSession session() {
        return session;
    }

    public ClientViewReceiver receiver() {
        return receiver;
    }

    public ClientViewTick tickState() {
        return tick;
    }

    public ClientViewStats stats() {
        return stats;
    }

    public ClientMeshViews meshViews() {
        return meshViews;
    }

    public ClientReflectionEntity reflections() {
        return reflections;
    }

    private void detach() {
        meshViews.clear();
        tick.detach();
        attachedLevel = null;
        attachedSurface = null;
    }

    private void freshSession() {
        ClientViewSession next = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), dataVersion, brandTag);
        ClientViewReceiver nextReceiver = new ClientViewReceiver(next);
        ClientViewTick nextTick = new ClientViewTick(next, nextReceiver, config, stats);
        nextTick.sender(this::send);
        session = next;
        receiver = nextReceiver;
        tick = nextTick;
        stats.reset();
    }

    private void send(ClientViewMessage message) {
        try {
            sender.accept(ClientViewCodec.encodeC2S(message));
        } catch (ClientViewProtocolException failure) {
            LOGGER.warn("Wormholes ClientView could not encode {}", message.type(), failure);
        }
    }

    private void reapplyChunk(LevelChunk chunk) {
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        ProjectionOverlay overlay = ProjectionOverlay.forLevel(chunk.getLevel());
        if (overlay != null) {
            overlay.reapply(chunkX, chunkZ, new ChunkSectionWriter(chunk, attachedSurface));
        }
        if (chunk.getLevel() == attachedLevel) {
            tick.chunkReloaded(chunkX, chunkZ);
        }
    }

    private void rebuildBlockEntities(LevelChunk chunk) {
        ProjectionOverlay overlay = ProjectionOverlay.forLevel(chunk.getLevel());
        if (overlay != null) {
            overlay.reapplyBlockEntities(chunk.getPos().x(), chunk.getPos().z(), new ChunkSectionWriter(chunk, attachedSurface));
        }
    }

    private void registerDebugEntry(Minecraft minecraft) {
        if (debugRegistered) {
            return;
        }
        debugRegistered = true;
        try {
            DebugScreenEntriesAccessor.wormholesRegister(ClientViewDebugEntry.ID, new ClientViewDebugEntry(this::debugLine));
            if (config.showDebugOverlay && !debugEnabled) {
                debugEnabled = true;
                minecraft.debugEntries.setStatus(ClientViewDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Wormholes ClientView debug entry unavailable", failure);
        }
    }

    private static final class ChunkSectionWriter implements ProjectionOverlay.ChunkSections {
        private final LevelChunk chunk;
        private final ClientLevelSurface surface;

        private ChunkSectionWriter(LevelChunk chunk, ClientLevelSurface surface) {
            this.chunk = chunk;
            this.surface = surface;
        }

        @Override
        public BlockState state(int x, int y, int z) {
            int sectionIndex = chunk.getSectionIndex(y);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
                return null;
            }
            return chunk.getSection(sectionIndex).getBlockState(x & 15, y & 15, z & 15);
        }

        @Override
        public void write(int x, int y, int z, BlockState state) {
            int sectionIndex = chunk.getSectionIndex(y);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
                return;
            }
            LevelChunkSection section = chunk.getSection(sectionIndex);
            section.setBlockState(x & 15, y & 15, z & 15, state, false);
            for (Map.Entry<Heightmap.Types, Heightmap> heightmap : chunk.getHeightmaps()) {
                heightmap.getValue().update(x & 15, y, z & 15, state);
            }
        }

        @Override
        public void blockEntity(int x, int y, int z, BlockEntitySample sample) {
            if (surface != null) {
                surface.rebuildBlockEntity(chunk, x, y, z, sample);
            }
        }
    }
}
