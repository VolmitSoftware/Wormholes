package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.Pose;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftChunkPacketEncoding;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import art.arcane.wormholes.modded.client.render.ClientTravelScene;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import art.arcane.wormholes.modded.clientview.MinecraftPortalEnvironment;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelDataAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedEntityAccess;
import art.arcane.wormholes.network.client.ClientTravelWindow;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
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
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
import art.arcane.wormholes.network.client.TravelMessage;

public final class ClientPreparedTravel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long DECODE_NANOS = 2_000_000L;
    private static final int MAX_DECODE_COLUMNS = 16;
    private static final int MAX_DECODE_BYTES = 256 * 1024;
    private static final int PENDING_PACKET_OVERHEAD_BYTES = 1024;
    private static final int MAX_PENDING_BYTES = 8 << 20;
    private static final long CROSS_TIMEOUT_MILLIS = 2_000;
    private static final int MAX_RETAINED_WORLDS = 2;
    private static final double POSITION_TOLERANCE = 1.0E-3D;
    private static final float LOOK_TOLERANCE = 0.5F;
    private static final double CHECKPOINT_NUDGE = 0.001D;
    private static final boolean SODIUM = ClientPreparedTravel.class.getClassLoader()
        .getResource("net/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTrackerHolder.class") != null;
    private static final boolean IRIS = ClientPreparedTravel.class.getClassLoader()
        .getResource("net/irisshaders/iris/Iris.class") != null;
    private static final ThreadLocal<AppliedColumn> APPLIED_COLUMN = new ThreadLocal<>();
    private final Consumer<TravelMessage> sender;
    private final ResidentLevels residents;
    private final ClientTravelCache cache = new ClientTravelCache();
    private int sourceCapture;
    private boolean nativeCacheFailureReported;
    private boolean nativeDifferenceFailureReported;
    private SourcePreparation sourcePreparation;
    private RetainedWorld retainedDestination;
    private ClientLevel authoritativeDestination;
    private AuthoritativeArrival authoritativeArrival;
    private final LinkedHashMap<ClientLevel, RetainedWorld> retainedWorlds = new LinkedHashMap<>(4, 0.75F, true);
    private final Map<TravelMessage.TravelCoordinate, byte[]> payloads = new HashMap<>();
    private final ArrayDeque<Column> decoding = new ArrayDeque<>();
    private final Map<TravelMessage.TravelCoordinate, Integer> decoded = new HashMap<>();
    private final LongOpenHashSet changed = new LongOpenHashSet();
    private TravelMessage.TravelBegin begin;
    private TravelMessage.TravelCommit commit;
    private Aperture straddleAperture;
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
    private Pose beforePosition;
    private Arrival arrival;
    private ResidentColumns resident;

    public ClientPreparedTravel(Consumer<TravelMessage> sender, ResidentLevels residents) {
        this.sender = sender;
        this.residents = residents;
    }

    public ResidentLevels residents() {
        return residents;
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
        discardRetainedWorlds();
        sourceCapture = begin != null && !adopted && prediction == null ? 0 : Integer.MAX_VALUE;
    }

    public boolean pendingCrossing() {
        return prediction != null && !positionConfirmed;
    }

    public boolean suppressesMovement() {
        return pendingCrossing() && !prediction.seamless;
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
        boolean nativeReady = staged != null && ClientSodiumTerrain.ready(staged);
        if (acknowledgedRevision == 0 || staged == null || System.currentTimeMillis() >= deadline
            || !nativeReady && (!ClientPortalRenderer.instance().travelDrawable() || !ClientPortalRenderer.instance().travelReady())
            || (IRIS && (!IrisMain.ready(staged) || !ClientPortalRenderer.instance().travelSourceShaderReady()))) {
            declinePreparation();
            return false;
        }
        Pose source = ClientTravelMotion.capture(player);
        TravelMessage.TravelPose crossingPose = crossingPose(player, partial);
        Vec3d crossingFeet = new Vec3d(crossingPose.x(), crossingPose.y(), crossingPose.z());
        Pose destination = ClientTravelMotion.arrive(begin, source, crossingFeet);
        if (!covers(pose(destination))) {
            declinePreparation();
            return false;
        }
        ClientLevel sourceLevel = minecraft.level;
        OpticTransform toward = begin.destinationToSource().inverse();
        Vec3 expectedArrival = ClientTravelMotion.point(toward, new Vec3(crossingPose.x(), crossingPose.y(), crossingPose.z()));
        boolean seamless = seamless(begin);
        prediction = new Prediction(new PredictionState(sourceLevel, source, ClientTravelMotion.carry(player), destination, expectedArrival,
            acknowledgedRevision, ((PreparedLevelAccess) sourceLevel).wormholes$extractor(), minecraft.getConnection(), seamless));
        try {
            prediction.deadline = System.currentTimeMillis() + CROSS_TIMEOUT_MILLIS;
            sender.accept(new TravelMessage.TravelCross(begin.token(), begin.generation(), prediction.revision,
                crossingPose, vector(previous), vector(eye), source.bodyYaw(), source.headYaw()));
            ClientTravelMotion.Carry carried = prediction.carry.moved(source, destination, toward);
            if (seamless && staged != sourceLevel) {
                residents.beginCrossing(sourceLevel);
                attachPlayer(staged, destination, carried, true);
            } else if (seamless) {
                ClientTravelMotion.apply(player, destination);
                carried.restore(player);
            } else if (begin.sourceWorld().equals(begin.world().dimension())) {
                prepareSameWorld(sourceLevel, source, destination);
                ClientTravelMotion.apply(player, destination);
                carried.restore(player);
            } else {
                attachPlayer(staged, destination, carried, false);
            }
            ClientPortalRenderer.instance().transitionTravel(true);
            arrival = null;
            if (seamless) {
                StraddleTracker.Endpoint back = destinationEndpoint(begin);
                straddleAperture = back.aperture();
                StraddleTracker.register(player, StraddleTracker.create(back, sourceEndpoint(begin, begin.sourceGeometry().aperture()), sourceLevel,
                    ClientTravelMotion.vector(player.getEyePosition())));
            }
            previousCamera = seamless ? checkpoint(begin, toward, previous, eye) : ClientTravelMotion.point(toward, eye);
            return true;
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to predict prepared portal crossing", failure);
            clear(true);
            return false;
        }
    }

    public boolean deferWorldPacket(Packet<ClientGamePacketListener> packet, Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || residents.routing() || prediction != null && prediction.seamless) {
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
                clear(true);
                return false;
            }
            prediction.retainedPacketBytes += retainedBytes;
            prediction.packets.add(action);
            return true;
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to retain source-world packet during prepared crossing", failure);
            clear(true);
            return false;
        } finally {
            buffer.release();
        }
    }

    public boolean beginRespawn(ResourceKey<Level> dimension, boolean keepPlayer) {
        seamlessRespawn = keepPlayer && prediction != null && commit != null && pendingCrossing()
            && dimension.identifier().toString().equals(commit.destinationWorld());
        if (prediction != null && !seamlessRespawn) {
            clear(true);
        }
        if (!seamlessRespawn) {
            residents.clear(commit != null ? staged : null);
        }
        return seamlessRespawn;
    }

    public void endRespawn() {
        seamlessRespawn = false;
        authoritativeDestination = null;
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
            clear(true);
            return;
        }
        beforePosition = ClientTravelMotion.capture(Minecraft.getInstance().player);
    }

    public void receive(TravelMessage message) {
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
                case TravelMessage.TravelBegin value -> begin(value);
                case TravelMessage.TravelChunk value -> chunk(value);
                case TravelMessage.TravelReuse value -> reuse(value);
                case TravelMessage.TravelEnd value -> {
                    if (chunks != null) {
                        chunks.end(value);
                    }
                }
                case TravelMessage.TravelCommit value -> commit(value);
                case TravelMessage.TravelAccept value -> accept(value);
                case TravelMessage.RemoteLevelOpen value -> residents.open(value);
                case TravelMessage.RemoteLevelClose value -> residents.close(value);
                case TravelMessage.RoutedPacket value -> residents.route(value);
                case TravelMessage.TravelCancel value -> {
                    if (chunks != null && chunks.matches(value.token(), value.generation())) {
                        clear(false);
                    }
                }
                default -> { }
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to prepare authoritative portal travel", failure);
            clear(true);
        }
    }

    public void tick() {
        pruneRetainedWorlds();
        advanceAuthoritativeArrival();
        advanceArrival();
        if (resident != null && !resident.valid(Minecraft.getInstance().getConnection())) {
            resident = null;
        }
        if (sourcePreparation != null && System.currentTimeMillis() >= sourcePreparation.deadline) {
            retireSourcePreparation();
            sourceCapture = Integer.MAX_VALUE;
        }
        updateStraddle(Minecraft.getInstance().player);
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
            || minecraft.player.isDeadOrDying() || !predictedTerrainAvailable()
            || !covers(new TravelMessage.TravelPose(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                minecraft.player.getYRot(), minecraft.player.getXRot())))) {
            clear(true);
            return;
        }
        if (!adopted && prediction == null && (minecraft.level == null || !begin.sourceWorld().equals(minecraft.level.dimension().identifier().toString()))) {
            clear(true);
            return;
        }
        if (adopted) {
            if (minecraft.level != staged) {
                clear(true);
                return;
            }
            if (minecraft.player == null || !covers(positionConfirmed
                ? new TravelMessage.TravelPose(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                    minecraft.player.getYRot(), minecraft.player.getXRot()) : commit.arrival())) {
                clear(true);
                return;
            }
            if (!ClientSodiumTerrain.usesPreparedTerrain(staged)) {
                scene.advance();
            }
            advancePreparation();
            if (positionConfirmed) {
                mainCompiled = ClientSodiumTerrain.ready(staged) || ClientPortalRenderer.instance().travelMainReady();
            }
            completeLoad(connection);
            if (mainCompiled) {
                finishArrival();
            } else if (positionConfirmed && connection.hasClientLoaded() && pendingPreparation != null && pendingPreparation.level != null) {
                retainArrivalPreparation();
            }
            return;
        }
        if (begin.seamless()) {
            advanceSeamless();
            return;
        }
        captureSource();
        if (!decodePending()) {
            return;
        }
        long revision = chunks.completeRevision();
        if (!decoding.isEmpty() || decoded.size() != begin.chunks().size()) {
            prepareTerrain(staged, begin, false);
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
        ClientSodiumTerrain.Preparation terrain = prepareTerrain(staged, begin, true);
        if (!ClientSodiumTerrain.usesPreparedTerrain(staged)) {
            scene.advance();
        }
        advanceSourcePreparation();
        if (terrain != ClientSodiumTerrain.Preparation.PENDING
            && (terrain == ClientSodiumTerrain.Preparation.READY || ClientPortalRenderer.instance().travelReady()) && revision != 0
            && (!IRIS || ClientPortalRenderer.instance().travelSourceShaderReady())) {
            if (acknowledgedRevision != revision) {
                acknowledgedRevision = revision;
                sender.accept(new TravelMessage.TravelReady(begin.token(), begin.generation(), revision));
            }
        }
    }

    public ClientLevel adopt(Construction construction) {
        if (System.currentTimeMillis() >= deadline || commit == null || staged == null || adopted
            || (prediction == null ? acknowledgedRevision != commit.contentRevision() : prediction.revision != commit.contentRevision())
            || (prediction == null ? !ClientSodiumTerrain.ready(staged) && !ClientPortalRenderer.instance().travelReady()
                : !predictedTerrainAvailable() || !covers(commit.arrival()))
            || !matches(construction)) {
            RetainedWorld retained = retainedWorld(construction);
            if (retained != null) {
                return restoreAuthoritativeLevel(retained, construction);
            }
            if (prediction != null) {
                clear(true);
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
        RetainedWorld retained = new RetainedWorld(staged, Minecraft.getInstance().getConnection(), staged.registryAccess(), begin.world(), deadline,
            payloads, null);
        rememberRetainedWorld(retained);
        if (prediction == null) {
            authoritativeArrival = new AuthoritativeArrival(retained);
        }
        retainResidentColumns();
        ClientPortalRenderer.instance().transitionTravel(true);
        return staged;
    }

    public boolean attachRespawnLevel(ClientLevel destination) {
        if (authoritativeDestination != null && destination == authoritativeDestination) {
            if (Minecraft.getInstance().level != destination) {
                attachLevel(destination, true);
            }
            return true;
        }
        if (!adopted || destination != staged) {
            return false;
        }
        if (Minecraft.getInstance().level != destination) {
            attachLevel(destination, false);
        }
        return true;
    }

    public boolean deferLoadingScreen(Screen screen) {
        if (!(screen instanceof LevelLoadingScreen)) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (authoritativeArrival != null && authoritativeArrival.valid(minecraft)) {
            authoritativeArrival.screen = screen;
            if (adopted) {
                deferredScreen = screen;
            }
            return true;
        }
        if (!adopted || commit == null) {
            return false;
        }
        deferredScreen = screen;
        return true;
    }

    public boolean holdAuthoritativeFrame() {
        AuthoritativeArrival active = authoritativeArrival;
        if (prediction != null || active == null || active.positionConfirmed || System.currentTimeMillis() >= active.deadline) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (adopted && (positionConfirmed || commit == null || staged != minecraft.level || System.currentTimeMillis() >= deadline)) {
            return false;
        }
        return active.valid(minecraft) && minecraft.player != null && minecraft.gui.screen() == null && minecraft.gui.overlay() == null;
    }

    public void serverPosition() {
        Minecraft minecraft = Minecraft.getInstance();
        if (authoritativeArrival != null && authoritativeArrival.valid(minecraft) && minecraft.player != null) {
            if (adopted) {
                authoritativeArrival = null;
            } else {
                authoritativeArrival.positionConfirmed = true;
                advanceAuthoritativeArrival();
            }
        }
        if (!adopted || minecraft.level != staged || minecraft.player == null || commit == null) {
            return;
        }
        TravelMessage.TravelPose arrival = commit.arrival();
        Vec3 position = minecraft.player.position();
        if (position.distanceToSqr(new Vec3(arrival.x(), arrival.y(), arrival.z())) > 0.000001) {
            clear(true);
            return;
        }
        positionConfirmed = true;
        if (prediction != null && beforePosition != null) {
            Vec3 offset = position.subtract(prediction.expectedArrival);
            Pose reconciled = ClientTravelMotion.reconcile(beforePosition, ClientTravelMotion.vector(offset), prediction.destination.velocity(),
                commit.velocity());
            if (covers(pose(reconciled))) {
                ClientTravelMotion.apply(minecraft.player, reconciled);
            }
            beforePosition = null;
            prediction.packets.clear();
            prediction.sourceColumns.clear();
            prediction = null;
        }
    }

    public boolean managesVanillaPortal(ClientLevel level, BlockPos position) {
        Minecraft minecraft = Minecraft.getInstance();
        if (level == null || level != minecraft.level) {
            return false;
        }
        RetainedWorld retained = retainedWorlds.get(level);
        if (retained == null || !retained.valid(minecraft.getConnection())) {
            return false;
        }
        ApertureDescriptor aperture = retained.aperture();
        return aperture != null && aperture.parentPortalKey() == 0 && aperture.kind() == ApertureDescriptor.KIND_VANILLA_REPLACEMENT
            && aperture.containsCell(position.getX(), position.getY(), position.getZ());
    }

    public void discardManagedVanillaPortal(ClientLevel level, ApertureDescriptor geometry) {
        RetainedWorld retained = retainedWorlds.get(level);
        if (retained != null && geometry.sameContentSurface(retained.aperture())) {
            retainedWorlds.put(level, retained.withAperture(null));
        }
        if (retainedDestination != null && retainedDestination.level() == level && geometry.sameContentSurface(retainedDestination.aperture())) {
            retainedDestination = retainedDestination.withAperture(null);
        }
        if (pendingPreparation != null && pendingPreparation.retainedWorld != null && pendingPreparation.retainedWorld.level() == level
            && geometry.sameContentSurface(pendingPreparation.retainedWorld.aperture())) {
            pendingPreparation.retainedWorld = pendingPreparation.retainedWorld.withAperture(null);
        }
        if (sourcePreparation != null && sourcePreparation.level == level && geometry.sameContentSurface(sourcePreparation.aperture)) {
            sourcePreparation.aperture = null;
        }
    }

    public void blockChanged(Object world, BlockPos position) {
        if (world instanceof ClientLevel level) {
            sectionChanged(level, position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4);
        }
    }

    public void chunkChanged(ClientLevel level, int x, int z) {
        if (applyingColumn(level, x, z)) {
            return;
        }
        invalidateColumn(level, x, z);
        for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
            sectionChanged(level, x, y, z);
        }
    }

    public void sectionChanged(ClientLevel level, int x, int y, int z) {
        if (applyingColumn(level, x, z)) {
            return;
        }
        invalidateColumn(level, x, z);
        long section = SectionPos.asLong(x, y, z);
        ClientSodiumTerrain.dirty(level, section);
        invalidateSceneSection(level, section);
    }

    public void lightChanged(ClientLevel level, SectionPos position) {
        if (applyingColumn(level, position.x(), position.z())) {
            return;
        }
        invalidateColumn(level, position.x(), position.z());
        invalidateSceneSection(level, position.asLong());
    }

    private void invalidateSceneSection(ClientLevel level, long section) {
        SourcePreparation source = sourcePreparation;
        if (source != null && source.level == level && source.scene != null) {
            for (long key : source.scene.changedSection(section)) {
                ClientPortalRenderer.instance().invalidate(-3, key, true);
            }
        }
        if (arrival != null && level == arrival.level) {
            for (long key : arrival.scene.changedSection(section)) {
                ClientPortalRenderer.instance().invalidateArrival(key);
            }
        }
        if (level == staged && scene != null) {
            for (long key : scene.changedSection(section)) {
                ClientPortalRenderer.instance().invalidateTravel(key);
            }
        }
    }

    private void invalidateColumn(ClientLevel level, int x, int z) {
        TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(x, z);
        cache.invalidate(level.dimension().identifier().toString(), x, z);
        SourcePreparation source = sourcePreparation;
        if (source != null && source.level == level) {
            if (source.scene != null) {
                source.scene.invalidateColumn(x, z);
            }
            byte[] previous = source.payloads.remove(coordinate);
            if (previous != null) {
                source.bytes -= previous.length;
            }
            source.decoded.remove(coordinate);
            int index = source.begin.chunks().indexOf(coordinate);
            if (index >= 0 && source.captured.get(index)) {
                source.captured.clear(index);
                if (sourceCapture != Integer.MAX_VALUE) {
                    sourceCapture--;
                }
            }
        }
        if (level == staged) {
            payloads.remove(coordinate);
            if (scene != null) {
                scene.invalidateColumn(x, z);
            }
        }
        if (arrival != null && arrival.level == level) {
            arrival.scene.invalidateColumn(x, z);
        }
        if (resident != null && resident.level() == level) {
            resident.payloads().remove(coordinate);
        }
        RetainedWorld visited = retainedWorlds.get(level);
        if (visited != null) {
            visited.payloads().remove(coordinate);
        }
        if (retainedDestination != null && retainedDestination.level() == level) {
            retainedDestination.payloads().remove(coordinate);
        }
        if (pendingPreparation != null && pendingPreparation.retainedWorld != null
            && pendingPreparation.retainedWorld.level() == level) {
            pendingPreparation.retainedWorld.payloads().remove(coordinate);
        }
    }

    public void clear() {
        clear(true);
        discardRetainedWorlds();
        residents.clear();
    }

    private void clear(boolean clearArrival) {
        retainActualWorlds();
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        cache.bind(connection, connection == null ? null : connection.registryAccess());
        if (connection == null) {
            nativeCacheFailureReported = false;
            nativeDifferenceFailureReported = false;
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
        if (staged != null && staged != minecraft.level && !hasRetainedWorld(staged) && !residents.resident(staged)) {
            ClientSodiumTerrain.forget(staged);
        }
        discardPendingPreparation();
        if (clearArrival || resident != null && resident.level() != minecraft.level) {
            resident = null;
        }
        retireSourcePreparation();
        for (Map.Entry<ClientLevel, RetainedWorld> entry : retainedWorlds.entrySet()) {
            if (entry.getValue().payloads() == payloads) {
                entry.setValue(entry.getValue().withPayloads(new HashMap<>(payloads)));
            }
        }
        decoding.clear();
        retainedDestination = null;
        authoritativeDestination = null;
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

    private boolean deferPreparation(TravelMessage message) {
        if (message instanceof TravelMessage.TravelBegin next && adopted && !mainCompiled) {
            if (begin.world().dimension().equals(next.sourceWorld())
                && (pendingPreparation == null || !pendingPreparation.chunks.matches(next.token(), next.generation()))) {
                discardPendingPreparation();
                pendingPreparation = preparation(next);
            }
            return true;
        }
        if (pendingPreparation == null) {
            return false;
        }
        try {
            return switch (message) {
                case TravelMessage.TravelChunk value -> {
                    if (!pendingPreparation.chunks.matches(value.token(), value.generation())) {
                        yield false;
                    }
                    if (System.currentTimeMillis() >= pendingPreparation.deadline) {
                        discardPendingPreparation();
                        yield true;
                    }
                    byte[] data = pendingPreparation.chunks.accept(value);
                    if (data != null) {
                        cache.put(pendingPreparation.begin.world().dimension(), value.chunkX(), value.chunkZ(), data);
                        pendingPreparation.columns.put(new TravelMessage.TravelCoordinate(value.chunkX(), value.chunkZ()),
                            new Column(value.chunkX(), value.chunkZ(), value.revision(), data));
                    }
                    yield true;
                }
                case TravelMessage.TravelReuse value -> {
                    if (!pendingPreparation.chunks.matches(value.token(), value.generation())) {
                        yield false;
                    }
                    byte[] data = cache.get(pendingPreparation.begin.world().dimension(), value.chunkX(), value.chunkZ(), value.hash());
                    boolean available = data != null && pendingPreparation.chunks.reuse(value, data);
                    if (available) {
                        pendingPreparation.columns.put(new TravelMessage.TravelCoordinate(value.chunkX(), value.chunkZ()),
                            new Column(value.chunkX(), value.chunkZ(), value.revision(), data));
                    }
                    sender.accept(new TravelMessage.TravelCached(value.token(), value.generation(), value.chunkX(), value.chunkZ(),
                        value.revision(), value.hash(), available));
                    yield true;
                }
                case TravelMessage.TravelEnd value -> {
                    if (!pendingPreparation.chunks.matches(value.token(), value.generation())) {
                        yield false;
                    }
                    pendingPreparation.chunks.end(value);
                    yield true;
                }
                case TravelMessage.TravelCancel value -> {
                    if (!pendingPreparation.chunks.matches(value.token(), value.generation())) {
                        yield false;
                    }
                    discardPendingPreparation();
                    yield true;
                }
                case TravelMessage.TravelCommit value -> pendingPreparation.chunks.matches(value.token(), value.generation());
                default -> false;
            };
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to retain upcoming authoritative portal travel", failure);
            discardPendingPreparation();
            return true;
        }
    }

    private PendingPreparation preparation(TravelMessage.TravelBegin value) {
        PendingPreparation next = new PendingPreparation(value);
        if (value.seamless()) {
            return next;
        }
        SourcePreparation source = sourcePreparation;
        RetainedWorld retained = retainedWorld(value.world());
        if (retained != null) {
            cleanRetainedLevel(retained.level(), value.arrival());
            retained.level().getChunkSource().updateViewCenter((int) Math.floor(value.arrival().x()) >> 4,
                (int) Math.floor(value.arrival().z()) >> 4);
            next.level = retained.level();
            if (source != null && source.level == retained.level() && source.scene != null
                && new HashSet<>(source.begin.chunks()).equals(new HashSet<>(value.chunks()))) {
                source.scene.rebind(value);
                next.scene = source.scene;
            }
            Set<TravelMessage.TravelCoordinate> manifest = new HashSet<>(value.chunks());
            for (Map.Entry<TravelMessage.TravelCoordinate, byte[]> entry : retained.payloads().entrySet()) {
                if (manifest.contains(entry.getKey())) {
                    next.payloads.put(entry.getKey(), entry.getValue());
                    next.decoded.put(entry.getKey(), 0);
                }
            }
            next.retainedWorld = retained.withPayloads(next.payloads);
            rememberRetainedWorld(next.retainedWorld);
        }
        if (source != null) {
            retireSourcePreparation();
        }
        for (Column column : cachedColumns(value)) {
            next.columns.put(new TravelMessage.TravelCoordinate(column.x(), column.z()), column);
        }
        return next;
    }

    private void advancePreparation() {
        PendingPreparation next = pendingPreparation;
        if (next == null || next.begin.seamless()) {
            return;
        }
        if (System.currentTimeMillis() >= next.deadline) {
            discardPendingPreparation();
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
                TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(column.x(), column.z());
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
                prepareTerrain(next.level, next.begin, false);
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
            prepareTerrain(next.level, next.begin, true);
            if (!ClientSodiumTerrain.usesPreparedTerrain(next.level)) {
                next.scene.advance();
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to prepare upcoming authoritative portal travel", failure);
            discardPendingPreparation();
        }
    }

    private ClientSodiumTerrain.Preparation prepareTerrain(ClientLevel level, TravelMessage.TravelBegin value, boolean complete) {
        if (!complete) {
            Minecraft minecraft = Minecraft.getInstance();
            RetainedWorld retained = retainedWorlds.get(level);
            if (level == null || level == minecraft.level || retained == null || retained.level() != level
                || !retained.valid(minecraft.getConnection()) || !retained.world().equals(value.world())
                || !covers(value, level, value.arrival())) {
                return ClientSodiumTerrain.Preparation.PENDING;
            }
        }
        return !IRIS || IrisMain.prepare(level)
            ? ClientSodiumTerrain.prepare(level, value.environment(), travelCamera(value))
            : ClientSodiumTerrain.Preparation.PENDING;
    }

    private void retainArrivalPreparation() {
        PendingPreparation next = pendingPreparation;
        if (next == null || System.currentTimeMillis() >= next.deadline) {
            discardPendingPreparation();
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
        staged = next.level;
        adoptPreparation(next);
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
        if (System.currentTimeMillis() >= arrival.deadline || minecraft.player == null || !covers(arrival.begin, arrival.level, new TravelMessage.TravelPose(
            minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(), minecraft.player.getYRot(), minecraft.player.getXRot()))
            || !ClientSodiumTerrain.ready(arrival.level) && !ClientPortalRenderer.instance().arrivalDrawable()) {
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
        if (!ClientSodiumTerrain.usesPreparedTerrain(arrival.level)) {
            arrival.scene.advance();
        }
        if (ClientSodiumTerrain.ready(arrival.level) || ClientPortalRenderer.instance().arrivalMainReady()) {
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
        SourcePreparation retained = sourcePreparation;
        ResidentColumns retainedColumns = resident;
        sourcePreparation = null;
        clear(true);
        sourcePreparation = retained;
        resident = retainedColumns;
        sourceCapture = Integer.MAX_VALUE;
        if (next == null) {
            return;
        }
        try {
            if (next.begin.seamless()) {
                staged = seamlessLevel(next.begin);
                if (staged == null) {
                    declineSeamless(next.begin);
                    return;
                }
            } else if (next.level == null) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.getConnection() == null || minecraft.level == null
                    || !minecraft.level.dimension().identifier().toString().equals(next.begin.sourceWorld())) {
                    return;
                }
                staged = createLevel(next.begin);
            } else {
                staged = next.level;
            }
            adoptPreparation(next);
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to begin retained authoritative portal travel", failure);
            clear(true);
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
                clear(true);
                return false;
            }
            bytes += column.data().length;
        }
        return true;
    }

    private void begin(TravelMessage.TravelBegin value) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || minecraft.level == null
            || !minecraft.level.dimension().identifier().toString().equals(value.sourceWorld())) {
            return;
        }
        PendingPreparation next = preparation(value);
        clear(false);
        if (value.seamless()) {
            staged = seamlessLevel(value);
            if (staged == null) {
                declineSeamless(value);
                return;
            }
        } else {
            staged = next.level == null ? createLevel(value) : next.level;
        }
        cache.bind(connection, connection.registryAccess());
        adoptPreparation(next);
    }

    private static void prepareSeamlessSource(Minecraft minecraft) {
        if (minecraft.player == null || !(minecraft.level instanceof ClientTravelWorld world) || world.wormholes$travelWorld() == null) {
            return;
        }
        ClientPortalRenderer.instance().prepareTravelSourceEnvironment(MinecraftPortalEnvironment.capture(minecraft.level,
            vector(minecraft.player.getEyePosition()), OpticTransform.IDENTITY, world.wormholes$travelWorld().flat()));
    }

    private void adoptPreparation(PendingPreparation next) {
        begin = next.begin;
        sourceCapture = 0;
        chunks = next.chunks;
        deadline = next.deadline;
        scene = next.scene;
        drawnRevision = next.drawnRevision;
        retainedDestination = next.retainedWorld == null ? null : next.retainedWorld.withPayloads(payloads);
        payloads.putAll(next.payloads);
        decoded.putAll(next.decoded);
        changed.addAll(next.changed);
        for (Column column : next.columns.values()) {
            TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(column.x(), column.z());
            if (!decoded.containsKey(coordinate) || decoded.get(coordinate) < column.revision()) {
                decoding.add(column);
            }
        }
        if (scene != null) {
            ClientPortalRenderer.instance().prepareTravel(scene, arrivalCamera(begin.arrival()));
        }
        if (begin.seamless()) {
            sourceCapture = Integer.MAX_VALUE;
            prepareSeamlessSource(Minecraft.getInstance());
        }
    }

    private List<Column> cachedColumns(TravelMessage.TravelBegin value) {
        List<Column> retained = new ArrayList<>(value.chunks().size());
        for (TravelMessage.TravelCoordinate coordinate : value.chunks()) {
            byte[] data = cache.peek(value.world().dimension(), coordinate.x(), coordinate.z());
            if (data != null) {
                retained.add(new Column(coordinate.x(), coordinate.z(), 0, data));
            }
        }
        return retained;
    }

    private ClientLevel createLevel(TravelMessage.TravelBegin value) {
        return ResidentLevel.create(value.world(), value.environment(),
            new TravelMessage.TravelCoordinate((int) Math.floor(value.arrival().x()) >> 4, (int) Math.floor(value.arrival().z()) >> 4));
    }

    private void chunk(TravelMessage.TravelChunk fragment) {
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

    private void reuse(TravelMessage.TravelReuse proof) {
        if (chunks == null || adopted || !chunks.matches(proof.token(), proof.generation())) {
            return;
        }
        byte[] data = cache.get(begin.world().dimension(), proof.chunkX(), proof.chunkZ(), proof.hash());
        boolean available = data != null && chunks.reuse(proof, data);
        if (available && decoded.getOrDefault(new TravelMessage.TravelCoordinate(proof.chunkX(), proof.chunkZ()), 0) < proof.revision()) {
            queueColumn(new Column(proof.chunkX(), proof.chunkZ(), proof.revision(), data));
        }
        sender.accept(new TravelMessage.TravelCached(proof.token(), proof.generation(), proof.chunkX(), proof.chunkZ(),
            proof.revision(), proof.hash(), available));
    }

    private void queueColumn(Column column) {
        decoding.removeIf(previous -> previous.x() == column.x() && previous.z() == column.z() && previous.revision() <= column.revision());
        decoding.add(column);
    }

    public boolean receiveNativeChunk(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        WormholesClient client = WormholesClient.instance();
        if (level == null || client == null || !client.session().active()
            || !client.session().has(ViewStreamCapability.PREPARED_TRAVEL_CACHE) || client.session().has(ViewStreamCapability.SEAMLESS_TRAVEL)) {
            return false;
        }
        TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(packet.x(), packet.z());
        SourcePreparation source = sourcePreparation;
        boolean sourceColumn = source != null && source.level == level && source.begin.chunks().contains(coordinate);
        boolean destination = begin != null && begin.world().dimension().equals(level.dimension().identifier().toString())
            && begin.chunks().contains(coordinate);
        boolean residentColumn = resident != null && resident.level() == level;
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return false;
        }
        if (!sourceColumn && begin != null && begin.sourceWorld().equals(level.dimension().identifier().toString())) {
            int radius = ClientTravelWindow.radius(((PreparedPacketAccess) connection).wormholes$chunkRadius());
            sourceColumn = Math.abs((long) packet.x() - (begin.sourceGeometry().originX() >> 4)) <= radius
                && Math.abs((long) packet.z() - (begin.sourceGeometry().originZ() >> 4)) <= radius;
        }
        if (!sourceColumn && !destination && !residentColumn) {
            return false;
        }
        cache.bind(connection, connection.registryAccess());
        try {
            byte[] data = encodeNativeChunk(level, packet);
            byte[] installed = residentColumn && resident.valid(connection) ? resident.payloads().get(coordinate) : null;
            LevelChunk physical = level.getChunkSource().getChunk(packet.x(), packet.z(), FULL, false);
            if (installed == null && physical != null && residentColumn && resident.valid(connection)) {
                installed = encodeNativeChunk(level, new ClientboundLevelChunkWithLightPacket(physical, level.getLightEngine(), null, null));
                resident.remember(coordinate, installed);
            }
            boolean unchanged = Arrays.equals(installed, data) && physical != null;
            cache.put(level.dimension().identifier().toString(), packet.x(), packet.z(), data);
            return unchanged;
        } catch (RuntimeException failure) {
            if (!nativeCacheFailureReported) {
                nativeCacheFailureReported = true;
                LOGGER.warn("Unable to retain native portal return chunk packets", failure);
            }
            return false;
        }
    }

    public LevelChunk replaceNativeColumn(ClientLevel level, int x, int z, Supplier<LevelChunk> replacement) {
        return applyNativeColumn(new AppliedColumn(level, x, z), replacement);
    }

    public Runnable nativeLightUpdate(ClientLevel level, int x, int z, Runnable update) {
        return () -> {
            if (!nativeResidentColumn(level, x, z)) {
                update.run();
                return;
            }
            AppliedColumn column = new AppliedColumn(level, x, z);
            column.lightChanges = new LongOpenHashSet();
            invalidateColumn(level, x, z);
            try {
                applyColumn(column, () -> {
                    update.run();
                    return null;
                });
            } catch (RuntimeException | Error failure) {
                dirtyNativeColumn(column);
                throw failure;
            }
            try {
                LongIterator sections = column.lightChanges.iterator();
                while (sections.hasNext()) {
                    dirtyNativeSection(column, sections.nextLong());
                }
            } catch (RuntimeException failure) {
                reportNativeDifferenceFailure(failure);
                dirtyNativeColumn(column);
            }
        };
    }

    private <T> T applyNativeColumn(AppliedColumn column, Supplier<T> update) {
        ClientLevel level = column.level();
        if (!nativeResidentColumn(level, column.x(), column.z())) {
            return update.get();
        }
        ClientTravelSectionState[] before;
        try {
            before = captureColumn(column, ColumnPhase.BLOCKS);
        } catch (RuntimeException failure) {
            reportNativeDifferenceFailure(failure);
            return update.get();
        }
        invalidateColumn(level, column.x(), column.z());
        T result;
        try {
            result = applyColumn(column, update);
        } catch (RuntimeException failure) {
            dirtyNativeColumn(column);
            throw failure;
        }
        try {
            compareColumn(column, ColumnPhase.BLOCKS, before, section -> dirtyNativeSection(column, section));
        } catch (RuntimeException failure) {
            reportNativeDifferenceFailure(failure);
            dirtyNativeColumn(column);
        }
        return result;
    }

    private static void dirtyNativeSection(AppliedColumn column, long section) {
        int y = SectionPos.y(section);
        if (!ClientSodiumTerrain.handlesMainUpdates(column.level())) {
            column.level().setSectionDirtyWithNeighbors(column.x(), y, column.z());
        }
        WormholesClient.localSectionChanged(column.level(), column.x(), y, column.z());
    }

    private boolean nativeResidentColumn(ClientLevel level, int x, int z) {
        Minecraft minecraft = Minecraft.getInstance();
        return level == minecraft.level && resident != null && resident.level() == level && resident.valid(minecraft.getConnection())
            && level.registryAccess() == resident.registry() && level.getChunkSource().getChunk(x, z, FULL, false) != null;
    }

    private static void dirtyNativeColumn(AppliedColumn column) {
        ClientLevel level = column.level();
        level.setSectionRangeDirty(column.x() - 1, level.getMinSectionY(), column.z() - 1,
            column.x() + 1, level.getMaxSectionY(), column.z() + 1);
        WormholesClient.localChunkChanged(level, column.x(), column.z());
    }

    private void reportNativeDifferenceFailure(RuntimeException failure) {
        if (!nativeDifferenceFailureReported) {
            nativeDifferenceFailureReported = true;
            LOGGER.warn("Unable to compare retained native portal column updates", failure);
        }
    }

    private static ClientTravelSectionState[] captureColumn(AppliedColumn column, ColumnPhase phase) {
        ClientLevel level = column.level();
        int minY = level.getMinSectionY() - 1;
        ClientTravelSectionState[] states = new ClientTravelSectionState[level.getSectionsCount() + 2];
        for (int index = 0; index < states.length; index++) {
            states[index] = phase.capture(column, minY + index);
        }
        return states;
    }

    private static void compareColumn(AppliedColumn column, ColumnPhase phase, ClientTravelSectionState[] before, LongConsumer changed) {
        int minY = column.level().getMinSectionY() - 1;
        for (int index = 0; index < before.length; index++) {
            if (before[index] == null) {
                continue;
            }
            int y = minY + index;
            ClientTravelSectionState after = phase.capture(column, y);
            if (!before[index].same(after)) {
                changed.accept(SectionPos.asLong(column.x(), y, column.z()));
            }
        }
    }

    private void retainResidentColumns() {
        if (!begin.sourceWorld().equals(begin.world().dimension())) {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            resident = new ResidentColumns(staged, connection, connection.registryAccess(), deadline, new HashMap<>(payloads));
        }
    }

    private void discardPendingPreparation() {
        if (pendingPreparation != null && pendingPreparation.level != null
            && pendingPreparation.level != Minecraft.getInstance().level && pendingPreparation.level != staged
            && !hasRetainedWorld(pendingPreparation.level)) {
            ClientSodiumTerrain.forget(pendingPreparation.level);
        }
        pendingPreparation = null;
    }

    private static byte[] encodeNativeChunk(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        return MinecraftChunkPacketEncoding.encode(level.registryAccess(), packet);
    }

    private void captureSource() {
        Minecraft minecraft = Minecraft.getInstance();
        if (prediction != null || minecraft.level == null || minecraft.player == null || sourceCapture == Integer.MAX_VALUE) {
            return;
        }
        try {
            ClientLevel level = minecraft.level;
            if (sourcePreparation == null) {
                sourcePreparation = new SourcePreparation(sourceBegin(level, minecraft.player));
                sourcePreparation.level = level;
                sourcePreparation.connection = minecraft.getConnection();
                sourcePreparation.registry = level.registryAccess();
                sourceCapture = 0;
                seedSourcePreparation(sourcePreparation);
                ClientPortalRenderer.instance().prepareTravelSourceEnvironment(sourcePreparation.begin.environment());
                rememberRetainedWorld(new RetainedWorld(level, sourcePreparation.connection, sourcePreparation.registry,
                    sourcePreparation.begin.world(), sourcePreparation.deadline, sourcePreparation.payloads, sourcePreparation.aperture));
                ClientSodiumTerrain.prepare(level, sourcePreparation.begin.environment(), arrivalCamera(sourcePreparation.begin.arrival()));
            }
            SourcePreparation source = sourcePreparation;
            if (source.level != level || sourceCapture >= source.begin.chunks().size()) {
                return;
            }
            long deadline = System.nanoTime() + DECODE_NANOS;
            int bytes = 0;
            int captured = 0;
            for (int inspected = 0; inspected < source.begin.chunks().size() && captured < MAX_DECODE_COLUMNS; inspected++) {
                if (inspected > 0 && (System.nanoTime() >= deadline || bytes >= MAX_DECODE_BYTES)) {
                    break;
                }
                int index = source.nextCapture();
                if (index < 0) {
                    break;
                }
                TravelMessage.TravelCoordinate coordinate = source.begin.chunks().get(index);
                LevelChunk chunk = level.getChunkSource().getChunk(coordinate.x(), coordinate.z(), FULL, false);
                if (chunk == null) {
                    continue;
                }
                String world = level.dimension().identifier().toString();
                byte[] data = encodeNativeChunk(level, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                cache.put(world, coordinate.x(), coordinate.z(), data);
                source.capture(index, new Column(coordinate.x(), coordinate.z(), 0, data));
                sourceCapture++;
                captured++;
                bytes += data.length;
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to capture native portal return snapshots", failure);
            retireSourcePreparation();
            sourceCapture = Integer.MAX_VALUE;
        }
    }

    private void seedSourcePreparation(SourcePreparation source) {
        RetainedWorld retained = retainedWorlds.get(source.level);
        if (retained == null || !retained.valid(source.connection) || !retained.world().equals(source.begin.world())) {
            return;
        }
        for (int index = 0; index < source.begin.chunks().size(); index++) {
            TravelMessage.TravelCoordinate coordinate = source.begin.chunks().get(index);
            byte[] installed = retained.payloads().get(coordinate);
            if (installed != null && source.level.getChunkSource().getChunk(coordinate.x(), coordinate.z(), FULL, false) != null) {
                source.capture(index, new Column(coordinate.x(), coordinate.z(), 0, installed));
                sourceCapture++;
            }
        }
    }

    private TravelMessage.TravelBegin sourceBegin(ClientLevel level, LocalPlayer player) {
        TravelMessage.TravelWorld world = ((ClientTravelWorld) level).wormholes$travelWorld();
        int centerX = begin.sourceGeometry().originX() >> 4;
        int centerZ = begin.sourceGeometry().originZ() >> 4;
        int radius = ClientTravelWindow.radius(((PreparedPacketAccess) Minecraft.getInstance().getConnection()).wormholes$chunkRadius());
        List<TravelMessage.TravelCoordinate> manifest = ClientTravelWindow.coordinates(centerX, centerZ, radius);
        Vec3 eye = player.getEyePosition();
        return new TravelMessage.TravelBegin(begin.token(), begin.generation(), begin.sourcePortal(), begin.sourceWorld(),
            begin.sourceGeometry(), OpticTransform.IDENTITY, world,
            new TravelMessage.TravelPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()), manifest,
            MinecraftPortalEnvironment.capture(level, vector(eye), OpticTransform.IDENTITY, world.flat()), begin.expiresMillis(), TravelMessage.ArrivalRules.FRAME, false, 0, false);
    }

    private void advanceSourcePreparation() {
        SourcePreparation source = sourcePreparation;
        if (source == null || source.level == null || source.decoded.size() != source.begin.chunks().size()) {
            return;
        }
        try {
            if (source.scene == null) {
                source.scene = new ClientTravelScene(source.level, source.begin);
                source.scene.nativeColumns(source.payloads);
                ClientPortalRenderer.instance().prepareTravelSource(source.scene);
            }
            if (source.changed) {
                source.scene.nativeColumns(source.payloads);
                source.changed = false;
            }
            if (!ClientSodiumTerrain.usesPreparedTerrain(staged)
                && (pendingPreparation == null || !ClientSodiumTerrain.usesPreparedTerrain(pendingPreparation.level))) {
                source.scene.advance();
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to prepare native portal return snapshots", failure);
            retireSourcePreparation();
            sourceCapture = Integer.MAX_VALUE;
        }
    }

    private void retireSourcePreparation() {
        ClientPortalRenderer.instance().retireTravelSource();
        if (sourcePreparation != null && sourcePreparation.level != Minecraft.getInstance().level
            && !hasRetainedWorld(sourcePreparation.level)) {
            ClientSodiumTerrain.forget(sourcePreparation.level);
        }
        sourcePreparation = null;
    }

    private static void cleanRetainedLevel(ClientLevel level, TravelMessage.TravelPose arrival) {
        PreparedChunkColumns storage = (PreparedChunkColumns) level.getChunkSource();
        int centerX = (int) Math.floor(arrival.x()) >> 4;
        int centerZ = (int) Math.floor(arrival.z()) >> 4;
        int radius = storage.wormholes$radius();
        AtomicReferenceArray<LevelChunk> columns = storage.wormholes$columns();
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk column = columns.get(index);
            if (column != null && (Math.abs((long) column.getPos().x() - centerX) > radius
                || Math.abs((long) column.getPos().z() - centerZ) > radius)) {
                level.getChunkSource().drop(column.getPos());
            }
        }
        discardRetainedUpdates(level);
    }

    private static void discardRetainedUpdates(ClientLevel level) {
        ((PreparedLevelAccess) level).wormholes$lightUpdates().clear();
        ResidentLevel.discardEntities(level);
    }

    private static void decodeChanged(ClientLevel level, ClientTravelScene scene,
                                      Map<TravelMessage.TravelCoordinate, Integer> decoded, LongOpenHashSet changed,
                                      Map<TravelMessage.TravelCoordinate, byte[]> payloads, Column column) {
        TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(column.x(), column.z());
        if (Arrays.equals(payloads.get(coordinate), column.data())) {
            decoded.put(coordinate, column.revision());
            return;
        }
        decode(level, scene, decoded, changed, column);
        payloads.put(coordinate, column.data());
    }

    private static void decode(ClientLevel staged, ClientTravelScene scene,
                               Map<TravelMessage.TravelCoordinate, Integer> decoded, LongOpenHashSet changed, Column column) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(column.data()), staged.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
            if (buffer.isReadable() || packet.x() != column.x() || packet.z() != column.z()) {
                throw new IllegalArgumentException("Prepared travel native chunk does not match its envelope");
            }
            AppliedColumn applied = new AppliedColumn(staged, packet.x(), packet.z());
            ClientTravelSectionState[] before = scene == null
                && staged.getChunkSource().getChunk(packet.x(), packet.z(), FULL, false) == null
                ? null : captureColumn(applied, ColumnPhase.FULL);
            applyPreparedChunk(staged, packet);
            TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(column.x(), column.z());
            decoded.put(coordinate, column.revision());
            if (before == null) {
                for (int y = staged.getMinSectionY(); y <= staged.getMaxSectionY(); y++) {
                    ClientSodiumTerrain.dirty(staged, SectionPos.asLong(packet.x(), y, packet.z()));
                }
            } else {
                compareColumn(applied, ColumnPhase.FULL, before, section -> {
                    changed.add(section);
                    ClientSodiumTerrain.dirty(staged, section);
                });
            }
        } finally {
            buffer.release();
        }
    }

    private void prepareSameWorld(ClientLevel source, Pose original, Pose destination) {
        boolean missing = false;
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
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
        int sourceX = (int) Math.floor(original.position().x()) >> 4;
        int sourceZ = (int) Math.floor(original.position().z()) >> 4;
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
        source.getChunkSource().updateViewCenter((int) Math.floor(destination.position().x()) >> 4,
            (int) Math.floor(destination.position().z()) >> 4);
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
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
        if (!applyingColumn(level, packet.x(), packet.z())) {
            level.setSectionRangeDirty(packet.x() - 1, level.getMinSectionY(), packet.z() - 1,
                packet.x() + 1, level.getMaxSectionY(), packet.z() + 1);
        }
        lighting.runLightUpdates();
        if (SODIUM) {
            SodiumChunks.lightReady(level, packet.x(), packet.z());
        }
    }

    private static void applyPreparedChunk(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        applyColumn(new AppliedColumn(level, packet.x(), packet.z()), () -> {
            applyChunk(level, packet);
            return null;
        });
    }

    private static <T> T applyColumn(AppliedColumn column, Supplier<T> update) {
        AppliedColumn previous = APPLIED_COLUMN.get();
        APPLIED_COLUMN.set(column);
        try {
            return update.get();
        } finally {
            if (previous == null) {
                APPLIED_COLUMN.remove();
            } else {
                APPLIED_COLUMN.set(previous);
            }
        }
    }

    public static boolean applyingColumn(ClientLevel level, int x, int z) {
        AppliedColumn column = APPLIED_COLUMN.get();
        return column != null && column.level() == level && column.x() == x && column.z() == z;
    }

    public static boolean applyingNativeLight(ClientLevel level, int x, int z) {
        AppliedColumn column = APPLIED_COLUMN.get();
        return column != null && column.lightChanges != null && column.level() == level && column.x() == x && column.z() == z;
    }

    public static boolean applyingNativeLight(LightChunkGetter source, long section) {
        AppliedColumn column = APPLIED_COLUMN.get();
        return column != null && column.lightChanges != null && column.level().getChunkSource() == source
            && column.x() == SectionPos.x(section) && column.z() == SectionPos.z(section);
    }

    public static void nativeLightSectionChanged(LightChunkGetter source, long section) {
        if (applyingNativeLight(source, section)) {
            APPLIED_COLUMN.get().lightChanges.add(section);
        }
    }

    private void declinePreparation() {
        try {
            sender.accept(new TravelMessage.TravelCancel(begin.token(), begin.generation()));
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to decline unready prepared portal crossing", failure);
        } finally {
            clear(false);
        }
    }

    private void advanceSeamless() {
        long revision = chunks.endRevision();
        if (revision == 0 || !seamlessWindow()) {
            return;
        }
        if (scene == null) {
            scene = new ClientTravelScene(staged, begin);
            ClientPortalRenderer.instance().prepareTravel(scene, arrivalCamera(begin.arrival()));
        }
        drawnRevision = revision;
        ClientSodiumTerrain.Preparation terrain = prepareTerrain(staged, begin, true);
        if (!ClientSodiumTerrain.usesPreparedTerrain(staged)) {
            scene.advance();
        }
        if (terrain != ClientSodiumTerrain.Preparation.PENDING
            && (terrain == ClientSodiumTerrain.Preparation.READY || ClientPortalRenderer.instance().travelReady())
            && (!IRIS || ClientPortalRenderer.instance().travelSourceShaderReady()) && acknowledgedRevision != revision) {
            acknowledgedRevision = revision;
            sender.accept(new TravelMessage.TravelReady(begin.token(), begin.generation(), revision));
        }
    }

    private boolean seamlessWindow() {
        if (!begin.resident()) {
            return covers(begin.arrival());
        }
        for (TravelMessage.TravelCoordinate coordinate : begin.chunks()) {
            if (staged.getChunkSource().getChunk(coordinate.x(), coordinate.z(), FULL, false) == null) {
                return false;
            }
        }
        return true;
    }

    private boolean seamless(TravelMessage.TravelBegin value) {
        if (value == null || !value.seamless()) {
            return false;
        }
        WormholesClient client = WormholesClient.instance();
        return client == null || client.session().has(ViewStreamCapability.SEAMLESS_TRAVEL);
    }

    private ClientLevel seamlessLevel(TravelMessage.TravelBegin value) {
        return value.resident() ? residents.level(value.levelHandle()) : Minecraft.getInstance().level;
    }

    private void declineSeamless(TravelMessage.TravelBegin value) {
        sender.accept(new TravelMessage.TravelCancel(value.token(), value.generation()));
        clear(false);
    }

    private void accept(TravelMessage.TravelAccept value) {
        Prediction current = prediction;
        if (current == null || !current.seamless || positionConfirmed || chunks == null || !chunks.matches(value.token(), value.generation())
            || current.revision != value.contentRevision()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        adopted = true;
        positionConfirmed = true;
        prediction = null;
        current.packets.clear();
        residents.endCrossing(true);
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.dropProjectedEntities(begin.sourceGeometry());
        }
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        TravelMessage.TravelPose authoritative = value.pose();
        Vec3d offset = new Vec3d(authoritative.x() - current.expectedArrival.x, authoritative.y() - current.expectedArrival.y,
            authoritative.z() - current.expectedArrival.z);
        float yaw = Mth.wrapDegrees(authoritative.yaw() - current.destination.yaw());
        float pitch = authoritative.pitch() - current.destination.pitch();
        Pose reconciled = ClientTravelMotion.reconcile(ClientTravelMotion.capture(player), offset, current.destination.velocity(), value.velocity());
        if (offset.lengthSquared() > POSITION_TOLERANCE * POSITION_TOLERANCE || Math.abs(yaw) > LOOK_TOLERANCE || Math.abs(pitch) > LOOK_TOLERANCE) {
            LOGGER.warn("Seamless crossing corrected by the server: offset {} yaw {} pitch {}", offset, yaw, pitch);
            reconciled = ClientTravelMotion.turned(reconciled, yaw, pitch);
        }
        ClientTravelMotion.apply(player, reconciled);
    }

    private void updateStraddle(LocalPlayer player) {
        if (player == null || prediction != null) {
            return;
        }
        boolean preparing = begin != null && !adopted && staged != null && seamless(begin);
        if (!preparing && straddleAperture == null) {
            StraddleTracker.clear(player);
            return;
        }
        Box stretched = StraddleTracker.stretched(box(player.getBoundingBox()), ClientTravelMotion.vector(player.getDeltaMovement()),
            new Vec3d(player.xo - player.getX(), player.yo - player.getY(), player.zo - player.getZ()));
        if (preparing) {
            StraddleTracker.Straddle straddle = straddle(begin, staged, stretched, ClientTravelMotion.vector(player.getEyePosition()));
            straddleAperture = straddle == null ? null : begin.sourceGeometry().aperture();
            if (straddle == null) {
                StraddleTracker.clear(player);
            } else {
                StraddleTracker.register(player, straddle);
            }
            return;
        }
        if (!StraddleTracker.qualifies(stretched, straddleAperture)) {
            straddleAperture = null;
            StraddleTracker.clear(player);
        }
    }

    static StraddleTracker.Straddle straddle(TravelMessage.TravelBegin value, Level destination, Box stretched, Vec3d eye) {
        ApertureCells aperture = value.sourceGeometry().aperture();
        if (!StraddleTracker.qualifies(stretched, aperture)) {
            return null;
        }
        return StraddleTracker.create(sourceEndpoint(value, aperture), destinationEndpoint(value), destination, eye);
    }

    static StraddleTracker.Endpoint sourceEndpoint(TravelMessage.TravelBegin value, Aperture aperture) {
        ApertureDescriptor geometry = value.sourceGeometry();
        return new StraddleTracker.Endpoint(aperture, geometry.frame(), planePoint(geometry));
    }

    static StraddleTracker.Endpoint destinationEndpoint(TravelMessage.TravelBegin value) {
        ApertureDescriptor geometry = value.sourceGeometry();
        OpticTransform toward = value.destinationToSource().inverse();
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(toward.box(geometry.apertureArea()));
        Frame exit = ClientTravelMotion.exitFrame(geometry.frame().view(geometry.frontSide()), toward, geometry.frontSide());
        return new StraddleTracker.Endpoint(aperture, exit, toward.point(planePoint(geometry)));
    }

    private static Vec3d planePoint(ApertureDescriptor geometry) {
        Vec3d center = geometry.apertureArea().center();
        double plane = geometry.planeCoordinate();
        return switch (geometry.facingDirection().getAxis()) {
            case X -> new Vec3d(plane, center.y(), center.z());
            case Y -> new Vec3d(center.x(), plane, center.z());
            case Z -> new Vec3d(center.x(), center.y(), plane);
        };
    }

    private static Box box(AABB bounds) {
        return new Box(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, bounds.minZ, bounds.maxZ);
    }

    private static Vec3 checkpoint(TravelMessage.TravelBegin value, OpticTransform toward, Vec3 previous, Vec3 eye) {
        ApertureDescriptor geometry = value.sourceGeometry();
        double before = geometry.signedDistance(previous.x, previous.y, previous.z);
        double after = geometry.signedDistance(eye.x, eye.y, eye.z);
        Vec3 crossing = before == after ? eye : previous.lerp(eye, before / (before - after));
        Vec3 motion = ClientTravelMotion.position(toward.vector(ClientTravelMotion.vector(eye.subtract(previous))));
        Vec3 mapped = ClientTravelMotion.point(toward, crossing);
        return motion.lengthSqr() == 0.0D ? mapped : mapped.add(motion.normalize().scale(CHECKPOINT_NUDGE));
    }

    private boolean predictedTerrainAvailable() {
        Minecraft minecraft = Minecraft.getInstance();
        return prediction != null && prediction.connection == minecraft.getConnection() && staged != null && minecraft.level == staged
            && (ClientSodiumTerrain.handlesMainUpdates(staged) && ClientSodiumTerrain.usesPreparedTerrain(staged)
                || ClientPortalRenderer.instance().travelDrawable());
    }

    private void commit(TravelMessage.TravelCommit value) {
        if (System.currentTimeMillis() >= deadline || chunks == null || !chunks.matches(value.token(), value.generation())
            || (prediction == null ? acknowledgedRevision != value.contentRevision() : prediction.revision != value.contentRevision())
            || (prediction == null ? revision(chunks, begin) != value.contentRevision() : prediction.revision != value.contentRevision())
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

    private void attachPlayer(ClientLevel destination, Pose pose, ClientTravelMotion.Carry carry, boolean seamless) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level != null) {
            minecraft.level.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        }
        PreparedEntityAccess access = (PreparedEntityAccess) player;
        access.wormholes$level(destination);
        access.wormholes$restore();
        ClientTravelMotion.apply(player, pose);
        carry.restore(player);
        ((PreparedLevelAccess) destination).wormholes$extractor(minecraft.levelExtractor);
        if (seamless) {
            PreparedPacketAccess connection = (PreparedPacketAccess) minecraft.getConnection();
            connection.wormholes$level(destination);
            connection.wormholes$data(destination.getLevelData());
            residents.activate(() -> attachLevel(destination, false));
        } else {
            attachLevel(destination, false);
        }
        destination.addEntity(player);
        minecraft.setCameraEntity(player);
    }

    private static void attachLevel(ClientLevel destination, boolean authoritative) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.level != destination) {
            ((PreparedLevelAccess) minecraft.level).wormholes$extractor(new PreparedLevelExtractor(minecraft));
        }
        try (ClientSodiumTerrain.Handoff ignored = authoritative
            ? ClientSodiumTerrain.authoritativeHandoff(destination) : ClientSodiumTerrain.handoff(destination)) {
            if (IRIS) {
                IrisMain.attach(minecraft, destination, authoritative);
            } else {
                minecraft.setLevel(destination);
            }
        }
    }

    private void rollback() {
        if (prediction == null) {
            return;
        }
        Prediction previous = prediction;
        prediction = null;
        if (previous.seamless) {
            residents.endCrossing(false);
            straddleAperture = null;
            if (Minecraft.getInstance().player != null) {
                StraddleTracker.clear(Minecraft.getInstance().player);
            }
        }
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
                ClientTravelMotion.apply(Minecraft.getInstance().player, previous.motion);
                previous.carry.restore(Minecraft.getInstance().player);
            } else if (Minecraft.getInstance().level == staged) {
                if (previous.source == staged) {
                    ClientTravelMotion.apply(Minecraft.getInstance().player, previous.motion);
                    previous.carry.restore(Minecraft.getInstance().player);
                } else {
                    attachPlayer(previous.source, previous.motion, previous.carry, previous.seamless);
                }
            }
        }
        if (commit == null && begin != null && Minecraft.getInstance().getConnection() != null) {
            sender.accept(new TravelMessage.TravelCancel(begin.token(), begin.generation()));
            for (Runnable packet : previous.packets) {
                packet.run();
            }
        }
        previous.packets.clear();
        previous.sourceColumns.clear();
    }

    static boolean crossed(ApertureDescriptor geometry, Vec3 previous, Vec3 current) {
        double side = geometry.frontSide() ? 1 : -1;
        double before = geometry.signedDistance(previous.x, previous.y, previous.z) * side;
        double after = geometry.signedDistance(current.x, current.y, current.z) * side;
        if (before <= 0 || after > 0) {
            return false;
        }
        Vec3 intersection = previous.lerp(current, before / (before - after));
        Frame frame = Frame.canonical(geometry.facingDirection());
        int columnAxis = ApertureDescriptor.axisOf(frame.getRight());
        int rowAxis = ApertureDescriptor.axisOf(frame.getUp());
        int column = (int) Math.floor(component(intersection, columnAxis)) - origin(geometry, columnAxis);
        int row = (int) Math.floor(component(intersection, rowAxis)) - origin(geometry, rowAxis);
        return geometry.apertureOpen(column, row);
    }

    private static double component(Vec3 point, int axis) {
        return switch (axis) { case 0 -> point.x; case 1 -> point.y; default -> point.z; };
    }

    private static int origin(ApertureDescriptor geometry, int axis) {
        return switch (axis) { case 0 -> geometry.originX(); case 1 -> geometry.originY(); default -> geometry.originZ(); };
    }

    private static Vec3d vector(Vec3 point) {
        return new Vec3d(point.x, point.y, point.z);
    }

    private static TravelMessage.TravelPose pose(Pose motion) {
        return new TravelMessage.TravelPose(motion.position().x(), motion.position().y(), motion.position().z(), motion.yaw(), motion.pitch());
    }

    private static long revision(ClientTravelChunks chunks, TravelMessage.TravelBegin value) {
        return value.seamless() ? chunks.endRevision() : chunks.completeRevision();
    }

    private static TravelMessage.TravelPose crossingPose(LocalPlayer player, float partial) {
        Vec3 feet = player.getPosition(partial);
        return new TravelMessage.TravelPose(feet.x, feet.y, feet.z, player.getYRot(), player.getXRot());
    }

    private boolean matches(Construction construction) {
        return matchesWorld(begin.world(), construction);
    }

    private static boolean matchesWorld(TravelMessage.TravelWorld world, Construction construction) {
        return world.dimension().equals(construction.dimension().identifier().toString())
            && world.dimensionType().equals(construction.type().unwrapKey().orElseThrow().identifier().toString())
            && world.seed() == construction.seed() && world.debug() == construction.debug()
            && world.flat() == ((PreparedLevelDataAccess) construction.data()).wormholes$flat() && world.seaLevel() == construction.seaLevel()
            && world.minY() == construction.type().value().minY() && world.height() == construction.type().value().height();
    }

    private void retainActualWorlds() {
        if (sourcePreparation != null && sourcePreparation.level != null) {
            SourcePreparation source = sourcePreparation;
            rememberRetainedWorld(new RetainedWorld(source.level, source.connection, source.registry, source.begin.world(), source.deadline,
                source.payloads, source.aperture));
        }
        if (retainedDestination != null) {
            rememberRetainedWorld(retainedDestination.withPayloads(payloads));
        }
        if (pendingPreparation != null && pendingPreparation.retainedWorld != null) {
            rememberRetainedWorld(pendingPreparation.retainedWorld);
        }
    }

    private void rememberRetainedWorld(RetainedWorld retained) {
        if (!retained.valid(Minecraft.getInstance().getConnection())) {
            return;
        }
        RetainedWorld previous = retainedWorlds.get(retained.level());
        if (retained.aperture() == null && previous != null && previous.valid(Minecraft.getInstance().getConnection())
            && previous.world().equals(retained.world())) {
            retained = new RetainedWorld(retained.level(), retained.connection(), retained.registry(), retained.world(), retained.deadline(),
                retained.payloads(), previous.aperture());
        }
        retainedWorlds.put(retained.level(), retained);
        pruneRetainedWorlds();
    }

    private boolean hasRetainedWorld(ClientLevel level) {
        RetainedWorld retained = retainedWorlds.get(level);
        return retained != null && retained.valid(Minecraft.getInstance().getConnection());
    }

    private void pruneRetainedWorlds() {
        if (retainedWorlds.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        for (Iterator<RetainedWorld> worlds = retainedWorlds.values().iterator(); worlds.hasNext();) {
            RetainedWorld retained = worlds.next();
            if (!retained.valid(connection)) {
                worlds.remove();
                if (retained.level() != minecraft.level) {
                    ClientSodiumTerrain.forget(retained.level());
                }
            }
        }
        for (Iterator<RetainedWorld> worlds = retainedWorlds.values().iterator();
             retainedWorlds.size() > MAX_RETAINED_WORLDS && worlds.hasNext();) {
            RetainedWorld retained = worlds.next();
            if (retained.level() != minecraft.level && retained.level() != staged && retained.level() != authoritativeDestination) {
                worlds.remove();
                ClientSodiumTerrain.forget(retained.level());
            }
        }
    }

    private void discardRetainedWorlds() {
        authoritativeArrival = null;
        for (RetainedWorld retained : retainedWorlds.values()) {
            ClientSodiumTerrain.forget(retained.level());
        }
        retainedWorlds.clear();
    }

    private RetainedWorld retainedWorld(TravelMessage.TravelWorld world) {
        if (sourcePreparation == null && retainedWorlds.isEmpty()) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        SourcePreparation source = sourcePreparation;
        if (source != null && source.level != null && source.level != minecraft.level) {
            RetainedWorld retained = new RetainedWorld(source.level, source.connection, source.registry, source.begin.world(), source.deadline,
                source.payloads, source.aperture);
            if (retained.valid(connection) && retained.world().equals(world)) {
                return retained;
            }
        }
        for (RetainedWorld retained : retainedWorlds.values()) {
            if (retained.level() != minecraft.level && retained.valid(connection) && retained.world().equals(world)) {
                return retained;
            }
        }
        return null;
    }

    private RetainedWorld retainedWorld(Construction construction) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (retainedDestination != null && retainedDestination.matches(connection, construction)) {
            return retainedDestination;
        }
        if (pendingPreparation != null && pendingPreparation.retainedWorld != null
            && pendingPreparation.retainedWorld.matches(connection, construction)) {
            return pendingPreparation.retainedWorld;
        }
        for (RetainedWorld retained : retainedWorlds.values()) {
            if (retained.matches(connection, construction)) {
                return retained;
            }
        }
        SourcePreparation source = sourcePreparation;
        if (source == null || source.level == null) {
            return null;
        }
        RetainedWorld retained = new RetainedWorld(source.level, source.connection, source.registry, source.begin.world(), source.deadline,
            source.payloads, source.aperture);
        return retained.matches(connection, construction) ? retained : null;
    }

    private ClientLevel restoreAuthoritativeLevel(RetainedWorld retained, Construction construction) {
        ClientLevel level = retained.level();
        Map<TravelMessage.TravelCoordinate, byte[]> installed = new HashMap<>(retained.payloads());
        rememberRetainedWorld(retained.withPayloads(installed));
        SourcePreparation previousSource = sourcePreparation != null && sourcePreparation.level == Minecraft.getInstance().level
            && sourcePreparation.level != level ? sourcePreparation : null;
        prediction = null;
        if (staged == level) {
            staged = null;
        }
        if (pendingPreparation != null && pendingPreparation.level == level) {
            pendingPreparation.level = null;
        }
        if (sourcePreparation != null && (sourcePreparation.level == level || sourcePreparation == previousSource)) {
            sourcePreparation = null;
        }
        clear(true);
        sourcePreparation = previousSource;
        discardRetainedUpdates(level);
        ((PreparedLevelAccess) level).wormholes$extractor(construction.extractor());
        ((PreparedLevelAccess) level).wormholes$data(construction.data());
        level.getChunkSource().updateViewRadius(construction.distance());
        level.setServerSimulationDistance(construction.simulation());
        resident = new ResidentColumns(level, retained.connection(), retained.registry(), retained.deadline(), installed);
        authoritativeDestination = level;
        authoritativeArrival = new AuthoritativeArrival(retained);
        sourceCapture = Integer.MAX_VALUE;
        return level;
    }

    private boolean covers(TravelMessage.TravelPose arrival) {
        return covers(begin, staged, arrival);
    }

    private static boolean covers(TravelMessage.TravelBegin begin, ClientLevel staged, TravelMessage.TravelPose arrival) {
        if (arrival.y() < begin.world().minY() || arrival.y() + 1.8 >= (long) begin.world().minY() + begin.world().height()) {
            return false;
        }
        int radius = 1;
        int x = (int) Math.floor(arrival.x()) >> 4;
        int z = (int) Math.floor(arrival.z()) >> 4;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (!begin.chunks().contains(new TravelMessage.TravelCoordinate(x + dx, z + dz))
                    || staged == null || staged.getChunkSource().getChunk(x + dx, z + dz, FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void advanceAuthoritativeArrival() {
        AuthoritativeArrival active = authoritativeArrival;
        if (active == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!active.valid(minecraft)) {
            authoritativeArrival = null;
            return;
        }
        if (System.currentTimeMillis() >= active.deadline) {
            showAuthoritativeLoadingScreen(active);
            return;
        }
        if (!active.positionConfirmed || minecraft.player == null) {
            return;
        }
        BlockPos position = BlockPos.containing(minecraft.player.getEyePosition());
        if (minecraft.level.getChunkSource().getChunk(position.getX() >> 4, position.getZ() >> 4, FULL, false) == null
            || !minecraft.levelRenderer.isSectionCompiledAndVisible(position, 0)) {
            showAuthoritativeLoadingScreen(active);
            return;
        }
        ClientPacketListener connection = minecraft.getConnection();
        LevelLoadTracker tracker = ((PreparedPacketAccess) connection).wormholes$loadTracker();
        if (tracker != null) {
            Runnable compiled = tracker.getPlayerCompiledSectionCallback();
            if (compiled != null) {
                compiled.run();
            }
        }
        if (connection.hasClientLoaded()) {
            authoritativeArrival = null;
        }
    }

    private void showAuthoritativeLoadingScreen(AuthoritativeArrival active) {
        Minecraft minecraft = Minecraft.getInstance();
        if (active.screen != null && minecraft.gui.screen() == null) {
            minecraft.setScreenAndShow(active.screen);
        }
        authoritativeArrival = null;
    }

    private void completeLoad(ClientPacketListener connection) {
        if (connection == null || !mainCompiled && !ClientSodiumTerrain.ready(staged) && !ClientPortalRenderer.instance().travelDrawable()) {
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

    private static CameraRenderState travelCamera(TravelMessage.TravelBegin value) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player != null && minecraft.level != null
            && value.sourceWorld().equals(minecraft.level.dimension().identifier().toString())) {
            Vec3d feet = value.destinationToSource().inverse().point(new Vec3d(player.getX(), player.getY(), player.getZ()));
            Angles.Look look = ClientTravelMotion.look(value.destinationToSource().inverse(), player.getYRot(), player.getXRot());
            return arrivalCamera(new TravelMessage.TravelPose(feet.x(), feet.y(), feet.z(), look.yaw(), look.pitch()), eyeHeight(player));
        }
        return arrivalCamera(value.arrival(), eyeHeight(player));
    }

    private static CameraRenderState arrivalCamera(TravelMessage.TravelPose arrival) {
        return arrivalCamera(arrival, eyeHeight(Minecraft.getInstance().player));
    }

    static CameraRenderState arrivalCamera(TravelMessage.TravelPose arrival, float eyeHeight) {
        CameraRenderState camera = new CameraRenderState();
        camera.pos = new Vec3(arrival.x(), arrival.y() + eyeHeight, arrival.z());
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

    private static float eyeHeight(LocalPlayer player) {
        return player == null ? EntityTypes.PLAYER.getDimensions().eyeHeight() : player.getEyeHeight();
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

        private static void attach(Minecraft minecraft, ClientLevel level, boolean authoritative) {
            try (PortalIrisMainPipelines.Handoff ignored = authoritative
                ? PortalIrisMainPipelines.authoritativeHandoff(level) : PortalIrisMainPipelines.handoff(level)) {
                minecraft.setLevel(level);
            }
        }

        private static void clearPending() {
            PortalIrisMainPipelines.clearPending();
        }
    }

    private static final class AuthoritativeArrival {
        private final RetainedWorld retained;
        private final long deadline = System.currentTimeMillis() + CROSS_TIMEOUT_MILLIS;
        private Screen screen;
        private boolean positionConfirmed;

        private AuthoritativeArrival(RetainedWorld retained) {
            this.retained = retained;
        }

        private boolean valid(Minecraft minecraft) {
            return minecraft.level == retained.level() && retained.valid(minecraft.getConnection());
        }
    }

    private record Arrival(ClientLevel level, ClientTravelScene scene, TravelMessage.TravelBegin begin, long deadline) { }

    private enum ColumnPhase {
        FULL, BLOCKS;

        private ClientTravelSectionState capture(AppliedColumn column, int y) {
            return switch (this) {
                case FULL -> ClientTravelSectionState.capture(column.level(), column.x(), y, column.z());
                case BLOCKS -> ClientTravelSectionState.captureBlocks(column.level(), column.x(), y, column.z());
            };
        }
    }

    private static final class AppliedColumn {
        private final ClientLevel level;
        private final int x;
        private final int z;
        private LongOpenHashSet lightChanges;

        private AppliedColumn(ClientLevel level, int x, int z) {
            this.level = level;
            this.x = x;
            this.z = z;
        }

        private ClientLevel level() {
            return level;
        }

        private int x() {
            return x;
        }

        private int z() {
            return z;
        }
    }

    private record RetainedWorld(ClientLevel level, ClientPacketListener connection, Object registry,
                                 TravelMessage.TravelWorld world, long deadline,
                                 Map<TravelMessage.TravelCoordinate, byte[]> payloads, ApertureDescriptor aperture) {
        private boolean valid(ClientPacketListener current) {
            return current != null && current == connection && current.registryAccess() == registry
                && level.registryAccess() == registry && System.currentTimeMillis() < deadline;
        }

        private boolean matches(ClientPacketListener current, Construction construction) {
            return valid(current) && matchesWorld(world, construction);
        }

        private RetainedWorld withAperture(ApertureDescriptor geometry) {
            return new RetainedWorld(level, connection, registry, world, deadline, payloads, geometry);
        }

        private RetainedWorld withPayloads(Map<TravelMessage.TravelCoordinate, byte[]> installed) {
            return new RetainedWorld(level, connection, registry, world, deadline, installed, aperture);
        }
    }

    private record ResidentColumns(ClientLevel level, ClientPacketListener connection, Object registry, long deadline,
                                   Map<TravelMessage.TravelCoordinate, byte[]> payloads) {
        private boolean valid(ClientPacketListener current) {
            return current != null && current == connection && current.registryAccess() == registry
                && System.currentTimeMillis() < deadline;
        }

        private void remember(TravelMessage.TravelCoordinate coordinate, byte[] data) {
            if (payloads.size() >= TravelMessage.MAX_TRAVEL_CHUNKS || data.length > TravelMessage.MAX_TRAVEL_CHUNK_BYTES) {
                return;
            }
            long bytes = data.length;
            for (byte[] installed : payloads.values()) {
                bytes += installed.length;
            }
            if (bytes <= TravelMessage.MAX_TRAVEL_BYTES) {
                payloads.put(coordinate, data);
            }
        }
    }

    private static final class SourcePreparation {
        private final TravelMessage.TravelBegin begin;
        private final long deadline;
        private final Map<TravelMessage.TravelCoordinate, Integer> decoded = new HashMap<>();
        private final Map<TravelMessage.TravelCoordinate, byte[]> payloads = new HashMap<>();
        private ClientLevel level;
        private ClientTravelScene scene;
        private ApertureDescriptor aperture;
        private boolean changed;
        private int cursor;
        private int bytes;
        private final BitSet captured = new BitSet();
        private ClientPacketListener connection;
        private Object registry;

        private SourcePreparation(TravelMessage.TravelBegin begin) {
            this.begin = begin;
            aperture = begin.sourceGeometry();
            deadline = System.currentTimeMillis() + begin.expiresMillis();
        }

        private int nextCapture() {
            for (int inspected = 0; inspected < begin.chunks().size(); inspected++) {
                int index = cursor;
                cursor = (cursor + 1) % begin.chunks().size();
                if (!captured.get(index)) {
                    return index;
                }
            }
            return -1;
        }

        private void capture(int index, Column column) {
            int length = column.data().length;
            TravelMessage.TravelCoordinate coordinate = new TravelMessage.TravelCoordinate(column.x(), column.z());
            byte[] previous = payloads.get(coordinate);
            int remaining = bytes - (previous == null ? 0 : previous.length);
            if (length > TravelMessage.MAX_TRAVEL_CHUNK_BYTES || length > TravelMessage.MAX_TRAVEL_BYTES - remaining) {
                throw new IllegalArgumentException("Native portal return snapshots exceed preparation bounds");
            }
            payloads.put(coordinate, column.data());
            decoded.put(coordinate, column.revision());
            bytes = remaining + length;
            captured.set(index);
            changed = true;
        }
    }

    private static final class PendingPreparation {
        private final TravelMessage.TravelBegin begin;
        private final ClientTravelChunks chunks;
        private final Map<TravelMessage.TravelCoordinate, Column> columns = new HashMap<>();
        private final long deadline;
        private ClientLevel level;
        private ClientTravelScene scene;
        private RetainedWorld retainedWorld;
        private final Map<TravelMessage.TravelCoordinate, byte[]> payloads = new HashMap<>();
        private final Map<TravelMessage.TravelCoordinate, Integer> decoded = new HashMap<>();
        private final LongOpenHashSet changed = new LongOpenHashSet();
        private long drawnRevision;

        private PendingPreparation(TravelMessage.TravelBegin begin) {
            this.begin = begin;
            chunks = new ClientTravelChunks(begin);
            deadline = System.currentTimeMillis() + begin.expiresMillis();
        }
    }

    private static final class Prediction {
        private final ClientLevel source;
        private final Pose motion;
        private final ClientTravelMotion.Carry carry;
        private final Pose destination;
        private final Vec3 expectedArrival;
        private final long revision;
        private final LevelExtractor extractor;
        private final ClientPacketListener connection;
        private final ProtocolInfo<ClientGamePacketListener> protocol;
        private final boolean seamless;
        private long deadline = Long.MAX_VALUE;
        private final ArrayDeque<Runnable> packets = new ArrayDeque<>();
        private int retainedPacketBytes;
        private ChunkPos sourceCenter;
        private final List<ClientboundLevelChunkWithLightPacket> sourceColumns = new ArrayList<>(9);

        private Prediction(PredictionState state) {
            source = state.source();
            motion = state.motion();
            carry = state.carry();
            destination = state.destination();
            expectedArrival = state.expectedArrival();
            revision = state.revision();
            extractor = state.extractor();
            connection = state.connection();
            seamless = state.seamless();
            protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(state.connection().registryAccess()));
        }
    }

    private record PredictionState(ClientLevel source, Pose motion, ClientTravelMotion.Carry carry, Pose destination, Vec3 expectedArrival,
                                   long revision, LevelExtractor extractor, ClientPacketListener connection, boolean seamless) {
    }

    private record Column(int x, int z, int revision, byte[] data) {
    }

    public record Construction(ClientLevel.ClientLevelData data, ResourceKey<Level> dimension, Holder<DimensionType> type,
                               LevelExtractor extractor, boolean debug, long seed, int seaLevel, int distance, int simulation) {
    }
}
