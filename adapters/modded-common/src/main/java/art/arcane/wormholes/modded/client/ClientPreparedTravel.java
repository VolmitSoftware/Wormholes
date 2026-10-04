package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftChunkPacketEncoding;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.ClientTravelScene;
import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import art.arcane.wormholes.modded.clientview.MinecraftPortalEnvironment;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelDataAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedEntityAccess;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.portal.PortalFrame;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.Consumer;

import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;

public final class ClientPreparedTravel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long DECODE_NANOS = 2_000_000L;
    private static final int MAX_DECODE_COLUMNS = 16;
    private static final int MAX_DECODE_BYTES = 256 * 1024;
    private static final int PENDING_PACKET_OVERHEAD_BYTES = 1024;
    private static final int MAX_PENDING_BYTES = 8 << 20;
    private static final long CROSS_TIMEOUT_MILLIS = 2_000;
    private static final boolean SODIUM = ClientPreparedTravel.class.getClassLoader()
        .getResource("net/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTrackerHolder.class") != null;
    private static final boolean IRIS = ClientPreparedTravel.class.getClassLoader()
        .getResource("net/irisshaders/iris/Iris.class") != null;
    private final Consumer<ClientViewMessage> sender;
    private final ClientTravelCache cache = new ClientTravelCache();
    private int sourceCapture;
    private boolean nativeCacheFailureReported;
    private SourcePreparation sourcePreparation;
    private final Map<ClientViewMessage.TravelCoordinate, byte[]> payloads = new HashMap<>();
    private final ArrayDeque<Column> decoding = new ArrayDeque<>();
    private final Map<ClientViewMessage.TravelCoordinate, Integer> decoded = new HashMap<>();
    private final LongOpenHashSet changed = new LongOpenHashSet();
    private ClientViewMessage.TravelBegin begin;
    private ClientViewMessage.TravelCommit commit;
    private ClientTravelChunks chunks;
    private ClientLevel staged;
    private ClientTravelScene scene;
    private long deadline;
    private long drawnRevision;
    private long acknowledgedRevision;
    private boolean adopted;
    private boolean positionConfirmed;
    private boolean mainCompiled;
    private Screen deferredScreen;
    private PendingPreparation pendingPreparation;
    private Prediction prediction;
    private Vec3 previousCamera;
    private boolean seamlessRespawn;
    private ClientTravelMotion beforePosition;
    private Arrival arrival;

    public ClientPreparedTravel(Consumer<ClientViewMessage> sender) {
        this.sender = sender;
    }

    public boolean active() {
        return begin != null;
    }

    public boolean adopted() {
        return adopted;
    }

    public boolean positionConfirmed() {
        return positionConfirmed;
    }

    public long readyRevision() {
        return acknowledgedRevision;
    }

    public ClientLevel level() {
        return staged;
    }

    public void discardSourcePreparation() {
        retireSourcePreparation();
        sourceCapture = begin != null && !adopted && prediction == null ? 0 : 49;
    }

    public boolean pendingCrossing() {
        return prediction != null && !positionConfirmed;
    }

    public boolean beforeFrame(Camera camera, DeltaTracker tracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null || !camera.isInitialized() || camera.entity() != player) {
            previousCamera = null;
            return false;
        }
        if (begin != null && !adopted && prediction == null) {
            ClientPortalRenderer.instance().updateTravelCamera(camera, begin.destinationToSource());
        }
        float partial = camera.getCameraEntityPartialTicks(tracker);
        Vec3 eye = player.getEyePosition(partial);
        Vec3 previous = previousCamera;
        previousCamera = eye;
        if (begin == null || adopted || prediction != null
            || previous == null || minecraft.getConnection() == null || player.isPassenger()
            || player.isDeadOrDying() || minecraft.gui.screen() != null
            || !begin.sourceWorld().equals(minecraft.level.dimension().identifier().toString())
            || !crossed(begin.sourceGeometry(), previous, eye)) {
            return false;
        }
        if (acknowledgedRevision == 0 || staged == null || System.currentTimeMillis() >= deadline
            || !ClientPortalRenderer.instance().travelDrawable() || (IRIS && !IrisMain.ready(staged))
            || !ClientPortalRenderer.instance().travelReady()) {
            declinePreparation();
            return false;
        }
        ClientTravelMotion source = ClientTravelMotion.capture(player);
        ClientTravelMotion destination = source.transform(begin.destinationToSource());
        if (!covers(pose(destination))) {
            declinePreparation();
            return false;
        }
        ClientLevel sourceLevel = minecraft.level;
        ClientViewMessage.TravelPose crossingPose = crossingPose(player, partial);
        Vec3 expectedArrival = ClientTravelMotion.point(begin.destinationToSource(), new Vec3(crossingPose.x(), crossingPose.y(), crossingPose.z()));
        prediction = new Prediction(new PredictionState(sourceLevel, source, destination, expectedArrival, acknowledgedRevision,
            ((PreparedLevelAccess) sourceLevel).wormholes$extractor(), minecraft.getConnection()));
        try {
            prediction.deadline = System.currentTimeMillis() + CROSS_TIMEOUT_MILLIS;
            sender.accept(new ClientViewMessage.TravelCross(begin.token(), begin.generation(), prediction.revision,
                crossingPose, vector(previous), vector(eye)));
            if (begin.sourceWorld().equals(begin.world().dimension())) {
                prepareSameWorld(sourceLevel, source, destination);
                destination.apply(player);
            } else {
                ((PreparedLevelAccess) sourceLevel).wormholes$extractor(new PreparedLevelExtractor(minecraft));
                attachPlayer(staged, destination);
            }
            ClientPortalRenderer.instance().transitionTravel(true);
            arrival = null;
            previousCamera = ClientTravelMotion.point(begin.destinationToSource(), eye);
            return true;
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to predict prepared portal crossing", failure);
            clear();
            return false;
        }
    }

    public boolean deferWorldPacket(Packet<ClientGamePacketListener> packet, Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            return false;
        }
        if (adopted && commit != null && pendingCrossing() && prediction.source != staged
            && packet instanceof ClientboundForgetLevelChunkPacket) {
            return true;
        }
        if (!pendingCrossing() || commit != null) {
            return false;
        }
        if (prediction.source == staged && !(packet instanceof ClientboundSetChunkCacheCenterPacket)
            && !(packet instanceof ClientboundSetChunkCacheRadiusPacket) && !(packet instanceof ClientboundForgetLevelChunkPacket)) {
            return false;
        }
        ByteBuf buffer = Unpooled.buffer(256, MAX_PENDING_BYTES + 1);
        try {
            prediction.protocol.codec().encode(buffer, packet);
            int retainedBytes = buffer.readableBytes() + PENDING_PACKET_OVERHEAD_BYTES;
            if (retainedBytes > MAX_PENDING_BYTES - prediction.retainedPacketBytes) {
                clear();
                return false;
            }
            prediction.retainedPacketBytes += retainedBytes;
            prediction.packets.add(action);
            return true;
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to retain source-world packet during prepared crossing", failure);
            clear();
            return false;
        } finally {
            buffer.release();
        }
    }

    public boolean beginRespawn(ResourceKey<Level> dimension, boolean keepPlayer) {
        seamlessRespawn = keepPlayer && prediction != null && commit != null && pendingCrossing()
            && dimension.identifier().toString().equals(commit.destinationWorld());
        if (prediction != null && !seamlessRespawn) {
            clear();
        }
        return seamlessRespawn;
    }

    public void endRespawn() {
        seamlessRespawn = false;
    }

    public boolean seamlessRespawn() {
        return seamlessRespawn;
    }

    public Level respawnSourceLevel() {
        return seamlessRespawn ? prediction.source : null;
    }

    public void beforeServerPosition() {
        previousCamera = null;
        if (prediction == null) {
            return;
        }
        if (commit == null || !adopted) {
            clear();
            return;
        }
        beforePosition = ClientTravelMotion.capture(Minecraft.getInstance().player);
    }

    public void receive(ClientViewMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> receive(message));
            return;
        }
        try {
            if (deferPreparation(message)) {
                return;
            }
            switch (message) {
                case ClientViewMessage.TravelBegin value -> begin(value);
                case ClientViewMessage.TravelChunk value -> chunk(value);
                case ClientViewMessage.TravelReuse value -> reuse(value);
                case ClientViewMessage.TravelEnd value -> {
                    if (chunks != null) {
                        chunks.end(value);
                    }
                }
                case ClientViewMessage.TravelCommit value -> commit(value);
                case ClientViewMessage.TravelCancel value -> {
                    if (chunks != null && chunks.matches(value.token(), value.generation())) {
                        clear(false);
                    }
                }
                default -> { }
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to prepare authoritative portal travel", failure);
            clear();
        }
    }

    public void tick() {
        advanceArrival();
        if (sourcePreparation != null && System.currentTimeMillis() >= sourcePreparation.deadline) {
            retireSourcePreparation();
            sourceCapture = 49;
        }
        if (begin == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        cache.bind(connection, connection == null ? null : connection.registryAccess());
        if (connection == null || System.currentTimeMillis() > deadline) {
            clear(connection == null);
            return;
        }
        if (pendingCrossing() && (System.currentTimeMillis() >= prediction.deadline || minecraft.player == null
            || minecraft.player.isDeadOrDying() || !ClientPortalRenderer.instance().travelDrawable()
            || !covers(pose(ClientTravelMotion.capture(minecraft.player))))) {
            clear();
            return;
        }
        if (!adopted && prediction == null && (minecraft.level == null || !begin.sourceWorld().equals(minecraft.level.dimension().identifier().toString()))) {
            clear();
            return;
        }
        if (adopted) {
            if (minecraft.level != staged) {
                clear();
                return;
            }
            if (minecraft.player == null || !covers(positionConfirmed
                ? new ClientViewMessage.TravelPose(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                    minecraft.player.getYRot(), minecraft.player.getXRot()) : commit.arrival())) {
                clear();
                return;
            }
            scene.advance();
            advancePreparation();
            if (positionConfirmed) {
                mainCompiled = ClientPortalRenderer.instance().travelMainReady();
            }
            completeLoad(connection);
            if (mainCompiled) {
                finishArrival();
            } else if (positionConfirmed && connection.hasClientLoaded() && pendingPreparation != null && pendingPreparation.level != null) {
                retainArrivalPreparation();
            }
            return;
        }
        captureSource();
        advanceSourcePreparation();
        if (!decodePending()) {
            return;
        }
        long revision = chunks.completeRevision();
        if (!decoding.isEmpty() || decoded.size() != begin.chunks().size()) {
            return;
        }
        if (scene == null || drawnRevision != revision || !changed.isEmpty()) {
            if (scene == null) {
                scene = new ClientTravelScene(staged, begin);
                scene.nativeColumns(payloads);
                ClientPortalRenderer.instance().prepareTravel(scene, arrivalCamera(begin.arrival()));
            } else {
                scene.nativeColumns(payloads);
                for (LongIterator iterator = changed.iterator(); iterator.hasNext();) {
                    for (long key : scene.changedSection(iterator.nextLong())) {
                        ClientPortalRenderer.instance().invalidateTravel(key);
                    }
                }
            }
            drawnRevision = revision;
            changed.clear();
        }
        scene.advance();
        if (ClientPortalRenderer.instance().travelReady() && (!IRIS || IrisMain.prepare(staged)) && revision != 0) {
            if (acknowledgedRevision != revision) {
                acknowledgedRevision = revision;
                sender.accept(new ClientViewMessage.TravelReady(begin.token(), begin.generation(), revision));
            }
        }
    }

    public ClientLevel adopt(Construction construction) {
        if (System.currentTimeMillis() >= deadline || commit == null || staged == null || adopted
            || (prediction == null ? acknowledgedRevision != commit.contentRevision() : prediction.revision != commit.contentRevision())
            || !(prediction == null ? ClientPortalRenderer.instance().travelReady() : ClientPortalRenderer.instance().travelDrawable())
            || !matches(construction)) {
            if (prediction != null) {
                clear();
            }
            return null;
        }
        ((PreparedLevelAccess) staged).wormholes$extractor(construction.extractor());
        ((PreparedLevelAccess) staged).wormholes$data(construction.data());
        if (prediction != null) {
            ((PreparedPacketAccess) Minecraft.getInstance().getConnection()).wormholes$level(staged);
            ((PreparedPacketAccess) Minecraft.getInstance().getConnection()).wormholes$data(construction.data());
        }
        adopted = true;
        ClientPortalRenderer.instance().transitionTravel(true);
        return staged;
    }

    public boolean deferLoadingScreen(Screen screen) {
        if (!adopted || commit == null || !(screen instanceof LevelLoadingScreen)) {
            return false;
        }
        deferredScreen = screen;
        return true;
    }

    public void serverPosition() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!adopted || minecraft.level != staged || minecraft.player == null || commit == null) {
            return;
        }
        ClientViewMessage.TravelPose arrival = commit.arrival();
        Vec3 position = minecraft.player.position();
        if (position.distanceToSqr(new Vec3(arrival.x(), arrival.y(), arrival.z())) > 0.000001) {
            clear();
            return;
        }
        positionConfirmed = true;
        if (prediction != null && beforePosition != null) {
            Vec3 offset = position.subtract(prediction.expectedArrival);
            ClientTravelMotion reconciled = beforePosition.reconcile(offset, prediction.destination.velocity(),
                new Vec3(commit.velocity().x(), commit.velocity().y(), commit.velocity().z()));
            if (covers(pose(reconciled))) {
                reconciled.apply(minecraft.player);
            }
            beforePosition = null;
            prediction.packets.clear();
            prediction.sourceColumns.clear();
            prediction = null;
        }
    }

    public Runnable compiledCallback(Runnable original) {
        if (!adopted) {
            return original;
        }
        return () -> {
            if (original != null) {
                original.run();
            }
        };
    }

    public void blockChanged(Object world, BlockPos position) {
        if (arrival != null && world == arrival.level) {
            for (long key : arrival.scene.changedSection(SectionPos.asLong(position))) {
                ClientPortalRenderer.instance().invalidateArrival(key);
            }
        }
        if (!adopted || world != staged || scene == null) {
            return;
        }
        for (long key : scene.changedSection(SectionPos.asLong(position))) {
            ClientPortalRenderer.instance().invalidateTravel(key);
        }
    }

    public void clear() {
        clear(true);
    }

    private void clear(boolean clearArrival) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        cache.bind(connection, connection == null ? null : connection.registryAccess());
        if (connection == null) {
            nativeCacheFailureReported = false;
        }
        rollback();
        if (clearArrival && arrival != null) {
            ClientPortalRenderer.instance().retireArrival();
            arrival = null;
        }
        if (IRIS) {
            IrisMain.clearPending();
        }
        if (adopted && !mainCompiled && minecraft.level == staged && minecraft.player != null && minecraft.getConnection() != null) {
            PreparedPacketAccess access = (PreparedPacketAccess) minecraft.getConnection();
            LevelLoadTracker tracker = access.wormholes$loadTracker();
            if (tracker == null && positionConfirmed) {
                tracker = new LevelLoadTracker();
                tracker.startClientLoad(minecraft.player, staged);
                tracker.loadingPacketsReceived();
                access.wormholes$loadTracker(tracker);
            }
            if (tracker != null && minecraft.gui.screen() == null) {
                minecraft.setScreenAndShow(new LevelLoadingScreen(tracker, LevelLoadingScreen.Reason.OTHER));
            }
        }
        ClientPortalRenderer.instance().cancelTravel();
        retireSourcePreparation();
        decoding.clear();
        payloads.clear();
        decoded.clear();
        changed.clear();
        begin = null;
        commit = null;
        chunks = null;
        staged = null;
        scene = null;
        deadline = 0;
        drawnRevision = 0;
        acknowledgedRevision = 0;
        adopted = false;
        positionConfirmed = false;
        mainCompiled = false;
        deferredScreen = null;
        pendingPreparation = null;
        prediction = null;
        previousCamera = null;
        seamlessRespawn = false;
        beforePosition = null;
    }

    private boolean deferPreparation(ClientViewMessage message) {
        if (message instanceof ClientViewMessage.TravelBegin next && adopted && !mainCompiled) {
            if (begin.world().dimension().equals(next.sourceWorld())
                && (pendingPreparation == null || !pendingPreparation.chunks.matches(next.token(), next.generation()))) {
                pendingPreparation = preparation(next);
            }
            return true;
        }
        if (pendingPreparation == null) {
            return false;
        }
        try {
            return switch (message) {
                case ClientViewMessage.TravelChunk value -> {
                    if (!pendingPreparation.chunks.matches(value.token(), value.generation())) {
                        yield false;
                    }
                    if (System.currentTimeMillis() >= pendingPreparation.deadline) {
                        pendingPreparation = null;
                        yield true;
                    }
                    byte[] data = pendingPreparation.chunks.accept(value);
                    if (data != null) {
                        cache.put(pendingPreparation.begin.world().dimension(), value.chunkX(), value.chunkZ(), data);
                        pendingPreparation.columns.put(new ClientViewMessage.TravelCoordinate(value.chunkX(), value.chunkZ()),
                            new Column(value.chunkX(), value.chunkZ(), value.revision(), data));
                    }
                    yield true;
                }
                case ClientViewMessage.TravelReuse value -> {
                    if (!pendingPreparation.chunks.matches(value.token(), value.generation())) {
                        yield false;
                    }
                    byte[] data = cache.get(pendingPreparation.begin.world().dimension(), value.chunkX(), value.chunkZ(), value.hash());
                    boolean available = data != null && pendingPreparation.chunks.reuse(value, data);
                    if (available) {
                        pendingPreparation.columns.put(new ClientViewMessage.TravelCoordinate(value.chunkX(), value.chunkZ()),
                            new Column(value.chunkX(), value.chunkZ(), value.revision(), data));
                    }
                    sender.accept(new ClientViewMessage.TravelCached(value.token(), value.generation(), value.chunkX(), value.chunkZ(),
                        value.revision(), value.hash(), available));
                    yield true;
                }
                case ClientViewMessage.TravelEnd value -> {
                    if (!pendingPreparation.chunks.matches(value.token(), value.generation())) {
                        yield false;
                    }
                    pendingPreparation.chunks.end(value);
                    yield true;
                }
                case ClientViewMessage.TravelCancel value -> {
                    if (!pendingPreparation.chunks.matches(value.token(), value.generation())) {
                        yield false;
                    }
                    pendingPreparation = null;
                    yield true;
                }
                case ClientViewMessage.TravelCommit value -> pendingPreparation.chunks.matches(value.token(), value.generation());
                default -> false;
            };
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to retain upcoming authoritative portal travel", failure);
            pendingPreparation = null;
            return true;
        }
    }

    private PendingPreparation preparation(ClientViewMessage.TravelBegin value) {
        PendingPreparation next = new PendingPreparation(value);
        SourcePreparation source = sourcePreparation;
        if (source != null) {
            if (source.matches(value)) {
                source.scene.rebind(value);
                next.level = source.level;
                next.scene = source.scene;
                next.payloads.putAll(source.payloads);
                for (ClientViewMessage.TravelCoordinate coordinate : source.decoded.keySet()) {
                    next.decoded.put(coordinate, 0);
                }
            }
            retireSourcePreparation();
        }
        for (Column column : cachedColumns(value)) {
            next.columns.put(new ClientViewMessage.TravelCoordinate(column.x(), column.z()), column);
        }
        return next;
    }

    private void advancePreparation() {
        PendingPreparation next = pendingPreparation;
        if (next == null) {
            return;
        }
        if (System.currentTimeMillis() >= next.deadline) {
            pendingPreparation = null;
            return;
        }
        try {
            if (next.level == null) {
                next.level = createLevel(next.begin);
            }
            long budget = System.nanoTime() + DECODE_NANOS;
            int bytes = 0;
            int count = 0;
            Iterator<Column> columns = next.columns.values().iterator();
            while (count < MAX_DECODE_COLUMNS && columns.hasNext()) {
                Column column = columns.next();
                ClientViewMessage.TravelCoordinate coordinate = new ClientViewMessage.TravelCoordinate(column.x(), column.z());
                if (next.decoded.containsKey(coordinate) && next.decoded.get(coordinate) >= column.revision()) {
                    continue;
                }
                if (count > 0 && (System.nanoTime() >= budget || bytes + column.data().length > MAX_DECODE_BYTES)) {
                    break;
                }
                decodeChanged(next.level, next.scene, next.decoded, next.changed, next.payloads, column);
                bytes += column.data().length;
                count++;
            }
            long revision = next.chunks.completeRevision();
            if (next.decoded.size() != next.begin.chunks().size()) {
                return;
            }
            if (next.scene == null) {
                next.scene = new ClientTravelScene(next.level, next.begin);
                next.scene.nativeColumns(next.payloads);
            }
            if (next.drawnRevision != revision || !next.changed.isEmpty()) {
                next.scene.nativeColumns(next.payloads);
                for (LongIterator changed = next.changed.iterator(); changed.hasNext();) {
                    next.scene.changedSection(changed.nextLong());
                }
                next.changed.clear();
                next.drawnRevision = revision;
            }
            next.scene.advance();
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to prepare upcoming authoritative portal travel", failure);
            pendingPreparation = null;
        }
    }

    private void retainArrivalPreparation() {
        PendingPreparation next = pendingPreparation;
        if (next == null || System.currentTimeMillis() >= next.deadline) {
            pendingPreparation = null;
            return;
        }
        arrival = new Arrival(staged, scene, begin, deadline);
        ClientPortalRenderer.instance().retainArrival();
        decoding.clear();
        payloads.clear();
        decoded.clear();
        changed.clear();
        commit = null;
        prediction = null;
        beforePosition = null;
        adopted = false;
        positionConfirmed = false;
        mainCompiled = false;
        acknowledgedRevision = 0;
        deferredScreen = null;
        previousCamera = null;
        pendingPreparation = null;
        begin = next.begin;
        staged = next.level;
        chunks = next.chunks;
        deadline = next.deadline;
        sourceCapture = 0;
        scene = next.scene;
        drawnRevision = next.drawnRevision;
        payloads.putAll(next.payloads);
        decoded.putAll(next.decoded);
        changed.addAll(next.changed);
        for (Column column : next.columns.values()) {
            if (!decoded.containsKey(new ClientViewMessage.TravelCoordinate(column.x(), column.z()))
                || decoded.get(new ClientViewMessage.TravelCoordinate(column.x(), column.z())) < column.revision()) {
                decoding.add(column);
            }
        }
        if (scene != null) {
            ClientPortalRenderer.instance().prepareTravel(scene, arrivalCamera(begin.arrival()));
        }
    }

    private void advanceArrival() {
        if (arrival == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != arrival.level || minecraft.getConnection() == null) {
            ClientPortalRenderer.instance().retireArrival();
            arrival = null;
            return;
        }
        if (System.currentTimeMillis() >= arrival.deadline || minecraft.player == null || !covers(arrival.begin, arrival.level, new ClientViewMessage.TravelPose(
            minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(), minecraft.player.getYRot(), minecraft.player.getXRot()))
            || !ClientPortalRenderer.instance().arrivalDrawable()) {
            PreparedPacketAccess access = (PreparedPacketAccess) minecraft.getConnection();
            LevelLoadTracker tracker = access.wormholes$loadTracker();
            if (tracker == null && minecraft.player != null) {
                tracker = new LevelLoadTracker();
                tracker.startClientLoad(minecraft.player, arrival.level);
                tracker.loadingPacketsReceived();
                access.wormholes$loadTracker(tracker);
            }
            if (tracker != null && minecraft.gui.screen() == null) {
                minecraft.setScreenAndShow(new LevelLoadingScreen(tracker, LevelLoadingScreen.Reason.OTHER));
            }
            ClientPortalRenderer.instance().retireArrival();
            arrival = null;
            return;
        }
        arrival.scene.advance();
        if (ClientPortalRenderer.instance().arrivalMainReady()) {
            ClientPortalRenderer.instance().retireArrival();
            arrival = null;
        }
    }

    private PendingPreparation nextPreparation() {
        if (!mainCompiled) {
            return null;
        }
        PendingPreparation next = pendingPreparation;
        pendingPreparation = null;
        return next != null && System.currentTimeMillis() < next.deadline ? next : null;
    }

    private void finishArrival() {
        PendingPreparation next = nextPreparation();
        clear();
        if (next == null) {
            return;
        }
        try {
            if (next.level == null) {
                begin(next.begin);
            } else {
                begin = next.begin;
                staged = next.level;
                sourceCapture = 0;
            }
            if (begin != null) {
                chunks = next.chunks;
                deadline = next.deadline;
                scene = next.scene;
                payloads.putAll(next.payloads);
                decoded.putAll(next.decoded);
                changed.addAll(next.changed);
                drawnRevision = next.drawnRevision;
                for (Column column : next.columns.values()) {
                    if (!decoded.containsKey(new ClientViewMessage.TravelCoordinate(column.x(), column.z()))
                        || decoded.get(new ClientViewMessage.TravelCoordinate(column.x(), column.z())) < column.revision()) {
                        decoding.add(column);
                    }
                }
                if (scene != null) {
                    ClientPortalRenderer.instance().prepareTravel(scene, arrivalCamera(begin.arrival()));
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to begin retained authoritative portal travel", failure);
            clear();
        }
    }

    private boolean decodePending() {
        long deadline = System.nanoTime() + DECODE_NANOS;
        int bytes = 0;
        for (int count = 0; count < MAX_DECODE_COLUMNS && !decoding.isEmpty(); count++) {
            Column column = decoding.peek();
            if (count > 0 && (System.nanoTime() >= deadline || column.data().length > MAX_DECODE_BYTES - bytes)) {
                break;
            }
            decoding.remove();
            try {
                decodeChanged(staged, scene, decoded, changed, payloads, column);
            } catch (RuntimeException failure) {
                LOGGER.warn("Unable to decode authoritative prepared destination chunk", failure);
                clear();
                return false;
            }
            bytes += column.data().length;
        }
        return true;
    }

    private void begin(ClientViewMessage.TravelBegin value) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || minecraft.level == null
            || !minecraft.level.dimension().identifier().toString().equals(value.sourceWorld())) {
            return;
        }
        PendingPreparation next = preparation(value);
        clear(false);
        staged = next.level == null ? createLevel(value) : next.level;
        begin = value;
        cache.bind(connection, connection.registryAccess());
        sourceCapture = 0;
        chunks = next.chunks;
        deadline = next.deadline;
        scene = next.scene;
        payloads.putAll(next.payloads);
        decoded.putAll(next.decoded);
        for (Column column : next.columns.values()) {
            if (!decoded.containsKey(new ClientViewMessage.TravelCoordinate(column.x(), column.z()))) {
                decoding.add(column);
            }
        }
        if (scene != null) {
            ClientPortalRenderer.instance().prepareTravel(scene, arrivalCamera(value.arrival()));
        }
    }

    private List<Column> cachedColumns(ClientViewMessage.TravelBegin value) {
        List<Column> retained = new ArrayList<>(value.chunks().size());
        for (ClientViewMessage.TravelCoordinate coordinate : value.chunks()) {
            byte[] data = cache.peek(value.world().dimension(), coordinate.x(), coordinate.z());
            if (data != null) {
                retained.add(new Column(coordinate.x(), coordinate.z(), 0, data));
            }
        }
        return retained;
    }

    private ClientLevel createLevel(ClientViewMessage.TravelBegin value) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        Holder<DimensionType> type = connection.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
            .getOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(value.world().dimensionType())));
        if (type.value().minY() != value.world().minY() || type.value().height() != value.world().height()) {
            throw new IllegalArgumentException("Prepared travel dimension height differs from synchronized registry");
        }
        ClientLevel.ClientLevelData source = minecraft.level.getLevelData();
        ClientLevel.ClientLevelData data = new ClientLevel.ClientLevelData(source.getDifficulty(), source.isHardcore(), value.world().flat());
        data.setGameTime(value.environment().gameTime());
        LevelExtractor extractor = new PreparedLevelExtractor(minecraft);
        ClientLevel level = new ClientLevel(connection, data, ResourceKey.create(Registries.DIMENSION, Identifier.parse(value.world().dimension())),
            type, ((PreparedPacketAccess) connection).wormholes$chunkRadius(), minecraft.level.getServerSimulationDistance(),
            extractor, value.world().debug(), value.world().seed(), value.world().seaLevel());
        level.getChunkSource().updateViewCenter((int) Math.floor(value.arrival().x()) >> 4, (int) Math.floor(value.arrival().z()) >> 4);
        level.setRainLevel(value.environment().sky().rain());
        level.setThunderLevel(value.environment().sky().thunder());
        return level;
    }

    private void chunk(ClientViewMessage.TravelChunk fragment) {
        if (chunks == null || adopted) {
            return;
        }
        byte[] data = chunks.accept(fragment);
        if (data == null) {
            return;
        }
        cache.put(begin.world().dimension(), fragment.chunkX(), fragment.chunkZ(), data);
        queueColumn(new Column(fragment.chunkX(), fragment.chunkZ(), fragment.revision(), data));
    }

    private void reuse(ClientViewMessage.TravelReuse proof) {
        if (chunks == null || adopted || !chunks.matches(proof.token(), proof.generation())) {
            return;
        }
        byte[] data = cache.get(begin.world().dimension(), proof.chunkX(), proof.chunkZ(), proof.hash());
        boolean available = data != null && chunks.reuse(proof, data);
        if (available && decoded.getOrDefault(new ClientViewMessage.TravelCoordinate(proof.chunkX(), proof.chunkZ()), 0) < proof.revision()) {
            queueColumn(new Column(proof.chunkX(), proof.chunkZ(), proof.revision(), data));
        }
        sender.accept(new ClientViewMessage.TravelCached(proof.token(), proof.generation(), proof.chunkX(), proof.chunkZ(),
            proof.revision(), proof.hash(), available));
    }

    private void queueColumn(Column column) {
        decoding.removeIf(previous -> previous.x() == column.x() && previous.z() == column.z() && previous.revision() <= column.revision());
        decoding.add(column);
    }

    public void rememberNativeChunk(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        WormholesClient client = WormholesClient.instance();
        if (level == null || begin == null || client == null || !client.session().active()
            || !client.session().has(ClientViewCapability.PREPARED_TRAVEL_CACHE)) {
            return;
        }
        String world = level.dimension().identifier().toString();
        boolean source = world.equals(begin.sourceWorld())
            && Math.abs((long) packet.x() - (begin.sourceGeometry().originX() >> 4)) <= 3
            && Math.abs((long) packet.z() - (begin.sourceGeometry().originZ() >> 4)) <= 3;
        boolean destination = world.equals(begin.world().dimension())
            && begin.chunks().contains(new ClientViewMessage.TravelCoordinate(packet.x(), packet.z()));
        if (!source && !destination) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        cache.bind(connection, connection.registryAccess());
        try {
            cache.put(world, packet.x(), packet.z(), encodeNativeChunk(level, packet));
        } catch (RuntimeException failure) {
            if (!nativeCacheFailureReported) {
                nativeCacheFailureReported = true;
                LOGGER.warn("Unable to retain native portal return chunk packets", failure);
            }
        }
    }

    private static byte[] encodeNativeChunk(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        return MinecraftChunkPacketEncoding.encode(level.registryAccess(), packet);
    }

    private void captureSource() {
        Minecraft minecraft = Minecraft.getInstance();
        if (prediction != null || minecraft.level == null || minecraft.player == null || sourceCapture >= 49) {
            return;
        }
        try {
            ClientLevel level = minecraft.level;
            if (sourcePreparation == null) {
                sourcePreparation = new SourcePreparation(sourceBegin(level, minecraft.player));
            }
            int centerX = begin.sourceGeometry().originX() >> 4;
            int centerZ = begin.sourceGeometry().originZ() >> 4;
            long deadline = System.nanoTime() + DECODE_NANOS;
            int bytes = 0;
            int captured = 0;
            for (int inspected = 0; inspected < 49 && captured < MAX_DECODE_COLUMNS; inspected++) {
                if (inspected > 0 && (System.nanoTime() >= deadline || bytes >= MAX_DECODE_BYTES)) {
                    break;
                }
                int index = sourcePreparation.nextCapture();
                if (index < 0) {
                    break;
                }
                int x = centerX + index % 7 - 3;
                int z = centerZ + index / 7 - 3;
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, FULL, false);
                if (chunk == null) {
                    continue;
                }
                String world = level.dimension().identifier().toString();
                byte[] data = cache.peek(world, x, z);
                if (data == null) {
                    data = encodeNativeChunk(level, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                    cache.seed(world, x, z, data);
                }
                sourcePreparation.capture(index, new Column(x, z, 0, data));
                sourceCapture++;
                captured++;
                bytes += data.length;
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to capture native portal return snapshots", failure);
            retireSourcePreparation();
            sourceCapture = 49;
        }
    }

    private ClientViewMessage.TravelBegin sourceBegin(ClientLevel level, LocalPlayer player) {
        ClientViewMessage.TravelWorld world = ((ClientTravelWorld) level).wormholes$travelWorld();
        int centerX = begin.sourceGeometry().originX() >> 4;
        int centerZ = begin.sourceGeometry().originZ() >> 4;
        List<ClientViewMessage.TravelCoordinate> manifest = new ArrayList<>(49);
        for (int z = centerZ - 3; z <= centerZ + 3; z++) {
            for (int x = centerX - 3; x <= centerX + 3; x++) {
                manifest.add(new ClientViewMessage.TravelCoordinate(x, z));
            }
        }
        Vec3 eye = player.getEyePosition();
        return new ClientViewMessage.TravelBegin(begin.token(), begin.generation(), begin.sourcePortal(), begin.sourceWorld(),
            begin.sourceGeometry(), ClientViewEnvironment.Transform.IDENTITY, world,
            new ClientViewMessage.TravelPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()), manifest,
            MinecraftPortalEnvironment.capture(level, vector(eye), ClientViewEnvironment.Transform.IDENTITY, world.flat()), begin.expiresMillis());
    }

    private void advanceSourcePreparation() {
        SourcePreparation source = sourcePreparation;
        if (source == null) {
            return;
        }
        try {
            if (source.level == null) {
                source.level = createLevel(source.begin);
            }
            long deadline = System.nanoTime() + DECODE_NANOS;
            int bytes = 0;
            for (int count = 0; count < MAX_DECODE_COLUMNS && !source.columns.isEmpty(); count++) {
                Column column = source.columns.peek();
                if (count > 0 && (System.nanoTime() >= deadline || bytes + column.data().length > MAX_DECODE_BYTES)) {
                    break;
                }
                source.columns.remove();
                decodeChanged(source.level, source.scene, source.decoded, source.changed, source.payloads, column);
                bytes += column.data().length;
            }
            if (source.decoded.size() != source.begin.chunks().size()) {
                return;
            }
            if (source.scene == null) {
                source.scene = new ClientTravelScene(source.level, source.begin);
                source.scene.nativeColumns(source.payloads);
                ClientPortalRenderer.instance().prepareTravelSource(source.scene);
            }
            source.scene.advance();
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to prepare native portal return snapshots", failure);
            retireSourcePreparation();
            sourceCapture = 49;
        }
    }

    private void retireSourcePreparation() {
        ClientPortalRenderer.instance().retireTravelSource();
        sourcePreparation = null;
    }

    private static void decodeChanged(ClientLevel level, ClientTravelScene scene,
                                      Map<ClientViewMessage.TravelCoordinate, Integer> decoded, LongOpenHashSet changed,
                                      Map<ClientViewMessage.TravelCoordinate, byte[]> payloads, Column column) {
        ClientViewMessage.TravelCoordinate coordinate = new ClientViewMessage.TravelCoordinate(column.x(), column.z());
        if (Arrays.equals(payloads.get(coordinate), column.data())) {
            decoded.put(coordinate, column.revision());
            return;
        }
        decode(level, scene, decoded, changed, column);
        payloads.put(coordinate, column.data());
    }

    private static void decode(ClientLevel staged, ClientTravelScene scene,
                               Map<ClientViewMessage.TravelCoordinate, Integer> decoded, LongOpenHashSet changed, Column column) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(column.data()), staged.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
            if (buffer.isReadable() || packet.x() != column.x() || packet.z() != column.z()) {
                throw new IllegalArgumentException("Prepared travel native chunk does not match its envelope");
            }
            int minY = staged.getMinSectionY() - 1;
            int sectionCount = staged.getSectionsCount() + 2;
            ClientTravelSectionState[] before = scene == null ? null : new ClientTravelSectionState[sectionCount];
            if (before != null) {
                for (int index = 0; index < sectionCount; index++) {
                    before[index] = ClientTravelSectionState.capture(staged, packet.x(), minY + index, packet.z());
                }
            }
            applyChunk(staged, packet);
            ClientViewMessage.TravelCoordinate coordinate = new ClientViewMessage.TravelCoordinate(column.x(), column.z());
            decoded.put(coordinate, column.revision());
            if (before != null) {
                for (int index = 0; index < sectionCount; index++) {
                    int y = minY + index;
                    if (!before[index].same(ClientTravelSectionState.capture(staged, packet.x(), y, packet.z()))) {
                        changed.add(SectionPos.asLong(packet.x(), y, packet.z()));
                    }
                }
            }
        } finally {
            buffer.release();
        }
    }

    private void prepareSameWorld(ClientLevel source, ClientTravelMotion original, ClientTravelMotion destination) {
        boolean missing = false;
        for (ClientViewMessage.TravelCoordinate coordinate : begin.chunks()) {
            if (source.getChunkSource().getChunk(coordinate.x(), coordinate.z(), FULL, false) == null) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            staged = source;
            scene.adoptLevel(source);
            return;
        }
        int sourceX = (int) Math.floor(original.position().x) >> 4;
        int sourceZ = (int) Math.floor(original.position().z) >> 4;
        prediction.sourceCenter = new ChunkPos(sourceX, sourceZ);
        int bytes = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                LevelChunk chunk = source.getChunkSource().getChunk(sourceX + dx, sourceZ + dz, FULL, false);
                if (chunk == null) {
                    throw new IllegalStateException("Source collision neighborhood is unavailable");
                }
                ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk,
                    source.getLightEngine(), null, null);
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(256, MAX_PENDING_BYTES + 1), source.registryAccess());
                try {
                    ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
                    bytes += buffer.readableBytes();
                    if (bytes > MAX_PENDING_BYTES) {
                        throw new IllegalStateException("Source collision neighborhood exceeds crossing budget");
                    }
                } finally {
                    buffer.release();
                }
                prediction.sourceColumns.add(packet);
            }
        }
        source.getChunkSource().updateViewCenter((int) Math.floor(destination.position().x) >> 4,
            (int) Math.floor(destination.position().z) >> 4);
        for (ClientViewMessage.TravelCoordinate coordinate : begin.chunks()) {
            if (source.getChunkSource().getChunk(coordinate.x(), coordinate.z(), FULL, false) != null) {
                continue;
            }
            LevelChunk column = staged.getChunkSource().getChunk(coordinate.x(), coordinate.z(), FULL, false);
            if (column == null) {
                throw new IllegalStateException("Prepared collision neighborhood is unavailable");
            }
            applyChunk(source, new ClientboundLevelChunkWithLightPacket(column, staged.getLightEngine(), null, null));
        }
        staged = source;
        scene.adoptLevel(source);
    }

    private static void applyChunk(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        LevelChunk loaded = level.getChunkSource().replaceWithPacketData(packet.x(), packet.z(), packet.chunkData());
        if (loaded == null) {
            throw new IllegalArgumentException("Prepared travel native chunk is outside its client cache");
        }
        LevelLightEngine lighting = level.getChunkSource().getLightEngine();
        ClientboundLightUpdatePacketData light = packet.lightData();
        light(lighting, LightLayer.SKY, packet.x(), packet.z(), light.skyYMask(), light.emptySkyYMask(), light.skyUpdates().iterator());
        light(lighting, LightLayer.BLOCK, packet.x(), packet.z(), light.blockYMask(), light.emptyBlockYMask(), light.blockUpdates().iterator());
        lighting.setLightEnabled(new ChunkPos(packet.x(), packet.z()), true);
        LevelChunkSection[] sections = loaded.getSections();
        for (int index = 0; index < sections.length; index++) {
            lighting.updateSectionStatus(SectionPos.of(loaded.getPos(), level.getSectionYFromSectionIndex(index)), sections[index].hasOnlyAir());
        }
        level.setSectionRangeDirty(packet.x() - 1, level.getMinSectionY(), packet.z() - 1,
            packet.x() + 1, level.getMaxSectionY(), packet.z() + 1);
        lighting.runLightUpdates();
        if (SODIUM) {
            SodiumChunks.lightReady(level, packet.x(), packet.z());
        }
    }

    private void declinePreparation() {
        try {
            sender.accept(new ClientViewMessage.TravelCancel(begin.token(), begin.generation()));
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to decline unready prepared portal crossing", failure);
        } finally {
            clear(false);
        }
    }

    private void commit(ClientViewMessage.TravelCommit value) {
        if (System.currentTimeMillis() >= deadline || chunks == null || !chunks.matches(value.token(), value.generation())
            || (prediction == null ? acknowledgedRevision != value.contentRevision() : prediction.revision != value.contentRevision())
            || (prediction == null ? chunks.completeRevision() != value.contentRevision() : prediction.revision != value.contentRevision())
            || !begin.sourceWorld().equals(value.sourceWorld())
            || !begin.world().dimension().equals(value.destinationWorld()) || !covers(value.arrival())) {
            return;
        }
        commit = value;
        if (prediction != null) {
            prediction.packets.clear();
            if (begin.sourceWorld().equals(begin.world().dimension())) {
                PreparedPacketAccess access = (PreparedPacketAccess) Minecraft.getInstance().getConnection();
                access.wormholes$level(staged);
                access.wormholes$data(staged.getLevelData());
                adopted = true;
            }
        }
    }

    private void attachPlayer(ClientLevel destination, ClientTravelMotion motion) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level != null) {
            minecraft.level.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        }
        PreparedEntityAccess access = (PreparedEntityAccess) player;
        access.wormholes$level(destination);
        access.wormholes$restore();
        motion.apply(player);
        ((PreparedLevelAccess) destination).wormholes$extractor(minecraft.levelExtractor);
        if (IRIS) {
            IrisMain.attach(minecraft, destination);
        } else {
            minecraft.setLevel(destination);
        }
        destination.addEntity(player);
        minecraft.setCameraEntity(player);
    }

    private void rollback() {
        if (prediction == null) {
            return;
        }
        Prediction previous = prediction;
        prediction = null;
        ((PreparedLevelAccess) previous.source).wormholes$extractor(previous.extractor);
        if (!positionConfirmed && !(commit != null && adopted)) {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                PreparedPacketAccess access = (PreparedPacketAccess) connection;
                access.wormholes$level(previous.source);
                access.wormholes$data(previous.source.getLevelData());
            }
        }
        if (!positionConfirmed && !(commit != null && adopted) && Minecraft.getInstance().player != null) {
            if (previous.sourceCenter != null && Minecraft.getInstance().level == previous.source) {
                previous.source.getChunkSource().updateViewCenter(previous.sourceCenter.x(), previous.sourceCenter.z());
                for (ClientboundLevelChunkWithLightPacket packet : previous.sourceColumns) {
                    applyChunk(previous.source, packet);
                }
                previous.motion.apply(Minecraft.getInstance().player);
            } else if (Minecraft.getInstance().level == staged) {
                if (previous.source == staged) {
                    previous.motion.apply(Minecraft.getInstance().player);
                } else {
                    attachPlayer(previous.source, previous.motion);
                }
            }
        }
        if (commit == null && begin != null && Minecraft.getInstance().getConnection() != null) {
            sender.accept(new ClientViewMessage.TravelCancel(begin.token(), begin.generation()));
            for (Runnable packet : previous.packets) {
                packet.run();
            }
        }
        previous.packets.clear();
        previous.sourceColumns.clear();
    }

    static boolean crossed(ClientPortalGeometry geometry, Vec3 previous, Vec3 current) {
        double side = geometry.frontSide() ? 1 : -1;
        double before = geometry.signedDistance(previous.x, previous.y, previous.z) * side;
        double after = geometry.signedDistance(current.x, current.y, current.z) * side;
        if (before <= 0 || after > 0) {
            return false;
        }
        Vec3 intersection = previous.lerp(current, before / (before - after));
        PortalFrame frame = PortalFrame.canonical(geometry.facingDirection());
        int columnAxis = ClientPortalGeometry.axisOf(frame.getRight());
        int rowAxis = ClientPortalGeometry.axisOf(frame.getUp());
        int column = (int) Math.floor(component(intersection, columnAxis)) - origin(geometry, columnAxis);
        int row = (int) Math.floor(component(intersection, rowAxis)) - origin(geometry, rowAxis);
        return geometry.apertureOpen(column, row);
    }

    private static double component(Vec3 point, int axis) {
        return switch (axis) { case 0 -> point.x; case 1 -> point.y; default -> point.z; };
    }

    private static int origin(ClientPortalGeometry geometry, int axis) {
        return switch (axis) { case 0 -> geometry.originX(); case 1 -> geometry.originY(); default -> geometry.originZ(); };
    }

    private static GeometryVector vector(Vec3 point) {
        return new GeometryVector(point.x, point.y, point.z);
    }

    private static ClientViewMessage.TravelPose pose(ClientTravelMotion motion) {
        return new ClientViewMessage.TravelPose(motion.position().x, motion.position().y, motion.position().z,
            motion.rotation().yaw(), motion.rotation().pitch());
    }

    private static ClientViewMessage.TravelPose crossingPose(LocalPlayer player, float partial) {
        Vec3 feet = player.getPosition(partial);
        return new ClientViewMessage.TravelPose(feet.x, feet.y, feet.z, player.getYRot(), player.getXRot());
    }

    private boolean matches(Construction construction) {
        ClientViewMessage.TravelWorld world = begin.world();
        return world.dimension().equals(construction.dimension().identifier().toString())
            && world.dimensionType().equals(construction.type().unwrapKey().orElseThrow().identifier().toString())
            && world.seed() == construction.seed() && world.debug() == construction.debug()
            && world.flat() == ((PreparedLevelDataAccess) construction.data()).wormholes$flat() && world.seaLevel() == construction.seaLevel();
    }

    private boolean covers(ClientViewMessage.TravelPose arrival) {
        return covers(begin, staged, arrival);
    }

    private static boolean covers(ClientViewMessage.TravelBegin begin, ClientLevel staged, ClientViewMessage.TravelPose arrival) {
        if (arrival.y() < begin.world().minY() || arrival.y() + 1.8 >= (long) begin.world().minY() + begin.world().height()) {
            return false;
        }
        int radius = 1;
        int x = (int) Math.floor(arrival.x()) >> 4;
        int z = (int) Math.floor(arrival.z()) >> 4;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (!begin.chunks().contains(new ClientViewMessage.TravelCoordinate(x + dx, z + dz))
                    || staged == null || staged.getChunkSource().getChunk(x + dx, z + dz, FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void completeLoad(ClientPacketListener connection) {
        if (connection == null || !mainCompiled && !ClientPortalRenderer.instance().travelDrawable()) {
            showDeferred();
            return;
        }
        if (positionConfirmed && connection.hasClientLoaded()) {
            if (Minecraft.getInstance().gui.screen() == deferredScreen) {
                Minecraft.getInstance().gui.setScreen(null);
            }
            return;
        }
        if (!positionConfirmed) {
            return;
        }
        LevelLoadTracker tracker = ((PreparedPacketAccess) connection).wormholes$loadTracker();
        if (tracker != null) {
            Runnable milestone = tracker.getPlayerCompiledSectionCallback();
            if (milestone == null) {
                return;
            }
            milestone.run();
        }
        if (connection.hasClientLoaded() && Minecraft.getInstance().gui.screen() == deferredScreen) {
            Minecraft.getInstance().gui.setScreen(null);
        }
    }

    private void showDeferred() {
        Minecraft minecraft = Minecraft.getInstance();
        if (deferredScreen != null && minecraft.gui.screen() == null) {
            minecraft.setScreenAndShow(deferredScreen);
        }
    }

    private static CameraRenderState arrivalCamera(ClientViewMessage.TravelPose arrival) {
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(arrival.x(), arrival.y() + 1.62, arrival.z());
        camera.blockPos = BlockPos.containing(camera.pos);
        camera.xRot = arrival.pitch();
        camera.yRot = arrival.yaw();
        camera.orientation = new Quaternionf().rotationYXZ((float) (Math.PI - Math.toRadians(arrival.yaw())),
            (float) Math.toRadians(-arrival.pitch()), 0);
        camera.viewRotationMatrix = new Matrix4f().rotation(camera.orientation).transpose();
        camera.projectionMatrix = new Matrix4f();
        camera.cullFrustum = new Frustum(camera.viewRotationMatrix, camera.projectionMatrix);
        camera.cullFrustum.prepare(camera.pos.x, camera.pos.y, camera.pos.z);
        camera.initialized = true;
        return camera;
    }

    private static void light(LevelLightEngine engine, LightLayer layer, int x, int z, BitSet present, BitSet empty, Iterator<byte[]> data) {
        for (int index = 0; index < engine.getLightSectionCount(); index++) {
            if (present.get(index) || empty.get(index)) {
                engine.queueSectionData(layer, SectionPos.of(x, engine.getMinLightSection() + index, z),
                    present.get(index) ? new DataLayer(data.next().clone()) : new DataLayer());
            }
        }
        if (data.hasNext()) {
            throw new IllegalArgumentException("Prepared travel light update exceeds its section mask");
        }
    }

    static final class SodiumChunks {
        static void lightReady(ClientLevel level, int x, int z) {
            ChunkTrackerHolder.get(level).onChunkStatusAdded(x, z, ChunkStatus.FLAG_HAS_LIGHT_DATA);
        }
    }

    private static final class IrisMain {
        private static boolean prepare(ClientLevel level) {
            return PortalIrisMainPipelines.prepare(level);
        }

        private static boolean ready(ClientLevel level) {
            return PortalIrisMainPipelines.ready(level);
        }

        private static void attach(Minecraft minecraft, ClientLevel level) {
            try (PortalIrisMainPipelines.Handoff ignored = PortalIrisMainPipelines.handoff(level)) {
                minecraft.setLevel(level);
            }
        }

        private static void clearPending() {
            PortalIrisMainPipelines.clearPending();
        }
    }

    private record Arrival(ClientLevel level, ClientTravelScene scene, ClientViewMessage.TravelBegin begin, long deadline) { }

    private static final class SourcePreparation {
        private final ClientViewMessage.TravelBegin begin;
        private final long deadline;
        private final ArrayDeque<Column> columns = new ArrayDeque<>();
        private final Map<ClientViewMessage.TravelCoordinate, Integer> decoded = new HashMap<>();
        private final Map<ClientViewMessage.TravelCoordinate, byte[]> payloads = new HashMap<>();
        private final LongOpenHashSet changed = new LongOpenHashSet();
        private ClientLevel level;
        private ClientTravelScene scene;
        private int cursor;
        private int bytes;
        private long captured;

        private SourcePreparation(ClientViewMessage.TravelBegin begin) {
            this.begin = begin;
            deadline = System.currentTimeMillis() + begin.expiresMillis();
        }

        private boolean matches(ClientViewMessage.TravelBegin next) {
            return System.currentTimeMillis() < deadline && level != null && scene != null
                && decoded.size() == begin.chunks().size() && begin.world().equals(next.world())
                && new HashSet<>(begin.chunks()).equals(new HashSet<>(next.chunks()));
        }

        private int nextCapture() {
            for (int inspected = 0; inspected < 49; inspected++) {
                int index = cursor;
                cursor = (cursor + 1) % 49;
                if ((captured & (1L << index)) == 0) {
                    return index;
                }
            }
            return -1;
        }

        private void capture(int index, Column column) {
            int length = column.data().length;
            if (length > ClientViewProtocol.MAX_TRAVEL_CHUNK_BYTES || length > ClientViewProtocol.MAX_TRAVEL_BYTES - bytes) {
                throw new IllegalArgumentException("Native portal return snapshots exceed preparation bounds");
            }
            columns.add(column);
            bytes += length;
            captured |= 1L << index;
        }
    }

    private static final class PendingPreparation {
        private final ClientViewMessage.TravelBegin begin;
        private final ClientTravelChunks chunks;
        private final Map<ClientViewMessage.TravelCoordinate, Column> columns = new HashMap<>();
        private final long deadline;
        private ClientLevel level;
        private ClientTravelScene scene;
        private final Map<ClientViewMessage.TravelCoordinate, byte[]> payloads = new HashMap<>();
        private final Map<ClientViewMessage.TravelCoordinate, Integer> decoded = new HashMap<>();
        private final LongOpenHashSet changed = new LongOpenHashSet();
        private long drawnRevision;

        private PendingPreparation(ClientViewMessage.TravelBegin begin) {
            this.begin = begin;
            chunks = new ClientTravelChunks(begin);
            deadline = System.currentTimeMillis() + begin.expiresMillis();
        }
    }

    private static final class Prediction {
        private final ClientLevel source;
        private final ClientTravelMotion motion;
        private final ClientTravelMotion destination;
        private final Vec3 expectedArrival;
        private final long revision;
        private final LevelExtractor extractor;
        private final ProtocolInfo<ClientGamePacketListener> protocol;
        private long deadline = Long.MAX_VALUE;
        private final ArrayDeque<Runnable> packets = new ArrayDeque<>();
        private int retainedPacketBytes;
        private ChunkPos sourceCenter;
        private final List<ClientboundLevelChunkWithLightPacket> sourceColumns = new ArrayList<>(9);

        private Prediction(PredictionState state) {
            source = state.source();
            motion = state.motion();
            destination = state.destination();
            expectedArrival = state.expectedArrival();
            revision = state.revision();
            extractor = state.extractor();
            protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(state.connection().registryAccess()));
        }
    }

    private record PredictionState(ClientLevel source, ClientTravelMotion motion, ClientTravelMotion destination, Vec3 expectedArrival,
                                   long revision, LevelExtractor extractor, ClientPacketListener connection) {
    }

    private record Column(int x, int z, int revision, byte[] data) {
    }

    public record Construction(ClientLevel.ClientLevelData data, ResourceKey<Level> dimension, Holder<DimensionType> type,
                               LevelExtractor extractor, boolean debug, long seed, int seaLevel) {
    }
}
