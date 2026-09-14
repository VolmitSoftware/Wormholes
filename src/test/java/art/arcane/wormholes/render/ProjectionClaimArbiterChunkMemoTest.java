package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectionClaimArbiterChunkMemoTest {
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final long CELL = packKey(3, 70, 5);

    @Test
    public void unchangedDeltaStillRepairsAReplacedClientChunk() {
        AtomicLong revision = new AtomicLong(1L);
        AtomicLong chunkRevision = new AtomicLong(1L);
        List<Location> sentLocations = new ArrayList<>();
        Player observer = player(sentLocations);
        World world = world();
        ILocalPortal portal = portal();
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(ignored -> availableView(world),
            countingVisibility(new AtomicInteger(), new AtomicBoolean(true), revision, chunkRevision));
        Long2ObjectOpenHashMap<ProjectedBlockClaim> first = claims(blockData("stable"));
        Long2ObjectOpenHashMap<ProjectedBlockClaim> next = new Long2ObjectOpenHashMap<>(first);
        assertEquals(1, arbiter.submit(observer, portal, world, first, 2.0D, false).getBlockChanges());
        chunkRevision.incrementAndGet();
        revision.incrementAndGet();
        assertEquals(1, arbiter.submitDelta(observer, portal, world,
            new ProjectionClaimSet.ClaimDelta(first, next, new LongOpenHashSet(), new LongOpenHashSet()),
            2.0D, false, false).getBlockChanges());
        assertEquals(2, sentLocations.size());
    }

    @Test
    public void stableClientChunkRevisionReusesVisibilityAcrossPasses() {
        AtomicLong revision = new AtomicLong(5L);
        AtomicInteger sentQueries = new AtomicInteger();
        List<Location> sentLocations = new ArrayList<Location>();
        Player observer = player(sentLocations);
        World world = world();
        ILocalPortal portal = portal();
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(
            ignored -> availableView(world),
            countingVisibility(sentQueries, new AtomicBoolean(true), revision, new AtomicLong(1L))
        );

        assertEquals(1, arbiter.submit(observer, portal, world, claims(blockData("a")), 2.0D, false).getBlockChanges());
        int afterFirstPass = sentQueries.get();
        assertEquals(1, afterFirstPass);

        assertEquals(1, arbiter.submit(observer, portal, world, claims(blockData("b")), 2.0D, false).getBlockChanges());

        assertEquals(afterFirstPass, sentQueries.get());
        assertEquals(2, sentLocations.size());
    }

    @Test
    public void revisionChangeInvalidatesVisibilityMemoAndRepairsOverlay() {
        AtomicLong revision = new AtomicLong(5L);
        AtomicLong chunkRevision = new AtomicLong(1L);
        AtomicBoolean chunkSent = new AtomicBoolean(true);
        AtomicInteger sentQueries = new AtomicInteger();
        List<Location> sentLocations = new ArrayList<Location>();
        Player observer = player(sentLocations);
        World world = world();
        ILocalPortal portal = portal();
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(
            ignored -> availableView(world),
            countingVisibility(sentQueries, chunkSent, revision, chunkRevision)
        );

        assertEquals(1, arbiter.submit(observer, portal, world, claims(blockData("a")), 2.0D, false).getBlockChanges());
        assertEquals(1, sentLocations.size());

        chunkSent.set(false);
        revision.incrementAndGet();
        assertEquals(0, arbiter.retryPending(observer, world).getBlockChanges());
        assertEquals(1, sentLocations.size());
        assertTrue(sentQueries.get() > 1);

        chunkSent.set(true);
        chunkRevision.incrementAndGet();
        revision.incrementAndGet();
        assertEquals(1, arbiter.retryPending(observer, world).getBlockChanges());
        assertEquals(2, sentLocations.size());

        assertEquals(1, arbiter.release(observer, portal, world, false).getReverts());
        assertTrue(arbiter.isIdle());
    }

    @Test
    public void unsupportedRevisionRequeriesVisibilityEveryPass() {
        AtomicInteger sentQueries = new AtomicInteger();
        AtomicBoolean chunkSent = new AtomicBoolean(true);
        List<Location> sentLocations = new ArrayList<Location>();
        Player observer = player(sentLocations);
        World world = world();
        ILocalPortal portal = portal();
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(
            ignored -> availableView(world),
            (player, chunkX, chunkZ) -> {
                sentQueries.incrementAndGet();
                return chunkSent.get();
            }
        );

        assertEquals(1, arbiter.submit(observer, portal, world, claims(blockData("a")), 2.0D, false).getBlockChanges());
        int afterFirstPass = sentQueries.get();

        chunkSent.set(false);
        assertEquals(0, arbiter.submit(observer, portal, world, claims(blockData("b")), 2.0D, false).getBlockChanges());

        assertTrue(sentQueries.get() > afterFirstPass);
        assertEquals(1, sentLocations.size());
    }

    @Test
    public void denseOverlayResendsOnlyTheReloadedChunk() {
        AtomicLong revision = new AtomicLong(1L);
        AtomicLong reloadedChunkRevision = new AtomicLong(1L);
        AtomicInteger localSamples = new AtomicInteger();
        List<Location> sentLocations = new ArrayList<Location>();
        Player observer = player(sentLocations);
        World world = world();
        ILocalPortal portal = portal();
        ProjectionChunkVisibility visibility = new ProjectionChunkVisibility() {
            @Override
            public boolean isChunkSent(Player player, int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public long revision(Player player) {
                return revision.get();
            }

            @Override
            public long chunkRevision(Player player, int chunkX, int chunkZ) {
                return chunkX == 7 ? reloadedChunkRevision.get() : 1L;
            }
        };
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(
            ignored -> availableView(world, localSamples), visibility);
        ProjectedBlockClaim projected = new ProjectedBlockClaim(
            blockData("projected"), null, ProjectedBlockClaim.NO_REMOTE_KEY, false);
        projected.setGlobalId(-1);
        Long2ObjectOpenHashMap<ProjectedBlockClaim> cells = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(4096);
        for (int chunkX = 0; chunkX < 16; chunkX++) {
            for (int cell = 0; cell < 256; cell++) {
                cells.put(packKey((chunkX << 4) + (cell & 15), 64 + (cell >> 4), 5), projected);
            }
        }

        assertEquals(4096, arbiter.submit(observer, portal, world, cells, 2.0D, false).getBlockChanges());
        sentLocations.clear();
        revision.incrementAndGet();
        assertEquals(0, arbiter.retryPending(observer, world).getBlockChanges());
        assertTrue(sentLocations.isEmpty());

        reloadedChunkRevision.incrementAndGet();
        revision.incrementAndGet();
        assertEquals(256, arbiter.retryPending(observer, world).getBlockChanges());
        assertEquals(256, sentLocations.size());
        assertTrue(sentLocations.stream().allMatch(location -> (location.getBlockX() >> 4) == 7));
        assertEquals(0, localSamples.get());
    }

    @Test
    public void partialRestoreRemovesOnlyReleasedCellsFromChunkReplay() {
        AtomicLong revision = new AtomicLong(1L);
        AtomicLong chunkRevision = new AtomicLong(1L);
        AtomicInteger localSamples = new AtomicInteger();
        List<Location> sentLocations = new ArrayList<Location>();
        Player observer = player(sentLocations);
        World world = world();
        ILocalPortal portal = portal();
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(
            ignored -> availableView(world, localSamples),
            countingVisibility(new AtomicInteger(), new AtomicBoolean(true), revision, chunkRevision));
        ProjectedBlockClaim projected = new ProjectedBlockClaim(
            blockData("projected"), null, ProjectedBlockClaim.NO_REMOTE_KEY, false);
        projected.setGlobalId(-1);
        long releasedKey = packKey(4, 70, 5);
        Long2ObjectOpenHashMap<ProjectedBlockClaim> cells = claims(projected.getData());
        cells.put(CELL, projected);
        cells.put(releasedKey, projected);
        assertEquals(2, arbiter.submit(observer, portal, world, cells, 2.0D, false).getBlockChanges());

        cells.remove(releasedKey);
        assertEquals(1, arbiter.submit(observer, portal, world, cells, 2.0D, false).getBlockChanges());
        assertEquals(1, localSamples.get());
        sentLocations.clear();
        chunkRevision.incrementAndGet();
        revision.incrementAndGet();

        assertEquals(1, arbiter.retryPending(observer, world).getBlockChanges());
        assertEquals(1, sentLocations.size());
        assertEquals(3, sentLocations.getFirst().getBlockX());
        assertEquals(1, localSamples.get());
        assertEquals(1, arbiter.release(observer, portal, world, false).getBlockChanges());
        assertEquals(2, localSamples.get());
        assertTrue(arbiter.isIdle());
    }

    @Test
    public void unloadedOverlayReleaseDoesNotReadLocalBlocks() {
        AtomicLong revision = new AtomicLong(1L);
        AtomicBoolean chunkSent = new AtomicBoolean(true);
        AtomicInteger localSamples = new AtomicInteger();
        List<Location> sentLocations = new ArrayList<Location>();
        Player observer = player(sentLocations);
        World world = world();
        ILocalPortal portal = portal();
        ProjectionClaimArbiter arbiter = new ProjectionClaimArbiter(
            ignored -> availableView(world, localSamples),
            countingVisibility(new AtomicInteger(), chunkSent, revision, new AtomicLong(1L)));
        assertEquals(1, arbiter.submit(observer, portal, world, claims(blockData("projected")), 2.0D, false)
            .getBlockChanges());

        chunkSent.set(false);
        revision.incrementAndGet();
        assertEquals(0, arbiter.retryPending(observer, world).getBlockChanges());
        assertEquals(0, arbiter.release(observer, portal, world, false).getBlockChanges());

        assertEquals(0, localSamples.get());
        assertEquals(1, sentLocations.size());
        assertTrue(arbiter.isIdle());
    }

    private static ProjectionChunkVisibility countingVisibility(AtomicInteger sentQueries,
                                                                AtomicBoolean chunkSent,
                                                                AtomicLong revision,
                                                                AtomicLong chunkRevision) {
        return new ProjectionChunkVisibility() {
            @Override
            public boolean isChunkSent(Player observer, int chunkX, int chunkZ) {
                sentQueries.incrementAndGet();
                return chunkSent.get();
            }

            @Override
            public long revision(Player observer) {
                return revision.get();
            }

            @Override
            public long chunkRevision(Player observer, int chunkX, int chunkZ) {
                return chunkRevision.get();
            }
        };
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim> claims(BlockData data) {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(1);
        claims.put(CELL, new ProjectedBlockClaim(data, null, ProjectedBlockClaim.NO_REMOTE_KEY, false));
        return claims;
    }

    private static long packKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
    }

    private static Player player(List<Location> sentLocations) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        World world = world();
        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            if ("getUniqueId".equals(methodName)) {
                return id;
            }
            if ("isOnline".equals(methodName)) {
                return Boolean.TRUE;
            }
            if ("getWorld".equals(methodName)) {
                return world;
            }
            if ("sendBlockChange".equals(methodName) && args != null && args.length > 0 && args[0] instanceof Location location) {
                sentLocations.add(location);
                return null;
            }
            return defaultValue(proxy, method, args, "player");
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class }, handler);
    }

    private static World world() {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getUID".equals(method.getName())) {
                return WORLD_ID;
            }
            return defaultValue(proxy, method, args, "world");
        };
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, handler);
    }

    private static ILocalPortal portal() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getId".equals(method.getName())) {
                return id;
            }
            return defaultValue(proxy, method, args, "portal");
        };
        return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(), new Class<?>[] { ILocalPortal.class }, handler);
    }

    private static ProjectionWorldView availableView(World world) {
        return availableView(world, new AtomicInteger());
    }

    private static ProjectionWorldView availableView(World world, AtomicInteger localSamples) {
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
                localSamples.incrementAndGet();
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

    private static BlockData blockData(String name) {
        InvocationHandler handler = (proxy, method, args) -> defaultValue(proxy, method, args, name);
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class }, handler);
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args, String name) {
        String methodName = method.getName();
        if ("equals".equals(methodName)) {
            return proxy == args[0];
        }
        if ("hashCode".equals(methodName)) {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("toString".equals(methodName)) {
            return name;
        }
        Class<?> returnType = method.getReturnType();
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
        if (returnType == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        if (returnType == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (returnType == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (returnType == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }
}
