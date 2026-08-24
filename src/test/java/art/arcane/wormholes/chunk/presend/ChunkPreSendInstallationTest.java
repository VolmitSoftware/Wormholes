package art.arcane.wormholes.chunk.presend;

import art.arcane.wormholes.service.WormholesTelemetry;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPreSendInstallationTest {
    @AfterEach
    void tearDown() {
        BukkitChunkPreSendProvider.shutdown();
        ChunkPreSendSettings.reset();
    }

    @Test
    void anUnconfiguredServerHasThePreSendOff() {
        ChunkPreSendSettings.reset();

        assertFalse(ChunkPreSendSettings.active().enabled());
        assertFalse(ChunkPreSendSettings.active().usable());
    }

    @Test
    void configuredValuesAreClampedOnTheWayIntoTheLiveSnapshot() {
        ChunkPreSendSettings.apply(true, 999, -5, 10_000_000);

        ChunkPreSendOptions active = ChunkPreSendSettings.active();
        assertEquals(ChunkPreSendOptions.MAX_RADIUS_CHUNKS, active.radiusChunks());
        assertEquals(0, active.maxChunks());
        assertEquals(
            ChunkPreSendOptions.MAX_BUDGET_MICROS * ChunkPreSendOptions.NANOS_PER_MICRO,
            active.budgetNanos()
        );
        assertFalse(active.usable());
    }

    @Test
    void aServiceIsReachableOnlyAfterInstallationAndOnlyOnce() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform();
        ChunkPreSendService<World, Player> service = new ChunkPreSendService<>(bukkitPlatform());

        assertFalse(BukkitChunkPreSendProvider.installed());
        assertThrows(IllegalStateException.class, BukkitChunkPreSendProvider::service);
        assertSame(service, BukkitChunkPreSendProvider.install(service));
        assertSame(service, BukkitChunkPreSendProvider.service());
        assertSame(service, BukkitChunkPreSendProvider.install(service));
        assertThrows(
            IllegalStateException.class,
            () -> BukkitChunkPreSendProvider.install(new ChunkPreSendService<>(bukkitPlatform()))
        );
        assertNotNull(platform);
    }

    @Test
    void shutdownReleasesTheServiceAndReturnsTheKnobsToOff() {
        ChunkPreSendSettings.apply(true, 4, 32, 2000);
        BukkitChunkPreSendProvider.install(new ChunkPreSendService<>(bukkitPlatform()));

        BukkitChunkPreSendProvider.shutdown();

        assertFalse(BukkitChunkPreSendProvider.installed());
        assertFalse(ChunkPreSendSettings.active().enabled());
    }

    @Test
    void aDeliveredProviderTransactionRollsBackExactlyOnceAfterFailedMovement() {
        AtomicInteger announcements = new AtomicInteger();
        AtomicInteger chunks = new AtomicInteger();
        World world = world();
        Player player = player();
        ChunkPreSendService<World, Player> service = deliveringService(world, announcements, chunks);
        BukkitChunkPreSendProvider.install(service);

        BukkitChunkPreSendTransaction transaction = BukkitChunkPreSendProvider.preSend(
            player,
            new Location(world, 512.0D, 80.0D, 512.0D)
        );

        assertNotNull(transaction);
        assertEquals(ChunkPreSendOutcome.PRE_SENT_PARTIAL, transaction.outcome());
        assertEquals(1, transaction.sentChunks());
        assertEquals(1, announcements.get());
        assertEquals(1, chunks.get());
        assertEquals(ChunkPreSendRollbackOutcome.RESTORED, transaction.rollback());
        assertEquals(ChunkPreSendRollbackOutcome.ALREADY_CONSUMED, transaction.rollback());
        assertEquals(2, announcements.get());
        assertEquals(2, chunks.get());
    }

    @Test
    void aCommittedProviderTransactionCannotRollBackLater() {
        AtomicInteger announcements = new AtomicInteger();
        AtomicInteger chunks = new AtomicInteger();
        World world = world();
        Player player = player();
        BukkitChunkPreSendProvider.install(deliveringService(world, announcements, chunks));

        BukkitChunkPreSendTransaction transaction = BukkitChunkPreSendProvider.preSend(
            player,
            new Location(world, 512.0D, 80.0D, 512.0D)
        );

        assertNotNull(transaction);
        assertTrue(transaction.commit());
        assertFalse(transaction.commit());
        assertEquals(ChunkPreSendRollbackOutcome.ALREADY_CONSUMED, transaction.rollback());
        assertEquals(1, announcements.get());
        assertEquals(1, chunks.get());
    }

    @Test
    void anUnavailableOrSkippedProviderDoesNotCreateATransaction() {
        World world = world();
        Player player = player();

        assertNull(BukkitChunkPreSendProvider.preSend(player, new Location(world, 0.0D, 64.0D, 0.0D)));

        BukkitChunkPreSendProvider.install(new ChunkPreSendService<>(
            deliveringPlatform(world, new AtomicInteger(), new AtomicInteger()),
            ChunkPreSendOptions::disabled
        ));

        assertNull(BukkitChunkPreSendProvider.preSend(player, new Location(world, 0.0D, 64.0D, 0.0D)));
    }

    @Test
    void aRuntimeWithoutTheVanillaChunkPacketDegradesToALoggedNoOp() {
        RecordingHandler handler = new RecordingHandler();
        Logger logger = Logger.getLogger("ChunkPreSendInstallationTest-unsupported");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);

        NmsChunkPreSendDelivery delivery = new NmsChunkPreSendDelivery(plugin(logger));

        assertFalse(delivery.supported(),
            "net.minecraft is absent from the unit-test classpath, which is exactly the Spigot-style degrade path");
        assertFalse(delivery.sendChunk(null, null, 0, 0));
        assertTrue(handler.messages.stream().anyMatch(message -> message.contains("chunk pre-send disabled")));
        logger.removeHandler(handler);
    }

    @Test
    void anUnavailablePacketPipelineFailsTheAnnouncementInsteadOfThrowingIntoTheTraversal() {
        RecordingHandler handler = new RecordingHandler();
        Logger logger = Logger.getLogger("ChunkPreSendInstallationTest-announce");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        NmsChunkPreSendDelivery delivery = new NmsChunkPreSendDelivery(plugin(logger));
        long before = failureCount("PRESEND_VIEW_CENTER_DELIVERY_FAILED");

        assertFalse(delivery.announceViewCenter(player(), 0, 0));

        assertEquals(before + 1L, failureCount("PRESEND_VIEW_CENTER_DELIVERY_FAILED"));
        assertTrue(handler.thrown.size() >= 1, "the first delivery failure must be logged with its cause");
        logger.removeHandler(handler);
    }

    @Test
    void everyChunkInABurstReusesTheAlreadyResolvedNmsMembersInsteadOfRewalkingTheHierarchy() {
        NmsChunkPreSendDelivery delivery =
            new NmsChunkPreSendDelivery(plugin(Logger.getLogger("ChunkPreSendInstallationTest-members")));

        Method firstHandle = delivery.handleMethod(SampleHandleOwner.class);
        Field firstConnection = delivery.connectionField(SampleConnectionOwner.class);

        assertNotNull(firstHandle);
        assertNotNull(firstConnection);
        for (int chunk = 0; chunk < 49; chunk++) {
            assertSame(firstHandle, delivery.handleMethod(SampleHandleOwner.class),
                "resolving the handle method per chunk would allocate a fresh Method inside the commitment window");
            assertSame(firstConnection, delivery.connectionField(SampleConnectionOwner.class));
        }
    }

    @Test
    void aMemberThatIsAbsentOnThisRuntimeIsRememberedAsAbsent() {
        NmsChunkPreSendDelivery delivery =
            new NmsChunkPreSendDelivery(plugin(Logger.getLogger("ChunkPreSendInstallationTest-absent")));

        assertNull(delivery.handleMethod(SampleConnectionOwner.class));
        assertNull(delivery.handleMethod(SampleConnectionOwner.class));
    }

    @Test
    void compatiblePacketConstructorSelectionIsReusedAcrossAChunkBurst() {
        NmsChunkPreSendDelivery delivery = new NmsChunkPreSendDelivery(
            plugin(Logger.getLogger("ChunkPreSendInstallationTest-constructors")),
            SampleChunkPacket.class
        );

        Constructor<?> selected = delivery.packetConstructor(SampleChunk.class, SampleLightEngine.class);

        assertNotNull(selected);
        assertEquals(4, selected.getParameterCount());
        for (int chunk = 0; chunk < 49; chunk++) {
            assertSame(selected, delivery.packetConstructor(SampleChunk.class, SampleLightEngine.class));
        }
        assertNull(delivery.packetConstructor(String.class, SampleLightEngine.class));
    }

    private static long failureCount(String reason) {
        Long count = WormholesTelemetry.failureBreakdown().get(reason);
        return count == null ? 0L : count;
    }

    private static BukkitChunkPreSendPlatform bukkitPlatform() {
        return new BukkitChunkPreSendPlatform(plugin(Logger.getLogger("ChunkPreSendInstallationTest-plugin")));
    }

    private static ChunkPreSendService<World, Player> deliveringService(
        World world,
        AtomicInteger announcements,
        AtomicInteger chunks
    ) {
        return new ChunkPreSendService<>(
            deliveringPlatform(world, announcements, chunks),
            () -> ChunkPreSendOptions.of(true, 1, 1, 25_000)
        );
    }

    private static ChunkPreSendPlatform<World, Player> deliveringPlatform(
        World world,
        AtomicInteger announcements,
        AtomicInteger chunks
    ) {
        return new ChunkPreSendPlatform<World, Player>() {
            @Override
            public boolean supported() {
                return true;
            }

            @Override
            public boolean online(Player player) {
                return true;
            }

            @Override
            public World world(Player player) {
                return world;
            }

            @Override
            public int chunkX(Player player) {
                return 0;
            }

            @Override
            public int chunkZ(Player player) {
                return 0;
            }

            @Override
            public int clientViewDistance(Player player) {
                return 0;
            }

            @Override
            public boolean chunkLoaded(World target, int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean regionOwned(
                World target,
                int minChunkX,
                int minChunkZ,
                int maxChunkX,
                int maxChunkZ
            ) {
                return true;
            }

            @Override
            public boolean alreadySent(Player player, int chunkX, int chunkZ) {
                return false;
            }

            @Override
            public boolean announceViewCenter(Player player, int chunkX, int chunkZ) {
                announcements.incrementAndGet();
                return true;
            }

            @Override
            public boolean sendChunk(Player player, World target, int chunkX, int chunkZ) {
                chunks.incrementAndGet();
                return true;
            }

            @Override
            public boolean scheduleForRegion(
                World target,
                int chunkX,
                int chunkZ,
                Runnable command,
                long delayTicks
            ) {
                command.run();
                return true;
            }

            @Override
            public long nanoTime() {
                return 0L;
            }
        };
    }

    private static World world() {
        UUID worldId = new UUID(17L, 29L);
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getUID" -> worldId;
                case "getName" -> "presend-world";
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "PreSendWorldProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "PlayerProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static Plugin plugin(Logger logger) {
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getLogger" -> logger;
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "PluginProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static final class SampleHandleOwner {
        Object getHandle() {
            return this;
        }
    }

    private static final class SampleConnectionOwner {
        private Object connection;

        Object connection() {
            return connection;
        }
    }

    private static final class SampleChunk {
    }

    private static final class SampleLightEngine {
    }

    private static final class SampleChunkPacket {
        private SampleChunkPacket(SampleChunk chunk, SampleLightEngine lightEngine, Object filter, Object packetData) {
        }

        private SampleChunkPacket(SampleChunk chunk, SampleLightEngine lightEngine, Object filter, Object packetData,
                                  boolean modifyBlocks) {
        }
    }

    private static final class RecordingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();
        private final List<Throwable> thrown = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(String.valueOf(record.getMessage()));
            if (record.getThrown() != null) {
                thrown.add(record.getThrown());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
