package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;

import art.arcane.wormholes.modded.mixin.client.DebugScreenEntriesAccessor;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.fidelity.BlockEntitySample;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import art.arcane.wormholes.modded.clientview.MinecraftClientViewExtensions;
import art.arcane.wormholes.network.client.TravelExtension;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.optics.aperture.ApertureDescriptor;

public final class WormholesClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static volatile WormholesClient instance;

    private final WormholesClientConfig config;
    private final ClientViewStats stats;
    private final ClientPreparedTravel preparedTravel;
    private final ClientLocalMeshSources localMeshes = new ClientLocalMeshSources(this::send);
    private final ClientMeshViews meshViews = new ClientMeshViews();
    private final ClientReflectionEntity reflections;
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
        Consumer<TravelMessage> travel = message -> send(TravelExtension.INSTANCE.wrap(message));
        this.preparedTravel = new ClientPreparedTravel(travel, new ResidentLevels(travel, config.residentLevelMemoryBytes()));
        this.reflections = new ClientReflectionEntity();
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

    public static boolean activeLevel(ClientLevel level) {
        WormholesClient client = instance;
        return (client == null ? Minecraft.getInstance().level : client.preparedTravel.residents().activeLevel()) == level;
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
            client.preparedTravel.blockChanged(level, position);
            client.tick.blockChanged(level, position.getX(), position.getY(), position.getZ());
            client.localMeshes.blockChanged(level, position);
        }
    }

    public static void localChunkChanged(ClientLevel level, int x, int z) {
        if (ClientPreparedTravel.applyingColumn(level, x, z)) {
            return;
        }
        WormholesClient client = instance;
        if (client != null) {
            client.preparedTravel.chunkChanged(level, x, z);
            client.localMeshes.chunkChanged(level, x, z);
        }
    }

    public static void localChunkUnloaded(ClientLevel level, int x, int z) {
        ClientSodiumTerrain.columnUnloaded(level, x, z);
        localChunkChanged(level, x, z);
    }

    public static void localSectionChanged(ClientLevel level, int x, int y, int z) {
        if (ClientPreparedTravel.applyingColumn(level, x, z)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> localSectionChanged(level, x, y, z));
            return;
        }
        WormholesClient client = instance;
        if (client != null) {
            client.preparedTravel.sectionChanged(level, x, y, z);
            client.localMeshes.blockChanged(level, new BlockPos(x << 4, y << 4, z << 4));
        }
    }

    public static void localLightChanged(ClientLevel level, SectionPos position) {
        if (ClientPreparedTravel.applyingColumn(level, position.x(), position.z())) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> localLightChanged(level, position));
            return;
        }
        WormholesClient client = instance;
        if (client != null) {
            client.preparedTravel.lightChanged(level, position);
            client.localMeshes.blockChanged(level, position.origin());
        }
    }

    public ClientPreparedTravel preparedTravel() {
        return preparedTravel;
    }

    public boolean managesVanillaPortal(ClientLevel level, BlockPos position) {
        return attachedLevel == level && session.managesVanillaPortal(position.getX(), position.getY(), position.getZ())
            || preparedTravel.managesVanillaPortal(level, position);
    }

    public void dropProjectedEntities(ApertureDescriptor geometry) {
        ClientProjectedEntities entities = tick == null ? null : tick.entities();
        if (entities == null) {
            return;
        }
        for (ClientPortal portal : session.portals().values()) {
            if (!portal.nested() && portal.geometry().sameSurface(geometry)) {
                entities.drop(portal.portalKey());
            }
        }
    }

    public ClientLocalMeshSources localMeshes() {
        return localMeshes;
    }

    public void receive(byte[] payload, Consumer<byte[]> reply) {
        receiver.receive(payload, reply == null ? sender : reply);
    }

    public void connected() {
        preparedTravel.clear();
        if (session.state() == ClientViewSession.State.VANILLA || session.state() == ClientViewSession.State.DECLINED) {
            return;
        }
        if (attachedLevel != null) {
            detach();
        }
    }

    public void disconnected() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::disconnected);
            return;
        }
        preparedTravel.clear();
        ClientSodiumTerrain.clear();
        reflections.clear(null, null);
        detach();
        meshViews.clear();
        freshSession();
    }

    public void tick(Minecraft minecraft) {
        ClientPortalRenderer.instance().finishBuilds();
        preparedTravel.tick();
        if (preparedTravel.pendingCrossing()) {
            return;
        }
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
        try {
            localMeshes.update(session, level, eye.x, eye.y, eye.z);
        } catch (ViewStreamProtocolException failure) {
            LOGGER.warn("Unable to capture local mirror sections", failure);
        }
        meshViews.update(session, level);
        reflections.tick(level, player, minecraft.getConnection(), session, tick,
            config.selfReflection && session.active() && session.has(ViewStreamCapability.CLIENT_MIRROR));
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
        meshViews.detach();
        tick.detach();
        attachedLevel = null;
        attachedSurface = null;
    }

    private void freshSession() {
        localMeshes.clear();
        ClientViewSession next = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), dataVersion, brandTag);
        next.meshes().otherMemory(() -> next.plates().bytes() + localMeshes.bytes());
        ClientViewReceiver nextReceiver = new ClientViewReceiver(next);
        nextReceiver.travel(preparedTravel::receive);
        ClientViewTick nextTick = new ClientViewTick(next, nextReceiver, config, stats);
        nextTick.sender(this::send);
        session = next;
        receiver = nextReceiver;
        tick = nextTick;
        stats.reset();
    }

    private void send(ViewStreamMessage message) {
        try {
            sender.accept(MinecraftClientViewExtensions.CODEC.encodeC2S(message));
        } catch (ViewStreamProtocolException failure) {
            LOGGER.warn("Wormholes ClientView could not encode {}", MinecraftClientViewExtensions.CODEC.name(message), failure);
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
            DebugScreenEntriesAccessor.wormholesRegister(ClientViewDebugEntry.STATUS_ID,
                new ClientViewDebugEntry(() -> session.connectionStatus().debugLine()));
            minecraft.debugEntries.setStatus(ClientViewDebugEntry.STATUS_ID, DebugScreenEntryStatus.IN_OVERLAY);
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
