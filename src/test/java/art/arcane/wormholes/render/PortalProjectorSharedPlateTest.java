package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateCache;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

/**
 * The plate is shared per portal, so its revision may only carry portal-scoped inputs. Two observers
 * of one gateway whose frustum fits differ - one coarsens to stay inside the cell budget because its
 * client renders further, the other does not - must land on the same plate instead of rebuilding it
 * for each other every frame.
 */
public final class PortalProjectorSharedPlateTest {
    private static final UUID PORTAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Test
    public void twoObserversWhoseFitsDifferShareOnePlate() throws Exception {
        int budget = Settings.PROJECTION_MAX_PROJECTED_CELLS;
        boolean mergeRuns = FidelitySettings.lodMergeRuns;
        Settings.PROJECTION_MAX_PROJECTED_CELLS = 10_000;
        FidelitySettings.lodMergeRuns = false;
        try {
            twoObserversShareOnePlate();
        } finally {
            Settings.PROJECTION_MAX_PROJECTED_CELLS = budget;
            FidelitySettings.lodMergeRuns = mergeRuns;
        }
    }

    private void twoObserversShareOnePlate() throws Exception {
        PortalStructure structure = structure();
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        ILocalPortal portal = portal(structure, frame);
        StoneView destinationView = new StoneView();

        List<ViewPlateBuilder.Job> scheduled = new ArrayList<>();
        ViewPlateCache cache = new ViewPlateCache(4_000_000L, scheduled::add);
        PortalProjector first = projector(portal, structure, destinationView, cache, 32);
        PortalProjector second = projector(portal, structure, destinationView, cache, 4);

        assertTrue(fitCoarse(first, structure, frame, eye(structure, 4.0D)),
            "the far-rendering client must coarsen to stay inside the cell budget");
        assertFalse(fitCoarse(second, structure, frame, eye(structure, 4.0D)),
            "the short-rendering client must still fit without coarsening");

        assertNull(acquirePlate(first, eye(structure, 4.0D)), "the first observer misses and schedules the build");
        assertEquals(1, scheduled.size());
        ViewPlate built = run(scheduled.get(0));
        cache.publish(built);

        ViewPlate shared = acquirePlate(second, eye(structure, 4.0D));
        assertSame(built, shared, "the second observer must hit the plate the first one built");
        assertEquals(1, scheduled.size(), "a differing per-observer fit must not schedule a second build");
        assertEquals(1L, cache.buildsCompleted());
        assertNotNull(acquirePlate(first, eye(structure, 4.0D)), "the first observer keeps hitting the same plate");
        assertEquals(1, scheduled.size());
    }

    private static ViewPlate run(ViewPlateBuilder.Job job) {
        while (!job.step(Integer.MAX_VALUE)) {
        }
        return job.result();
    }

    private static ViewPlate acquirePlate(PortalProjector projector, Location eye) throws Exception {
        Method method = PortalProjector.class.getDeclaredMethod("acquirePlate", Location.class, boolean.class, long.class);
        method.setAccessible(true);
        return (ViewPlate) method.invoke(projector, eye, Boolean.FALSE, Long.valueOf(7L));
    }

    /** Runs this projector's own frustum fit the way a projection pass does, and reports its coarsening. */
    private static boolean fitCoarse(PortalProjector projector, PortalStructure structure, PortalFrame frame, Location eye)
        throws Exception {
        Field frustumField = PortalProjector.class.getDeclaredField("viewFrustum");
        frustumField.setAccessible(true);
        ProjectorViewFrustum frustum = (ProjectorViewFrustum) frustumField.get(projector);
        frustum.setLodPolicy(LodPolicy.current(null));
        frustum.fit(projector.getObserver(), structure, frame, eye, 100.0D, 24.0D);
        return frustum.fittedCoarse();
    }

    private static Location eye(PortalStructure structure, double outward) {
        return structure.getCenter().add(0.0D, 0.0D, outward);
    }

    private static PortalProjector projector(ILocalPortal portal, PortalStructure structure,
                                             ProjectionWorldView destinationView, ViewPlateCache cache,
                                             int clientViewDistance) throws Exception {
        PortalProjector projector = withBukkitServer(() -> new PortalProjector(portal, viewer(clientViewDistance), null,
            world -> destinationView, () -> true, new EntityRenderLocalOcclusionArbiter(), cache));
        Field field = PortalProjector.class.getDeclaredField("destination");
        field.setAccessible(true);
        ProjectorDestination destination = (ProjectorDestination) field.get(projector);
        destination.dest = portal;
        destination.destAnchor = portal;
        destination.destView = destinationView;
        destination.localView = destinationView;
        destination.originX = structure.getCenter().getX();
        destination.originY = structure.getCenter().getY();
        destination.originZ = structure.getCenter().getZ();
        destination.mirrorMode = false;
        destination.mirrorRotationQuarterTurns = 0;
        return projector;
    }

    private static Player viewer(int clientViewDistance) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getClientViewDistance", "getFoodLevel" -> Integer.valueOf(clientViewDistance);
                case "getUniqueId" -> id;
                case "getName", "toString" -> "viewer-" + clientViewDistance;
                case "isOnline" -> Boolean.TRUE;
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                default -> primitiveDefault(method.getReturnType());
            });
    }

    private static PortalStructure structure() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(0));
        values.put("x2", Integer.valueOf(8));
        values.put("y1", Integer.valueOf(64));
        values.put("y2", Integer.valueOf(72));
        values.put("z1", Integer.valueOf(0));
        values.put("z2", Integer.valueOf(0));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }

    private static ILocalPortal portal(PortalStructure structure, PortalFrame frame) {
        Vector origin = structure.getCenter().toVector();
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(), new Class<?>[] {ILocalPortal.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getStructure" -> structure;
                case "getFrame" -> frame;
                case "getOrigin" -> origin;
                case "getId" -> PORTAL_ID;
                case "getWorld" -> null;
                case "getNetworkViewDepth" -> Integer.valueOf(100);
                case "getNetworkViewLateralPad" -> Integer.valueOf(2);
                case "getName", "toString" -> "shared-plate-portal";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                default -> primitiveDefault(method.getReturnType());
            });
    }

    private static Object primitiveDefault(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (returnType == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (returnType == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        return null;
    }

    private static <T> T withBukkitServer(ProjectorFactory<T> factory) throws ReflectiveOperationException {
        synchronized (Bukkit.class) {
            Field serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            Object previous = serverField.get(null);
            serverField.set(null, fakeServer());
            try {
                return factory.create();
            } finally {
                serverField.set(null, previous);
            }
        }
    }

    private static Server fakeServer() {
        return (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if ("createBlockData".equals(method.getName())) {
                    Material material = args[0] instanceof Material value ? value : Material.STONE;
                    return blockData(material);
                }
                return switch (method.getName()) {
                    case "getName", "toString" -> "PortalProjectorSharedPlateTestServer";
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "equals" -> Boolean.valueOf(proxy == args[0]);
                    default -> primitiveDefault(method.getReturnType());
                };
            });
    }

    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "getAsString", "toString" -> material.getKey().toString();
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> primitiveDefault(method.getReturnType());
            });
    }

    private interface ProjectorFactory<T> {
        T create();
    }

    private static final class StoneView implements ProjectionWorldView {
        private final BlockData stone = blockData(Material.STONE);

        @Override
        public World getWorld() {
            return null;
        }

        @Override
        public int getMinHeight() {
            return -64;
        }

        @Override
        public int getMaxHeight() {
            return 320;
        }

        @Override
        public BlockData sampleBlockData(int x, int y, int z) {
            return stone;
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return ProjectionWorldView.LIGHT_UNAVAILABLE;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }
    }
}
