package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.ILocalPortal;
import org.bukkit.World;
import org.bukkit.Material;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class ProjectionClaimSetTest {
    private static final long CELL_KEY = 42L;

    @Test
    public void nearestPortalClaimWinsSharedCell() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData farData = blockData("far");
        BlockData nearData = blockData("near");

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(farData));
        ProjectionClaimSet.ProjectionClaimSetResult result = set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(nearData));

        assertSame(nearData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, result.getConflicts());
        assertEquals(1, result.getWinnerChanges());
        assertTrue(result.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void losingPortalUpdateDoesNotOverwriteWinner() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData nearData = blockData("near");

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(blockData("far-a")));
        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(nearData));
        ProjectionClaimSet.ProjectionClaimSetResult result = set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(blockData("far-b")));

        assertSame(nearData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, result.getConflicts());
        assertEquals(0, result.getWinnerChanges());
        assertFalse(result.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void releasingWinnerFallsBackToNextClaimBeforeRealBlock() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData farData = blockData("far");

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(farData));
        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(blockData("near")));
        ProjectionClaimSet.ProjectionClaimSetResult fallback = set.releasePortal(nearPortal);

        assertSame(farData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, fallback.getWinnerChanges());
        assertEquals(0, fallback.getReverts());
        assertTrue(fallback.getPacketChangeKeys().contains(CELL_KEY));

        ProjectionClaimSet.ProjectionClaimSetResult revert = set.releasePortal(farPortal);
        assertNull(set.getWinningClaim(CELL_KEY));
        assertEquals(1, revert.getReverts());
        assertTrue(revert.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void equalDistanceTieBreaksByPortalId() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID highPortal = UUID.fromString("00000000-0000-0000-0000-000000000009");
        UUID lowPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData lowData = blockData("low");

        set.replacePortalClaims(highPortal, highPortal.toString(), 4.0D, singleClaim(blockData("high")));
        set.replacePortalClaims(lowPortal, lowPortal.toString(), 4.0D, singleClaim(lowData));

        assertSame(lowData, set.getWinningClaim(CELL_KEY).getData());
        assertTrue(ProjectionClaimSet.isHigherPriority(4.0D, lowPortal.toString(), 4.0D, highPortal.toString()));
    }

    @Test
    public void realProjectionBeatsNearerMaskAirClaim() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID maskPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID blockPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        BlockData blockData = blockData("block");

        set.replacePortalClaims(maskPortal, maskPortal.toString(), 1.0D, singleMaskClaim(blockData("mask")));
        set.replacePortalClaims(blockPortal, blockPortal.toString(), 8.0D, singleClaim(blockData));

        assertSame(blockData, set.getWinningClaim(CELL_KEY).getData());
        assertTrue(ProjectionClaimSet.isHigherPriority(8.0D, blockPortal.toString(), singleClaimValue(blockData),
            1.0D, maskPortal.toString(), singleMaskClaimValue(blockData("mask"))));
    }

    @Test
    public void stableResubmitsDoNotProducePacketChanges() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID portal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData data = blockData("stable");

        ProjectionClaimSet.ProjectionClaimSetResult first = set.replacePortalClaims(portal, portal.toString(), 2.0D, singleClaim(data));
        ProjectionClaimSet.ProjectionClaimSetResult second = set.replacePortalClaims(portal, portal.toString(), 2.0D, singleClaim(data));

        assertEquals(1, first.getPacketChangeKeys().size());
        assertEquals(0, second.getPacketChangeKeys().size());
        assertEquals(0, second.getWinnerChanges());
    }

    @Test
    public void fullBrightPolicyChangesOnlyLightingAndTracksRelease() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID portal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData data = blockData("stable");
        set.replacePortalClaims(portal, portal.toString(), 2.0D, singleClaim(data));
        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> fullBright = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(1);
        fullBright.put(CELL_KEY, new ProjectedBlockClaim<BlockData, ProjectionWorldView>(
            data, null, ProjectedBlockClaim.NO_REMOTE_KEY, false,
            ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT));

        ProjectionClaimSet.ProjectionClaimSetResult changed =
            set.replacePortalClaims(portal, portal.toString(), 2.0D, fullBright);

        assertTrue(set.hasFullBrightClaims());
        assertTrue(changed.getPacketChangeKeys().isEmpty());
        assertTrue(changed.getDirtyLightingKeys().contains(CELL_KEY));

        ProjectionClaimSet.ProjectionClaimSetResult released = set.releasePortal(portal);

        assertFalse(set.hasFullBrightClaims());
        assertTrue(released.getDirtyLightingKeys().contains(CELL_KEY));
        assertTrue(released.requiresImmediateLightingUpdate());
    }

    @Test
    public void unavailableClaimRetentionNormalizesBlackoutLightingWithoutLosingSource() {
        ProjectionWorldView sourceView = (ProjectionWorldView) Proxy.newProxyInstance(
            ProjectionWorldView.class.getClassLoader(), new Class<?>[] { ProjectionWorldView.class },
            (proxy, method, args) -> primitiveDefault(method.getReturnType()));
        BlockData data = blockData("retained");
        ProjectedBlockClaim<BlockData, ProjectionWorldView> source = new ProjectedBlockClaim<BlockData, ProjectionWorldView>(data, sourceView, 91L, false);
        source.setGlobalId(27);

        ProjectedBlockClaim<BlockData, ProjectionWorldView> fullBright = source.withFullBright(true);

        assertTrue(fullBright.isFullBright());
        assertSame(data, fullBright.getData());
        assertSame(sourceView, fullBright.getLightView());
        assertEquals(91L, fullBright.getLightRemoteKey());
        assertEquals(27, fullBright.getGlobalId());

        ProjectedBlockClaim<BlockData, ProjectionWorldView> restored = fullBright.withFullBright(false);

        assertEquals(ProjectedBlockClaim.LightingPolicy.SOURCE, restored.getLightingPolicy());
        assertSame(sourceView, restored.getLightView());
        assertEquals(91L, restored.getLightRemoteKey());
        assertEquals(27, restored.getGlobalId());

        ProjectedBlockClaim<BlockData, ProjectionWorldView> local = new ProjectedBlockClaim<BlockData, ProjectionWorldView>(
            data, null, ProjectedBlockClaim.NO_REMOTE_KEY, false,
            ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
        assertEquals(ProjectedBlockClaim.LightingPolicy.LOCAL,
            local.withFullBright(false).getLightingPolicy());
    }

    @Test
    public void fluidSkinOwnerDoesNotReplaceOrReleaseProjectionClaims() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID projectionOwner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID fluidOwner = UUID.fromString("00000000-0000-0000-0000-000000000002");
        long projectedCell = 42L;
        long skinCell = 84L;
        BlockData projectedData = blockData("projection");

        set.replacePortalClaims(projectionOwner, projectionOwner.toString(), 2.0D,
            singleClaim(projectedCell, projectedData));
        set.replacePortalClaims(fluidOwner, fluidOwner.toString(), 2.0D,
            singleClaim(skinCell, blockData("water")));

        assertSame(projectedData, set.getWinningClaim(projectedCell).getData());
        assertNotNull(set.getWinningClaim(skinCell));

        set.releasePortal(fluidOwner);

        assertSame(projectedData, set.getWinningClaim(projectedCell).getData());
        assertNull(set.getWinningClaim(skinCell));
    }

    @Test
    public void recursivePortalFallbackPolicyMasksOnlyBlockedApertures() {
        assertTrue(ProjectorSampler.shouldMaskRecursivePortalAperture(true, false, 0));
        assertTrue(ProjectorSampler.shouldMaskRecursivePortalAperture(true, true, 3));
        assertTrue(ProjectorSampler.shouldMaskRecursivePortalAperture(false, false, 3));
        assertFalse(ProjectorSampler.shouldMaskRecursivePortalAperture(true, false, 1));
    }

    @Test
    public void maskAndRemoteAirSkipCellsThatAreAlreadyLocalAir() {
        assertFalse(ProjectorCellScan.shouldProjectAirSample(ProjectorSample.Kind.MASK_AIR, true));
        assertTrue(ProjectorCellScan.shouldProjectAirSample(ProjectorSample.Kind.MASK_AIR, false));
        assertFalse(ProjectorCellScan.shouldProjectAirSample(ProjectorSample.Kind.REMOTE_AIR, true));
        assertTrue(ProjectorCellScan.shouldProjectAirSample(ProjectorSample.Kind.REMOTE_AIR, false));
        assertFalse(ProjectorCellScan.shouldProjectAirSample(ProjectorSample.Kind.NO_SAMPLE, false));
    }

    @Test
    public void liveClaimBeatsNearerHeldClaim() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID heldPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID livePortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        BlockData heldData = blockData("held");
        BlockData liveData = blockData("live");

        set.replacePortalClaims(heldPortal, heldPortal.toString(), 1.0D, singleHeldClaim(heldData));
        ProjectionClaimSet.ProjectionClaimSetResult result = set.replacePortalClaims(livePortal, livePortal.toString(), 8.0D, singleClaim(liveData));

        assertSame(liveData, set.getWinningClaim(CELL_KEY).getData());
        assertFalse(set.getWinningClaim(CELL_KEY).isHeld());
        assertEquals(1, result.getConflicts());
        assertEquals(1, result.getWinnerChanges());
        assertTrue(result.getPacketChangeKeys().contains(CELL_KEY));
        assertTrue(ProjectionClaimSet.isHigherPriority(8.0D, livePortal.toString(), singleClaimValue(liveData),
            1.0D, heldPortal.toString(), heldClaimValue(heldData)));
        assertFalse(ProjectionClaimSet.isHigherPriority(1.0D, heldPortal.toString(), heldClaimValue(heldData),
            8.0D, livePortal.toString(), singleClaimValue(liveData)));
    }

    @Test
    public void heldClaimWinsAgainstNoClaimAndRevertsOnRelease() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID portal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData heldData = blockData("held");

        ProjectionClaimSet.ProjectionClaimSetResult claimed = set.replacePortalClaims(portal, portal.toString(), 2.0D, singleHeldClaim(heldData));

        assertSame(heldData, set.getWinningClaim(CELL_KEY).getData());
        assertTrue(set.getWinningClaim(CELL_KEY).isHeld());
        assertTrue(claimed.getPacketChangeKeys().contains(CELL_KEY));
        assertEquals(0, claimed.getReverts());

        ProjectionClaimSet.ProjectionClaimSetResult released = set.releasePortal(portal);

        assertNull(set.getWinningClaim(CELL_KEY));
        assertEquals(1, released.getReverts());
        assertTrue(released.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void heldTransitionsRecomputeContestedWinnersWithoutResendingUncontestedCells() {
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID lonePortal = UUID.fromString("00000000-0000-0000-0000-000000000003");
        long loneCell = 84L;
        BlockData nearData = blockData("near");
        BlockData farData = blockData("far");
        BlockData loneData = blockData("lone");

        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(nearData));
        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(farData));
        assertSame(nearData, set.getWinningClaim(CELL_KEY).getData());

        ProjectionClaimSet.ProjectionClaimSetResult nearHeld = set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleHeldClaim(nearData));

        assertSame(farData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, nearHeld.getWinnerChanges());
        assertTrue(nearHeld.getPacketChangeKeys().contains(CELL_KEY));

        ProjectionClaimSet.ProjectionClaimSetResult nearLive = set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(nearData));

        assertSame(nearData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, nearLive.getWinnerChanges());
        assertTrue(nearLive.getPacketChangeKeys().contains(CELL_KEY));

        set.replacePortalClaims(lonePortal, lonePortal.toString(), 2.0D, singleClaim(loneCell, loneData));
        ProjectionClaimSet.ProjectionClaimSetResult loneHeld = set.replacePortalClaims(lonePortal, lonePortal.toString(), 2.0D, singleHeldClaim(loneCell, loneData));

        assertSame(loneData, set.getWinningClaim(loneCell).getData());
        assertTrue(set.getWinningClaim(loneCell).isHeld());
        assertEquals(0, loneHeld.getWinnerChanges());
        assertFalse(loneHeld.getPacketChangeKeys().contains(loneCell));
        assertFalse(loneHeld.getDirtyLightingKeys().contains(loneCell));
    }

    @Test
    public void maskTierOutranksHeldTierAndHeldPairsFallThroughToDistance() {
        BlockData data = blockData("block");
        assertTrue(ProjectionClaimSet.isHigherPriority(8.0D, "b", heldClaimValue(data),
            1.0D, "a", singleMaskClaimValue(blockData("mask"))));
        assertFalse(ProjectionClaimSet.isHigherPriority(1.0D, "a", singleMaskClaimValue(blockData("mask")),
            8.0D, "b", heldClaimValue(data)));
        assertTrue(ProjectionClaimSet.isHigherPriority(2.0D, "b", heldClaimValue(data),
            8.0D, "a", heldClaimValue(blockData("other"))));
        assertFalse(ProjectionClaimSet.isHigherPriority(8.0D, "a", heldClaimValue(data),
            2.0D, "b", heldClaimValue(blockData("other"))));
        assertTrue(ProjectionClaimSet.isHigherPriority(4.0D, "a", heldClaimValue(data),
            4.0D, "b", heldClaimValue(blockData("other"))));
    }

    @Test
    public void withHeldPreservesDataLightingAndGlobalId() {
        BlockData data = blockData("held");
        ProjectedBlockClaim<BlockData, ProjectionWorldView> live = singleClaimValue(data);
        live.setGlobalId(19);

        ProjectedBlockClaim<BlockData, ProjectionWorldView> held = live.withHeld(true);

        assertTrue(held.isHeld());
        assertFalse(live.isHeld());
        assertSame(data, held.getData());
        assertEquals(19, held.getGlobalId());
        assertEquals(live.getLightingPolicy(), held.getLightingPolicy());
        assertTrue(held.sameBlock(live));
        assertTrue(held.sameLightSource(live));
        assertSame(held, held.withHeld(true));
        assertFalse(held.withHeld(false).isHeld());
        assertTrue(held.withFullBright(true).withFullBright(false).isHeld());
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> singleHeldClaim(BlockData data) {
        return singleHeldClaim(CELL_KEY, data);
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> singleHeldClaim(long key, BlockData data) {
        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(1);
        claims.put(key, heldClaimValue(data));
        return claims;
    }

    private static ProjectedBlockClaim<BlockData, ProjectionWorldView> heldClaimValue(BlockData data) {
        return singleClaimValue(data).withHeld(true);
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> singleClaim(BlockData data) {
        return singleClaim(CELL_KEY, data);
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> singleClaim(long key, BlockData data) {
        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(1);
        claims.put(key, singleClaimValue(data));
        return claims;
    }

    private static Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> singleMaskClaim(BlockData data) {
        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> claims = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(1);
        claims.put(CELL_KEY, singleMaskClaimValue(data));
        return claims;
    }

    private static ProjectedBlockClaim<BlockData, ProjectionWorldView> singleClaimValue(BlockData data) {
        return new ProjectedBlockClaim<BlockData, ProjectionWorldView>(data, null, ProjectedBlockClaim.NO_REMOTE_KEY, false);
    }

    private static ProjectedBlockClaim<BlockData, ProjectionWorldView> singleMaskClaimValue(BlockData data) {
        return new ProjectedBlockClaim<BlockData, ProjectionWorldView>(data, null, ProjectedBlockClaim.NO_REMOTE_KEY, true);
    }

    private static BlockData blockData(String name) {
        InvocationHandler handler = new NamedBlockData(name);
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class }, handler);
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
        if (returnType == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        if (returnType == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        return null;
    }

    private static final class NamedBlockData implements InvocationHandler {
        private final String name;

        private NamedBlockData(String name) {
            this.name = name;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
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
            if ("clone".equals(methodName)) {
                return proxy;
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
        }
    }
}
