package art.arcane.wormholes.render.view;

import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.optics.view.WorldChangeTracker;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

final class RegionSnapshotRefreshTest {
    @Test
    void evictedSignedColumnsBecomeUnavailableWithoutRetiringAnEntityThatMovedToAnotherColumn() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture();
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            for (int column = -128; column <= 128; column++) {
                fixture.now.incrementAndGet();
                fixture.entityX.set((column << 4) + 1);
                fixture.entityZ.set((column << 4) + 8);
                fixture.capture(column, column);
                assertEquals(Material.STONE, fixture.view.sampleMaterial(column << 4, 64, column << 4));
            }
            assertNull(fixture.view.sampleMaterial(-128 << 4, 64, -128 << 4));
            assertFalse(fixture.view.isChunkReady(-128 << 4, -128 << 4));
            ProjectionEntityView entities = (ProjectionEntityView) fixture.view;
            List<EntitySnapshot> moved = entities.getEntities((128 << 4) + 1, 64, (128 << 4) + 8, 1);
            assertEquals(1, moved.size());
            assertEquals(fixture.entityId, moved.getFirst().id());
            fixture.tracker.markChanged(fixture.worldId, 128 << 4, 128 << 4);
            assertNull(fixture.view.sampleMaterial(128 << 4, 64, 128 << 4));
            assertFalse(fixture.view.isChunkReady(128 << 4, 128 << 4));
            fixture.capture(128, 128);
            assertTrue(fixture.view.isChunkReady(128 << 4, 128 << 4));
            assertEquals(Material.STONE, fixture.view.sampleMaterial(128 << 4, 64, 128 << 4));
        }
    }

    @Test
    void snapshotsCaptureDefaultVisibilityWithoutReadingEntitiesOnTheViewerThread() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture();
             MockedStatic<WormholesPlatform> visibility = mockStatic(WormholesPlatform.class)) {
            AtomicInteger visibilityReads = new AtomicInteger();
            Item item = proxy(Item.class, (instance, method, arguments) -> switch (method.getName()) {
                case "isVisibleByDefault" -> {
                    visibilityReads.incrementAndGet();
                    yield fixture.itemVisible.get();
                }
                case "getType" -> EntityType.ITEM;
                default -> fixture.entityValue(instance, method, arguments);
            });
            fixture.entities.set(new Entity[] {item});
            fixture.itemVisible.set(true);
            fixture.capture();
            ProjectionEntityView entityView = (ProjectionEntityView) fixture.view;
            Player observer = mock(Player.class);
            visibility.when(() -> WormholesPlatform.isEntityVisible(eq(observer), eq(fixture.entityId),
                anyBoolean(), eq(fixture.plugin))).thenAnswer(call -> call.getArgument(2));
            assertEquals(1, entityView.getEntities(4.0D, 64.0D, 8.0D, 4.0D).size());
            assertTrue(entityView.isVisibleTo(observer, fixture.entityId));

            fixture.itemVisible.set(false);
            assertTrue(entityView.isVisibleTo(observer, fixture.entityId));
            fixture.now.set(1_250L);
            fixture.capture();
            assertFalse(entityView.isVisibleTo(observer, fixture.entityId));
            assertEquals(1, entityView.getEntities(4.0D, 64.0D, 8.0D, 4.0D).size());

            fixture.itemVisible.set(true);
            fixture.now.set(1_500L);
            fixture.capture();
            assertEquals(1, entityView.getEntities(4.0D, 64.0D, 8.0D, 4.0D).size());
            assertTrue(entityView.isVisibleTo(observer, fixture.entityId));
            assertFalse(entityView.isVisibleTo(observer, UUID.randomUUID()));
            assertEquals(3, visibilityReads.get());
        }
    }

    @Test
    void continuousEntityCapturesDoNotPostponeTheBlockSnapshotBackstop() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.capture();
            assertEquals(1, fixture.snapshotCaptures.get());
            assertEquals(Material.STONE, fixture.view.sampleMaterial(0, 64, 0));
            long initialRevision = fixture.view.getRevision();
            fixture.material.set(Material.DIRT);

            for (long elapsed = 250L; elapsed < 60_000L; elapsed += 250L) {
                fixture.now.set(1_000L + elapsed);
                fixture.capture();
            }

            assertEquals(1, fixture.snapshotCaptures.get());
            assertEquals(initialRevision, fixture.view.getRevision());
            assertEquals(Material.STONE, fixture.view.sampleMaterial(0, 64, 0));

            fixture.now.set(61_000L);
            fixture.capture();

            assertEquals(2, fixture.snapshotCaptures.get());
            assertTrue(fixture.view.getRevision() > initialRevision);
            assertEquals(Material.DIRT, fixture.view.sampleMaterial(0, 64, 0));
        }
    }

    @Test
    void continuousMotionCapturesRefreshEntityStateEveryHalfSecond() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.capture();
            assertEquals(1, fixture.equipmentCaptures.get());
            fixture.now.set(1_250L);
            fixture.entityX.set(4L);
            fixture.capture();

            assertEquals(1, fixture.equipmentCaptures.get());
            List<EntitySnapshot> moving = ((ProjectionEntityView) fixture.view).getEntities(4.0D, 64.0D, 8.0D, 1.0D);
            assertEquals(1, moving.size());
            assertEquals(4.0D, moving.getFirst().x());

            fixture.now.set(1_500L);
            fixture.capture();
            assertEquals(2, fixture.equipmentCaptures.get());

            fixture.now.set(1_750L);
            fixture.capture();
            assertEquals(2, fixture.equipmentCaptures.get());

            fixture.now.set(2_000L);
            fixture.capture();
            assertEquals(3, fixture.equipmentCaptures.get());
            assertEquals(1, fixture.snapshotCaptures.get());
        }
    }

    @Test
    void shortMetadataTransitionsRefreshRegionVisualsBeforeTheStateBackstop() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture();
             MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class, CALLS_REAL_METHODS);
             MockedStatic<PacketBlobs> blobs = mockStatic(PacketBlobs.class)) {
            AtomicLong revision = new AtomicLong();
            AtomicInteger captures = new AtomicInteger();
            AtomicReference<byte[]> values = new AtomicReference<>(new byte[] {0, 0, 0});
            platform.when(() -> WormholesPlatform.entityMetadataFingerprint(fixture.entities.get()[0]))
                .thenAnswer(call -> revision.get());
            blobs.when(() -> PacketBlobs.captureMetadata(fixture.entities.get()[0])).thenAnswer(call -> {
                captures.incrementAndGet();
                return values.get();
            });
            blobs.when(() -> PacketBlobs.captureEquipment(fixture.entities.get()[0])).thenReturn(EntitySnapshot.EMPTY);
            blobs.when(() -> PacketBlobs.readMetadata(any(byte[].class))).thenReturn(List.of());
            blobs.when(() -> PacketBlobs.readEquipment(any(byte[].class))).thenReturn(List.of());
            fixture.capture();
            ProjectionEntityView view = (ProjectionEntityView) fixture.view;
            int version = view.getStateVersion(fixture.entityId);
            byte[][] transitions = {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {0, 1, 4}};
            for (byte[] transition : transitions) {
                revision.incrementAndGet();
                values.set(transition);
                fixture.now.addAndGet(250L);
                fixture.capture();
                EntitySnapshot visual = view.getEntities(1, 64, 8, 1).getFirst();
                assertEquals(transition[0], visual.metadata()[0]);
                assertEquals(transition[1], visual.metadata()[1]);
                assertEquals(transition[2], visual.metadata()[2]);
                assertTrue(view.getStateVersion(fixture.entityId) > version);
                version = view.getStateVersion(fixture.entityId);
            }
            fixture.now.addAndGet(250L);
            fixture.capture();
            assertEquals(5, captures.get());
            assertEquals(version, view.getStateVersion(fixture.entityId));
            assertEquals(1, fixture.snapshotCaptures.get());
        }
    }

    @Test
    void trackedChangesRefreshBlocksImmediatelyWithoutWaitingForTheBackstop() throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture()) {
            fixture.capture();
            long initialRevision = fixture.view.getRevision();
            fixture.material.set(Material.DIRT);
            fixture.tracker.markChanged(fixture.worldId, 0, 0);
            fixture.now.set(1_250L);
            fixture.capture();

            assertEquals(2, fixture.snapshotCaptures.get());
            assertTrue(fixture.view.getRevision() > initialRevision);
            assertEquals(Material.DIRT, fixture.view.sampleMaterial(0, 64, 0));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final UUID worldId = UUID.randomUUID();
        private final UUID entityId = UUID.randomUUID();
        private final AtomicLong now = new AtomicLong(1_000L);
        private final AtomicLong entityX = new AtomicLong(1L);
        private final AtomicLong entityZ = new AtomicLong(8L);
        private final AtomicInteger snapshotCaptures = new AtomicInteger();
        private final AtomicInteger equipmentCaptures = new AtomicInteger();
        private final AtomicReference<Material> material = new AtomicReference<Material>(Material.STONE);
        private final AtomicReference<Entity[]> entities = new AtomicReference<Entity[]>();
        private final AtomicBoolean itemVisible = new AtomicBoolean();
        private final WorldChangeTracker tracker = new WorldChangeTracker();
        private final WorldChangeTracker previousTracker;
        private final Plugin plugin;
        private final RegionSnapshotWorldViewProvider provider;
        private final ProjectionWorldView view;
        private final Method capture;

        private Fixture() throws ReflectiveOperationException {
            LivingEntity entity = proxy(LivingEntity.class, this::entityValue);
            entities.set(new Entity[] {entity});
            Chunk chunk = proxy(Chunk.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getChunkSnapshot" -> snapshot();
                case "getEntities" -> entities.get();
                default -> objectValue(instance, method, arguments);
            });
            World world = proxy(World.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getUID" -> worldId;
                case "isChunkLoaded" -> Boolean.TRUE;
                case "getChunkAt" -> chunk;
                case "getMinHeight" -> Integer.valueOf(-64);
                case "getMaxHeight" -> Integer.valueOf(320);
                case "getTime" -> Long.valueOf(6_000L);
                default -> objectValue(instance, method, arguments);
            });
            plugin = proxy(Plugin.class, (instance, method, arguments) -> {
                throw new AssertionError("Unexpected plugin access: " + method.getName());
            });
            provider = new RegionSnapshotWorldViewProvider(plugin, now::get);
            synchronized (Bukkit.class) {
                Field serverField = Bukkit.class.getDeclaredField("server");
                serverField.setAccessible(true);
                Object previousServer = serverField.get(null);
                Server server = proxy(Server.class, (instance, method, arguments) -> switch (method.getName()) {
                    case "createBlockData" -> blockData(Material.AIR);
                    default -> objectValue(instance, method, arguments);
                });
                serverField.set(null, server);
                try {
                    view = provider.view(world);
                } finally {
                    serverField.set(null, previousServer);
                }
            }
            capture = RegionSnapshotWorldViewProvider.class.getDeclaredMethod(
                "captureLoaded", view.getClass(), Integer.TYPE, Integer.TYPE, Long.TYPE);
            capture.setAccessible(true);
            previousTracker = Wormholes.projectionChangeTracker;
            Wormholes.projectionChangeTracker = tracker;
        }

        private void capture() throws ReflectiveOperationException {
            capture(0, 0);
        }

        private void capture(int x, int z) throws ReflectiveOperationException {
            capture.invoke(provider, view, Integer.valueOf(x), Integer.valueOf(z),
                Long.valueOf(RegionSnapshotWorldViewProvider.chunkLookupKey(x, z)));
        }

        private ChunkSnapshot snapshot() {
            snapshotCaptures.incrementAndGet();
            Material captured = material.get();
            return proxy(ChunkSnapshot.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getBlockType" -> captured;
                case "getBlockData" -> blockData(captured);
                default -> objectValue(instance, method, arguments);
            });
        }

        private Object entityValue(Object instance, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "getUniqueId" -> entityId;
                case "isValid", "isOnGround" -> Boolean.TRUE;
                case "getLocation" -> new Location(null, entityX.get(), 64.0D, entityZ.get());
                case "getEyeLocation" -> new Location(null, entityX.get(), 65.6D, entityZ.get());
                case "getVelocity" -> new Vector();
                case "getType" -> EntityType.ZOMBIE;
                case "getHeight" -> Double.valueOf(1.8D);
                case "getEquipment" -> {
                    equipmentCaptures.incrementAndGet();
                    yield null;
                }
                default -> objectValue(instance, method, arguments);
            };
        }

        @Override
        public void close() {
            provider.close();
            Wormholes.projectionChangeTracker = previousTracker;
        }
    }

    private static BlockData blockData(Material material) {
        return proxy(BlockData.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getMaterial" -> material;
            default -> objectValue(instance, method, arguments);
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object objectValue(Object instance, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> Boolean.valueOf(instance == arguments[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(instance));
            case "toString" -> method.getDeclaringClass().getSimpleName();
            default -> primitiveDefault(method.getReturnType());
        };
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        return null;
    }
}
