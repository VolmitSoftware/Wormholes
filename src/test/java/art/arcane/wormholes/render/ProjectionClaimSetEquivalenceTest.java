package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectionClaimSetEquivalenceTest {
    private static final double PRIORITY_EPSILON = 1.0E-7D;
    private static final int CELL_UNIVERSE = 40;
    private static final double[] PRIORITY_POOL = new double[] { 1.0D, 1.0D, 2.5D, 4.0D, 4.0D, 9.75D };

    @Test
    public void incrementalUpdatesMatchFullRebuildOverRandomMutationSequences() {
        for (int seed = 0; seed < 24; seed++) {
            runEquivalenceSequence(seed, 260);
        }
    }

    @Test
    public void contestedCellReactsToPriorityOnlyResubmit() {
        ProjectionClaimSet set = new ProjectionClaimSet();
        ReferenceClaimSet reference = new ReferenceClaimSet();
        UUID first = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID second = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        ProjectedBlockClaim firstClaim = claim(blockData("first"), null, ProjectedBlockClaim.NO_REMOTE_KEY, false);
        ProjectedBlockClaim secondClaim = claim(blockData("second"), null, ProjectedBlockClaim.NO_REMOTE_KEY, false);

        submitBoth(set, reference, first, 2.0D, claims(7L, firstClaim));
        submitBoth(set, reference, second, 8.0D, claims(7L, secondClaim));
        assertSame(firstClaim, set.getWinningClaim(7L));

        ProjectionClaimSet.ProjectionClaimSetResult moved =
            submitBoth(set, reference, second, 0.5D, claims(7L, secondClaim));

        assertSame(secondClaim, set.getWinningClaim(7L));
        assertEquals(1, moved.getWinnerChanges());
        assertTrue(moved.getPacketChangeKeys().contains(7L));
    }

    @Test
    public void carriedOverClaimInstancesDoNotReemitPackets() {
        ProjectionClaimSet set = new ProjectionClaimSet();
        UUID portal = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        ProjectedBlockClaim stable = claim(blockData("stable"), null, ProjectedBlockClaim.NO_REMOTE_KEY, false);
        ProjectedBlockClaim dropped = claim(blockData("dropped"), null, ProjectedBlockClaim.NO_REMOTE_KEY, false);

        Long2ObjectOpenHashMap<ProjectedBlockClaim> first = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(2);
        first.put(1L, stable);
        first.put(2L, dropped);
        set.replacePortalClaims(portal, portal.toString(), 3.0D, first);

        Long2ObjectOpenHashMap<ProjectedBlockClaim> second = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(1);
        second.put(1L, stable);
        ProjectionClaimSet.ProjectionClaimSetResult result = set.replacePortalClaims(portal, portal.toString(), 3.0D, second);

        assertEquals(1, result.getPacketChangeKeys().size());
        assertTrue(result.getPacketChangeKeys().contains(2L));
        assertEquals(1, result.getReverts());
        assertSame(stable, set.getWinningClaim(1L));
    }

    private static void runEquivalenceSequence(int seed, int operations) {
        Random random = new Random(seed * 7919L + 13L);
        ProjectionClaimSet set = new ProjectionClaimSet();
        ReferenceClaimSet reference = new ReferenceClaimSet();
        List<UUID> portalIds = portalIds();
        List<BlockData> dataPool = dataPool();
        List<ProjectionWorldView> lightViews = lightViews();
        Map<UUID, Map<Long, ProjectedBlockClaim>> lastSubmitted = new HashMap<UUID, Map<Long, ProjectedBlockClaim>>();

        for (int operation = 0; operation < operations; operation++) {
            int roll = random.nextInt(100);
            if (roll < 6) {
                set.clear();
                reference.clear();
                lastSubmitted.clear();
                assertEquals(reference.isEmpty(), set.isEmpty(), () -> "isEmpty mismatch after clear");
                continue;
            }
            UUID portalId = portalIds.get(random.nextInt(portalIds.size()));
            if (roll < 22) {
                ProjectionClaimSet.ProjectionClaimSetResult actual = set.releasePortal(portalId);
                ReferenceResult expected = reference.releasePortal(portalId);
                lastSubmitted.remove(portalId);
                assertResultsMatch(seed, operation, expected, actual, set, reference);
                continue;
            }

            Map<Long, ProjectedBlockClaim> previous = lastSubmitted.get(portalId);
            Map<Long, ProjectedBlockClaim> submitted = randomClaims(random, previous, dataPool, lightViews);
            lastSubmitted.put(portalId, submitted);
            double priorityDistance = PRIORITY_POOL[random.nextInt(PRIORITY_POOL.length)];
            String tieKey = portalId.toString();

            ProjectionClaimSet.ProjectionClaimSetResult actual =
                set.replacePortalClaims(portalId, tieKey, priorityDistance, toFastMap(submitted));
            ReferenceResult expected = reference.replacePortalClaims(portalId, tieKey, priorityDistance, submitted);
            assertResultsMatch(seed, operation, expected, actual, set, reference);
        }
    }

    private static ProjectionClaimSet.ProjectionClaimSetResult submitBoth(ProjectionClaimSet set,
                                                                          ReferenceClaimSet reference,
                                                                          UUID portalId,
                                                                          double priorityDistance,
                                                                          Map<Long, ProjectedBlockClaim> claims) {
        ProjectionClaimSet.ProjectionClaimSetResult actual =
            set.replacePortalClaims(portalId, portalId.toString(), priorityDistance, toFastMap(claims));
        ReferenceResult expected = reference.replacePortalClaims(portalId, portalId.toString(), priorityDistance, claims);
        assertResultsMatch(0, 0, expected, actual, set, reference);
        return actual;
    }

    private static void assertResultsMatch(int seed,
                                           int operation,
                                           ReferenceResult expected,
                                           ProjectionClaimSet.ProjectionClaimSetResult actual,
                                           ProjectionClaimSet set,
                                           ReferenceClaimSet reference) {
        String context = "seed=" + seed + " op=" + operation;
        assertEquals(expected.packetChangeKeys, toSet(actual.getPacketChangeKeys()), () -> context + " packet keys");
        assertEquals(expected.dirtyLightingKeys, toSet(actual.getDirtyLightingKeys()), () -> context + " lighting keys");
        assertEquals(expected.conflicts, actual.getConflicts(), () -> context + " conflicts");
        assertEquals(expected.winnerChanges, actual.getWinnerChanges(), () -> context + " winner changes");
        assertEquals(expected.reverts, actual.getReverts(), () -> context + " reverts");
        assertEquals(reference.isEmpty(), set.isEmpty(), () -> context + " isEmpty");
        for (int cell = 0; cell < CELL_UNIVERSE; cell++) {
            long key = cellKey(cell);
            assertSame(reference.winningClaim(key), set.getWinningClaim(key), () -> context + " winner at cell " + key);
        }
    }

    private static Map<Long, ProjectedBlockClaim> randomClaims(Random random,
                                                               Map<Long, ProjectedBlockClaim> previous,
                                                               List<BlockData> dataPool,
                                                               List<ProjectionWorldView> lightViews) {
        Map<Long, ProjectedBlockClaim> claims = new LinkedHashMap<Long, ProjectedBlockClaim>();
        int count = random.nextInt(CELL_UNIVERSE + 1);
        for (int i = 0; i < count; i++) {
            long key = cellKey(random.nextInt(CELL_UNIVERSE));
            ProjectedBlockClaim carried = previous == null ? null : previous.get(Long.valueOf(key));
            if (carried != null && random.nextInt(100) < 65) {
                claims.put(Long.valueOf(key), carried);
                continue;
            }
            BlockData data = dataPool.get(random.nextInt(dataPool.size()));
            int lightPick = random.nextInt(lightViews.size() + 1);
            ProjectionWorldView lightView = lightPick == lightViews.size() ? null : lightViews.get(lightPick);
            long lightRemoteKey = lightView == null ? ProjectedBlockClaim.NO_REMOTE_KEY : random.nextInt(3);
            claims.put(Long.valueOf(key), claim(data, lightView, lightRemoteKey, random.nextInt(100) < 25));
        }
        return claims;
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim> toFastMap(Map<Long, ProjectedBlockClaim> claims) {
        Long2ObjectOpenHashMap<ProjectedBlockClaim> fast =
            new Long2ObjectOpenHashMap<ProjectedBlockClaim>(Math.max(1, claims.size()));
        for (Map.Entry<Long, ProjectedBlockClaim> entry : claims.entrySet()) {
            fast.put(entry.getKey().longValue(), entry.getValue());
        }
        return fast;
    }

    private static Map<Long, ProjectedBlockClaim> claims(long key, ProjectedBlockClaim claim) {
        Map<Long, ProjectedBlockClaim> claims = new LinkedHashMap<Long, ProjectedBlockClaim>();
        claims.put(Long.valueOf(key), claim);
        return claims;
    }

    private static Set<Long> toSet(LongOpenHashSet keys) {
        Set<Long> boxed = new HashSet<Long>();
        for (long key : keys) {
            boxed.add(Long.valueOf(key));
        }
        return boxed;
    }

    private static long cellKey(int index) {
        return (((long) index) << 17) ^ (index * 1103515245L);
    }

    private static List<UUID> portalIds() {
        List<UUID> ids = new ArrayList<UUID>();
        ids.add(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ids.add(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        ids.add(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        ids.add(UUID.fromString("00000000-0000-0000-0000-000000000004"));
        return ids;
    }

    private static List<BlockData> dataPool() {
        List<BlockData> pool = new ArrayList<BlockData>();
        pool.add(blockData("stone"));
        pool.add(blockData("dirt"));
        pool.add(blockData("air"));
        pool.add(blockData("glass"));
        return pool;
    }

    private static List<ProjectionWorldView> lightViews() {
        List<ProjectionWorldView> views = new ArrayList<ProjectionWorldView>();
        views.add(new StubWorldView());
        views.add(new StubWorldView());
        return views;
    }

    private static ProjectedBlockClaim claim(BlockData data, ProjectionWorldView lightView, long lightRemoteKey, boolean maskAir) {
        return new ProjectedBlockClaim(data, lightView, lightRemoteKey, maskAir);
    }

    private static BlockData blockData(String name) {
        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            if ("equals".equals(methodName)) {
                return proxy == args[0];
            }
            if ("hashCode".equals(methodName)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("toString".equals(methodName) || "getAsString".equals(methodName)) {
                return name;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == Boolean.TYPE) {
                return Boolean.FALSE;
            }
            if (returnType == Integer.TYPE) {
                return Integer.valueOf(0);
            }
            if (returnType == Float.TYPE) {
                return Float.valueOf(0.0F);
            }
            return null;
        };
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class }, handler);
    }

    private static final class StubWorldView implements ProjectionWorldView {
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
            return null;
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return "minecraft:plains";
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

    private static final class ReferenceResult {
        private final Set<Long> packetChangeKeys = new HashSet<Long>();
        private final Set<Long> dirtyLightingKeys = new HashSet<Long>();
        private int conflicts;
        private int winnerChanges;
        private int reverts;
    }

    private static final class ReferencePortal {
        private final UUID portalId;
        private final Map<Long, ProjectedBlockClaim> claims = new LinkedHashMap<Long, ProjectedBlockClaim>();
        private String tieKey;
        private double priorityDistance;

        private ReferencePortal(UUID portalId) {
            this.portalId = portalId;
            this.tieKey = portalId.toString();
            this.priorityDistance = 0.0D;
        }
    }

    private static final class ReferenceWinner {
        private final UUID portalId;
        private final ProjectedBlockClaim claim;

        private ReferenceWinner(UUID portalId, ProjectedBlockClaim claim) {
            this.portalId = portalId;
            this.claim = claim;
        }
    }

    private static final class ReferenceClaimSet {
        private final Map<UUID, ReferencePortal> portals = new LinkedHashMap<UUID, ReferencePortal>();
        private final Map<Long, ReferenceWinner> winners = new LinkedHashMap<Long, ReferenceWinner>();

        private ReferenceResult replacePortalClaims(UUID portalId,
                                                    String tieKey,
                                                    double priorityDistance,
                                                    Map<Long, ProjectedBlockClaim> claims) {
            if (claims == null || claims.isEmpty()) {
                return releasePortal(portalId);
            }
            ReferencePortal existing = portals.get(portalId);
            ReferencePortal portal = existing == null ? new ReferencePortal(portalId) : existing;
            boolean priorityChanged = existing != null
                && (!existing.tieKey.equals(tieKey) || Math.abs(existing.priorityDistance - priorityDistance) > PRIORITY_EPSILON);

            List<Long> affected = new ArrayList<Long>();
            List<Long> removed = new ArrayList<Long>();
            for (Long key : portal.claims.keySet()) {
                if (!claims.containsKey(key)) {
                    removed.add(key);
                }
            }
            for (Long key : removed) {
                portal.claims.remove(key);
                affected.add(key);
            }
            for (Map.Entry<Long, ProjectedBlockClaim> entry : claims.entrySet()) {
                Long key = entry.getKey();
                ProjectedBlockClaim nextClaim = entry.getValue();
                ProjectedBlockClaim previousClaim = portal.claims.get(key);
                if (previousClaim == null) {
                    portal.claims.put(key, nextClaim);
                    affected.add(key);
                } else if (!sameClaim(previousClaim, nextClaim)) {
                    portal.claims.put(key, nextClaim);
                    affected.add(key);
                } else if (priorityChanged && ownerCount(key) > 1) {
                    affected.add(key);
                }
            }
            portal.tieKey = tieKey;
            portal.priorityDistance = priorityDistance;
            portals.put(portalId, portal);
            return recompute(affected);
        }

        private ReferenceResult releasePortal(UUID portalId) {
            ReferencePortal existing = portals.remove(portalId);
            if (existing == null) {
                return new ReferenceResult();
            }
            List<Long> affected = new ArrayList<Long>(existing.claims.keySet());
            existing.claims.clear();
            return recompute(affected);
        }

        private void clear() {
            portals.clear();
            winners.clear();
        }

        private boolean isEmpty() {
            return winners.isEmpty();
        }

        private ProjectedBlockClaim winningClaim(long key) {
            ReferenceWinner winner = winners.get(Long.valueOf(key));
            return winner == null ? null : winner.claim;
        }

        private int ownerCount(Long key) {
            int count = 0;
            for (ReferencePortal portal : portals.values()) {
                if (portal.claims.get(key) != null) {
                    count++;
                }
            }
            return count;
        }

        private ReferenceResult recompute(List<Long> affected) {
            ReferenceResult result = new ReferenceResult();
            for (Long key : affected) {
                ReferenceWinner previous = winners.get(key);
                ReferencePortal bestPortal = null;
                ProjectedBlockClaim bestClaim = null;
                int claimCount = 0;
                for (ReferencePortal portal : portals.values()) {
                    ProjectedBlockClaim claim = portal.claims.get(key);
                    if (claim == null) {
                        continue;
                    }
                    claimCount++;
                    if (bestPortal == null
                        || higherPriority(portal.priorityDistance, portal.tieKey, claim,
                            bestPortal.priorityDistance, bestPortal.tieKey, bestClaim)) {
                        bestPortal = portal;
                        bestClaim = claim;
                    }
                }
                if (claimCount > 1) {
                    result.conflicts++;
                }
                if (bestPortal == null) {
                    if (previous != null) {
                        winners.remove(key);
                        result.packetChangeKeys.add(key);
                        result.dirtyLightingKeys.add(key);
                        result.reverts++;
                    }
                    continue;
                }
                boolean ownerChanged = previous == null || !previous.portalId.equals(bestPortal.portalId);
                boolean dataChanged = previous == null || !previous.claim.getData().equals(bestClaim.getData());
                boolean lightChanged = previous == null || !previous.claim.sameLightSource(bestClaim);
                winners.put(key, new ReferenceWinner(bestPortal.portalId, bestClaim));
                if (dataChanged) {
                    result.packetChangeKeys.add(key);
                }
                if (ownerChanged) {
                    result.winnerChanges++;
                }
                if (lightChanged) {
                    result.dirtyLightingKeys.add(key);
                }
            }
            return result;
        }

        private static boolean sameClaim(ProjectedBlockClaim previous, ProjectedBlockClaim next) {
            if (previous == next) {
                return true;
            }
            if (previous == null || next == null) {
                return false;
            }
            return previous.isMaskAir() == next.isMaskAir()
                && previous.getData().equals(next.getData())
                && previous.sameLightSource(next);
        }

        private static boolean higherPriority(double candidateDistance,
                                              String candidateTieKey,
                                              ProjectedBlockClaim candidateClaim,
                                              double currentDistance,
                                              String currentTieKey,
                                              ProjectedBlockClaim currentClaim) {
            if (candidateClaim.isMaskAir() && !currentClaim.isMaskAir()) {
                return false;
            }
            if (!candidateClaim.isMaskAir() && currentClaim.isMaskAir()) {
                return true;
            }
            if (candidateDistance < currentDistance - PRIORITY_EPSILON) {
                return true;
            }
            if (candidateDistance > currentDistance + PRIORITY_EPSILON) {
                return false;
            }
            return candidateTieKey.compareTo(currentTieKey) < 0;
        }
    }
}
