package art.arcane.wormholes.render.view;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;

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
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RegionSnapshotRefreshTest {
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
            List<EntityVisual> moving = ((ProjectionEntityView) fixture.view).getEntities(4.0D, 64.0D, 8.0D, 1.0D);
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
        private final AtomicInteger snapshotCaptures = new AtomicInteger();
        private final AtomicInteger equipmentCaptures = new AtomicInteger();
        private final AtomicReference<Material> material = new AtomicReference<Material>(Material.STONE);
        private final ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        private final ProjectionWorldChangeTracker previousTracker;
        private final RegionSnapshotWorldViewProvider provider;
        private final ProjectionWorldView view;
        private final Method capture;

        private Fixture() throws ReflectiveOperationException {
            LivingEntity entity = proxy(LivingEntity.class, this::entityValue);
            Chunk chunk = proxy(Chunk.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getChunkSnapshot" -> snapshot();
                case "getEntities" -> new Entity[] {entity};
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
            Plugin plugin = proxy(Plugin.class, (instance, method, arguments) -> {
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
            capture.invoke(provider, view, Integer.valueOf(0), Integer.valueOf(0), Long.valueOf(0L));
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
                case "getLocation" -> new Location(null, entityX.get(), 64.0D, 8.0D);
                case "getEyeLocation" -> new Location(null, entityX.get(), 65.6D, 8.0D);
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
