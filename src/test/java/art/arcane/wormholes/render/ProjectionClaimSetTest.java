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
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.claim.ClaimSet;
import art.arcane.optics.scan.CellScan;
import art.arcane.optics.scan.Sample;
import art.arcane.optics.scan.Sampler;

public final class ProjectionClaimSetTest {
    private static final long CELL_KEY = 42L;

    @Test
    public void nearestPortalClaimWinsSharedCell() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData farData = blockData("far");
        BlockData nearData = blockData("near");

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(farData));
        ClaimSet.Result result = set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(nearData));

        assertSame(nearData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, result.getConflicts());
        assertEquals(1, result.getWinnerChanges());
        assertTrue(result.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void losingPortalUpdateDoesNotOverwriteWinner() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData nearData = blockData("near");

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(blockData("far-a")));
        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(nearData));
        ClaimSet.Result result = set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(blockData("far-b")));

        assertSame(nearData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, result.getConflicts());
        assertEquals(0, result.getWinnerChanges());
        assertFalse(result.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void releasingWinnerFallsBackToNextClaimBeforeRealBlock() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData farData = blockData("far");

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(farData));
        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(blockData("near")));
        ClaimSet.Result fallback = set.releasePortal(nearPortal);

        assertSame(farData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, fallback.getWinnerChanges());
        assertEquals(0, fallback.getReverts());
        assertTrue(fallback.getPacketChangeKeys().contains(CELL_KEY));

        ClaimSet.Result revert = set.releasePortal(farPortal);
        assertNull(set.getWinningClaim(CELL_KEY));
        assertEquals(1, revert.getReverts());
        assertTrue(revert.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void equalDistanceTieBreaksByPortalId() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID highPortal = UUID.fromString("00000000-0000-0000-0000-000000000009");
        UUID lowPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData lowData = blockData("low");

        set.replacePortalClaims(highPortal, highPortal.toString(), 4.0D, singleClaim(blockData("high")));
        set.replacePortalClaims(lowPortal, lowPortal.toString(), 4.0D, singleClaim(lowData));

        assertSame(lowData, set.getWinningClaim(CELL_KEY).getData());
        assertTrue(ClaimSet.isHigherPriority(4.0D, lowPortal.toString(), 4.0D, highPortal.toString()));
    }

    @Test
    public void realProjectionBeatsNearerMaskAirClaim() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID maskPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID blockPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        BlockData blockData = blockData("block");

        set.replacePortalClaims(maskPortal, maskPortal.toString(), 1.0D, singleMaskClaim(blockData("mask")));
        set.replacePortalClaims(blockPortal, blockPortal.toString(), 8.0D, singleClaim(blockData));

        assertSame(blockData, set.getWinningClaim(CELL_KEY).getData());
        assertTrue(ClaimSet.isHigherPriority(8.0D, blockPortal.toString(), singleClaimValue(blockData),
            1.0D, maskPortal.toString(), singleMaskClaimValue(blockData("mask"))));
    }

    @Test
    public void stableResubmitsDoNotProducePacketChanges() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID portal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData data = blockData("stable");

        ClaimSet.Result first = set.replacePortalClaims(portal, portal.toString(), 2.0D, singleClaim(data));
        ClaimSet.Result second = set.replacePortalClaims(portal, portal.toString(), 2.0D, singleClaim(data));

        assertEquals(1, first.getPacketChangeKeys().size());
        assertEquals(0, second.getPacketChangeKeys().size());
        assertEquals(0, second.getWinnerChanges());
    }

    @Test
    public void fullBrightPolicyChangesOnlyLightingAndTracksRelease() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID portal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData data = blockData("stable");
        set.replacePortalClaims(portal, portal.toString(), 2.0D, singleClaim(data));
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> fullBright = new Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>>(1);
        fullBright.put(CELL_KEY, new BlockClaim<BlockData, ProjectionWorldView>(
            data, null, BlockClaim.NO_REMOTE_KEY, false,
            BlockClaim.LightingPolicy.FULL_BRIGHT));

        ClaimSet.Result changed =
            set.replacePortalClaims(portal, portal.toString(), 2.0D, fullBright);

        assertTrue(set.hasFullBrightClaims());
        assertTrue(changed.getPacketChangeKeys().isEmpty());
        assertTrue(changed.getDirtyLightingKeys().contains(CELL_KEY));

        ClaimSet.Result released = set.releasePortal(portal);

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
        BlockClaim<BlockData, ProjectionWorldView> source = new BlockClaim<BlockData, ProjectionWorldView>(data, sourceView, 91L, false);
        source.setGlobalId(27);

        BlockClaim<BlockData, ProjectionWorldView> fullBright = source.withFullBright(true);

        assertTrue(fullBright.isFullBright());
        assertSame(data, fullBright.getData());
        assertSame(sourceView, fullBright.getLightView());
        assertEquals(91L, fullBright.getLightRemoteKey());
        assertEquals(27, fullBright.getGlobalId());

        BlockClaim<BlockData, ProjectionWorldView> restored = fullBright.withFullBright(false);

        assertEquals(BlockClaim.LightingPolicy.SOURCE, restored.getLightingPolicy());
        assertSame(sourceView, restored.getLightView());
        assertEquals(91L, restored.getLightRemoteKey());
        assertEquals(27, restored.getGlobalId());

        BlockClaim<BlockData, ProjectionWorldView> local = new BlockClaim<BlockData, ProjectionWorldView>(
            data, null, BlockClaim.NO_REMOTE_KEY, false,
            BlockClaim.LightingPolicy.FULL_BRIGHT);
        assertEquals(BlockClaim.LightingPolicy.LOCAL,
            local.withFullBright(false).getLightingPolicy());
    }

    @Test
    public void fluidSkinOwnerDoesNotReplaceOrReleaseProjectionClaims() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
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
        assertTrue(Sampler.shouldMaskRecursivePortalAperture(true, false, 0));
        assertTrue(Sampler.shouldMaskRecursivePortalAperture(true, true, 3));
        assertTrue(Sampler.shouldMaskRecursivePortalAperture(false, false, 3));
        assertFalse(Sampler.shouldMaskRecursivePortalAperture(true, false, 1));
    }

    @Test
    public void maskAndRemoteAirSkipCellsThatAreAlreadyLocalAir() {
        assertFalse(CellScan.shouldProjectAirSample(Sample.Kind.MASK_AIR, true));
        assertTrue(CellScan.shouldProjectAirSample(Sample.Kind.MASK_AIR, false));
        assertFalse(CellScan.shouldProjectAirSample(Sample.Kind.REMOTE_AIR, true));
        assertTrue(CellScan.shouldProjectAirSample(Sample.Kind.REMOTE_AIR, false));
        assertFalse(CellScan.shouldProjectAirSample(Sample.Kind.NO_SAMPLE, false));
    }

    @Test
    public void liveClaimBeatsNearerHeldClaim() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID heldPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID livePortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        BlockData heldData = blockData("held");
        BlockData liveData = blockData("live");

        set.replacePortalClaims(heldPortal, heldPortal.toString(), 1.0D, singleHeldClaim(heldData));
        ClaimSet.Result result = set.replacePortalClaims(livePortal, livePortal.toString(), 8.0D, singleClaim(liveData));

        assertSame(liveData, set.getWinningClaim(CELL_KEY).getData());
        assertFalse(set.getWinningClaim(CELL_KEY).isHeld());
        assertEquals(1, result.getConflicts());
        assertEquals(1, result.getWinnerChanges());
        assertTrue(result.getPacketChangeKeys().contains(CELL_KEY));
        assertTrue(ClaimSet.isHigherPriority(8.0D, livePortal.toString(), singleClaimValue(liveData),
            1.0D, heldPortal.toString(), heldClaimValue(heldData)));
        assertFalse(ClaimSet.isHigherPriority(1.0D, heldPortal.toString(), heldClaimValue(heldData),
            8.0D, livePortal.toString(), singleClaimValue(liveData)));
    }

    @Test
    public void heldClaimWinsAgainstNoClaimAndRevertsOnRelease() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID portal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        BlockData heldData = blockData("held");

        ClaimSet.Result claimed = set.replacePortalClaims(portal, portal.toString(), 2.0D, singleHeldClaim(heldData));

        assertSame(heldData, set.getWinningClaim(CELL_KEY).getData());
        assertTrue(set.getWinningClaim(CELL_KEY).isHeld());
        assertTrue(claimed.getPacketChangeKeys().contains(CELL_KEY));
        assertEquals(0, claimed.getReverts());

        ClaimSet.Result released = set.releasePortal(portal);

        assertNull(set.getWinningClaim(CELL_KEY));
        assertEquals(1, released.getReverts());
        assertTrue(released.getPacketChangeKeys().contains(CELL_KEY));
    }

    @Test
    public void heldTransitionsRecomputeContestedWinnersWithoutResendingUncontestedCells() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
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

        ClaimSet.Result nearHeld = set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleHeldClaim(nearData));

        assertSame(farData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, nearHeld.getWinnerChanges());
        assertTrue(nearHeld.getPacketChangeKeys().contains(CELL_KEY));

        ClaimSet.Result nearLive = set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(nearData));

        assertSame(nearData, set.getWinningClaim(CELL_KEY).getData());
        assertEquals(1, nearLive.getWinnerChanges());
        assertTrue(nearLive.getPacketChangeKeys().contains(CELL_KEY));

        set.replacePortalClaims(lonePortal, lonePortal.toString(), 2.0D, singleClaim(loneCell, loneData));
        ClaimSet.Result loneHeld = set.replacePortalClaims(lonePortal, lonePortal.toString(), 2.0D, singleHeldClaim(loneCell, loneData));

        assertSame(loneData, set.getWinningClaim(loneCell).getData());
        assertTrue(set.getWinningClaim(loneCell).isHeld());
        assertEquals(0, loneHeld.getWinnerChanges());
        assertFalse(loneHeld.getPacketChangeKeys().contains(loneCell));
        assertFalse(loneHeld.getDirtyLightingKeys().contains(loneCell));
    }

    @Test
    public void onlyTheKeysThatStartLosingToAnotherPortalsLiveClaimAreReportedDisplaced() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        long ownCell = 84L;
        LongOpenHashSet displaced = new LongOpenHashSet();
        LongOpenHashSet restored = new LongOpenHashSet();

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, twoClaims(CELL_KEY, blockData("far"), ownCell, blockData("own")));
        assertFalse(set.drainLosingTransitions(farPortal, displaced, restored, false), "uncontested claims report nothing");
        assertTrue(displaced.isEmpty());

        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(blockData("near")));
        assertTrue(set.drainLosingTransitions(farPortal, displaced, restored, false));
        assertEquals(LongOpenHashSet.of(CELL_KEY), displaced, "only the contested key is displaced");
        assertTrue(restored.isEmpty());
        displaced.clear();
        assertFalse(set.drainLosingTransitions(farPortal, displaced, restored, false), "a transition drains once");
        assertFalse(set.drainLosingTransitions(nearPortal, displaced, restored, false), "the winner never loses");

        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(blockData("near-b")));
        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, twoClaims(CELL_KEY, blockData("far-b"), ownCell, blockData("own")));
        assertFalse(set.drainLosingTransitions(farPortal, displaced, restored, false), "content changes on a losing key are not transitions");

        set.replacePortalClaims(farPortal, farPortal.toString(), 9.0D, twoClaims(CELL_KEY, blockData("far-b"), ownCell, blockData("own")));
        assertFalse(set.drainLosingTransitions(farPortal, displaced, restored, false), "an eye move alone is not a transition");

        set.releasePortal(nearPortal);
        set.replacePortalClaims(farPortal, farPortal.toString(), 10.0D, twoClaims(CELL_KEY, blockData("far-b"), ownCell, blockData("own")));
        assertTrue(set.drainLosingTransitions(farPortal, displaced, restored, false));
        assertTrue(displaced.isEmpty());
        assertEquals(LongOpenHashSet.of(CELL_KEY), restored, "a key that wins again is restored");
    }

    @Test
    public void newClaimsBehindALiveWinnerAreDisplacedButHeldWinnersDisplaceNothing() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID heldPortal = UUID.fromString("00000000-0000-0000-0000-000000000003");
        long heldCell = 84L;
        LongOpenHashSet displaced = new LongOpenHashSet();
        LongOpenHashSet restored = new LongOpenHashSet();

        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, singleClaim(blockData("near")));
        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(blockData("far")));
        assertTrue(set.drainLosingTransitions(farPortal, displaced, restored, false));
        assertEquals(LongOpenHashSet.of(CELL_KEY), displaced, "a new claim that never wins is displaced from the start");
        displaced.clear();

        set.replacePortalClaims(heldPortal, heldPortal.toString(), 1.0D, singleHeldClaim(heldCell, blockData("held-near")));
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> farHeld = singleClaim(blockData("far"));
        farHeld.put(heldCell, heldClaimValue(blockData("held-far")));
        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, farHeld);
        assertFalse(set.drainLosingTransitions(farPortal, displaced, restored, false), "losing to another held claim is not a transition");
        assertFalse(set.drainLosingTransitions(heldPortal, displaced, restored, false));

        LongOpenHashSet staged = new LongOpenHashSet();
        set.stagePortalClaims(nearPortal, nearPortal.toString(), 2.0D, twoClaims(CELL_KEY, blockData("near"), heldCell, blockData("near-live")), staged);
        set.resolveStaged(staged);
        assertTrue(set.drainLosingTransitions(heldPortal, displaced, restored, false));
        assertEquals(LongOpenHashSet.of(heldCell), displaced, "a held claim displaced by a staged live claim is reported");
        displaced.clear();
        assertTrue(set.drainLosingTransitions(farPortal, displaced, restored, false));
        assertEquals(LongOpenHashSet.of(heldCell), displaced, "the far portal's cell already lost, only its held cell is new");
    }

    @Test
    public void removedLosingClaimsAreRestoredAndAResyncReportsEveryLosingKey() {
        ClaimSet<BlockClaim<BlockData, ProjectionWorldView>> set = new ClaimSet<BlockClaim<BlockData, ProjectionWorldView>>();
        UUID nearPortal = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID farPortal = UUID.fromString("00000000-0000-0000-0000-000000000002");
        long ownCell = 84L;
        LongOpenHashSet displaced = new LongOpenHashSet();
        LongOpenHashSet restored = new LongOpenHashSet();

        set.replacePortalClaims(nearPortal, nearPortal.toString(), 2.0D, twoClaims(CELL_KEY, blockData("near"), ownCell, blockData("near-own")));
        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, twoClaims(CELL_KEY, blockData("far"), ownCell, blockData("own")));
        assertTrue(set.drainLosingTransitions(farPortal, displaced, restored, false));
        assertEquals(LongOpenHashSet.of(CELL_KEY, ownCell), displaced);
        displaced.clear();

        set.replacePortalClaims(farPortal, farPortal.toString(), 8.0D, singleClaim(ownCell, blockData("own")));
        assertTrue(set.drainLosingTransitions(farPortal, displaced, restored, false));
        assertTrue(displaced.isEmpty());
        assertEquals(LongOpenHashSet.of(CELL_KEY), restored, "a losing claim the portal dropped is restored");
        restored.clear();

        assertTrue(set.drainLosingTransitions(farPortal, displaced, restored, true));
        assertEquals(LongOpenHashSet.of(ownCell), displaced, "a resync lists the keys still losing");
        assertTrue(restored.isEmpty());
        displaced.clear();
        assertFalse(set.drainLosingTransitions(farPortal, displaced, restored, false), "a resync drains pending transitions too");

        set.releasePortal(farPortal);
        assertFalse(set.drainLosingTransitions(farPortal, displaced, restored, false));
        assertFalse(set.drainLosingTransitions(farPortal, displaced, restored, true));
    }

    @Test
    public void maskTierOutranksHeldTierAndHeldPairsFallThroughToDistance() {
        BlockData data = blockData("block");
        assertTrue(ClaimSet.isHigherPriority(8.0D, "b", heldClaimValue(data),
            1.0D, "a", singleMaskClaimValue(blockData("mask"))));
        assertFalse(ClaimSet.isHigherPriority(1.0D, "a", singleMaskClaimValue(blockData("mask")),
            8.0D, "b", heldClaimValue(data)));
        assertTrue(ClaimSet.isHigherPriority(2.0D, "b", heldClaimValue(data),
            8.0D, "a", heldClaimValue(blockData("other"))));
        assertFalse(ClaimSet.isHigherPriority(8.0D, "a", heldClaimValue(data),
            2.0D, "b", heldClaimValue(blockData("other"))));
        assertTrue(ClaimSet.isHigherPriority(4.0D, "a", heldClaimValue(data),
            4.0D, "b", heldClaimValue(blockData("other"))));
    }

    @Test
    public void withHeldPreservesDataLightingAndGlobalId() {
        BlockData data = blockData("held");
        BlockClaim<BlockData, ProjectionWorldView> live = singleClaimValue(data);
        live.setGlobalId(19);

        BlockClaim<BlockData, ProjectionWorldView> held = live.withHeld(true);

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

    private static Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> twoClaims(long firstKey, BlockData first,
                                                                                                      long secondKey, BlockData second) {
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims = singleClaim(firstKey, first);
        claims.put(secondKey, singleClaimValue(second));
        return claims;
    }

    private static Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> singleHeldClaim(BlockData data) {
        return singleHeldClaim(CELL_KEY, data);
    }

    private static Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> singleHeldClaim(long key, BlockData data) {
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims = new Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>>(1);
        claims.put(key, heldClaimValue(data));
        return claims;
    }

    private static BlockClaim<BlockData, ProjectionWorldView> heldClaimValue(BlockData data) {
        return singleClaimValue(data).withHeld(true);
    }

    private static Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> singleClaim(BlockData data) {
        return singleClaim(CELL_KEY, data);
    }

    private static Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> singleClaim(long key, BlockData data) {
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims = new Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>>(1);
        claims.put(key, singleClaimValue(data));
        return claims;
    }

    private static Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> singleMaskClaim(BlockData data) {
        Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>> claims = new Long2ObjectOpenHashMap<BlockClaim<BlockData, ProjectionWorldView>>(1);
        claims.put(CELL_KEY, singleMaskClaimValue(data));
        return claims;
    }

    private static BlockClaim<BlockData, ProjectionWorldView> singleClaimValue(BlockData data) {
        return new BlockClaim<BlockData, ProjectionWorldView>(data, null, BlockClaim.NO_REMOTE_KEY, false);
    }

    private static BlockClaim<BlockData, ProjectionWorldView> singleMaskClaimValue(BlockData data) {
        return new BlockClaim<BlockData, ProjectionWorldView>(data, null, BlockClaim.NO_REMOTE_KEY, true);
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
