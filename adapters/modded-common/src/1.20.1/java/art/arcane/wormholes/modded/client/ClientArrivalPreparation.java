package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.CrossingPacketAccess;
import art.arcane.wormholes.network.client.ArrivalChunkBuffer;
import art.arcane.wormholes.network.client.ArrivalMessage;
import art.arcane.wormholes.network.client.TravelMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class ClientArrivalPreparation {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long RENDER_RESERVE_BYTES = 64L * 1024L * 1024L;
    private static volatile Staging staging;

    private final Consumer<ArrivalMessage> sender;
    private final long budgetBytes;
    private LongSupplier otherMemory = () -> 0L;
    private volatile Stage stage;
    private final AtomicLong epoch = new AtomicLong();
    private Registry<Biome> fingerprintRegistry;
    private String fingerprint;
    private long prepared;
    private long dataReady;
    private long renderReady;
    private long committed;
    private long adopted;
    private long rejected;

    public ClientArrivalPreparation(Consumer<ArrivalMessage> sender, long budgetBytes) {
        this.sender = Objects.requireNonNull(sender);
        this.budgetBytes = budgetBytes;
    }

    /** Installs the prepared-level backend; until one is installed every Prepare is answered REJECTED. */
    public static void staging(Staging backend) {
        staging = backend;
    }

    /** Whether a prepared-level backend is installed. */
    public static boolean staged() {
        return staging != null;
    }

    public void otherMemory(LongSupplier memory) {
        otherMemory = Objects.requireNonNull(memory);
    }

    public long bytes() {
        Stage current = stage;
        return current == null || current.adopted ? 0L : current.bytes();
    }

    public Diagnostics diagnostics() {
        Stage current = stage;
        return new Diagnostics(prepared, dataReady, renderReady, committed, adopted, rejected,
            current == null ? null : current.prepare.token(), current == null ? null : current.readiness, bytes());
    }

    public void deliver(ArrivalMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            long expectedEpoch = epoch.get();
            ClientPacketListener expectedConnection = minecraft.getConnection();
            minecraft.execute(() -> {
                if (epoch.get() == expectedEpoch && minecraft.getConnection() == expectedConnection) {
                    deliver(message);
                }
            });
            return;
        }
        try {
            switch (message) {
                case ArrivalMessage.Prepare prepare -> prepare(prepare);
                case ArrivalMessage.Chunk chunk -> chunk(chunk);
                case ArrivalMessage.Commit commit -> commit(commit);
                case ArrivalMessage.Cancel cancel -> {
                    if (matches(cancel.token(), cancel.revision())) {
                        discard();
                    }
                }
                case ArrivalMessage.Ready ignored -> { }
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to prepare authoritative arrival terrain", failure);
            reject();
        }
    }

    public void tick() {
        Stage current = stage;
        if (current == null) {
            return;
        }
        if (System.nanoTime() >= current.deadline || current.bytes() > available()) {
            reject();
            return;
        }
        if (current.adopted) {
            return;
        }
        if (current.buffer.receivedChunks() != current.prepare.chunkCount()) {
            return;
        }
        try {
            withLevel(current.level, () -> {
                current.level.pollLightUpdates();
                current.level.getChunkSource().getLightEngine().runLightUpdates();
            });
            if (!current.level.isLightUpdateQueueEmpty() || current.level.getChunkSource().getLightEngine().hasLightWork()) {
                return;
            }
            if (current.readiness == null) {
                current.readiness = ArrivalMessage.Readiness.DATA;
                dataReady++;
                ready(current);
            }
            withLevel(current.level, current.renderer::prepare);
            if (current.bytes() > available()) {
                reject();
                return;
            }
            if (current.readiness != ArrivalMessage.Readiness.RENDER && current.renderer.ready()) {
                current.readiness = ArrivalMessage.Readiness.RENDER;
                renderReady++;
                ready(current);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to compile authoritative arrival terrain for {}", current.prepare.world().dimension(), failure);
            reject();
        }
    }

    public void clear() {
        long expectedEpoch = epoch.incrementAndGet();
        if (stage == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> {
                if (epoch.get() == expectedEpoch) {
                    discard();
                }
            });
            return;
        }
        discard();
    }

    private void discard() {
        Stage previous = stage;
        stage = null;
        if (previous != null && !previous.adopted) {
            ClientWorldLoader.forget(previous.level);
        }
    }

    public void reset(ViewStreamMessage.ResetReason reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && !minecraft.isSameThread()) {
            minecraft.execute(() -> reset(reason));
            return;
        }
        if (stage != null && stage.commit != null
            && (reason == ViewStreamMessage.ResetReason.TELEPORT || reason == ViewStreamMessage.ResetReason.DIMENSION)) {
            return;
        }
        discard();
    }

    public void respawn(ClientboundRespawnPacket packet) {
        try {
            respawnPrepared(packet);
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to adopt prepared arrival terrain during respawn", failure);
            reject();
        }
    }

    private void respawnPrepared(ClientboundRespawnPacket packet) {
        Stage current = stage;
        if (current == null) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        ResourceKey<DimensionType> typeKey = packet.getDimensionType();
        DimensionType type = connection == null ? null : connection.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE).get(typeKey);
        if (type == null) {
            reject();
            return;
        }
        TravelMessage.TravelWorld world = new TravelMessage.TravelWorld(packet.getDimension().location().toString(),
            typeKey.location().toString(), packet.getSeed(), packet.isDebug(), packet.isFlat(), current.prepare.world().seaLevel(), type.minY(),
            type.height());
        if (System.nanoTime() >= current.deadline || current.commit == null || !packet.shouldKeep(ClientboundRespawnPacket.KEEP_ALL_DATA)
            || !matchesWorld(current.prepare.world(), world) || !current.renderer.ready()) {
            reject();
            return;
        }
        current.respawning = true;
    }

    public ClientLevel respawnLevel() {
        Stage current = stage;
        if (current == null || !current.respawning || current.adopted) {
            return null;
        }
        return current.level;
    }

    public boolean installLevel(ClientLevel destination) {
        try {
            return installPreparedLevel(destination);
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to install prepared arrival renderer", failure);
            reject();
            ClientWorldLoader.bindMainRenderer(destination);
            return false;
        }
    }

    private boolean installPreparedLevel(ClientLevel destination) {
        Stage current = stage;
        if (current == null || !current.respawning || destination != current.level || current.adopted) {
            return false;
        }
        ClientWorldLoader.adoptArrival(current.level, current.level, current.position());
        current.adopted = true;
        adopted++;
        return true;
    }

    public boolean position(ClientboundPlayerPositionPacket packet) {
        try {
            return positionPrepared(packet);
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to adopt prepared arrival terrain during position", failure);
            if (stage != null && stage.terrainMoved && !stage.adopted) {
                ClientWorldLoader.rebuildMainTerrain();
            }
            reject();
            return false;
        }
    }

    private boolean positionPrepared(ClientboundPlayerPositionPacket packet) {
        Stage current = stage;
        if (current == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (System.nanoTime() >= current.deadline || current.commit == null || player == null
            || !matchesPosition(current.commit.arrival(), absolutePosition(player, packet), absoluteYaw(player, packet), absolutePitch(player, packet))) {
            reject();
            return false;
        }
        if (!current.adopted) {
            ClientLevel active = minecraft.level;
            if (!(active instanceof ClientTravelWorld travelWorld) || !matchesWorld(current.prepare.world(), travelWorld.wormholes$travelWorld())
                || !current.renderer.ready()) {
                reject();
                return false;
            }
            active.pollLightUpdates();
            if (!active.isLightUpdateQueueEmpty()) {
                reject();
                return false;
            }
            current.renderer.rebind(active);
            current.terrainMoved = true;
            current.backend.transfer(current.level, active);
            current.renderer.adoptChunks(active);
            ClientWorldLoader.adoptArrival(current.level, active, current.position());
            current.adopted = true;
            adopted++;
        }
        stage = null;
        return true;
    }

    static boolean matchesPosition(TravelMessage.TravelPose expected, Vec3 position, float yRot, float xRot) {
        return Math.abs(expected.x() - position.x) < 0.00001D && Math.abs(expected.y() - position.y) < 0.00001D
            && Math.abs(expected.z() - position.z) < 0.00001D && Math.abs(Mth.wrapDegrees(expected.yaw() - yRot)) < 0.001F
            && Math.abs(expected.pitch() - xRot) < 0.001F;
    }

    static Vec3 absolutePosition(LocalPlayer player, ClientboundPlayerPositionPacket packet) {
        Set<RelativeMovement> relative = packet.getRelativeArguments();
        return new Vec3(relative.contains(RelativeMovement.X) ? player.getX() + packet.getX() : packet.getX(),
            relative.contains(RelativeMovement.Y) ? player.getY() + packet.getY() : packet.getY(),
            relative.contains(RelativeMovement.Z) ? player.getZ() + packet.getZ() : packet.getZ());
    }

    static float absoluteYaw(LocalPlayer player, ClientboundPlayerPositionPacket packet) {
        return packet.getRelativeArguments().contains(RelativeMovement.Y_ROT) ? player.getYRot() + packet.getYRot() : packet.getYRot();
    }

    static float absolutePitch(LocalPlayer player, ClientboundPlayerPositionPacket packet) {
        return packet.getRelativeArguments().contains(RelativeMovement.X_ROT) ? player.getXRot() + packet.getXRot() : packet.getXRot();
    }

    static boolean matchesWorld(TravelMessage.TravelWorld expected, TravelMessage.TravelWorld actual) {
        return expected.equals(actual);
    }

    static boolean matchesLanding(ArrivalMessage.Prepare prepare, TravelMessage.TravelPose landing) {
        return (Mth.floor(landing.x()) >> 4) == prepare.centerX() && (Mth.floor(landing.z()) >> 4) == prepare.centerZ()
            && Math.abs(landing.y() - prepare.arrival().y()) <= 8D;
    }

    private void prepare(ArrivalMessage.Prepare prepare) {
        discard();
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        Staging backend = staging;
        if (backend == null || minecraft.level == null || connection == null || !ClientWorldLoader.rendererPerLevel()
            || available() <= RENDER_RESERVE_BYTES || !registryFingerprint(connection).equals(prepare.registryFingerprint())) {
            rejected++;
            sender.accept(new ArrivalMessage.Ready(prepare.token(), prepare.revision(), ArrivalMessage.Readiness.REJECTED));
            return;
        }
        ClientLevel level = backend.open(prepare);
        if (level == null) {
            rejected++;
            sender.accept(new ArrivalMessage.Ready(prepare.token(), prepare.revision(), ArrivalMessage.Readiness.REJECTED));
            return;
        }
        stage = new Stage(prepare, level, backend);
        ClientWorldLoader.worldRenderer(level);
        prepared++;
    }

    private void chunk(ArrivalMessage.Chunk chunk) {
        if (!matches(chunk.token(), chunk.revision()) || stage.readiness != null) {
            return;
        }
        Stage current = stage;
        Optional<byte[]> payload = current.buffer.accept(chunk);
        if (payload.isEmpty()) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload.get()));
        try {
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(bytes);
            if (bytes.isReadable() || packet.getX() != chunk.x() || packet.getZ() != chunk.z()) {
                throw new IllegalArgumentException("Arrival chunk payload does not match its coordinates");
            }
            withLevel(current.level, () -> packet.handle(connection));
        } finally {
            bytes.release();
        }
    }

    private void commit(ArrivalMessage.Commit commit) {
        if (!matches(commit.token(), commit.revision())) {
            return;
        }
        Stage current = stage;
        if (System.nanoTime() >= current.deadline || current.readiness != ArrivalMessage.Readiness.RENDER
            || !matchesLanding(current.prepare, commit.arrival()) || !current.renderer.ready()) {
            reject();
            return;
        }
        current.commit = commit;
        committed++;
    }

    private boolean matches(UUID token, long revision) {
        return stage != null && stage.prepare.token().equals(token) && stage.prepare.revision() == revision;
    }

    private void ready(Stage current) {
        sender.accept(new ArrivalMessage.Ready(current.prepare.token(), current.prepare.revision(), current.readiness));
    }

    private void reject() {
        Stage current = stage;
        if (current == null) {
            return;
        }
        rejected++;
        sender.accept(new ArrivalMessage.Ready(current.prepare.token(), current.prepare.revision(), ArrivalMessage.Readiness.REJECTED));
        discard();
    }

    private long available() {
        return Math.max(0L, budgetBytes - otherMemory.getAsLong());
    }

    private String registryFingerprint(ClientPacketListener connection) {
        Registry<Biome> registry = connection.registryAccess().registryOrThrow(Registries.BIOME);
        if (fingerprintRegistry == registry) {
            return fingerprint;
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        digest.update("biomes\n".getBytes(StandardCharsets.UTF_8));
        for (int index = 0; index < registry.size(); index++) {
            Biome biome = registry.byId(index);
            digest.update((registry.getKey(biome) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        digest.update("blockstates\n".getBytes(StandardCharsets.UTF_8));
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            digest.update((state.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        fingerprintRegistry = registry;
        fingerprint = HexFormat.of().formatHex(digest.digest());
        return fingerprint;
    }

    private static void withLevel(ClientLevel level, Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        CrossingPacketAccess access = (CrossingPacketAccess) connection;
        ClientLevel previous = minecraft.level;
        ClientLevel previousConnection = connection.getLevel();
        ClientLevel.ClientLevelData data = access.wormholes$crossingData();
        minecraft.level = level;
        access.wormholes$crossingLevel(level);
        access.wormholes$crossingData(level.getLevelData());
        try {
            ClientWorldLoader.withWorldRenderer(level, action);
        } finally {
            minecraft.level = previous;
            access.wormholes$crossingLevel(previousConnection);
            access.wormholes$crossingData(data);
        }
    }

    /** Creates and compiles prepared arrival levels. Implemented by the client world backend. */
    public interface Staging {
        /** A detached level for {@code prepare}, or {@code null} when it cannot be prepared now. */
        ClientLevel open(ArrivalMessage.Prepare prepare);

        /** The terrain compiler for a level returned by {@link #open}. */
        Terrain terrain(ClientLevel level, ArrivalMessage.Prepare prepare);

        /** Moves prepared chunks and lighting from {@code prepared} into {@code active}. */
        void transfer(ClientLevel prepared, ClientLevel active);

        /** Estimated bytes held by {@code level}. */
        long estimate(ClientLevel level);
    }

    /** Compiled terrain of one prepared level. */
    public interface Terrain {
        void prepare();

        boolean ready();

        void rebind(ClientLevel active);

        void adoptChunks(ClientLevel active);

        long bytes();
    }

    public record Diagnostics(long prepared, long dataReady, long renderReady, long committed, long adopted, long rejected,
                              UUID token, ArrivalMessage.Readiness readiness, long bytes) {
    }

    private final class Stage {
        private final ArrivalMessage.Prepare prepare;
        private final ClientLevel level;
        private final ArrivalChunkBuffer buffer;
        private final Staging backend;
        private final Terrain renderer;
        private final long deadline;
        private ArrivalMessage.Readiness readiness;
        private ArrivalMessage.Commit commit;
        private boolean terrainMoved;
        private boolean respawning;
        private boolean adopted;

        private Stage(ArrivalMessage.Prepare prepare, ClientLevel level, Staging backend) {
            this.prepare = prepare;
            this.level = level;
            this.backend = backend;
            buffer = new ArrivalChunkBuffer(prepare, available() - RENDER_RESERVE_BYTES);
            renderer = backend.terrain(level, prepare);
            deadline = System.nanoTime() + prepare.lifetimeTicks() * 50_000_000L;
        }

        private Vec3 position() {
            TravelMessage.TravelPose pose = commit == null ? prepare.arrival() : commit.arrival();
            return new Vec3(pose.x(), pose.y(), pose.z());
        }

        private long bytes() {
            return RENDER_RESERVE_BYTES + buffer.bytes() + backend.estimate(level) * 2L + renderer.bytes();
        }
    }
}
