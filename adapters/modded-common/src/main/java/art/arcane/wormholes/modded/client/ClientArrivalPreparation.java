package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.client.world.PreparedLevels;
import art.arcane.wormholes.modded.client.world.PreparedTerrain;
import art.arcane.wormholes.modded.client.world.PreparedTerrainRenderer;
import art.arcane.wormholes.modded.client.render.iris.IrisPipelines;
import art.arcane.wormholes.modded.mixin.client.ArrivalLevelAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalChunkPacketAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedPacketAccess;
import art.arcane.wormholes.network.client.ArrivalChunkBuffer;
import art.arcane.wormholes.network.client.ArrivalMessage;
import art.arcane.wormholes.network.client.TravelMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.block.Block;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class ClientArrivalPreparation {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final long RENDER_RESERVE_BYTES = 64L * 1024L * 1024L;
    private static final boolean IRIS = ClientArrivalPreparation.class.getClassLoader()
        .getResource("net/irisshaders/iris/Iris.class") != null;

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
            if (!((ArrivalLevelAccess) current.level).wormholes$pendingLight().isEmpty()
                || current.level.getChunkSource().getLightEngine().hasLightWork()) {
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
        CommonPlayerSpawnInfo spawn = packet.commonPlayerSpawnInfo();
        TravelMessage.TravelWorld world = new TravelMessage.TravelWorld(spawn.dimension().identifier().toString(),
            spawn.dimensionType().unwrapKey().orElseThrow().identifier().toString(), spawn.seed(), spawn.isDebug(), spawn.isFlat(),
            spawn.seaLevel(), spawn.dimensionType().value().minY(), spawn.dimensionType().value().height());
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
        if (System.nanoTime() >= current.deadline || current.commit == null || minecraft.player == null
            || !matchesPosition(current.commit.arrival(), PositionMoveRotation.calculateAbsolute(
                PositionMoveRotation.of(minecraft.player), packet.change(), packet.relatives()))) {
            reject();
            return false;
        }
        if (!current.adopted) {
            ClientLevel active = minecraft.level;
            if (!matchesWorld(current.prepare.world(), ((ClientTravelWorld) active).wormholes$travelWorld())
                || !current.renderer.ready()) {
                reject();
                return false;
            }
            active.pollLightUpdates();
            if (!((ArrivalLevelAccess) active).wormholes$pendingLight().isEmpty()) {
                reject();
                return false;
            }
            current.renderer.rebind(active);
            current.terrainMoved = true;
            PreparedTerrain.transfer(current.level, active);
            current.renderer.adoptChunks(active);
            ClientWorldLoader.adoptArrival(current.level, active, current.position());
            current.adopted = true;
            adopted++;
        }
        stage = null;
        return true;
    }

    static boolean matchesPosition(TravelMessage.TravelPose expected, PositionMoveRotation actual) {
        Vec3 position = actual.position();
        return Math.abs(expected.x() - position.x) < 0.00001D && Math.abs(expected.y() - position.y) < 0.00001D
            && Math.abs(expected.z() - position.z) < 0.00001D && Math.abs(Mth.wrapDegrees(expected.yaw() - actual.yRot())) < 0.001F
            && Math.abs(expected.pitch() - actual.xRot()) < 0.001F;
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
        if (minecraft.level == null || connection == null || available() <= RENDER_RESERVE_BYTES
            || !registryFingerprint(connection).equals(prepare.registryFingerprint())) {
            rejected++;
            sender.accept(new ArrivalMessage.Ready(prepare.token(), prepare.revision(), ArrivalMessage.Readiness.REJECTED));
            return;
        }
        ClientLevel level = PreparedLevels.arrival(prepare);
        if (IRIS && !IrisPipelines.arrivalReady(level)) {
            rejected++;
            sender.accept(new ArrivalMessage.Ready(prepare.token(), prepare.revision(), ArrivalMessage.Readiness.REJECTED));
            return;
        }
        stage = new Stage(prepare, level);
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
        RegistryFriendlyByteBuf bytes = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload.get()), connection.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(bytes);
            if (bytes.isReadable() || ((ArrivalChunkPacketAccess) (Object) packet).wormholes$arrivalX() != chunk.x()
                || ((ArrivalChunkPacketAccess) (Object) packet).wormholes$arrivalZ() != chunk.z()) {
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
        Registry<Biome> registry = connection.registryAccess().lookupOrThrow(Registries.BIOME);
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
        PreparedPacketAccess access = (PreparedPacketAccess) connection;
        ClientLevel previous = minecraft.level;
        ClientLevel previousConnection = connection.getLevel();
        ClientLevel.ClientLevelData data = access.wormholes$data();
        minecraft.level = level;
        access.wormholes$level(level);
        access.wormholes$data(level.getLevelData());
        try {
            ClientWorldLoader.withWorldRenderer(level, action);
        } finally {
            minecraft.level = previous;
            access.wormholes$level(previousConnection);
            access.wormholes$data(data);
        }
    }

    public record Diagnostics(long prepared, long dataReady, long renderReady, long committed, long adopted, long rejected,
                              UUID token, ArrivalMessage.Readiness readiness, long bytes) {
    }

    private final class Stage {
        private final ArrivalMessage.Prepare prepare;
        private final ClientLevel level;
        private final ArrivalChunkBuffer buffer;
        private final PreparedTerrainRenderer renderer;
        private final long deadline;
        private ArrivalMessage.Readiness readiness;
        private ArrivalMessage.Commit commit;
        private boolean terrainMoved;
        private boolean respawning;
        private boolean adopted;

        private Stage(ArrivalMessage.Prepare prepare, ClientLevel level) {
            this.prepare = prepare;
            this.level = level;
            buffer = new ArrivalChunkBuffer(prepare, available() - RENDER_RESERVE_BYTES);
            renderer = new PreparedTerrainRenderer(level, prepare);
            deadline = System.nanoTime() + prepare.lifetimeTicks() * 50_000_000L;
        }

        private Vec3 position() {
            TravelMessage.TravelPose pose = commit == null ? prepare.arrival() : commit.arrival();
            return new Vec3(pose.x(), pose.y(), pose.z());
        }

        private long bytes() {
            return RENDER_RESERVE_BYTES + buffer.bytes() + ResidentLevel.estimate(level) * 2L + renderer.bytes();
        }
    }
}
