package art.arcane.wormholes.render;

import art.arcane.optics.scan.ScanMode;
import art.arcane.wormholes.render.BukkitProjectorBlocks;
import art.arcane.wormholes.util.BukkitGeometry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.Settings;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.optics.math.Face;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.claim.ProjectionClaimSet;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.occlusion.ProjectedEntityOcclusion;
import art.arcane.optics.occlusion.ProjectorHoldProof;
import art.arcane.optics.occlusion.ProjectorViewOcclusion;
import art.arcane.optics.scan.CellScan;
import art.arcane.optics.scan.ProjectorPassRevision;
import art.arcane.optics.scan.ProjectorRemoteFootprint;
import art.arcane.optics.scan.ProjectorSampleMemo;
import art.arcane.optics.scan.ProjectorSampler;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.volume.PlaneWindow;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.volume.ProjectionVolume;

public final class ProjectorCellScanLightingRetentionTest {
    private static final BukkitProjectorBlocks TEST_BLOCKS =
        new BukkitProjectorBlocks(ProjectorCellScanLightingRetentionTest::testMaterialOccluding);

    @Test
    public void stagedScansKeepCommittedStateAndMatchUnlimitedScans() throws ReflectiveOperationException {
        int yieldedCalls = 0;
        for (Face normal : Face.values()) {
            for (int variant = 0; variant < 4; variant++) {
                Frame frame = Frame.canonical(normal);
                ScanFixture unlimited = scanFixture(frame, variant >= 2);
                ScanFixture staged = scanFixture(frame, variant >= 2);
                unlimited.destination().mirrorMode = (variant & 1) != 0;
                staged.destination().mirrorMode = (variant & 1) != 0;
                unlimited.destination().mirrorRotationQuarterTurns = variant;
                staged.destination().mirrorRotationQuarterTurns = variant;
                if (variant == 3) {
                    unlimited.remote().data = blockData(Material.CHEST);
                    staged.remote().data = blockData(Material.CHEST);
                    unlimited.remote().blockEntity = new BlockEntitySample("minecraft:chest", new byte[] {10, 0, 0, 0});
                    staged.remote().blockEntity = unlimited.remote().blockEntity;
                }
                if ((variant & 2) != 0) {
                    enableBlackout(unlimited.blackout());
                    enableBlackout(staged.blackout());
                }
                Location initialEye = unlimited.structure().getCenter()
                    .add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
                ProjectionRenderMode mode = variant == 2 ? ProjectionRenderMode.VENTICULAR : ProjectionRenderMode.PANOPTIC;
                LodPolicy lod = variant == 3 ? new LodPolicy(true, 2, 4) : LodPolicy.NONE;
                for (int pass = 0; pass < 3; pass++) {
                    Face right = frame.getRight();
                    Location eye = initialEye.clone().add(right.x() * pass * 0.4D,
                        right.y() * pass * 0.4D, right.z() * pass * 0.4D);
                    ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), unlimited.structure(), new ViewVolume.Options(12.0D, 5.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
                    unlimited.scan().run(unlimited.destination(), null, BukkitGeometry.vector(eye), frustum, 12.0D,
                        pass == 0, false, true, new ScanMode(false, mode.scanMode().observerOcclusion()), null, variant == 3, lod);
                    Long2ObjectMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> committed = staged.scan().claims();
                    Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> committedCopy = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(committed);
                    Long2ObjectMap<BlockEntitySample> committedBlockEntities = staged.scan().blockEntities();
                    Long2ObjectOpenHashMap<BlockEntitySample> blockEntityCopy = new Long2ObjectOpenHashMap<BlockEntitySample>(committedBlockEntities);
                    ProjectedEntityOcclusion<BlockData, ProjectionWorldView> entityOcclusion = staged.scan().entityOcclusion();
                    Field blockerField = CellScan.class.getDeclaredField("projectedOcclusionGeometry");
                    blockerField.setAccessible(true);
                    LongOpenHashSet committedBlockers = (LongOpenHashSet) blockerField.get(staged.scan());
                    LongOpenHashSet blockerCopy = new LongOpenHashSet(committedBlockers);
                    Frame localFrame = staged.scan().localFrame();
                    Frame remoteFrame = staged.scan().remoteFrame();
                    double eyeDot = staged.scan().eyeDot();
                    staged.scan().begin(staged.destination(), null, BukkitGeometry.vector(eye), frustum, 12.0D,
                        pass == 0, false, true, new ScanMode(false, mode.scanMode().observerOcclusion()), null, variant == 3, lod);
                    assertTrue(staged.scan().hasPending());
                    assertThrows(IllegalStateException.class, staged.scan()::commit);
                    assertThrows(IllegalStateException.class, staged.scan()::claimDelta);
                    int advances = 0;
                    int processedCells = 0;
                    Field nextClaimsField = CellScan.class.getDeclaredField("nextProjected");
                    nextClaimsField.setAccessible(true);
                    while (!staged.scan().advance(0L)) {
                        advances++;
                        yieldedCalls++;
                        assertTrue(advances < 1_000);
                        assertSame(committed, staged.scan().claims());
                        assertEquals(committedCopy, committed);
                        assertSame(committedBlockEntities, staged.scan().blockEntities());
                        assertEquals(blockEntityCopy, committedBlockEntities);
                        assertSame(entityOcclusion, staged.scan().entityOcclusion());
                        assertEquals(blockerCopy, committedBlockers);
                        assertSame(localFrame, staged.scan().localFrame());
                        assertSame(remoteFrame, staged.scan().remoteFrame());
                        assertEquals(eyeDot, staged.scan().eyeDot());
                        Long2ObjectMap<?> nextClaims = (Long2ObjectMap<?>) nextClaimsField.get(staged.scan());
                        int nextProcessedCells = nextClaims.size() + staged.scan().windowRejected() + staged.scan().frustumRejected();
                        assertTrue(nextProcessedCells - processedCells <= 128);
                        processedCells = nextProcessedCells;
                        Location latestEye = eye.clone().add(23.0D, 17.0D, 11.0D);
                        staged.scan().updateEntityOcclusionEye(BukkitGeometry.vector(latestEye), staged.destination(), frame, frame);
                    }
                    assertTrue(advances > 1);
                    assertSame(staged.scan().claims(), staged.scan().claimDelta().claims());
                    assertSame(pass == 0 ? null : committed, staged.scan().claimDelta().previousClaims());
                    assertEquivalentClaims(unlimited.scan().claims(), staged.scan().claims());
                    assertEquals(unlimited.scan().blockEntities(), staged.scan().blockEntities());
                    assertEquals(unlimited.scan().frustumMaskedRows(), staged.scan().frustumMaskedRows());
                    assertEquals(unlimited.scan().frustumScalarRows(), staged.scan().frustumScalarRows());
                    unlimited.scan().commit();
                    staged.scan().commit();
                    assertFalse(staged.scan().hasPending());
                    assertEquivalentClaims(unlimited.scan().claims(), staged.scan().claims());
                }
            }
        }
        assertTrue(yieldedCalls > 300);
    }

    @Test
    public void cancelledScansKeepCommittedClaimsAndCannotPublishPartialBuffers() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.D);
        ScanFixture fixture = scanFixture(frame, false);
        Location eye = fixture.structure().getCenter().add(0.0D, -1.5D, 0.0D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), fixture.structure(), new ViewVolume.Options(16.0D, 5.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = fixture.scan();
        scan.run(fixture.destination(), null, BukkitGeometry.vector(eye), frustum, 16.0D,
            true, false, true, new ScanMode(false, false), null, false, LodPolicy.NONE);
        scan.commit();
        Long2ObjectMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> committed = scan.claims();
        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> expected = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(committed);
        ProjectedEntityOcclusion<BlockData, ProjectionWorldView> entityOcclusion = scan.entityOcclusion();
        for (int cancelled = 0; cancelled < 3; cancelled++) {
            scan.begin(fixture.destination(), null, BukkitGeometry.vector(eye), frustum, 16.0D,
                true, cancelled == 1, true, new ScanMode(false, false), null, false, LodPolicy.NONE);
            assertFalse(scan.advance(0L));
            scan.cancelPending();
            assertFalse(scan.hasPending());
            assertFalse(scan.advance(Long.MAX_VALUE));
            assertSame(committed, scan.claims());
            assertEquals(expected, scan.claims());
            assertSame(entityOcclusion, scan.entityOcclusion());
            assertThrows(IllegalStateException.class, scan::commit);
        }
        scan.begin(fixture.destination(), null, BukkitGeometry.vector(eye), frustum, 16.0D,
            true, true, true, new ScanMode(false, false), null, false, LodPolicy.NONE);
        while (!scan.advance(0L)) {
        }
        assertSame(null, scan.claimDelta().previousClaims());
        assertEquivalentClaims(expected, scan.claims());
        scan.commit();
        scan.begin(fixture.destination(), null, BukkitGeometry.vector(eye), frustum, 16.0D,
            false, false, true, new ScanMode(false, false), null, false, LodPolicy.NONE);
        assertFalse(scan.advance(0L));
        scan.clear();
        assertFalse(scan.hasPending());
        assertFalse(scan.hasProjection());
        assertTrue(scan.claims().isEmpty());
        assertFalse(scan.advance(Long.MAX_VALUE));
    }

    @Test
    public void contentRevisionsDoNotRestartScansAndKeepEntityOcclusionRevisionStale() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.E);
        ScanFixture fixture = scanFixture(frame, true);
        Location eye = fixture.structure().getCenter().add(1.5D, 0.0D, 0.0D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), fixture.structure(), new ViewVolume.Options(12.0D, 5.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        fixture.scan().begin(fixture.destination(), null, BukkitGeometry.vector(eye), frustum, 12.0D,
            true, false, true, new ScanMode(false, true), null, false, LodPolicy.NONE);
        int advances = 0;
        while (!fixture.scan().advance(0L)) {
            fixture.remote().revision++;
            fixture.local().revision++;
            eye.add(0.0D, 0.1D, 0.2D);
            assertTrue(++advances < 1_000);
        }
        assertTrue(advances > 1);
        Field revision = ProjectedEntityOcclusion.class.getDeclaredField("revision");
        revision.setAccessible(true);
        assertEquals(0L, revision.getLong(fixture.scan().entityOcclusion()));
        fixture.scan().commit();
        fixture.scan().entityOcclusion().startBatch();
        Field batchReady = ProjectedEntityOcclusion.class.getDeclaredField("batchReady");
        batchReady.setAccessible(true);
        assertFalse(batchReady.getBoolean(fixture.scan().entityOcclusion()));
    }

    private static ScanFixture scanFixture(Frame frame, boolean solidRemote) throws ReflectiveOperationException {
        PortalStructure structure = orientedStructure(frame);
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView local = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remote = new MutableWorldView(blockData(solidRemote ? Material.STONE : Material.AIR));
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remote));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
        useOcclusion(scan);
        return new ScanFixture(scan, destination(portal, structure, local, remote), structure, blackout, local, remote);
    }

    private static void assertEquivalentClaims(Long2ObjectMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> expected,
                                               Long2ObjectMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (long key : expected.keySet()) {
            ProjectedBlockClaim<BlockData, ProjectionWorldView> expectedClaim = expected.get(key);
            ProjectedBlockClaim<BlockData, ProjectionWorldView> actualClaim = actual.get(key);
            assertEquals(expectedClaim.getData().getAsString(), actualClaim.getData().getAsString());
            assertEquals(expectedClaim.getLightRemoteKey(), actualClaim.getLightRemoteKey());
            assertEquals(expectedClaim.getLightingPolicy(), actualClaim.getLightingPolicy());
            assertEquals(expectedClaim.isMaskAir(), actualClaim.isMaskAir());
        }
    }

    private record ScanFixture(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan, ProjectorDestination destination,
                               PortalStructure structure, ProjectorBlackoutSeal blackout,
                               MutableWorldView local, MutableWorldView remote) {
    }

    @Test
    public void completedGeometryFinishesInTheSameSlotOnlyWhileTimeRemains() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        boolean previous = Settings.PROJECTION_FINISH_IN_SLOT;
        try {
            Settings.PROJECTION_FINISH_IN_SLOT = true;
            assertEquals(1, advancesToFinish(scanFixture(frame, true), frame, System.nanoTime() + 3_600_000_000_000L));
            assertEquals(2, advancesToFinish(scanFixture(frame, true), frame, 0L));
            Settings.PROJECTION_FINISH_IN_SLOT = false;
            assertEquals(2, advancesToFinish(scanFixture(frame, true), frame, System.nanoTime() + 3_600_000_000_000L));
        } finally {
            Settings.PROJECTION_FINISH_IN_SLOT = previous;
        }
    }

    @Test
    public void occlusionFinishResumesAcrossSlotsWithTheUninterruptedClaimSet() throws ReflectiveOperationException {
        for (Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            ScanFixture unlimited = scanFixture(frame, true);
            ScanFixture staged = scanFixture(frame, true);
            Location eye = unlimited.structure().getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), unlimited.structure(), new ViewVolume.Options(12.0D, 4.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            unlimited.scan().run(unlimited.destination(), null, BukkitGeometry.vector(eye), frustum, 12.0D,
                true, false, false, new ScanMode(false, true), null, false, LodPolicy.NONE);
            staged.scan().begin(staged.destination(), null, BukkitGeometry.vector(eye), frustum, 12.0D,
                true, false, false, new ScanMode(false, true), null, false, LodPolicy.NONE);
            int targets = -1;
            int finishSlots = 0;
            boolean ready = false;
            while (!ready) {
                boolean finishing = geometryComplete(staged.scan());
                if (finishing && targets < 0) {
                    targets = listSize(staged.scan(), "observerTargetCells") + listSize(staged.scan(), "unresolvedTargetCells");
                }
                ready = staged.scan().advance(0L);
                if (finishing) {
                    finishSlots++;
                }
                assertTrue(finishSlots < 1_000, normal.name());
            }
            assertTrue(targets > 256, normal.name() + " targets=" + targets);
            assertEquals((targets + 127) / 128, finishSlots, normal.name());
            assertTrue(finishSlots >= 3, normal.name());
            assertTrue(staged.scan().occlusionRejected() > 0, normal.name());
            assertEquals(unlimited.scan().occlusionRejected(), staged.scan().occlusionRejected(), normal.name());
            assertEquals(unlimited.scan().unresolvedOcclusionCells(), staged.scan().unresolvedOcclusionCells(), normal.name());
            assertEquivalentClaims(unlimited.scan().claims(), staged.scan().claims());
        }
    }

    private static int advancesToFinish(ScanFixture fixture, Frame frame, long deadlineNanos) {
        Face normal = frame.getNormal();
        Location eye = fixture.structure().getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), fixture.structure(), new ViewVolume.Options(3.0D, 1.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        fixture.scan().begin(fixture.destination(), null, BukkitGeometry.vector(eye), frustum, 3.0D,
            true, false, false, new ScanMode(false, true), null, false, LodPolicy.NONE);
        int advances = 1;
        while (!fixture.scan().advance(deadlineNanos)) {
            advances++;
            assertTrue(advances < 1_000);
        }
        assertFalse(fixture.scan().claims().isEmpty());
        return advances;
    }

    private static boolean geometryComplete(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan)
        throws ReflectiveOperationException {
        Field pendingField = CellScan.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        Object pass = pendingField.get(scan);
        Field geometryField = pass.getClass().getDeclaredField("geometryComplete");
        geometryField.setAccessible(true);
        return geometryField.getBoolean(pass);
    }

    private static int listSize(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan, String name)
        throws ReflectiveOperationException {
        Field field = CellScan.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((LongArrayList) field.get(scan)).size();
    }

    @Test
    public void claimDeltasMatchFullSubmissionsAcrossMovementLightingFilteringAndAbortedPasses()
        throws ReflectiveOperationException {
        for (Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            PortalStructure structure = orientedStructure(frame);
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
            useOcclusion(scan);
            ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> incremental = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
            ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> full = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
            UUID owner = new UUID(0L, 1L);
            Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            Face right = frame.getRight();
            for (int pass = 0; pass < 12; pass++) {
                if (pass == 2) {
                    enableBlackout(blackout);
                }
                if (pass == 3) {
                    blackout.disable();
                    remoteView.data = blockData(Material.STONE);
                    remoteView.revision++;
                    memo.clearDestinationSamples();
                }
                if (pass == 8) {
                    scan.clear();
                }
                eye.add(right.x() * 0.12D, right.y() * 0.12D, right.z() * 0.12D);
                ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(6.0D, 3.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
                scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 6.0D, pass == 0 || pass == 3, pass == 7, true,
                    new ScanMode(false, false), null, false, LodPolicy.NONE);
                if (pass == 4) {
                    scan.claims().keySet().removeIf(key -> (key & 1L) == 0L);
                }
                ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockData, ProjectionWorldView>> delta = scan.claimDelta();
                if (pass == 0 || pass == 6 || pass == 7 || pass == 8) {
                    assertSame(null, delta.previousClaims(), normal + " pass=" + pass);
                }
                ProjectionClaimSet.ProjectionClaimSetResult actual =
                    incremental.replacePortalDelta(owner, owner.toString(), 2.0D, delta);
                ProjectionClaimSet.ProjectionClaimSetResult expected =
                    full.replacePortalClaims(owner, owner.toString(), 2.0D, scan.claims());
                assertEquals(expected.getPacketChangeKeys(), actual.getPacketChangeKeys(), normal + " pass=" + pass);
                assertEquals(expected.getDirtyLightingKeys(), actual.getDirtyLightingKeys(), normal + " pass=" + pass);
                assertEquals(expected.getReverts(), actual.getReverts(), normal + " pass=" + pass);
                assertEquals(full.getWinningClaims().keySet(), incremental.getWinningClaims().keySet(), normal + " pass=" + pass);
                assertEquals(full.hasFullBrightClaims(), incremental.hasFullBrightClaims(), normal + " pass=" + pass);
                for (long key : full.getWinningClaims().keySet()) {
                    assertSame(full.getWinningClaim(key), incremental.getWinningClaim(key), normal + " pass=" + pass);
                }
                if (pass != 5) {
                    scan.commit();
                }
            }
        }
    }

    @Test
    public void movingEyesSkipKnownEmptyRunsAndContentInvalidationRestoresGeometry()
        throws ReflectiveOperationException {
        for (Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            PortalStructure structure = orientedStructure(frame);
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
            CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
            useOcclusion(scan);
            Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            ViewVolume initial = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(8.0D, 4.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            scan.run(destination, null, BukkitGeometry.vector(eye), initial, 8.0D, true, false, false,
                new ScanMode(false, false), null, false, LodPolicy.NONE);
            assertTrue(scan.claims().isEmpty(), normal.name());
            scan.commit();
            Face right = frame.getRight();
            eye.add(right.x() * 0.15D, right.y() * 0.15D, right.z() * 0.15D);
            ViewVolume moved = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(8.0D, 4.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            scan.run(destination, null, BukkitGeometry.vector(eye), moved, 8.0D, false, false, true,
                new ScanMode(false, false), null, false, LodPolicy.NONE);
            assertTrue(scan.emptyCellSkips() > 0, normal.name());
            assertTrue(scan.claims().isEmpty(), normal.name());
            scan.commit();
            localView.data = blockData(Material.STONE);
            localView.revision++;
            memo.refreshLocal(false, true, localView.revision, 4096);
            scan.run(destination, null, BukkitGeometry.vector(eye), moved, 8.0D, false, false, false,
                new ScanMode(false, false), null, false, LodPolicy.NONE);
            assertEquals(0, scan.emptyCellSkips(), normal.name());
            assertFalse(scan.claims().isEmpty(), normal.name());
            Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> expected =
                new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(scan.claims());
            scan.commit();
            scan.invalidateContent();
            scan.run(destination, null, BukkitGeometry.vector(eye), moved, 8.0D, true, false, false,
                new ScanMode(false, false), null, false, LodPolicy.NONE);
            assertEquals(expected.keySet(), scan.claims().keySet(), normal.name());
        }
    }

    @Test
    public void stationaryOcclusionRetriesConvergeWithoutRepeatingGeometryOrSampling()
        throws ReflectiveOperationException {
        double previousMargin = Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES;
        Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES = 2.0D;
        try {
            for (Face normal : Face.values()) {
                Frame frame = Frame.canonical(normal);
                PortalStructure structure = orientedStructure(frame);
                ILocalPortal portal = portal(structure, frame);
                MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
                MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
                ProjectorDestination destination = destination(portal, structure, localView, remoteView);
                ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
                    ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
                ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                    () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
                ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
                enableBlackout(blackout);
                CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
                Field occlusion = CellScan.class.getDeclaredField("viewOcclusion");
                occlusion.setAccessible(true);
                occlusion.set(scan, new ProjectorViewOcclusion<BlockData>(
                    TEST_BLOCKS, Integer.MAX_VALUE));
                Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
                ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(6.0D, 3.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
                scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 6.0D, true, false, false,
                    new ScanMode(false, true), null, false, LodPolicy.NONE);
                Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> expected =
                    new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(scan.claims());
                assertFalse(expected.isEmpty(), normal.name());
                assertEquals(0, scan.unresolvedOcclusionCells(), normal.name());
                scan.clear();
                occlusion.set(scan, new ProjectorViewOcclusion<BlockData>(
                    TEST_BLOCKS, 64));
                scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 6.0D, true, false, false,
                    new ScanMode(false, true), null, false, LodPolicy.NONE);
                assertTrue(scan.unresolvedOcclusionCells() > 0, normal.name());
                assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum), normal.name());
                ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> incremental = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
                ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> full = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
                UUID owner = new UUID(0L, 1L);
                incremental.replacePortalDelta(owner, owner.toString(), 1.0D, scan.claimDelta());
                full.replacePortalClaims(owner, owner.toString(), 1.0D, scan.claims());
                scan.commit();
                sampler.resetRemoteSampleCount();
                localView.readinessQueries = 0;
                int passes = 0;
                while (scan.hasUnresolvedOcclusion() && passes++ < 200) {
                    assertTrue(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum), normal.name());
                    scan.resumeOcclusion();
                    assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum), normal.name());
                    ProjectionClaimSet.ProjectionClaimSetResult actual =
                        incremental.replacePortalDelta(owner, owner.toString(), 1.0D, scan.claimDelta());
                    ProjectionClaimSet.ProjectionClaimSetResult expectedChanges =
                        full.replacePortalClaims(owner, owner.toString(), 1.0D, scan.claims());
                    assertEquals(expectedChanges.getPacketChangeKeys(), actual.getPacketChangeKeys(), normal.name());
                    assertEquals(expectedChanges.getReverts(), actual.getReverts(), normal.name());
                    assertEquals(full.getWinningClaims().keySet(), incremental.getWinningClaims().keySet(), normal.name());
                    scan.commit();
                }
                assertFalse(scan.hasUnresolvedOcclusion(), normal.name() + " retries=" + passes);
                assertTrue(passes > 0, normal.name());
                assertEquals(0, sampler.remoteSampleCount(), normal.name());
                assertEquals(0, localView.readinessQueries, normal.name());
                scan.resumeOcclusion();
                assertEquals(expected.keySet(), liveKeys(scan), normal.name());
                for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : scan.claims().long2ObjectEntrySet()) {
                    if (entry.getValue().isHeld()) {
                        assertFalse(expected.containsKey(entry.getLongKey()), normal.name());
                    }
                }
                for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : expected.long2ObjectEntrySet()) {
                    ProjectedBlockClaim<BlockData, ProjectionWorldView> actual = scan.claims().get(entry.getLongKey());
                    assertEquals(entry.getValue().getData(), actual.getData(), normal.name());
                    assertEquals(entry.getValue().getLightingPolicy(), actual.getLightingPolicy(), normal.name());
                    assertEquals(entry.getValue().getLightRemoteKey(), actual.getLightRemoteKey(), normal.name());
                }
            }
        } finally {
            Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES = previousMargin;
        }
    }

    @Test
    public void occlusionContinuationRejectsChangedEyesViewsLightingAndMissingChunks()
        throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
        Field occlusion = CellScan.class.getDeclaredField("viewOcclusion");
        occlusion.setAccessible(true);
        occlusion.set(scan, new ProjectorViewOcclusion<BlockData>(TEST_BLOCKS, 1));
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(6.0D, 3.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 6.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        scan.commit();
        assertTrue(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
        assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye.clone().add(0.001D, 0.0D, 0.0D)), frustum));
        assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(3.0D, 3.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS))));
        remoteView.revision++;
        assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
        remoteView.revision--;
        localView.revision++;
        assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
        localView.revision--;
        destination.destView = new MutableWorldView(remoteView.data);
        assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
        destination.destView = remoteView;
        enableBlackout(blackout);
        assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
        blackout.disable();
        assertTrue(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
        scan.invalidateOcclusionContinuation();
        assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 6.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        scan.commit();
        assertTrue(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
        localView.ready = false;
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 6.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        scan.commit();
        assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
        scan.clear();
        assertFalse(scan.canResumeOcclusion(destination, BukkitGeometry.vector(eye), frustum));
    }

    @Test
    public void changedDetailCutoffResamplesRetainedRemoteClaims() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.SHORT_GRASS));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        long initialRevision = scanRevision(structure, frame, LodPolicy.NONE, false);
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        long targetKey = CellKeys.pack(0, 64, -3);
        assertTrue(hasRemoteClaim(scan, targetKey));
        int initialClaimCount = scan.claims().size();
        scan.commit();

        LodPolicy reducedDetail = new LodPolicy(false, Integer.MAX_VALUE, 0);
        boolean presentationChanged = initialRevision != scanRevision(structure, frame, reducedDetail, false);
        assertTrue(presentationChanged);
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, presentationChanged, false, true,
            new ScanMode(false, true), null, false, reducedDetail);

        assertFalse(hasRemoteClaim(scan, targetKey));
        assertTrue(scan.claims().size() < initialClaimCount);
    }

    @Test
    public void disablingBlockEntitiesClearsRetainedRemotePayloads() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.CHEST));
        remoteView.blockEntity = new BlockEntitySample("minecraft:chest", new byte[] {10, 0, 0, 0});
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        long initialRevision = scanRevision(structure, frame, LodPolicy.NONE, true);
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, true), null, true, LodPolicy.NONE);
        assertFalse(scan.blockEntities().isEmpty());
        LongOpenHashSet initialKeys = new LongOpenHashSet(scan.claims().keySet());
        scan.commit();

        boolean presentationChanged = initialRevision != scanRevision(structure, frame, LodPolicy.NONE, false);
        assertTrue(presentationChanged);
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, presentationChanged, false, true,
            new ScanMode(false, true), null, false, LodPolicy.NONE);

        assertEquals(initialKeys, scan.claims().keySet());
        assertTrue(scan.blockEntities().isEmpty());
    }

    @Test
    public void cameraMovementRechecksRetainedVisibilityAndRevealsHiddenRemoteCells()
        throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = false;
        try {
            assertHiddenTargetRechecked(false);
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    @Test
    public void committedHiddenCellsStayHeldAndReturnLiveWhenRevealed() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            assertHiddenTargetRechecked(true);
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    @Test
    public void starvedOcclusionBudgetKeepsCommittedHiddenHoldsInsteadOfFlippingThemLive() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            Frame frame = Frame.canonical(Face.S);
            PortalStructure structure = structure();
            Map<String, Object> area = new HashMap<String, Object>();
            area.put("worldKey", "minecraft:overworld");
            area.put("x1", Integer.valueOf(-2));
            area.put("x2", Integer.valueOf(2));
            area.put("y1", Integer.valueOf(64));
            area.put("y2", Integer.valueOf(66));
            area.put("z1", Integer.valueOf(0));
            area.put("z2", Integer.valueOf(0));
            structure.setArea(new Cuboid(area));
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            long targetKey = CellKeys.pack(0, 65, -5);
            remoteView.blocks.put(CellKeys.pack(0, 65, -2), blockData(Material.STONE));
            remoteView.blocks.put(targetKey, blockData(Material.GOLD_BLOCK));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
            CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
            useOcclusion(scan);
            Location sideEye = structure.getCenter().add(2.5D, 0.0D, 1.5D);
            Location centerEye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
            ViewVolume sideFrustum = new ViewVolume(BukkitGeometry.vector(sideEye), structure, new ViewVolume.Options(6.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            ViewVolume centerFrustum = new ViewVolume(BukkitGeometry.vector(centerEye), structure, new ViewVolume.Options(6.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            scan.run(destination, null, BukkitGeometry.vector(sideEye), sideFrustum, 6.0D, true, false, false,
                new ScanMode(false, true), null, false, LodPolicy.NONE);
            long targetLocalKey = remoteClaimKey(scan, targetKey);
            scan.commit();
            scan.run(destination, null, BukkitGeometry.vector(centerEye), centerFrustum, 6.0D, false, false, true,
                new ScanMode(false, true), null, false, LodPolicy.NONE);
            assertTrue(scan.claims().get(targetLocalKey).isHeld());
            scan.commit();

            Field field = CellScan.class.getDeclaredField("viewOcclusion");
            field.setAccessible(true);
            field.set(scan, new ProjectorViewOcclusion<BlockData>(TEST_BLOCKS, 1));
            memo.clearDestinationSamples();
            scan.run(destination, null, BukkitGeometry.vector(centerEye), centerFrustum, 6.0D, true, false, true,
                new ScanMode(false, true), null, false, LodPolicy.NONE);

            ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockData, ProjectionWorldView>> delta = scan.claimDelta();
            assertTrue(scan.unresolvedOcclusionCells() > 0, "the starved pass must leave cells unresolved");
            assertTrue(scan.claims().get(targetLocalKey).isHeld(), "an unresolved committed hold stays held");
            assertFalse(delta.changedKeys().contains(targetLocalKey));
            assertFalse(delta.removedKeys().contains(targetLocalKey));
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    private static void assertHiddenTargetRechecked(boolean hold) throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        Map<String, Object> area = new HashMap<String, Object>();
        area.put("worldKey", "minecraft:overworld");
        area.put("x1", Integer.valueOf(-2));
        area.put("x2", Integer.valueOf(2));
        area.put("y1", Integer.valueOf(64));
        area.put("y2", Integer.valueOf(66));
        area.put("z1", Integer.valueOf(0));
        area.put("z2", Integer.valueOf(0));
        structure.setArea(new Cuboid(area));
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        long targetKey = CellKeys.pack(0, 65, -5);
        remoteView.blocks.put(CellKeys.pack(0, 65, -2), blockData(Material.STONE));
        remoteView.blocks.put(targetKey, blockData(Material.GOLD_BLOCK));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        Location sideEye = structure.getCenter().add(2.5D, 0.0D, 1.5D);
        Location centerEye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume sideFrustum = new ViewVolume(BukkitGeometry.vector(sideEye), structure, new ViewVolume.Options(6.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        ViewVolume centerFrustum = new ViewVolume(BukkitGeometry.vector(centerEye), structure, new ViewVolume.Options(6.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        scan.run(destination, null, BukkitGeometry.vector(sideEye), sideFrustum, 6.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);

        assertTrue(hasRemoteClaim(scan, targetKey));
        long targetLocalKey = remoteClaimKey(scan, targetKey);
        scan.commit();
        memo.clearDestinationSamples();
        remoteView.readKeys.clear();
        scan.run(destination, null, BukkitGeometry.vector(centerEye), centerFrustum, 6.0D, false, false, true,
            new ScanMode(false, true), null, false, LodPolicy.NONE);

        assertFalse(remoteView.readKeys.contains(targetKey), "camera-only refresh must reuse the retained target sample");
        ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockData, ProjectionWorldView>> hiddenDelta = scan.claimDelta();
        if (hold) {
            ProjectedBlockClaim<BlockData, ProjectionWorldView> held = scan.claims().get(targetLocalKey);
            assertTrue(held != null && held.isHeld(), "a committed cell turning hidden stays claimed as held");
            assertTrue(scan.hiddenHolds() > 0);
            assertFalse(hiddenDelta.removedKeys().contains(targetLocalKey));
            assertTrue(hiddenDelta.changedKeys().contains(targetLocalKey));
        } else {
            assertFalse(hasRemoteClaim(scan, targetKey));
            assertTrue(scan.occlusionRejected() > 0);
            assertTrue(hiddenDelta.removedKeys().contains(targetLocalKey));
        }
        scan.commit();
        if (hold) {
            scan.revokeConeHolds();
            scan.run(destination, null, BukkitGeometry.vector(centerEye), centerFrustum, 6.0D, false, false, true,
                new ScanMode(false, true), null, false, LodPolicy.NONE);
            assertTrue(scan.claims().get(targetLocalKey).isHeld(), "a local change keeps holds proven by remote occlusion");
            assertFalse(scan.claimDelta().removedKeys().contains(targetLocalKey));
            scan.commit();
        }
        memo.clearDestinationSamples();
        remoteView.readKeys.clear();
        scan.run(destination, null, BukkitGeometry.vector(sideEye), sideFrustum, 6.0D, false, false, true,
            new ScanMode(false, true), null, false, LodPolicy.NONE);

        assertTrue(hasRemoteClaim(scan, targetKey));
        assertFalse(scan.claims().get(targetLocalKey).isHeld(), "a revealed cell is live again");
        assertTrue(remoteView.readKeys.contains(targetKey), "a previously hidden target must be sampled again");
    }

    @Test
    public void claimsLeavingTheConeAreHeldOnlyBehindAnOccludingLocalWall() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            for (boolean wall : new boolean[] {true, false}) {
                HoldFixture fixture = holdFixture(wall);
                ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> incremental = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
                ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> full = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
                Location first = fixture.eye(3.0D);
                fixture.run(first, true);
                assertDeltaMatchesFull(fixture.scan(), incremental, full, "wall=" + wall + " first");
                LongOpenHashSet firstKeys = new LongOpenHashSet(fixture.scan().claims().keySet());
                fixture.scan().commit();

                Location second = fixture.eye(-3.0D);
                fixture.run(second, false);
                ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockData, ProjectionWorldView>> delta = fixture.scan().claimDelta();
                LongOpenHashSet removed = new LongOpenHashSet(delta.removedKeys());
                LongOpenHashSet held = heldKeys(fixture.scan());
                ProjectionClaimSet.ProjectionClaimSetResult result = assertDeltaMatchesFull(fixture.scan(), incremental, full, "wall=" + wall + " second");
                ProjectorHoldProof proof = ProjectorHoldProof.create(fixture.structure().getArea(), fixture.scan().localFrame(),
                    fixture.structure().getCenter().getX(), fixture.structure().getCenter().getY(), fixture.structure().getCenter().getZ(),
                    Settings.PROJECTION_APERTURE_PADDING_BLOCKS);
                assertTrue(proof.beginEye(second.getX(), second.getY(), second.getZ()));
                LongOpenHashSet live = liveKeys(fixture.scan());
                int left = 0;
                for (long key : firstKeys) {
                    if (live.contains(key)) {
                        continue;
                    }
                    left++;
                    ProjectorHoldProof.Verdict verdict = proof.verdict(CellKeys.unpackX(key), CellKeys.unpackY(key),
                        CellKeys.unpackZ(key), fixture::occupancy);
                    if (held.contains(key)) {
                        assertEquals(ProjectorHoldProof.Verdict.HOLD, verdict, "wall=" + wall);
                        assertFalse(removed.contains(key), "wall=" + wall);
                        assertFalse(result.getPacketChangeKeys().contains(key), "a held cell sends nothing");
                    } else {
                        assertFalse(verdict.holds(), "wall=" + wall);
                        assertTrue(removed.contains(key), "wall=" + wall);
                    }
                }
                assertTrue(left > 0, "wall=" + wall);
                if (wall) {
                    assertFalse(held.isEmpty());
                    assertEquals(held.size(), fixture.scan().coneHolds());
                    assertEquals(held.size(), fixture.scan().heldClaims());
                } else {
                    assertTrue(held.isEmpty());
                    assertEquals(0, fixture.scan().coneHolds());
                }
                fixture.scan().commit();
            }
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    @Test
    public void sideFlipAndLocalChangesRevertEveryHeldClaim() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            for (boolean sideFlip : new boolean[] {true, false}) {
                HoldFixture fixture = holdFixture(true);
                ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> incremental = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
                ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> full = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
                fixture.run(fixture.eye(3.0D), true);
                assertDeltaMatchesFull(fixture.scan(), incremental, full, "first");
                fixture.scan().commit();
                Location second = fixture.eye(-3.0D);
                fixture.run(second, false);
                assertDeltaMatchesFull(fixture.scan(), incremental, full, "second");
                LongOpenHashSet held = heldKeys(fixture.scan());
                assertFalse(held.isEmpty());
                fixture.scan().commit();

                Location third;
                if (sideFlip) {
                    third = second.clone().add(0.0D, 0.0D, -5.0D);
                } else {
                    fixture.scan().revokeConeHolds();
                    third = second;
                }
                fixture.run(third, false);
                ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockData, ProjectionWorldView>> delta = fixture.scan().claimDelta();
                assertTrue(heldKeys(fixture.scan()).isEmpty(), "sideFlip=" + sideFlip);
                assertEquals(0, fixture.scan().heldClaims(), "sideFlip=" + sideFlip);
                for (long key : held) {
                    assertTrue(delta.removedKeys().contains(key), "sideFlip=" + sideFlip);
                }
                assertDeltaMatchesFull(fixture.scan(), incremental, full, "third sideFlip=" + sideFlip);
                fixture.scan().commit();
            }
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    @Test
    public void droppedHoldsRevertEveryHeldClaimEvenAfterACancelledPass() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            HoldFixture fixture = holdFixture(true);
            ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> incremental = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
            ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> full = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
            fixture.run(fixture.eye(3.0D), true);
            assertDeltaMatchesFull(fixture.scan(), incremental, full, "first");
            fixture.scan().commit();
            Location second = fixture.eye(-3.0D);
            fixture.run(second, false);
            assertDeltaMatchesFull(fixture.scan(), incremental, full, "second");
            LongOpenHashSet held = heldKeys(fixture.scan());
            assertFalse(held.isEmpty());
            fixture.scan().commit();

            fixture.scan().dropHolds();
            fixture.run(second, false);
            ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockData, ProjectionWorldView>> delta = fixture.scan().claimDelta();
            LongOpenHashSet live = liveKeys(fixture.scan());
            assertTrue(heldKeys(fixture.scan()).isEmpty());
            assertEquals(0, fixture.scan().heldClaims());
            for (long key : held) {
                assertTrue(delta.removedKeys().contains(key) || live.contains(key), "a dropped hold is released or resampled live");
            }
            assertDeltaMatchesFull(fixture.scan(), incremental, full, "dropped");
            fixture.scan().commit();

            fixture.run(second, false);
            assertTrue(heldKeys(fixture.scan()).isEmpty(), "released cells are never re-held from a dropped baseline");
            fixture.scan().commit();

            fixture.run(fixture.eye(3.0D), false);
            fixture.scan().commit();
            fixture.run(second, false);
            assertFalse(heldKeys(fixture.scan()).isEmpty());
            fixture.scan().commit();
            fixture.scan().dropHolds();
            ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(second), fixture.structure(), new ViewVolume.Options(8.0D, 4.0D,
                Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            fixture.scan().begin(fixture.destination(), null, BukkitGeometry.vector(second), frustum, 8.0D, false, false, true,
                new ScanMode(false, false), null, false, LodPolicy.NONE);
            fixture.scan().cancelPending();
            fixture.run(second, false);
            assertTrue(heldKeys(fixture.scan()).isEmpty(), "a drop request survives a cancelled pass");
            fixture.scan().commit();
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    @Test
    public void displacedBlockersReleaseOnlyTheHiddenHoldsTheyProveAndReleasedCellsResample() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            WallFixture wall = wallFixture();
            wall.runCenter(true);
            long nearLocalKey = remoteClaimKey(wall.scan(), wall.nearTarget());
            long farLocalKey = remoteClaimKey(wall.scan(), wall.farTarget());
            assertFalse(wall.scan().claims().get(nearLocalKey).isHeld());
            assertFalse(wall.scan().claims().get(farLocalKey).isHeld());
            wall.scan().commit();

            wall.buildWall();
            wall.memo().clearDestinationSamples();
            wall.runCenter(true);
            assertTrue(wall.scan().claims().get(nearLocalKey).isHeld(), "the near target hides behind the wall");
            assertTrue(wall.scan().claims().get(farLocalKey).isHeld(), "the far target hides behind the wall");
            assertEquals(2, wall.scan().hiddenHolds());
            wall.scan().commit();
            LongOpenHashSet nearBlockers = heldBlockers(wall.scan(), nearLocalKey);
            LongOpenHashSet farBlockers = heldBlockers(wall.scan(), farLocalKey);
            assertFalse(nearBlockers.isEmpty());
            assertFalse(farBlockers.isEmpty());
            for (long blocker : nearBlockers) {
                assertTrue(wall.wallKeys().contains(blocker), "hidden holds are proven by wall cells");
                assertFalse(farBlockers.contains(blocker), "the two targets hide behind different wall cells");
            }

            LongOpenHashSet displaced = new LongOpenHashSet();
            displaced.add(nearBlockers.iterator().nextLong());
            LongOpenHashSet restored = new LongOpenHashSet();
            assertTrue(wall.scan().exposeLosingClaims(displaced, restored, false), "a displaced blocker with dependents needs a pass");
            wall.runCenter(false);
            assertFalse(wall.scan().claims().containsKey(nearLocalKey), "the hold behind the displaced blocker is released");
            assertTrue(wall.scan().claimDelta().removedKeys().contains(nearLocalKey));
            assertTrue(wall.scan().claims().get(farLocalKey).isHeld(), "holds behind other blockers stay");
            assertEquals(1, wall.scan().hiddenHolds());
            wall.scan().commit();
            assertFalse(wall.scan().exposeLosingClaims(new LongOpenHashSet(), restored, false), "nothing else is pending");

            wall.runCenter(false);
            assertFalse(wall.scan().claims().containsKey(nearLocalKey), "a released cell behind a losing blocker is not re-held");
            assertTrue(wall.scan().claims().get(farLocalKey).isHeld());
            wall.scan().commit();

            restored.addAll(displaced);
            assertFalse(wall.scan().exposeLosingClaims(new LongOpenHashSet(), restored, false));
            for (long blocker : nearBlockers) {
                wall.remote().blocks.remove(blocker);
            }
            wall.memo().clearDestinationSamples();
            wall.runCenter(true);
            ProjectedBlockClaim<BlockData, ProjectionWorldView> resampled = wall.scan().claims().get(nearLocalKey);
            assertTrue(resampled != null && !resampled.isHeld(), "a released cell is resampled live once its blockers open");
            assertTrue(wall.scan().claimDelta().changedKeys().contains(nearLocalKey));
            assertTrue(wall.scan().claims().get(farLocalKey).isHeld());
            wall.scan().commit();
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    @Test
    public void hiddenHoldsAreNeverProvenByBlockersWhoseClaimsAlreadyLose() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            WallFixture reference = wallFixture();
            reference.runCenter(true);
            long nearLocalKey = remoteClaimKey(reference.scan(), reference.nearTarget());
            long farLocalKey = remoteClaimKey(reference.scan(), reference.farTarget());
            reference.scan().commit();
            reference.buildWall();
            reference.memo().clearDestinationSamples();
            reference.runCenter(true);
            reference.scan().commit();
            LongOpenHashSet farBlockers = heldBlockers(reference.scan(), farLocalKey);
            assertFalse(farBlockers.isEmpty());

            WallFixture wall = wallFixture();
            wall.runCenter(true);
            wall.scan().commit();
            LongOpenHashSet restored = new LongOpenHashSet();
            assertFalse(wall.scan().exposeLosingClaims(farBlockers, restored, false), "losing cells without holds behind them need no pass");

            wall.buildWall();
            wall.memo().clearDestinationSamples();
            wall.runAt(0.25D, true);
            assertTrue(wall.scan().claims().get(nearLocalKey).isHeld(), "a hold behind winning blockers is taken");
            assertFalse(wall.scan().claims().containsKey(farLocalKey), "a cell hidden only by losing blockers is released");
            assertEquals(1, wall.scan().hiddenHolds());
            wall.scan().commit();

            assertFalse(wall.scan().exposeLosingClaims(new LongOpenHashSet(), farBlockers, false));
            wall.runAt(0.25D, false);
            assertFalse(wall.scan().claims().containsKey(farLocalKey), "a released cell has nothing to re-hold");
            assertTrue(wall.scan().claims().get(nearLocalKey).isHeld());
            wall.scan().commit();

            HoldFixture cone = holdFixture(true);
            cone.run(cone.eye(3.0D), true);
            cone.scan().commit();
            LongOpenHashSet allCommitted = new LongOpenHashSet(cone.scan().claims().keySet());
            assertFalse(cone.scan().exposeLosingClaims(allCommitted, restored, false), "wall-proven holds depend on no blocker claim");
            cone.run(cone.eye(-3.0D), false);
            assertTrue(cone.scan().coneHolds() > 0);
            assertEquals(0, cone.scan().hiddenHolds());
            cone.scan().commit();
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    @Test
    public void claimSetTransitionsReleaseTheHoldsBehindTheDisplacedClaim() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            WallFixture wall = wallFixture();
            wall.runCenter(true);
            long nearLocalKey = remoteClaimKey(wall.scan(), wall.nearTarget());
            long farLocalKey = remoteClaimKey(wall.scan(), wall.farTarget());
            wall.scan().commit();
            wall.buildWall();
            wall.memo().clearDestinationSamples();
            wall.runCenter(true);
            wall.scan().commit();
            long nearBlocker = heldBlockers(wall.scan(), nearLocalKey).iterator().nextLong();

            ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> set = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
            UUID owner = new UUID(0L, 7L);
            UUID other = new UUID(0L, 8L);
            set.replacePortalClaims(owner, owner.toString(), 5.0D, wall.scan().claims());
            LongOpenHashSet displaced = new LongOpenHashSet();
            LongOpenHashSet restored = new LongOpenHashSet();
            assertFalse(set.drainLosingTransitions(owner, displaced, restored, true), "an uncontested portal has no losing keys");
            assertTrue(displaced.isEmpty() && restored.isEmpty());
            assertFalse(wall.scan().exposeLosingClaims(displaced, restored, true));

            Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> otherClaims = new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
            otherClaims.put(nearBlocker, new ProjectedBlockClaim<BlockData, ProjectionWorldView>(blockData(Material.AIR), null, ProjectedBlockClaim.NO_REMOTE_KEY, false));
            set.replacePortalClaims(other, other.toString(), 1.0D, otherClaims);
            assertTrue(set.drainLosingTransitions(owner, displaced, restored, false));
            assertEquals(LongOpenHashSet.of(nearBlocker), displaced);
            assertTrue(wall.scan().exposeLosingClaims(displaced, restored, false));
            wall.runCenter(false);
            assertFalse(wall.scan().claims().containsKey(nearLocalKey));
            assertTrue(wall.scan().claims().get(farLocalKey).isHeld());
            set.replacePortalDelta(owner, owner.toString(), 5.0D, wall.scan().claimDelta());
            wall.scan().commit();
            assertTrue(set.getWinningClaim(farLocalKey).isHeld());
            assertFalse(set.getWinningClaim(nearBlocker).isHeld());
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    private static WallFixture wallFixture() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        Map<String, Object> area = new HashMap<String, Object>();
        area.put("worldKey", "minecraft:overworld");
        area.put("x1", Integer.valueOf(-2));
        area.put("x2", Integer.valueOf(2));
        area.put("y1", Integer.valueOf(64));
        area.put("y2", Integer.valueOf(66));
        area.put("z1", Integer.valueOf(0));
        area.put("z2", Integer.valueOf(0));
        structure.setArea(new Cuboid(area));
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        long nearTarget = CellKeys.pack(0, 65, -5);
        long farTarget = CellKeys.pack(3, 65, -5);
        remoteView.blocks.put(nearTarget, blockData(Material.GOLD_BLOCK));
        remoteView.blocks.put(farTarget, blockData(Material.GOLD_BLOCK));
        LongOpenHashSet wallKeys = new LongOpenHashSet();
        for (int x = -4; x <= 6; x++) {
            for (int y = 63; y <= 67; y++) {
                wallKeys.add(CellKeys.pack(x, y, -2));
            }
        }
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        return new WallFixture(scan, destination, structure, remoteView, memo, nearTarget, farTarget, wallKeys);
    }

    private record WallFixture(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan,
                               ProjectorDestination destination, PortalStructure structure, MutableWorldView remote,
                               ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo, long nearTarget, long farTarget,
                               LongOpenHashSet wallKeys) {
        private void buildWall() {
            BlockData stone = blockData(Material.STONE);
            for (long key : wallKeys) {
                remote.blocks.put(key, stone);
            }
        }

        private void runCenter(boolean forceResample) {
            runAt(0.0D, forceResample);
        }

        private void runAt(double lateral, boolean forceResample) {
            Location eye = structure.getCenter().add(lateral, 0.0D, 1.5D);
            ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(6.0D, 2.0D,
                Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 6.0D, forceResample, false, true,
                new ScanMode(false, true), null, false, LodPolicy.NONE);
        }
    }

    private static LongOpenHashSet heldBlockers(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan, long key)
        throws ReflectiveOperationException {
        Field field = CellScan.class.getDeclaredField("heldBlockers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Long2ObjectOpenHashMap<long[]> blockers = (Long2ObjectOpenHashMap<long[]>) field.get(scan);
        long[] recorded = blockers.get(key);
        return recorded == null ? new LongOpenHashSet() : new LongOpenHashSet(recorded);
    }

    @Test
    public void hiddenHoldsCarryTheFreshBlockWhenTheDestinationChanged() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            HiddenFixture hidden = hiddenFixture();
            hidden.runSide(true);
            long targetLocalKey = remoteClaimKey(hidden.scan(), hidden.targetKey());
            hidden.scan().commit();
            hidden.runCenter(false);
            ProjectedBlockClaim<BlockData, ProjectionWorldView> held = hidden.scan().claims().get(targetLocalKey);
            assertTrue(held.isHeld());
            hidden.scan().commit();

            hidden.runCenter(false);
            assertSame(held, hidden.scan().claims().get(targetLocalKey), "an unchanged hidden cell keeps its committed hold");
            assertFalse(hidden.scan().claimDelta().changedKeys().contains(targetLocalKey));
            hidden.scan().commit();

            hidden.remote().blocks.put(hidden.targetKey(), blockData(Material.DIAMOND_BLOCK));
            hidden.memo().clearDestinationSamples();
            hidden.runCenter(false);
            ProjectedBlockClaim<BlockData, ProjectionWorldView> refreshed = hidden.scan().claims().get(targetLocalKey);
            assertTrue(refreshed.isHeld(), "the cell is still hidden");
            assertEquals(Material.DIAMOND_BLOCK, refreshed.getData().getMaterial(), "a hidden hold never pins stale destination content");
            assertTrue(hidden.scan().claimDelta().changedKeys().contains(targetLocalKey));
            hidden.scan().commit();
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
    }

    private static HiddenFixture hiddenFixture() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        Map<String, Object> area = new HashMap<String, Object>();
        area.put("worldKey", "minecraft:overworld");
        area.put("x1", Integer.valueOf(-2));
        area.put("x2", Integer.valueOf(2));
        area.put("y1", Integer.valueOf(64));
        area.put("y2", Integer.valueOf(66));
        area.put("z1", Integer.valueOf(0));
        area.put("z2", Integer.valueOf(0));
        structure.setArea(new Cuboid(area));
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        long targetKey = CellKeys.pack(0, 65, -5);
        remoteView.blocks.put(CellKeys.pack(0, 65, -2), blockData(Material.STONE));
        remoteView.blocks.put(targetKey, blockData(Material.GOLD_BLOCK));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        return new HiddenFixture(scan, destination, structure, remoteView, memo, targetKey);
    }

    private record HiddenFixture(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan,
                                 ProjectorDestination destination, PortalStructure structure, MutableWorldView remote,
                                 ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo, long targetKey) {
        private void runSide(boolean forceResample) {
            run(structure.getCenter().add(2.5D, 0.0D, 1.5D), forceResample);
        }

        private void runCenter(boolean forceResample) {
            run(structure.getCenter().add(0.0D, 0.0D, 1.5D), forceResample);
        }

        private void run(Location eye, boolean forceResample) {
            ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(6.0D, 2.0D,
                Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 6.0D, forceResample, false, true,
                new ScanMode(false, true), null, false, LodPolicy.NONE);
        }
    }

    @Test
    public void heldCapEvictsTheOldestHeldCellsFirst() throws ReflectiveOperationException {
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        int originalCap = Settings.PROJECTION_MAX_HELD_CELLS_PER_PORTAL;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = true;
        try {
            double[] offsets = new double[] {3.0D, 0.0D, -3.0D};
            Settings.PROJECTION_MAX_HELD_CELLS_PER_PORTAL = Integer.MAX_VALUE;
            HoldFixture reference = holdFixture(true);
            LongOpenHashSet previousHeld = new LongOpenHashSet();
            LongOpenHashSet newest = new LongOpenHashSet();
            LongOpenHashSet older = new LongOpenHashSet();
            for (int pass = 0; pass < offsets.length; pass++) {
                reference.run(reference.eye(offsets[pass]), pass == 0);
                LongOpenHashSet held = heldKeys(reference.scan());
                if (pass == offsets.length - 1) {
                    for (long key : held) {
                        if (previousHeld.contains(key)) {
                            older.add(key);
                        } else {
                            newest.add(key);
                        }
                    }
                }
                previousHeld = held;
                reference.scan().commit();
            }
            assertFalse(newest.isEmpty());
            assertFalse(older.isEmpty());

            Settings.PROJECTION_MAX_HELD_CELLS_PER_PORTAL = newest.size();
            HoldFixture capped = holdFixture(true);
            ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> incremental = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
            ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> full = new ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>>();
            for (int pass = 0; pass < offsets.length; pass++) {
                capped.run(capped.eye(offsets[pass]), pass == 0);
                assertTrue(capped.scan().heldClaims() <= newest.size(), "pass=" + pass);
                assertDeltaMatchesFull(capped.scan(), incremental, full, "capped pass=" + pass);
                if (pass == offsets.length - 1) {
                    assertEquals(newest, heldKeys(capped.scan()));
                    assertTrue(capped.scan().heldEvictions() > 0);
                }
                capped.scan().commit();
            }
        } finally {
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
            Settings.PROJECTION_MAX_HELD_CELLS_PER_PORTAL = originalCap;
        }
    }

    private static ProjectionClaimSet.ProjectionClaimSetResult assertDeltaMatchesFull(
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan,
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> incremental,
        ProjectionClaimSet<ProjectedBlockClaim<BlockData, ProjectionWorldView>> full,
        String label) {
        UUID owner = new UUID(0L, 7L);
        ProjectionClaimSet.ProjectionClaimSetResult actual =
            incremental.replacePortalDelta(owner, owner.toString(), 2.0D, scan.claimDelta());
        ProjectionClaimSet.ProjectionClaimSetResult expected =
            full.replacePortalClaims(owner, owner.toString(), 2.0D, scan.claims());
        assertEquals(expected.getPacketChangeKeys(), actual.getPacketChangeKeys(), label);
        assertEquals(expected.getReverts(), actual.getReverts(), label);
        assertEquals(full.getWinningClaims().keySet(), incremental.getWinningClaims().keySet(), label);
        for (long key : full.getWinningClaims().keySet()) {
            assertSame(full.getWinningClaim(key), incremental.getWinningClaim(key), label);
        }
        return actual;
    }

    private static HoldFixture holdFixture(boolean wall) throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        Map<String, Object> area = new HashMap<String, Object>();
        area.put("worldKey", "minecraft:overworld");
        area.put("x1", Integer.valueOf(-1));
        area.put("x2", Integer.valueOf(1));
        area.put("y1", Integer.valueOf(64));
        area.put("y2", Integer.valueOf(66));
        area.put("z1", Integer.valueOf(0));
        area.put("z2", Integer.valueOf(0));
        structure.setArea(new Cuboid(area));
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        if (wall) {
            BlockData stone = blockData(Material.STONE);
            for (int x = -32; x <= 32; x++) {
                for (int y = 32; y <= 96; y++) {
                    if (x >= -1 && x <= 1 && y >= 64 && y <= 66) {
                        continue;
                    }
                    localView.blocks.put(CellKeys.pack(x, y, 0), stone);
                }
            }
        }
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        return new HoldFixture(scan, destination, structure, localView);
    }

    private record HoldFixture(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan,
                               ProjectorDestination destination, PortalStructure structure, MutableWorldView local) {
        private Location eye(double lateral) {
            return structure.getCenter().add(lateral, 0.0D, 2.0D);
        }

        private void run(Location eye, boolean forceResample) {
            ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(8.0D, 4.0D,
                Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 8.0D, forceResample, false, true,
                new ScanMode(false, false), null, false, LodPolicy.NONE);
        }

        private ProjectorHoldProof.Occupancy occupancy(int x, int y, int z) {
            return testMaterialOccluding(local.sampleBlockData(x, y, z).getMaterial())
                ? ProjectorHoldProof.Occupancy.OCCLUDING : ProjectorHoldProof.Occupancy.OPEN;
        }
    }

    @Test
    public void remoteFootprintCoversScannedCellsAndRestartsOnlyOnFreshPasses() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.GLASS));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        scan.restartRemoteFootprint();
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        scan.commit();
        long firstCell = scan.claims().values().iterator().next().getLightRemoteKey();
        for (ProjectedBlockClaim<BlockData, ProjectionWorldView> claim : scan.claims().values()) {
            assertTrue(affects(scan.remoteFootprint(), claim.getLightRemoteKey()));
        }
        assertFalse(scan.remoteFootprint().affectsBlock(CellKeys.unpackX(firstCell) + 400,
            CellKeys.unpackY(firstCell), CellKeys.unpackZ(firstCell)));

        destination.originX += 512.0D;
        memo.clearDestinationSamples();
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        scan.commit();
        long movedCell = scan.claims().values().iterator().next().getLightRemoteKey();
        assertTrue(affects(scan.remoteFootprint(), firstCell), "a pass without a restart keeps earlier reads");
        assertTrue(affects(scan.remoteFootprint(), movedCell));

        scan.restartRemoteFootprint();
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        scan.commit();
        assertFalse(affects(scan.remoteFootprint(), firstCell), "a fresh pass replaces the footprint");
        assertTrue(affects(scan.remoteFootprint(), movedCell));
        assertFalse(scan.remoteFootprint().nested());
    }

    private static boolean affects(ProjectorRemoteFootprint footprint, long cellKey) {
        return footprint.affectsBlock(CellKeys.unpackX(cellKey), CellKeys.unpackY(cellKey),
            CellKeys.unpackZ(cellKey));
    }

    @Test
    public void changedRemoteRevisionResamplesRetainedClaims() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.GLASS));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        memo.refreshDestination(remoteView.getRevision());
        assertFalse(scan.claims().isEmpty());
        scan.commit();
        remoteView.data = blockData(Material.GOLD_BLOCK);
        remoteView.revision++;

        boolean destinationStale = memo.destinationStale(remoteView.getRevision(), false, since -> WorldChangeTracker.AFFECTED);
        assertTrue(destinationStale);
        memo.clearDestinationSamples();
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, destinationStale, false, true,
            new ScanMode(false, true), null, false, LodPolicy.NONE);

        assertFalse(scan.claims().isEmpty());
        for (ProjectedBlockClaim<BlockData, ProjectionWorldView> claim : scan.claims().values()) {
            assertSame(remoteView.data, claim.getData());
        }
    }

    @Test
    public void retainedCellMappingsMatchFreshSamplesAfterOriginMirrorAndFrameChanges()
        throws ReflectiveOperationException {
        for (Face normal : Face.values()) {
            for (int scenario = 0; scenario < 3; scenario++) {
                Frame frame = Frame.canonical(normal);
                PortalStructure structure = orientedStructure(frame);
                ILocalPortal portal = portal(structure, frame);
                MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
                MutableWorldView remoteView = new MutableWorldView(blockData(Material.GLASS));
                ProjectorDestination destination = destination(portal, structure, localView, remoteView);
                destination.mirrorMode = scenario == 1;
                ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
                ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                    () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
                CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
                useOcclusion(scan);
                Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
                ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(6.0D, 3.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
                scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 6.0D, true, false, false,
                    new ScanMode(false, false), null, false, LodPolicy.NONE);
                assertFalse(scan.claims().isEmpty());
                scan.commit();

                Face right = frame.getRight();
                eye.add(right.x() * 0.15D, right.y() * 0.15D, right.z() * 0.15D);
                ViewVolume moved = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(6.0D, 3.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
                scan.run(destination, null, BukkitGeometry.vector(eye), moved, 6.0D, false, false, true,
                    new ScanMode(false, false), null, false, LodPolicy.NONE);
                scan.commit();
                if (scenario == 0) {
                    destination.originX += 2.0D;
                } else if (scenario == 1) {
                    destination.mirrorRotationQuarterTurns = 1;
                } else {
                    destination.destAnchor = portal(structure, frame.rotateClockwise());
                }
                scan.run(destination, null, BukkitGeometry.vector(eye), moved, 6.0D, false, false, true,
                    new ScanMode(false, false), null, false, LodPolicy.NONE);
                Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> actual =
                    new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(scan.claims());
                scan.run(destination, null, BukkitGeometry.vector(eye), moved, 6.0D, true, false, true,
                    new ScanMode(false, false), null, false, LodPolicy.NONE);

                assertEquals(scan.claims().keySet(), actual.keySet());
                for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : scan.claims().long2ObjectEntrySet()) {
                    ProjectedBlockClaim<BlockData, ProjectionWorldView> retained = actual.get(entry.getLongKey());
                    assertEquals(entry.getValue().getLightRemoteKey(), retained.getLightRemoteKey(),
                        normal.name() + " scenario=" + scenario);
                    assertSame(entry.getValue().getData(), retained.getData());
                }
            }
        }
    }

    @Test
    public void changedLocalAirRemovesRemoteAirClaimsDuringCameraRefresh() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        memo.refreshLocal(false, false, localView.getRevision(), 4096);
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        assertFalse(scan.claims().isEmpty());
        scan.commit();
        localView.data = blockData(Material.AIR);
        localView.revision++;

        boolean localSamplesStale = memo.refreshLocal(false, false, localView.getRevision(), 4096);
        assertTrue(localSamplesStale);
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, localSamplesStale, false, true,
            new ScanMode(false, true), null, false, LodPolicy.NONE);

        assertTrue(scan.claims().isEmpty());
    }

    @Test
    public void cameraRefreshDoesNotReuseClaimsFromAnotherDestination() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.GLASS));
        MutableWorldView nextRemoteView = new MutableWorldView(blockData(Material.GOLD_BLOCK));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, true), null, false, LodPolicy.NONE);
        assertFalse(scan.claims().isEmpty());
        scan.commit();
        destination.destView = nextRemoteView;
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, false, false, true,
            new ScanMode(false, true), null, false, LodPolicy.NONE);

        assertFalse(scan.claims().isEmpty());
        for (ProjectedBlockClaim<BlockData, ProjectionWorldView> claim : scan.claims().values()) {
            assertSame(nextRemoteView.data, claim.getData());
            assertSame(nextRemoteView, claim.getLightView());
        }
    }

    @Test
    public void chunkReadinessAndRequestsAreSharedWithinEachScanAndRetriedNextPass()
        throws ReflectiveOperationException {
        for (Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            PortalStructure structure = orientedStructure(frame);
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
            CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
            useOcclusion(scan);
            Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

            assertFalse(scan.claims().isEmpty(), normal.name());
            LongOpenHashSet chunks = new LongOpenHashSet();
            for (long key : scan.claims().keySet()) {
                int chunkX = CellKeys.unpackX(key) >> 4;
                int chunkZ = CellKeys.unpackZ(key) >> 4;
                chunks.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
            }
            assertEquals(chunks.size(), localView.readinessQueries, normal.name());
            assertTrue(localView.readinessQueries < scan.claims().size(), normal.name());
            LongOpenHashSet initialKeys = new LongOpenHashSet(scan.claims().keySet());
            scan.commit();
            localView.ready = false;
            localView.readinessQueries = 0;
            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

            assertEquals(initialKeys, scan.claims().keySet(), normal.name());
            assertEquals(chunks.size(), localView.readinessQueries, normal.name());
            assertEquals(chunks.size(), localView.requests, normal.name());
            scan.commit();
            localView.ready = true;
            localView.readinessQueries = 0;
            localView.requests = 0;
            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

            assertEquals(initialKeys, scan.claims().keySet(), normal.name());
            assertEquals(chunks.size(), localView.readinessQueries, normal.name());
            assertEquals(0, localView.requests, normal.name());
        }
    }

    @Test
    public void unavailableLocalAndRemoteChunksFollowCurrentBlackoutLighting() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));

        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

        assertFalse(scan.claims().isEmpty());
        assertLighting(scan, ProjectedBlockClaim.LightingPolicy.SOURCE);
        LongOpenHashSet initialKeys = new LongOpenHashSet(scan.claims().keySet());
        scan.commit();

        enableBlackout(blackout);
        localView.ready = false;
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

        assertEquals(initialKeys, scan.claims().keySet());
        assertLighting(scan, ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT);
        assertTrue(localView.requests > 0);
        scan.commit();

        blackout.disable();
        localView.ready = true;
        remoteView.ready = false;
        remoteView.data = null;
        remoteView.reads = 0;
        memo.clearDestinationSamples();
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

        assertEquals(initialKeys, scan.claims().keySet());
        assertLighting(scan, ProjectedBlockClaim.LightingPolicy.SOURCE);
        assertTrue(remoteView.reads > 0);
        assertEquals(0, remoteView.requests);
    }

    @Test
    public void venticularKeepsDestinationSurfaceAndBackingMaterialOverLocalStone() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        LayeredWorldView remoteView = new LayeredWorldView();
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));

        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(true, true), null, false, LodPolicy.NONE);

        int grassClaims = 0;
        int foliageClaims = 0;
        int backingClaims = 0;
        int deepClaims = 0;
        for (ProjectedBlockClaim<BlockData, ProjectionWorldView> claim : scan.claims().values()) {
            Material material = claim.getData().getMaterial();
            if (material == Material.GRASS_BLOCK) {
                grassClaims++;
            } else if (material == Material.SHORT_GRASS) {
                foliageClaims++;
            } else if (material == Material.DIRT) {
                backingClaims++;
            } else if (material == Material.DEEPSLATE) {
                deepClaims++;
            }
            assertFalse(material == Material.STONE);
        }
        assertTrue(grassClaims >= 3, "grassClaims=" + grassClaims);
        assertTrue(foliageClaims >= 4, "foliageClaims=" + foliageClaims);
        assertTrue(backingClaims >= 3, "backingClaims=" + backingClaims);
        assertEquals(0, deepClaims, "depth-two interior must stay culled");
    }

    @Test
    public void blackoutSealsTransparentFarAndLateralBoundariesWithConcreteClaims()
        throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        int expectedFarZ = ProjectionVolume.minBlockForCenter(frustum.getRegion().getZa());

        for (ProjectionRenderMode renderMode : ProjectionRenderMode.values()) {
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            TransparentSkylineWorldView remoteView = new TransparentSkylineWorldView(expectedFarZ);
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
            useOcclusion(scan);
            boolean buriedCellCulling = renderMode.scanMode().buriedCellCulling();
            sampler.setBuriedCellCullingPass(buriedCellCulling);

            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
                renderMode.scanMode(), null, false, LodPolicy.NONE);

            LongOpenHashSet geometry = blackoutGeometry(scan);
            assertFalse(geometry.isEmpty(), renderMode.name());
            assertEquals(expectedFarZ, minimumBlackoutGeometryZ(scan), renderMode.name());
            Vector portalOrigin = structure.getCenter().toVector();
            Frame projectionFrame = scan.localFrame();
            PlaneWindow exactWindow = PlaneWindow.create(
                structure, structure.getArea(), projectionFrame,
                portalOrigin.getX(), portalOrigin.getY(), portalOrigin.getZ(), 0.0D, scan.eyeDot());
            Face projectionNormal = projectionFrame.getNormal();
            boolean foundOpaque = false;
            boolean foundSealedGlassOrWater = false;
            boolean foundSealedAir = false;
            boolean foundNearTransparent = false;
            boolean foundLateralShell = false;
            for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : scan.claims().long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                ProjectedBlockClaim<BlockData, ProjectionWorldView> claim = entry.getValue();
                int x = CellKeys.unpackX(key);
                int y = CellKeys.unpackY(key);
                int z = CellKeys.unpackZ(key);
                if (claim.isBlackout()) {
                    assertTrue(geometry.contains(key), renderMode.name());
                    assertEquals(Material.BLACK_CONCRETE, claim.getData().getMaterial(), renderMode.name());
                    assertEquals(ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT, claim.getLightingPolicy(), renderMode.name());
                    assertFalse(claim.isMaskAir(), renderMode.name());
                    Material remote = remoteView.sampleBlockData(x, y, z).getMaterial();
                    assertFalse(testMaterialOccluding(remote), renderMode.name() + " sealed an opaque cell");
                    foundSealedGlassOrWater |= remote == Material.GLASS || remote == Material.WATER;
                    foundSealedAir |= remote == Material.AIR;
                    foundLateralShell |= z != expectedFarZ;
                    continue;
                }
                boolean transparent = !TEST_BLOCKS.occludes(claim.getData());
                if (z == expectedFarZ) {
                    double cx = x + 0.5D;
                    double cy = y + 0.5D;
                    double cz = z + 0.5D;
                    double cellSignedDistance = ((cx - portalOrigin.getX()) * projectionNormal.x())
                        + ((cy - portalOrigin.getY()) * projectionNormal.y())
                        + ((cz - portalOrigin.getZ()) * projectionNormal.z());
                    boolean exactAperture = exactWindow.containsRayIntersection(
                        eye.getX(), eye.getY(), eye.getZ(), cx, cy, cz, cellSignedDistance);
                    assertFalse(transparent && exactAperture,
                        renderMode.name() + " left a transparent far cell unsealed at " + x + "," + y + "," + z);
                    foundOpaque |= !transparent && exactAperture;
                } else if (transparent) {
                    foundNearTransparent = true;
                }
            }
            assertTrue(foundOpaque, renderMode.name());
            assertTrue(foundSealedGlassOrWater, renderMode.name());
            assertTrue(foundSealedAir, renderMode.name());
            assertTrue(foundNearTransparent, renderMode.name());
            assertTrue(foundLateralShell, renderMode.name());
            for (long key : geometry) {
                ProjectedBlockClaim<BlockData, ProjectionWorldView> claim = scan.claims().get(key);
                assertTrue(claim != null && claim.isBlackout(), renderMode.name());
            }
        }
    }

    @Test
    public void lateralBlackoutKeepsTheWholeApertureClearWhileMoving() throws ReflectiveOperationException {
        double originalPadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        boolean originalHold = Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS;
        Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = false;
        int lateralClaims = 0;
        try {
            for (double padding : new double[] {0.0D, 0.75D}) {
                Settings.PROJECTION_APERTURE_PADDING_BLOCKS = padding;
                for (Face normal : Face.values()) {
                    Frame frame = Frame.canonical(normal);
                    ScanFixture fixture = scanFixture(frame, false);
                    enableBlackout(fixture.blackout());
                    Location origin = fixture.structure().getCenter();
                    for (double offset : new double[] {0.0D, 2.75D, -2.75D, 0.0D}) {
                        Face right = frame.getRight();
                        Location eye = origin.clone().add(normal.x() * 1.5D + right.x() * offset,
                            normal.y() * 1.5D + right.y() * offset,
                            normal.z() * 1.5D + right.z() * offset);
                        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), fixture.structure(), new ViewVolume.Options(6.0D, 4.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
                        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = fixture.scan();
                        scan.run(fixture.destination(), null, BukkitGeometry.vector(eye), frustum, 6.0D,
                            false, false, true, new ScanMode(false, false), null, false, LodPolicy.NONE);
                        PlaneWindow window = PlaneWindow.create(fixture.structure(),
                            fixture.structure().getArea(), scan.localFrame(),
                            origin.getX(), origin.getY(), origin.getZ(), 0.0D, scan.eyeDot());
                        int farCoordinate = 0;
                        double farDistance = 0.0D;
                        for (long key : scan.claims().keySet()) {
                            double x = CellKeys.unpackX(key) + 0.5D;
                            double y = CellKeys.unpackY(key) + 0.5D;
                            double z = CellKeys.unpackZ(key) + 0.5D;
                            double distance = (x - origin.getX()) * normal.x()
                                + (y - origin.getY()) * normal.y() + (z - origin.getZ()) * normal.z();
                            if (distance < farDistance && window.containsRayIntersection(
                                eye.getX(), eye.getY(), eye.getZ(), x, y, z, distance)) {
                                farDistance = distance;
                                farCoordinate = coordinate(key, normal);
                            }
                        }
                        boolean farClaim = false;
                        for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : scan.claims().long2ObjectEntrySet()) {
                            if (!entry.getValue().isBlackout()) {
                                continue;
                            }
                            long key = entry.getLongKey();
                            if (coordinate(key, normal) == farCoordinate) {
                                farClaim = true;
                                continue;
                            }
                            lateralClaims++;
                            double x = CellKeys.unpackX(key) + 0.5D;
                            double y = CellKeys.unpackY(key) + 0.5D;
                            double z = CellKeys.unpackZ(key) + 0.5D;
                            double distance = (x - origin.getX()) * normal.x()
                                + (y - origin.getY()) * normal.y() + (z - origin.getZ()) * normal.z();
                            assertFalse(window.intersectsBlockSilhouette(eye.getX(), eye.getY(), eye.getZ(),
                                x, y, z, distance), normal + " offset=" + offset + " padding=" + padding);
                        }
                        assertTrue(farClaim, normal + " offset=" + offset + " padding=" + padding);
                        Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>> moved =
                            new Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockData, ProjectionWorldView>>(scan.claims());
                        scan.run(fixture.destination(), null, BukkitGeometry.vector(eye), frustum, 6.0D,
                            true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);
                        assertEquivalentClaims(moved, scan.claims());
                        scan.commit();
                    }
                }
            }
        } finally {
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = originalPadding;
            Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS = originalHold;
        }
        assertTrue(lateralClaims > 0);
    }

    @Test
    public void blackoutIncludesFarAirWhenTheLocalCellIsAlreadyAir() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));

        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

        LongOpenHashSet initialMask = new LongOpenHashSet(blackoutGeometry(scan));
        assertFalse(initialMask.isEmpty());
        assertEquals(initialMask, scan.claims().keySet(), "every claim is a shell cell over air");
        assertAllBlackout(scan);
        assertEquals(ProjectionVolume.minBlockForCenter(frustum.getRegion().getZa()),
            minimumBlackoutGeometryZ(scan));
        scan.commit();

        localView.ready = false;
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

        assertEquals(initialMask, blackoutGeometry(scan));
        assertEquals(initialMask, scan.claims().keySet(), "the shell carries over while the local chunk loads");
        assertAllBlackout(scan);
        scan.commit();

        localView.ready = true;
        remoteView.data = blockData(Material.STONE);
        memo.clearDestinationSamples();
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

        assertTrue(blackoutGeometry(scan).isEmpty());
        assertNoBlackoutClaims(scan);
        assertFalse(scan.claims().isEmpty());
        for (ProjectedBlockClaim<BlockData, ProjectionWorldView> claim : scan.claims().values()) {
            assertEquals(Material.STONE, claim.getData().getMaterial());
        }
    }

    @Test
    public void fullyOpaqueFarBoundaryCreatesNoBlackout() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));

        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false, new ScanMode(false, false), null, false, LodPolicy.NONE);

        assertFalse(scan.claims().isEmpty());
        assertTrue(blackoutGeometry(scan).isEmpty());
        assertNoBlackoutClaims(scan);
    }

    @Test
    public void blackoutUsesTheDeepestProjectionSlabForEveryNormal()
        throws ReflectiveOperationException {
        for (Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            PortalStructure structure = orientedStructure(frame);
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
            useOcclusion(scan);
            Location eye = structure.getCenter().add(
                normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
            int expectedCoordinate = farFrustumCoordinate(frustum, normal);

            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
                new ScanMode(false, false), null, false, LodPolicy.NONE);

            LongOpenHashSet geometry = blackoutGeometry(scan);
            assertFalse(geometry.isEmpty(), normal.name()
                + " planeRejected=" + scan.planeRejected()
                + " windowRejected=" + scan.windowRejected()
                + " frustumRejected=" + scan.frustumRejected()
                + " region=" + frustum.getRegion());
            boolean farFace = false;
            boolean lateral = false;
            for (long key : geometry) {
                ProjectedBlockClaim<BlockData, ProjectionWorldView> claim = scan.claims().get(key);
                assertTrue(claim != null && claim.isBlackout(), normal.name());
                assertEquals(Material.BLACK_CONCRETE, claim.getData().getMaterial(), normal.name());
                farFace |= coordinate(key, normal) == expectedCoordinate;
                lateral |= coordinate(key, normal) != expectedCoordinate;
            }
            assertTrue(farFace, normal.name());
            assertTrue(lateral, normal.name());
            for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : scan.claims().long2ObjectEntrySet()) {
                if (!entry.getValue().isBlackout()) {
                    assertEquals(Material.AIR, entry.getValue().getData().getMaterial(), normal.name());
                    assertFalse(geometry.contains(entry.getLongKey()), normal.name());
                }
            }
        }
    }

    @Test
    public void staleShellClaimsAreResampledWhenTheCellLeavesTheShell() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume shallow = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        ViewVolume deep = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(6.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        int shallowFarZ = ProjectionVolume.minBlockForCenter(shallow.getRegion().getZa());
        int deepFarZ = ProjectionVolume.minBlockForCenter(deep.getRegion().getZa());
        assertTrue(deepFarZ < shallowFarZ);

        scan.run(destination, null, BukkitGeometry.vector(eye), shallow, 4.0D, true, false, false,
            new ScanMode(false, false), null, false, LodPolicy.NONE);
        assertEquals(shallowFarZ, minimumBlackoutGeometryZ(scan));
        scan.commit();

        scan.run(destination, null, BukkitGeometry.vector(eye), deep, 6.0D, false, false, false,
            new ScanMode(false, false), null, false, LodPolicy.NONE);

        LongOpenHashSet geometry = blackoutGeometry(scan);
        assertEquals(deepFarZ, minimumBlackoutGeometryZ(scan));
        int formerFarCells = 0;
        for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : scan.claims().long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            ProjectedBlockClaim<BlockData, ProjectionWorldView> claim = entry.getValue();
            assertEquals(geometry.contains(key), claim.isBlackout(),
                "shell membership and concrete must agree at " + CellKeys.unpackX(key)
                    + "," + CellKeys.unpackY(key) + "," + CellKeys.unpackZ(key));
            if (CellKeys.unpackZ(key) == shallowFarZ && !claim.isBlackout()) {
                assertEquals(Material.AIR, claim.getData().getMaterial());
                formerFarCells++;
            }
        }
        assertTrue(formerFarCells > 0, "the old far slab must be resampled as air once it is interior");
        ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockData, ProjectionWorldView>> delta = scan.claimDelta();
        for (long key : delta.changedKeys()) {
            ProjectedBlockClaim<BlockData, ProjectionWorldView> previous = delta.previousClaims().get(key);
            ProjectedBlockClaim<BlockData, ProjectionWorldView> next = delta.claims().get(key);
            assertTrue(previous == null || next == null || previous != next);
        }
    }

    @Test
    public void unavailableChunksDoNotRetainAnInteriorBlackoutCap() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume shallow = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        ViewVolume deep = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(6.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));
        long oldCap = CellKeys.pack(structure.getCenter().getBlockX(),
            structure.getCenter().getBlockY(), ProjectionVolume.minBlockForCenter(shallow.getRegion().getZa()));
        for (boolean localUnavailable : new boolean[] {true, false}) {
            MutableWorldView local = new MutableWorldView(blockData(Material.AIR));
            MutableWorldView remote = new MutableWorldView(blockData(Material.AIR));
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remote));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
            useOcclusion(scan);
            ProjectorDestination destination = destination(portal, structure, local, remote);
            scan.run(destination, null, BukkitGeometry.vector(eye), shallow, 4.0D, true, false, false,
                new ScanMode(false, false), null, false, LodPolicy.NONE);
            assertTrue(scan.claims().get(oldCap).isBlackout());
            scan.commit();
            if (localUnavailable) {
                local.ready = false;
            } else {
                remote.ready = false;
                remote.data = null;
                memo.clearDestinationSamples();
            }
            scan.run(destination, null, BukkitGeometry.vector(eye), deep, 6.0D, false, false, false,
                new ScanMode(false, false), null, false, LodPolicy.NONE);
            assertFalse(blackoutGeometry(scan).contains(oldCap));
            assertFalse(scan.claims().containsKey(oldCap), "localUnavailable=" + localUnavailable);
            assertTrue(scan.claimDelta().removedKeys().contains(oldCap), "localUnavailable=" + localUnavailable);
            assertFalse(scan.claims().isEmpty(), "safe lateral shell cells must remain while chunks load");
            assertEquals(blackoutGeometry(scan), scan.claims().keySet());
            assertAllBlackout(scan);
        }
    }

    @Test
    public void disablingBlackoutRestoresSampledClaims() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));

        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, false), null, false, LodPolicy.NONE);
        LongOpenHashSet shell = new LongOpenHashSet(blackoutGeometry(scan));
        assertFalse(shell.isEmpty());
        LongOpenHashSet keys = new LongOpenHashSet(scan.claims().keySet());
        scan.commit();

        blackout.disable();
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, false), null, false, LodPolicy.NONE);

        assertTrue(blackoutGeometry(scan).isEmpty());
        assertNoBlackoutClaims(scan);
        assertEquals(keys, scan.claims().keySet());
        assertLighting(scan, ProjectedBlockClaim.LightingPolicy.SOURCE);
        for (long key : shell) {
            assertEquals(Material.AIR, scan.claims().get(key).getData().getMaterial());
        }
    }

    @Test
    public void shellClaimsFollowTheSealBlock() throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
            () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
        useOcclusion(scan);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));

        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
            new ScanMode(false, false), null, false, LodPolicy.NONE);
        LongOpenHashSet shell = new LongOpenHashSet(blackoutGeometry(scan));
        assertFalse(shell.isEmpty());
        scan.commit();

        Field data = ProjectorBlackoutSeal.class.getDeclaredField("blackoutData");
        data.setAccessible(true);
        data.set(blackout, blockData(Material.RED_CONCRETE));
        scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, false, false, false,
            new ScanMode(false, false), null, false, LodPolicy.NONE);

        assertEquals(shell, blackoutGeometry(scan));
        ProjectionClaimSet.ClaimDelta<ProjectedBlockClaim<BlockData, ProjectionWorldView>> delta = scan.claimDelta();
        for (long key : shell) {
            ProjectedBlockClaim<BlockData, ProjectionWorldView> claim = scan.claims().get(key);
            assertTrue(claim.isBlackout());
            assertEquals(Material.RED_CONCRETE, claim.getData().getMaterial());
            assertTrue(delta.changedKeys().contains(key), "recoloured shell cells must be resent");
        }
    }

    @Test
    public void unavailableMaskDoesNotCrossDestinationOrCoordinateMappings()
        throws ReflectiveOperationException {
        Frame frame = Frame.canonical(Face.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        ViewVolume frustum = new ViewVolume(BukkitGeometry.vector(eye), structure, new ViewVolume.Options(4.0D, 2.0D, Settings.NEAR_PLANE_PADDING, Settings.FRUSTUM_CULLING_RATIO, Settings.PROJECTION_APERTURE_PADDING_BLOCKS));

        for (int scenario = 0; scenario < 2; scenario++) {
            MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo = BukkitProjectorBlocks.memo();
            ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler = withBukkitServer(
                () -> BukkitProjectorBlocks.sampler(memo, BukkitProjectorPortalAccess.create(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan = BukkitProjectorBlocks.scan(portal, sampler, memo, blackout);
            useOcclusion(scan);

            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
                new ScanMode(false, false), null, false, LodPolicy.NONE);
            assertFalse(blackoutGeometry(scan).isEmpty());
            scan.commit();
            localView.ready = false;
            if (scenario == 0) {
                destination.originX += 1.0D;
            } else {
                MutableWorldView replacementView = new MutableWorldView(null);
                replacementView.ready = false;
                destination.destView = replacementView;
            }

            scan.run(destination, null, BukkitGeometry.vector(eye), frustum, 4.0D, true, false, false,
                new ScanMode(false, false), null, false, LodPolicy.NONE);

            assertTrue(blackoutGeometry(scan).isEmpty(), "scenario=" + scenario);
            assertNoBlackoutClaims(scan);
        }
    }

    private static void assertAllBlackout(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan) {
        assertFalse(scan.claims().isEmpty());
        for (ProjectedBlockClaim<BlockData, ProjectionWorldView> claim : scan.claims().values()) {
            assertTrue(claim.isBlackout());
            assertEquals(Material.BLACK_CONCRETE, claim.getData().getMaterial());
            assertEquals(ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT, claim.getLightingPolicy());
        }
    }

    private static void assertNoBlackoutClaims(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan) {
        for (ProjectedBlockClaim<BlockData, ProjectionWorldView> claim : scan.claims().values()) {
            assertFalse(claim.isBlackout());
            assertFalse(claim.getData().getMaterial() == Material.BLACK_CONCRETE);
        }
    }

    private static void useOcclusion(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan)
        throws ReflectiveOperationException {
        Field field = CellScan.class.getDeclaredField("viewOcclusion");
        field.setAccessible(true);
        field.set(scan, new ProjectorViewOcclusion<BlockData>(TEST_BLOCKS));
    }

    private static long remoteClaimKey(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan, long remoteKey) {
        for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : scan.claims().long2ObjectEntrySet()) {
            if (entry.getValue().getLightRemoteKey() == remoteKey) {
                return entry.getLongKey();
            }
        }
        throw new AssertionError("no claim for remote cell " + remoteKey);
    }

    private static LongOpenHashSet liveKeys(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan) {
        LongOpenHashSet keys = new LongOpenHashSet();
        for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : scan.claims().long2ObjectEntrySet()) {
            if (!entry.getValue().isHeld()) {
                keys.add(entry.getLongKey());
            }
        }
        return keys;
    }

    private static LongOpenHashSet heldKeys(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan) {
        LongOpenHashSet keys = new LongOpenHashSet();
        for (Long2ObjectMap.Entry<ProjectedBlockClaim<BlockData, ProjectionWorldView>> entry : scan.claims().long2ObjectEntrySet()) {
            if (entry.getValue().isHeld()) {
                keys.add(entry.getLongKey());
            }
        }
        return keys;
    }

    private static boolean hasRemoteClaim(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan, long remoteKey) {
        for (ProjectedBlockClaim<BlockData, ProjectionWorldView> claim : scan.claims().values()) {
            if (claim.getLightRemoteKey() == remoteKey) {
                return true;
            }
        }
        return false;
    }

    private static long scanRevision(PortalStructure structure, Frame frame, LodPolicy lod, boolean blockEntities) {
        Location origin = structure.getCenter();
        return ProjectorPassRevision.transform(frame, frame,
            origin.getX(), origin.getY(), origin.getZ(), origin.getX(), origin.getY(), origin.getZ(),
            4, 2, 0.0D, false, lod, blockEntities);
    }

    private static int minimumBlackoutGeometryZ(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan) throws ReflectiveOperationException {
        LongOpenHashSet geometry = blackoutGeometry(scan);
        int minimum = Integer.MAX_VALUE;
        for (long key : geometry) {
            minimum = Math.min(minimum, CellKeys.unpackZ(key));
        }
        return minimum;
    }

    private static LongOpenHashSet blackoutGeometry(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan) throws ReflectiveOperationException {
        Field field = CellScan.class.getDeclaredField("blackoutGeometry");
        field.setAccessible(true);
        return (LongOpenHashSet) field.get(scan);
    }

    private static int farFrustumCoordinate(ViewVolume frustum, Face normal) {
        if (normal.x() > 0) {
            return ProjectionVolume.minBlockForCenter(frustum.getRegion().getXa());
        }
        if (normal.x() < 0) {
            return ProjectionVolume.maxBlockForCenter(frustum.getRegion().getXb());
        }
        if (normal.y() > 0) {
            return ProjectionVolume.minBlockForCenter(frustum.getRegion().getYa());
        }
        if (normal.y() < 0) {
            return ProjectionVolume.maxBlockForCenter(frustum.getRegion().getYb());
        }
        return normal.z() > 0
            ? ProjectionVolume.minBlockForCenter(frustum.getRegion().getZa())
            : ProjectionVolume.maxBlockForCenter(frustum.getRegion().getZb());
    }

    private static int coordinate(long key, Face direction) {
        if (direction.x() != 0) {
            return CellKeys.unpackX(key);
        }
        if (direction.y() != 0) {
            return CellKeys.unpackY(key);
        }
        return CellKeys.unpackZ(key);
    }

    private static boolean testMaterialOccluding(Material material) {
        return material == Material.STONE
            || material == Material.GRASS_BLOCK
            || material == Material.DIRT
            || material == Material.DEEPSLATE;
    }

    private static ProjectorDestination destination(ILocalPortal portal,
                                                    PortalStructure structure,
                                                    ProjectionWorldView localView,
                                                    ProjectionWorldView remoteView) {
        ProjectorDestination destination = new ProjectorDestination(portal, world -> null);
        destination.dest = portal;
        destination.destAnchor = portal;
        destination.localView = localView;
        destination.destView = remoteView;
        destination.originX = structure.getCenter().getX();
        destination.originY = structure.getCenter().getY();
        destination.originZ = structure.getCenter().getZ();
        destination.mirrorMode = false;
        destination.mirrorRotationQuarterTurns = 0;
        return destination;
    }

    private static void assertLighting(CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan, ProjectedBlockClaim.LightingPolicy expected) {
        for (ProjectedBlockClaim<BlockData, ProjectionWorldView> claim : scan.claims().values()) {
            assertEquals(expected, claim.getLightingPolicy());
        }
    }

    private static void enableBlackout(ProjectorBlackoutSeal blackout) throws ReflectiveOperationException {
        Field enabled = ProjectorBlackoutSeal.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        enabled.setBoolean(blackout, true);
        Field data = ProjectorBlackoutSeal.class.getDeclaredField("blackoutData");
        data.setAccessible(true);
        data.set(blackout, blockData(Material.BLACK_CONCRETE));
    }

    private static PortalStructure structure() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(0));
        values.put("x2", Integer.valueOf(0));
        values.put("y1", Integer.valueOf(64));
        values.put("y2", Integer.valueOf(65));
        values.put("z1", Integer.valueOf(0));
        values.put("z2", Integer.valueOf(0));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }

    private static PortalStructure orientedStructure(Frame frame) {
        Face up = frame.getUp();
        Face right = frame.getRight();
        int x = up.x() + right.x();
        int y = up.y() + right.y();
        int z = up.z() + right.z();
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(Math.min(0, x)));
        values.put("x2", Integer.valueOf(Math.max(0, x)));
        values.put("y1", Integer.valueOf(64 + Math.min(0, y)));
        values.put("y2", Integer.valueOf(64 + Math.max(0, y)));
        values.put("z1", Integer.valueOf(Math.min(0, z)));
        values.put("z2", Integer.valueOf(Math.max(0, z)));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return structure;
    }

    private static ILocalPortal portal(PortalStructure structure, Frame frame) {
        Vector origin = structure.getCenter().toVector();
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(), new Class<?>[] { ILocalPortal.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getStructure" -> structure;
                case "getFrame", "frame" -> frame;
                case "getOrigin", "origin" -> BukkitGeometry.vector(origin);
                case "getWorld" -> null;
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

    private static ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> withBukkitServer(SamplerFactory factory) throws ReflectiveOperationException {
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
            Server.class.getClassLoader(), new Class<?>[] { Server.class },
            (proxy, method, args) -> {
                if ("createBlockData".equals(method.getName())) {
                    Material material = args[0] instanceof Material value ? value : Material.STONE;
                    return blockData(material);
                }
                return switch (method.getName()) {
                    case "getName", "toString" -> "ProjectorCellScanLightingRetentionTestServer";
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                    case "equals" -> Boolean.valueOf(proxy == args[0]);
                    default -> primitiveDefault(method.getReturnType());
                };
            });
    }

    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "getAsString", "toString" -> material.getKey().toString();
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> primitiveDefault(method.getReturnType());
            });
    }

    private interface SamplerFactory {
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> create();
    }

    private static final class MutableWorldView implements ProjectionWorldView {
        private final Long2ObjectOpenHashMap<BlockData> blocks = new Long2ObjectOpenHashMap<BlockData>();
        private final LongOpenHashSet readKeys = new LongOpenHashSet();
        private BlockData data;
        private BlockEntitySample blockEntity;
        private long revision;
        private boolean ready;
        private int reads;
        private int requests;
        private int readinessQueries;

        private MutableWorldView(BlockData data) {
            this.data = data;
            this.ready = true;
            this.reads = 0;
            this.requests = 0;
        }

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
            reads++;
            long key = CellKeys.pack(x, y, z);
            readKeys.add(key);
            return blocks.getOrDefault(key, data);
        }

        @Override
        public long getRevision() {
            return revision;
        }

        @Override
        public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
            return blockEntity;
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

        @Override
        public boolean isChunkReady(int x, int z) {
            readinessQueries++;
            return ready;
        }

        @Override
        public void requestChunk(int x, int z) {
            requests++;
        }
    }

    private static final class LayeredWorldView implements ProjectionWorldView {
        private final BlockData air = blockData(Material.AIR);
        private final BlockData grass = blockData(Material.GRASS_BLOCK);
        private final BlockData foliage = blockData(Material.SHORT_GRASS);
        private final BlockData dirt = blockData(Material.DIRT);
        private final BlockData deep = blockData(Material.DEEPSLATE);

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
            if (y == 64) {
                return grass;
            }
            if (y == 65) {
                return foliage;
            }
            if (y == 63) {
                return dirt;
            }
            if (y <= 62) {
                return deep;
            }
            return air;
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

        @Override
        public boolean isChunkReady(int x, int z) {
            return true;
        }
    }

    private static final class TransparentSkylineWorldView implements ProjectionWorldView {
        private final BlockData air = blockData(Material.AIR);
        private final BlockData glass = blockData(Material.GLASS);
        private final BlockData water = blockData(Material.WATER);
        private final BlockData grass = blockData(Material.GRASS_BLOCK);
        private final BlockData stone = blockData(Material.STONE);
        private final int farZ;

        private TransparentSkylineWorldView(int farZ) {
            this.farZ = farZ;
        }

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
            if (x < 0) {
                return grass;
            }
            if (x == 0) {
                return (y & 1) == 0 ? glass : water;
            }
            if (x == 2 && z == farZ) {
                return stone;
            }
            return air;
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

        @Override
        public boolean isChunkReady(int x, int z) {
            return true;
        }
    }
}
