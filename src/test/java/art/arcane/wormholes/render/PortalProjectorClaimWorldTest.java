package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class PortalProjectorClaimWorldTest {
    private static final UUID WORLD_A_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a7");
    private static final UUID WORLD_B_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b7");
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000077");
    private static final UUID PORTAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000078");
    private static final long CELL = ProjectionCellKey.pack(3, 70, 5);

    @Test
    public void closingRevertsTheWorldTheFakeBlocksWereActuallySentTo() throws Exception {
        World worldA = world(WORLD_A_ID);
        World worldB = world(WORLD_B_ID);
        AtomicReference<World> portalWorld = new AtomicReference<World>(worldA);
        List<Location> sent = new ArrayList<Location>();
        Player observer = player(OBSERVER_ID, worldB, sent);
        ILocalPortal portal = portal(PORTAL_ID, portalWorld);
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(
            PortalProjectorClaimWorldTest::view,
            (player, chunkX, chunkZ) -> true);

        PortalProjector projector = withBukkitServer(() -> new PortalProjector(portal, observer, arbiter,
            PortalProjectorClaimWorldTest::view, () -> true));

        portalWorld.set(worldB);
        projector.noteClaimWorld(worldB);
        assertEquals(1, arbiter.submit(observer, portal, worldB, claims(), 2.0D, false).getBlockChanges(),
            "the pass must send its fake block into the world the portal now lives in");
        assertEquals(1, sent.size());
        assertFalse(arbiter.isIdle());

        assertTrue(projector.releaseClaims(),
            "closing must revert the projection rather than abandon it in the world it was sent to");
        assertEquals(2, sent.size(), "the observer must be told the real block again");
        assertTrue(arbiter.isIdle(), "no claim state may survive a close");
    }

    @Test
    public void closingAfterTheObserverLeavesTheClaimWorldDiscardsInsteadOfReverting() throws Exception {
        World worldA = world(WORLD_A_ID);
        World worldB = world(WORLD_B_ID);
        AtomicReference<World> portalWorld = new AtomicReference<World>(worldA);
        AtomicReference<World> observerWorld = new AtomicReference<World>(worldA);
        List<Location> sent = new ArrayList<Location>();
        Player observer = player(OBSERVER_ID, observerWorld, sent);
        ILocalPortal portal = portal(PORTAL_ID, portalWorld);
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(
            PortalProjectorClaimWorldTest::view,
            (player, chunkX, chunkZ) -> true);

        PortalProjector projector = withBukkitServer(() -> new PortalProjector(portal, observer, arbiter,
            PortalProjectorClaimWorldTest::view, () -> true));

        projector.noteClaimWorld(worldA);
        arbiter.submit(observer, portal, worldA, claims(), 2.0D, false);
        sent.clear();
        observerWorld.set(worldB);

        assertFalse(projector.releaseClaims(),
            "an observer that already left the claim world cannot be sent revert packets");
        assertTrue(sent.isEmpty(), "no block packets may be aimed at a world the observer is not in");
        assertTrue(arbiter.isIdle(), "the abandoned claim state must be discarded, not leaked");
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim> claims() {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(1);
        claims.put(CELL, new ProjectedBlockClaim(blockData("projected"), null, ProjectedBlockClaim.NO_REMOTE_KEY, false));
        return claims;
    }

    private static ProjectionWorldView view(World world) {
        return new ProjectionWorldView() {
            @Override
            public World getWorld() {
                return world;
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
                return blockData("local");
            }

            @Override
            public String sampleBiome(int x, int y, int z) {
                return "minecraft:plains";
            }

            @Override
            public int getLight(int x, int y, int z) {
                return ProjectionWorldView.packLight(15, 0);
            }

            @Override
            public int getSkyDarken() {
                return 0;
            }
        };
    }

    private interface ProjectorFactory {
        PortalProjector create();
    }

    private static PortalProjector withBukkitServer(ProjectorFactory factory) throws Exception {
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
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if ("createBlockData".equals(method.getName())) {
                    return blockData(String.valueOf(args[0]));
                }
                return switch (method.getName()) {
                    case "getName", "toString" -> "PortalProjectorClaimWorldTestServer";
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "equals" -> Boolean.valueOf(proxy == args[0]);
                    default -> defaultValue(method);
                };
            });
    }

    private static BlockData blockData(String state) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getAsString", "toString" -> state;
                case "getMaterial" -> Material.STONE;
                case "hashCode" -> Integer.valueOf(state.hashCode());
                case "equals" -> Boolean.valueOf(args[0] != null && state.equals(args[0].toString()));
                case "clone" -> proxy;
                default -> defaultValue(method);
            });
    }

    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUID" -> id;
                case "getName", "toString" -> "world-" + id;
                case "hashCode" -> Integer.valueOf(id.hashCode());
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                default -> defaultValue(method);
            });
    }

    private static ILocalPortal portal(UUID id, AtomicReference<World> world) {
        return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(), new Class<?>[] {ILocalPortal.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getId" -> id;
                case "getWorld" -> world.get();
                case "getName", "toString" -> "portal-" + id;
                case "hashCode" -> Integer.valueOf(id.hashCode());
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                default -> defaultValue(method);
            });
    }

    private static Player player(UUID id, World fixedWorld, List<Location> sent) {
        return player(id, new AtomicReference<World>(fixedWorld), sent);
    }

    private static Player player(UUID id, AtomicReference<World> world, List<Location> sent) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getUniqueId":
                        return id;
                    case "isOnline":
                        return Boolean.TRUE;
                    case "getWorld":
                        return world.get();
                    case "isChunkSent":
                        return Boolean.TRUE;
                    case "getName":
                    case "toString":
                        return "observer-" + id;
                    case "hashCode":
                        return Integer.valueOf(id.hashCode());
                    case "equals":
                        return Boolean.valueOf(proxy == args[0]);
                    case "sendBlockChange":
                        if (args != null && args.length > 0 && args[0] instanceof Location location) {
                            sent.add(location);
                        }
                        return null;
                    default:
                        return defaultValue(method);
                }
            });
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == char.class) {
            return Character.valueOf(' ');
        }
        return null;
    }
}
