package art.arcane.wormholes.chunk.presend;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitChunkPreSendPlatformTest {
    private static final Plugin PLUGIN = proxy(Plugin.class);
    private static final World WORLD = proxy(World.class);

    @Test
    void theServerViewDistanceIsUsedOnlyWhenThePlayerHasNotNegotiatedOne() {
        ManualOperations operations = new ManualOperations();
        operations.sendViewDistance = 0;
        operations.serverViewDistance = 12;
        BukkitChunkPreSendPlatform platform = platform(new ManualDelivery(), operations);

        assertEquals(12, platform.clientViewDistance(player(0.0D, 0.0D)));

        operations.sendViewDistance = 7;
        assertEquals(7, platform.clientViewDistance(player(0.0D, 0.0D)));
    }

    @Test
    void thePlayerChunkIsDerivedFromTheLivePositionAndFloorsCorrectlyForNegativeCoordinates() {
        BukkitChunkPreSendPlatform platform = platform(new ManualDelivery(), new ManualOperations());
        Player player = player(35.5D, -17.25D);

        assertEquals(2, platform.chunkX(player));
        assertEquals(-2, platform.chunkZ(player));
    }

    @Test
    void aRuntimeWithoutTheSentChunkQueryNeverClaimsAChunkIsAlreadyOnTheClient() {
        ManualOperations operations = new ManualOperations();
        operations.sentChunkQuerySupported = false;
        operations.chunkSent = true;
        BukkitChunkPreSendPlatform platform = platform(new ManualDelivery(), operations);

        assertFalse(platform.alreadySent(player(0.0D, 0.0D), 3, 4));

        operations.sentChunkQuerySupported = true;
        assertTrue(platform.alreadySent(player(0.0D, 0.0D), 3, 4));
    }

    @Test
    void aNullWorldNeverReachesTheBukkitChunkLookup() {
        ManualOperations operations = new ManualOperations();
        BukkitChunkPreSendPlatform platform = platform(new ManualDelivery(), operations);

        assertFalse(platform.chunkLoaded(null, 0, 0));
        assertFalse(platform.regionOwned(null, 0, 0, 0, 0));
        assertEquals(0, operations.chunkLoadedCalls);
        assertEquals(0, operations.regionOwnedCalls);
    }

    @Test
    void theWholeBurstSquareIsHandedToTheRegionOwnershipCheckInOneCall() {
        ManualOperations operations = new ManualOperations();
        BukkitChunkPreSendPlatform platform = platform(new ManualDelivery(), operations);

        assertTrue(platform.regionOwned(WORLD, -4, -9, 6, 11));

        assertEquals(1, operations.regionOwnedCalls);
        assertEquals(List.of(-4, -9, 6, 11), operations.lastOwnershipBox);
    }

    @Test
    void aRejectedRegionSchedulerIsReportedAsARejectionRatherThanSwallowed() {
        ManualOperations operations = new ManualOperations();
        operations.regionAccepted = false;
        BukkitChunkPreSendPlatform platform = platform(new ManualDelivery(), operations);

        assertFalse(platform.scheduleForRegion(WORLD, 0, 0, () -> {
        }, 1L));
    }

    @Test
    void aNullWorldIsNeverHandedToTheRegionScheduler() {
        ManualOperations operations = new ManualOperations();
        BukkitChunkPreSendPlatform platform = platform(new ManualDelivery(), operations);

        assertFalse(platform.scheduleForRegion(null, 0, 0, () -> {
        }, 1L));
        assertEquals(0, operations.regionScheduleCalls);
    }

    @Test
    void theRollbackContinuationIsScheduledOnTheRegionThatOwnsTheChunkAndNotOnThePlayer() {
        ManualOperations operations = new ManualOperations();
        BukkitChunkPreSendPlatform platform = platform(new ManualDelivery(), operations);

        assertTrue(platform.scheduleForRegion(WORLD, -13, 7, () -> {
        }, 1L));

        assertSame(WORLD, operations.lastScheduledWorld);
        assertEquals(-13, operations.lastScheduledChunkX);
        assertEquals(7, operations.lastScheduledChunkZ);
    }

    @Test
    void aDelayOfZeroTicksIsRaisedToOneSoTheContinuationLandsOnALaterTick() {
        ManualOperations operations = new ManualOperations();
        BukkitChunkPreSendPlatform platform = platform(new ManualDelivery(), operations);

        assertTrue(platform.scheduleForRegion(WORLD, 0, 0, () -> {
        }, 0L));
        assertEquals(1L, operations.lastDelayTicks);
    }

    @Test
    void supportIsDelegatedToTheDeliveryBackendSoAnUnsupportedRuntimeShutsTheFeatureOff() {
        ManualDelivery delivery = new ManualDelivery();
        delivery.supported = false;
        BukkitChunkPreSendPlatform platform = platform(delivery, new ManualOperations());

        assertFalse(platform.supported());
    }

    @Test
    void announcementsAndChunksAreHandedToTheDeliveryBackendUnchanged() {
        ManualDelivery delivery = new ManualDelivery();
        BukkitChunkPreSendPlatform platform = platform(delivery, new ManualOperations());
        Player player = player(0.0D, 0.0D);

        assertTrue(platform.announceViewCenter(player, 4, -8));
        assertTrue(platform.sendChunk(player, WORLD, 4, -8));

        assertEquals(List.of(new ChunkCoordinate(4, -8)), delivery.announced);
        assertEquals(List.of(new ChunkCoordinate(4, -8)), delivery.chunks);
        assertSame(WORLD, delivery.lastWorld);
    }

    private static BukkitChunkPreSendPlatform platform(
        ChunkPreSendDelivery delivery,
        BukkitChunkPreSendPlatform.Operations operations
    ) {
        return new BukkitChunkPreSendPlatform(PLUGIN, delivery, operations);
    }

    private static Player player(double x, double z) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "isOnline" -> true;
                case "getWorld" -> WORLD;
                case "getLocation" -> {
                    Location target = (Location) arguments[0];
                    target.setX(x);
                    target.setY(64.0D);
                    target.setZ(z);
                    yield target;
                }
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "PlayerProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (instance, method, arguments) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> type.getSimpleName() + "Proxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static final class ManualDelivery implements ChunkPreSendDelivery {
        private final List<ChunkCoordinate> announced = new ArrayList<>();
        private final List<ChunkCoordinate> chunks = new ArrayList<>();
        private boolean supported = true;
        private World lastWorld;

        @Override
        public boolean supported() {
            return supported;
        }

        @Override
        public boolean announceViewCenter(Player player, int chunkX, int chunkZ) {
            announced.add(new ChunkCoordinate(chunkX, chunkZ));
            return true;
        }

        @Override
        public boolean sendChunk(Player player, World world, int chunkX, int chunkZ) {
            lastWorld = world;
            chunks.add(new ChunkCoordinate(chunkX, chunkZ));
            return true;
        }
    }

    private static final class ManualOperations implements BukkitChunkPreSendPlatform.Operations {
        private int sendViewDistance = 8;
        private int serverViewDistance = 10;
        private boolean chunkLoaded = true;
        private boolean regionOwned = true;
        private boolean sentChunkQuerySupported = true;
        private boolean chunkSent;
        private boolean regionAccepted = true;
        private int chunkLoadedCalls;
        private int regionOwnedCalls;
        private int regionScheduleCalls;
        private long lastDelayTicks;
        private List<Integer> lastOwnershipBox = List.of();
        private World lastScheduledWorld;
        private int lastScheduledChunkX;
        private int lastScheduledChunkZ;

        @Override
        public int sendViewDistance(Player player) {
            return sendViewDistance;
        }

        @Override
        public int serverViewDistance() {
            return serverViewDistance;
        }

        @Override
        public boolean chunkLoaded(World world, int chunkX, int chunkZ) {
            chunkLoadedCalls++;
            return chunkLoaded;
        }

        @Override
        public boolean regionOwned(World world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
            regionOwnedCalls++;
            lastOwnershipBox = List.of(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
            return regionOwned;
        }

        @Override
        public boolean sentChunkQuerySupported() {
            return sentChunkQuerySupported;
        }

        @Override
        public boolean chunkSent(Player player, int chunkX, int chunkZ) {
            return chunkSent;
        }

        @Override
        public boolean runRegion(
            Plugin plugin,
            World world,
            int chunkX,
            int chunkZ,
            Runnable command,
            long delayTicks
        ) {
            regionScheduleCalls++;
            lastScheduledWorld = world;
            lastScheduledChunkX = chunkX;
            lastScheduledChunkZ = chunkZ;
            lastDelayTicks = delayTicks;
            return regionAccepted;
        }

        @Override
        public long nanoTime() {
            return 0L;
        }
    }
}
