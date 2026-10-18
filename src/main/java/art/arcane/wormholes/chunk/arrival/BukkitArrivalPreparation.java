package art.arcane.wormholes.chunk.arrival;

import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketAccess;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketSnapshot;
import art.arcane.volmlib.nativelib.chunk.ChunkPosition;
import art.arcane.volmlib.nativelib.chunk.ChunkWorldContext;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.presend.ChunkPreSendSettings;
import art.arcane.wormholes.network.client.ArrivalMessage;
import art.arcane.wormholes.network.client.ArrivalPreparedState;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.render.clientview.BukkitClientView;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class BukkitArrivalPreparation {
    private static final int RADIUS = 2;
    private static final int LIFETIME_TICKS = 100;
    private static final int WAIT_TICKS = 10;
    private static final int MAX_PLAYERS = 32;
    private static final int BYTES_PER_TICK = 192 * 1024;
    private static final int GLOBAL_BYTES_PER_TICK = 1024 * 1024;
    private static final long PLAYER_BYTES = 8L * 1024 * 1024;
    private static final long GLOBAL_BYTES = 64L * 1024 * 1024;
    private static volatile BukkitArrivalPreparation active;

    private final Plugin plugin;
    private final BukkitClientView views;
    private final ChunkPacketAccess packets;
    private final ConcurrentHashMap<UUID, Stage> stages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> rejectedUntil = new ConcurrentHashMap<>();
    private final AtomicLong retainedBytes = new AtomicLong();
    private final Semaphore capacity = new Semaphore(MAX_PLAYERS);
    private boolean capturing;
    private long captureWindow;
    private long captureNanos;
    private long sendWindow;
    private int sentBytes;
    private volatile boolean stopping;

    private BukkitArrivalPreparation(Plugin plugin, BukkitClientView views, ChunkPacketAccess packets) {
        this.plugin = plugin;
        this.views = views;
        this.packets = packets;
    }

    public static void install(Plugin plugin, BukkitClientView views) {
        shutdown();
        Optional<ChunkPacketAccess> packets = NativeAdapters.find(ChunkPacketAccess.class);
        if (packets.isPresent() && packets.get().snapshotSupported()) {
            active = new BukkitArrivalPreparation(plugin, views, packets.get());
        }
    }

    public static void shutdown() {
        BukkitArrivalPreparation service = active;
        active = null;
        if (service != null) {
            service.stopping = true;
            for (Stage stage : service.stages.values()) {
                service.release(stage);
            }
            service.stages.clear();
            service.rejectedUntil.clear();
        }
    }

    public static void prepare(Player player, Location destination) {
        BukkitArrivalPreparation service = active;
        if (service == null || player == null || destination == null || destination.getWorld() == null) {
            return;
        }
        Location target = destination.clone();
        service.onPlayer(player, () -> service.begin(player, target, false), () -> { });
    }

    public static boolean claimed(Player player) {
        BukkitArrivalPreparation service = active;
        Stage stage = service == null || player == null ? null : service.stages.get(player.getUniqueId());
        return stage != null && stage.claimed && !stage.closed.get() && System.currentTimeMillis() < stage.deadline;
    }

    public static Attempt claimForTeleport(Player player, Location destination) {
        BukkitArrivalPreparation service = active;
        if (service == null || player == null || destination == null || destination.getWorld() == null) {
            return Attempt.EMPTY;
        }
        Stage stage = service.stages.get(player.getUniqueId());
        if (stage == null) {
            return Attempt.EMPTY;
        }
        if (service.stopping || !player.isOnline() || !service.views.arrivalReceiver(player)
            || !ChunkPreSendSettings.active().enabled() || stage.closed.get() || !stage.matches(destination)
            || stage.state == null || !stage.state.renderReady(System.currentTimeMillis()) || service.dirty(stage)) {
            service.cancel(stage, true);
            return Attempt.EMPTY;
        }
        stage.claimed = true;
        return new Attempt(service, stage, destination.clone());
    }

    public static void prepareForTeleport(Player player, Location destination, Consumer<Attempt> continuation, Runnable retired) {
        BukkitArrivalPreparation service = active;
        if (service == null || player == null || destination == null || destination.getWorld() == null) {
            continuation.accept(Attempt.EMPTY);
            return;
        }
        service.begin(player, destination.clone(), true);
        Stage stage = service.stages.get(player.getUniqueId());
        if (stage == null) {
            continuation.accept(Attempt.EMPTY);
            return;
        }
        Attempt attempt = new Attempt(service, stage, destination.clone());
        attempt.departureWorld = player.getWorld();
        attempt.await(continuation, retired, WAIT_TICKS);
    }

    public static void disconnected(UUID playerId) {
        BukkitArrivalPreparation service = active;
        if (service != null) {
            service.forget(playerId);
            service.rejectedUntil.remove(playerId);
        }
    }

    public static void receive(Player player, ArrivalMessage.Ready ready) {
        BukkitArrivalPreparation service = active;
        if (service == null || player == null) {
            return;
        }
        service.onPlayer(player, () -> {
            Stage stage = service.stages.get(player.getUniqueId());
            if (stage == null || stage.state == null || stage.closed.get()
                || !stage.token.equals(ready.token()) || ready.revision() != 1) {
                return;
            }
            stage.state.ready(ready);
            if (ready.readiness() == ArrivalMessage.Readiness.REJECTED) {
                service.rejectedUntil.put(player.getUniqueId(), System.currentTimeMillis() + 5000);
                service.cancel(player.getUniqueId(), true);
            }
        }, () -> { });
    }

    private void begin(Player player, Location destination, boolean claimed) {
        UUID id = player.getUniqueId();
        if (!ChunkPreSendSettings.active().enabled()) {
            cancel(id, true);
            return;
        }
        if (stopping || !player.isOnline() || !views.arrivalReceiver(player)
            || rejectedUntil.getOrDefault(id, 0L) > System.currentTimeMillis() || transformersPresent()) {
            return;
        }
        Stage existing = stages.get(id);
        if (existing != null && existing.claimed && !claimed && !existing.closed.get()
            && System.currentTimeMillis() < existing.deadline) {
            return;
        }
        if (existing != null && existing.matches(destination) && !existing.closed.get()
            && System.currentTimeMillis() < existing.deadline && !dirty(existing)) {
            existing.claimed |= claimed;
            return;
        }
        cancel(id, true);
        if (!capacity.tryAcquire()) {
            return;
        }
        WorldChangeTracker changes = Wormholes.projectionChangeTracker;
        Stage stage = new Stage(player, destination, changes);
        stage.claimed = claimed;
        stages.put(id, stage);
        if (stopping) {
            forget(stage);
            return;
        }
        if (!FoliaScheduler.runEntity(plugin, player, () -> expire(stage), LIFETIME_TICKS,
            () -> forget(stage))) {
            forget(stage);
            return;
        }
        captureNext(stage);
    }

    private boolean transformersPresent() {
        for (Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
            if (installed == plugin || !installed.isEnabled()) {
                continue;
            }
            String name = installed.getName();
            if (name.equalsIgnoreCase("ProtocolLib") || name.equalsIgnoreCase("Orebfuscator")
                || installed.getDescription().getDepend().stream().anyMatch(value -> value.equalsIgnoreCase("packetevents"))
                || installed.getDescription().getSoftDepend().stream().anyMatch(value -> value.equalsIgnoreCase("packetevents"))) {
                return true;
            }
        }
        return false;
    }

    private void captureNext(Stage stage) {
        if (stage.closed.get() || stopping || stage.captureIndex >= stage.positions.size()) {
            return;
        }
        ChunkPosition position = stage.positions.get(stage.captureIndex);
        ChunkLease lease = BukkitChunkLeaseProvider.registry().retain(stage.world, stage.worldId, position.x(), position.z());
        synchronized (stage.leases) {
            if (stage.closed.get()) {
                lease.close();
                return;
            }
            stage.leases.add(lease);
        }
        lease.ready().whenComplete((loaded, failure) -> {
            if (stage.closed.get()) {
                return;
            }
            if (failure != null) {
                fail(stage, failure);
            } else if (!Boolean.TRUE.equals(loaded)) {
                cancelAsync(stage);
            } else if (FoliaScheduler.isOwnedByCurrentRegion(stage.world, position.x(), position.z())) {
                capture(stage, position);
            } else if (!FoliaScheduler.runRegion(plugin, stage.world, position.x(), position.z(),
                () -> capture(stage, position), 1L)) {
                cancelAsync(stage);
            }
        });
    }

    private void capture(Stage stage, ChunkPosition position) {
        if (stage.closed.get() || stopping) {
            return;
        }
        if (!claimCapture()) {
            if (!FoliaScheduler.runRegion(plugin, stage.world, position.x(), position.z(), () -> capture(stage, position), 1L)) {
                cancelAsync(stage);
            }
            return;
        }
        long started = System.nanoTime();
        CapturedChunk captured;
        try {
            Optional<ChunkWorldContext> context = packets.context(stage.world);
            Optional<String> fingerprint = packets.registryFingerprint(stage.world);
            Optional<ChunkPacketSnapshot> snapshot = packets.snapshot(stage.world, position);
            if (context.isEmpty() || fingerprint.isEmpty() || snapshot.isEmpty()) {
                rejectedUntil.put(stage.playerId, System.currentTimeMillis() + 5000);
                cancelAsync(stage);
                return;
            }
            byte[] payload = snapshot.get().payload();
            if (stage.totalBytes + payload.length > PLAYER_BYTES) {
                cancelAsync(stage);
                return;
            }
            Payload retained = new Payload(payload);
            if (retainedBytes.addAndGet(payload.length) > GLOBAL_BYTES) {
                retained.close();
                cancelAsync(stage);
                return;
            }
            stage.pending.set(retained);
            if (stage.closed.get()) {
                stage.pending.compareAndSet(retained, null);
                retained.close();
                return;
            }
            stage.totalBytes += payload.length;
            stage.captureIndex++;
            captured = new CapturedChunk(context.get(), fingerprint.get(), retained);
        } catch (RuntimeException failure) {
            fail(stage, failure);
            return;
        } finally {
            finishCapture(System.nanoTime() - started);
        }
        if (!onPlayer(stage.player, () -> sendChunk(stage, captured.context(), captured.fingerprint(), position, captured.payload(), 0),
            () -> forget(stage))) {
            forget(stage);
        }
    }

    private void sendChunk(Stage stage, ChunkWorldContext context, String fingerprint, ChunkPosition position, Payload retained, int offset) {
        byte[] payload = retained.bytes;
        if (stage.closed.get() || !ChunkPreSendSettings.active().enabled() || !stage.player.isOnline()
            || !views.arrivalReceiver(stage.player) || dirty(stage)) {
            retained.close();
            cancel(stage, true);
            return;
        }
        if (stage.state == null) {
            TravelMessage.TravelWorld world = new TravelMessage.TravelWorld(context.dimension(), context.dimensionType(), context.seed(),
                context.debug(), context.flat(), context.seaLevel(), context.minY(), context.height());
            ArrivalMessage.Prepare prepare = new ArrivalMessage.Prepare(stage.token, 1, stage.worldId, world, pose(stage.destination),
                RADIUS, fingerprint, context.gameTime(), context.clockTime(), context.rain(), context.thunder(), LIFETIME_TICKS);
            stage.state = new ArrivalPreparedState(prepare, stage.created);
            if (!views.sendArrival(stage.player, prepare)) {
                retained.close();
                cancel(stage, true);
                return;
            }
        }
        int count = (payload.length + ArrivalMessage.FRAGMENT_BYTES - 1) / ArrivalMessage.FRAGMENT_BYTES;
        while (offset < payload.length) {
            int end = Math.min(payload.length, offset + ArrivalMessage.FRAGMENT_BYTES);
            if (!claimSend(stage, end - offset)) {
                break;
            }
            ArrivalMessage.Chunk message = new ArrivalMessage.Chunk(stage.token, 1, position.x(), position.z(),
                offset / ArrivalMessage.FRAGMENT_BYTES, count, payload.length, Arrays.copyOfRange(payload, offset, end));
            if (!views.sendArrival(stage.player, message)) {
                retained.close();
                cancel(stage, true);
                return;
            }
            offset = end;
        }
        if (offset < payload.length) {
            int next = offset;
            if (!FoliaScheduler.runEntity(plugin, stage.player, () -> sendChunk(stage, context, fingerprint, position, retained, next), 1L,
                () -> forget(stage))) {
                forget(stage);
            }
            return;
        }
        retained.close();
        stage.pending.compareAndSet(retained, null);
        stage.state.sent(position.x(), position.z());
        captureNext(stage);
    }

    private void commit(Stage stage, Location destination) {
        if (stages.get(stage.playerId) != stage || stage.closed.get()) {
            return;
        }
        if (!ChunkPreSendSettings.active().enabled()) {
            cancel(stage, true);
            return;
        }
        World world = destination.getWorld();
        if (world == null || stage.state == null || !stage.state.commit(world.getUID(), pose(destination),
            System.currentTimeMillis(), dirty(stage)) || !views.sendArrival(stage.player,
            new ArrivalMessage.Commit(stage.token, 1, pose(destination)))) {
            cancel(stage, true);
        }
    }

    private boolean dirty(Stage stage) {
        WorldChangeTracker changes = Wormholes.projectionChangeTracker;
        return changes == null || changes != stage.tracker || stage.worldVersion < 0 || changes.dirtySince(stage.worldId,
            stage.centerX - RADIUS, stage.centerZ - RADIUS, stage.centerX + RADIUS, stage.centerZ + RADIUS, stage.worldVersion);
    }

    private synchronized boolean claimCapture() {
        long window = System.nanoTime() / 50_000_000L;
        if (window != captureWindow) {
            captureWindow = window;
            captureNanos = 0;
        }
        if (capturing || captureNanos >= 2_000_000L) {
            return false;
        }
        capturing = true;
        return true;
    }

    private synchronized boolean claimSend(Stage stage, int bytes) {
        long window = System.nanoTime() / 50_000_000L;
        if (window != sendWindow) {
            sendWindow = window;
            sentBytes = 0;
        }
        if (stage.sendWindow != window) {
            stage.sendWindow = window;
            stage.sentBytes = 0;
        }
        if (sentBytes + bytes > GLOBAL_BYTES_PER_TICK || stage.sentBytes + bytes > BYTES_PER_TICK) {
            return false;
        }
        sentBytes += bytes;
        stage.sentBytes += bytes;
        return true;
    }

    private synchronized void finishCapture(long nanos) {
        capturing = false;
        captureNanos += nanos;
    }

    private boolean onPlayer(Player player, Runnable task, Runnable retired) {
        if (FoliaScheduler.isOwnedByCurrentRegion(player)) {
            task.run();
            return true;
        }
        return FoliaScheduler.runEntity(plugin, player, task, 0L, retired);
    }

    private void expire(Stage stage) {
        if (stages.get(stage.playerId) == stage) {
            cancel(stage, true);
        }
    }

    private void cancelAsync(Stage stage) {
        if (!onPlayer(stage.player, () -> {
            if (stages.get(stage.playerId) == stage) {
                cancel(stage, true);
            }
        }, () -> forget(stage))) {
            forget(stage);
        }
    }

    private void fail(Stage stage, Throwable failure) {
        plugin.getLogger().log(Level.WARNING, "Unable to prepare arrival terrain in world " + stage.worldId, failure);
        cancelAsync(stage);
    }

    private void cancel(UUID playerId, boolean notify) {
        Stage stage = stages.remove(playerId);
        if (stage == null) {
            return;
        }
        if (notify && stage.player.isOnline()) {
            views.sendArrival(stage.player, new ArrivalMessage.Cancel(stage.token, 1));
        }
        release(stage);
    }

    private void cancel(Stage stage, boolean notify) {
        if (stages.remove(stage.playerId, stage)) {
            if (notify && stage.player.isOnline()) {
                views.sendArrival(stage.player, new ArrivalMessage.Cancel(stage.token, 1));
            }
            release(stage);
        }
    }

    private void forget(Stage stage) {
        stages.remove(stage.playerId, stage);
        release(stage);
    }

    private void forget(UUID playerId) {
        Stage stage = stages.remove(playerId);
        if (stage != null) {
            release(stage);
        }
    }

    private void release(Stage stage) {
        if (!stage.closed.compareAndSet(false, true)) {
            return;
        }
        capacity.release();
        Payload retained = stage.pending.getAndSet(null);
        if (retained != null) {
            retained.close();
        }
        synchronized (stage.leases) {
            for (ChunkLease lease : stage.leases) {
                lease.close();
            }
            stage.leases.clear();
        }
    }

    private static TravelMessage.TravelPose pose(Location location) {
        return new TravelMessage.TravelPose(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    public static final class Attempt {
        private static final Attempt EMPTY = new Attempt(null, null, null);
        private final BukkitArrivalPreparation service;
        private final Stage stage;
        private final Location destination;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean resumed = new AtomicBoolean();
        private World departureWorld;
        private boolean waited;

        private Attempt(BukkitArrivalPreparation service, Stage stage, Location destination) {
            this.service = service;
            this.stage = stage;
            this.destination = destination;
        }

        public boolean waitedForPreparation() {
            return waited;
        }

        public void beforeTeleport() {
            if (stage != null && !terminal.get()) {
                service.commit(stage, destination);
            }
        }

        public void complete() {
            if (stage != null && terminal.compareAndSet(false, true)) {
                service.forget(stage);
            }
        }

        public void cancel() {
            if (stage != null && terminal.compareAndSet(false, true)) {
                service.cancelAsync(stage);
            }
        }

        private void await(Consumer<Attempt> continuation, Runnable retired, int remaining) {
            if (resumed.get()) {
                return;
            }
            Stage current = service.stages.get(stage.playerId);
            if (terminal.get() || service.stopping || !stage.player.isOnline()
                || stage.player.getWorld() != departureWorld || current != null && current != stage) {
                retire(retired);
                return;
            }
            if (current == null || stage.state != null && stage.state.renderReady(System.currentTimeMillis())) {
                resume(continuation);
                return;
            }
            if (remaining == 0 || !ChunkPreSendSettings.active().enabled() || service.dirty(stage)) {
                service.cancel(stage, true);
                resume(continuation);
                return;
            }
            waited = true;
            if (!FoliaScheduler.runEntity(service.plugin, stage.player, () -> await(continuation, retired, remaining - 1),
                1L, () -> retire(retired))) {
                retire(retired);
            }
        }

        private void resume(Consumer<Attempt> continuation) {
            if (resumed.compareAndSet(false, true)) {
                continuation.accept(this);
            }
        }

        private void retire(Runnable retired) {
            if (resumed.compareAndSet(false, true)) {
                cancel();
                retired.run();
            }
        }

    }

    private final class Payload implements AutoCloseable {
        private final byte[] bytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Payload(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                retainedBytes.addAndGet(-bytes.length);
            }
        }
    }

    private record CapturedChunk(ChunkWorldContext context, String fingerprint, Payload payload) {}

    private static final class Stage {
        private final UUID token = UUID.randomUUID();
        private final Player player;
        private final UUID playerId;
        private final World world;
        private final UUID worldId;
        private final Location destination;
        private final int centerX;
        private final int centerZ;
        private final long worldVersion;
        private final WorldChangeTracker tracker;
        private final long created = System.currentTimeMillis();
        private final long deadline = created + LIFETIME_TICKS * 50L;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Payload> pending = new AtomicReference<>();
        private final List<ChunkLease> leases = new ArrayList<>((RADIUS * 2 + 1) * (RADIUS * 2 + 1));
        private final List<ChunkPosition> positions = new ArrayList<>((RADIUS * 2 + 1) * (RADIUS * 2 + 1));
        private volatile ArrivalPreparedState state;
        private volatile boolean claimed;
        private int captureIndex;
        private long totalBytes;
        private long sendWindow;
        private int sentBytes;

        private Stage(Player player, Location destination, WorldChangeTracker tracker) {
            this.player = player;
            this.playerId = player.getUniqueId();
            this.destination = destination;
            this.world = destination.getWorld();
            this.worldId = world.getUID();
            this.centerX = destination.getBlockX() >> 4;
            this.centerZ = destination.getBlockZ() >> 4;
            this.tracker = tracker;
            this.worldVersion = tracker == null ? -1 : tracker.currentVersion();
            for (int z = -RADIUS; z <= RADIUS; z++) {
                for (int x = -RADIUS; x <= RADIUS; x++) {
                    positions.add(new ChunkPosition(centerX + x, centerZ + z));
                }
            }
            positions.sort(Comparator.comparingInt(position -> Math.abs(position.x() - centerX) + Math.abs(position.z() - centerZ)));
        }

        private boolean matches(Location target) {
            return target.getWorld() == world && (target.getBlockX() >> 4) == centerX && (target.getBlockZ() >> 4) == centerZ
                && Math.abs(target.getY() - destination.getY()) <= 8;
        }
    }
}
