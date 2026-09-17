package art.arcane.wormholes.render;

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
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class ProjectorCellScanLightingRetentionTest {
    @Test
    public void stagedScansKeepCommittedStateAndMatchUnlimitedScans() throws ReflectiveOperationException {
        int yieldedCalls = 0;
        for (Direction normal : Direction.values()) {
            for (int variant = 0; variant < 4; variant++) {
                PortalFrame frame = PortalFrame.canonical(normal);
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
                    Direction right = frame.getRight();
                    Location eye = initialEye.clone().add(right.x() * pass * 0.4D,
                        right.y() * pass * 0.4D, right.z() * pass * 0.4D);
                    Frustum4D frustum = new Frustum4D(eye, unlimited.structure(), 12.0D, 5.0D);
                    unlimited.scan().run(unlimited.destination(), null, eye, frustum, 12.0D,
                        pass == 0, false, true, false, mode, null, variant == 3, lod);
                    Long2ObjectMap<ProjectedBlockClaim> committed = staged.scan().claims();
                    Long2ObjectOpenHashMap<ProjectedBlockClaim> committedCopy = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(committed);
                    Long2ObjectMap<BlockEntitySample> committedBlockEntities = staged.scan().blockEntities();
                    Long2ObjectOpenHashMap<BlockEntitySample> blockEntityCopy = new Long2ObjectOpenHashMap<BlockEntitySample>(committedBlockEntities);
                    ProjectedEntityOcclusion entityOcclusion = staged.scan().entityOcclusion();
                    Field blockerField = ProjectorCellScan.class.getDeclaredField("projectedOcclusionGeometry");
                    blockerField.setAccessible(true);
                    LongOpenHashSet committedBlockers = (LongOpenHashSet) blockerField.get(staged.scan());
                    LongOpenHashSet blockerCopy = new LongOpenHashSet(committedBlockers);
                    PortalFrame localFrame = staged.scan().localFrame();
                    PortalFrame remoteFrame = staged.scan().remoteFrame();
                    double eyeDot = staged.scan().eyeDot();
                    staged.scan().begin(staged.destination(), null, eye, frustum, 12.0D,
                        pass == 0, false, true, false, mode, null, variant == 3, lod);
                    assertTrue(staged.scan().hasPending());
                    assertThrows(IllegalStateException.class, staged.scan()::commit);
                    assertThrows(IllegalStateException.class, staged.scan()::claimDelta);
                    int advances = 0;
                    int processedCells = 0;
                    Field nextClaimsField = ProjectorCellScan.class.getDeclaredField("nextProjected");
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
                        staged.scan().updateEntityOcclusionEye(latestEye, staged.destination(), frame, frame);
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
        PortalFrame frame = PortalFrame.canonical(Direction.D);
        ScanFixture fixture = scanFixture(frame, false);
        Location eye = fixture.structure().getCenter().add(0.0D, -1.5D, 0.0D);
        Frustum4D frustum = new Frustum4D(eye, fixture.structure(), 16.0D, 5.0D);
        ProjectorCellScan scan = fixture.scan();
        scan.run(fixture.destination(), null, eye, frustum, 16.0D,
            true, false, true, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
        scan.commit();
        Long2ObjectMap<ProjectedBlockClaim> committed = scan.claims();
        Long2ObjectOpenHashMap<ProjectedBlockClaim> expected = new Long2ObjectOpenHashMap<ProjectedBlockClaim>(committed);
        ProjectedEntityOcclusion entityOcclusion = scan.entityOcclusion();
        for (int cancelled = 0; cancelled < 3; cancelled++) {
            scan.begin(fixture.destination(), null, eye, frustum, 16.0D,
                true, cancelled == 1, true, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
            assertFalse(scan.advance(0L));
            scan.cancelPending();
            assertFalse(scan.hasPending());
            assertFalse(scan.advance(Long.MAX_VALUE));
            assertSame(committed, scan.claims());
            assertEquals(expected, scan.claims());
            assertSame(entityOcclusion, scan.entityOcclusion());
            assertThrows(IllegalStateException.class, scan::commit);
        }
        scan.begin(fixture.destination(), null, eye, frustum, 16.0D,
            true, true, true, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
        while (!scan.advance(0L)) {
        }
        assertSame(null, scan.claimDelta().previousClaims());
        assertEquivalentClaims(expected, scan.claims());
        scan.commit();
        scan.begin(fixture.destination(), null, eye, frustum, 16.0D,
            false, false, true, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
        assertFalse(scan.advance(0L));
        scan.clear();
        assertFalse(scan.hasPending());
        assertFalse(scan.hasProjection());
        assertTrue(scan.claims().isEmpty());
        assertFalse(scan.advance(Long.MAX_VALUE));
    }

    @Test
    public void contentRevisionsDoNotRestartScansAndKeepEntityOcclusionRevisionStale() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.E);
        ScanFixture fixture = scanFixture(frame, true);
        Location eye = fixture.structure().getCenter().add(1.5D, 0.0D, 0.0D);
        Frustum4D frustum = new Frustum4D(eye, fixture.structure(), 12.0D, 5.0D);
        fixture.scan().begin(fixture.destination(), null, eye, frustum, 12.0D,
            true, false, true, false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
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

    private static ScanFixture scanFixture(PortalFrame frame, boolean solidRemote) throws ReflectiveOperationException {
        PortalStructure structure = orientedStructure(frame);
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView local = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remote = new MutableWorldView(blockData(solidRemote ? Material.STONE : Material.AIR));
        ProjectorSampleMemo memo = new ProjectorSampleMemo(ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remote));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        return new ScanFixture(scan, destination(portal, structure, local, remote), structure, blackout, local, remote);
    }

    private static void assertEquivalentClaims(Long2ObjectMap<ProjectedBlockClaim> expected,
                                               Long2ObjectMap<ProjectedBlockClaim> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (long key : expected.keySet()) {
            ProjectedBlockClaim expectedClaim = expected.get(key);
            ProjectedBlockClaim actualClaim = actual.get(key);
            assertEquals(expectedClaim.getData().getAsString(), actualClaim.getData().getAsString());
            assertEquals(expectedClaim.getLightRemoteKey(), actualClaim.getLightRemoteKey());
            assertEquals(expectedClaim.getLightingPolicy(), actualClaim.getLightingPolicy());
            assertEquals(expectedClaim.isMaskAir(), actualClaim.isMaskAir());
        }
    }

    private record ScanFixture(ProjectorCellScan scan, ProjectorDestination destination,
                               PortalStructure structure, ProjectorBlackoutSeal blackout,
                               MutableWorldView local, MutableWorldView remote) {
    }

    @Test
    public void claimDeltasMatchFullSubmissionsAcrossMovementLightingFilteringAndAbortedPasses()
        throws ReflectiveOperationException {
        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            PortalStructure structure = orientedStructure(frame);
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo memo = new ProjectorSampleMemo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler sampler = withBukkitServer(
                () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
            useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
            ProjectionClaimSet incremental = new ProjectionClaimSet();
            ProjectionClaimSet full = new ProjectionClaimSet();
            UUID owner = new UUID(0L, 1L);
            Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            Direction right = frame.getRight();
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
                Frustum4D frustum = new Frustum4D(eye, structure, 6.0D, 3.0D);
                scan.run(destination, null, eye, frustum, 6.0D, pass == 0 || pass == 3, pass == 7, true,
                    false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
                if (pass == 4) {
                    scan.claims().keySet().removeIf(key -> (key & 1L) == 0L);
                }
                ProjectionClaimSet.ClaimDelta delta = scan.claimDelta();
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
        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            PortalStructure structure = orientedStructure(frame);
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo memo = new ProjectorSampleMemo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler sampler = withBukkitServer(
                () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
            ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
            useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
            Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            Frustum4D initial = new Frustum4D(eye, structure, 8.0D, 4.0D);
            scan.run(destination, null, eye, initial, 8.0D, true, false, false,
                false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
            assertTrue(scan.claims().isEmpty(), normal.name());
            scan.commit();
            Direction right = frame.getRight();
            eye.add(right.x() * 0.15D, right.y() * 0.15D, right.z() * 0.15D);
            Frustum4D moved = new Frustum4D(eye, structure, 8.0D, 4.0D);
            scan.run(destination, null, eye, moved, 8.0D, false, false, true,
                false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
            assertTrue(scan.emptyCellSkips() > 0, normal.name());
            assertTrue(scan.claims().isEmpty(), normal.name());
            scan.commit();
            localView.data = blockData(Material.STONE);
            localView.revision++;
            memo.refreshLocal(false, true, localView.revision, 4096);
            scan.run(destination, null, eye, moved, 8.0D, false, false, false,
                false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
            assertEquals(0, scan.emptyCellSkips(), normal.name());
            assertFalse(scan.claims().isEmpty(), normal.name());
            Long2ObjectOpenHashMap<ProjectedBlockClaim> expected =
                new Long2ObjectOpenHashMap<ProjectedBlockClaim>(scan.claims());
            scan.commit();
            scan.invalidateContent();
            scan.run(destination, null, eye, moved, 8.0D, true, false, false,
                false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
            assertEquals(expected.keySet(), scan.claims().keySet(), normal.name());
        }
    }

    @Test
    public void stationaryOcclusionRetriesConvergeWithoutRepeatingGeometryOrSampling()
        throws ReflectiveOperationException {
        double previousMargin = Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES;
        Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES = 2.0D;
        try {
            for (Direction normal : Direction.values()) {
                PortalFrame frame = PortalFrame.canonical(normal);
                PortalStructure structure = orientedStructure(frame);
                ILocalPortal portal = portal(structure, frame);
                MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
                MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
                ProjectorDestination destination = destination(portal, structure, localView, remoteView);
                ProjectorSampleMemo memo = new ProjectorSampleMemo(
                    ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
                ProjectorSampler sampler = withBukkitServer(
                    () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
                ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
                enableBlackout(blackout);
                ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
                Field occlusion = ProjectorCellScan.class.getDeclaredField("viewOcclusion");
                occlusion.setAccessible(true);
                occlusion.set(scan, new ProjectorViewOcclusion(
                    ProjectorCellScanLightingRetentionTest::testOccluding, Integer.MAX_VALUE));
                Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
                Frustum4D frustum = new Frustum4D(eye, structure, 6.0D, 3.0D);
                scan.run(destination, null, eye, frustum, 6.0D, true, false, false,
                    false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
                Long2ObjectOpenHashMap<ProjectedBlockClaim> expected =
                    new Long2ObjectOpenHashMap<ProjectedBlockClaim>(scan.claims());
                assertFalse(expected.isEmpty(), normal.name());
                assertEquals(0, scan.unresolvedOcclusionCells(), normal.name());
                scan.clear();
                occlusion.set(scan, new ProjectorViewOcclusion(
                    ProjectorCellScanLightingRetentionTest::testOccluding, 64));
                scan.run(destination, null, eye, frustum, 6.0D, true, false, false,
                    false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
                assertTrue(scan.unresolvedOcclusionCells() > 0, normal.name());
                assertFalse(scan.canResumeOcclusion(destination, eye, frustum), normal.name());
                ProjectionClaimSet incremental = new ProjectionClaimSet();
                ProjectionClaimSet full = new ProjectionClaimSet();
                UUID owner = new UUID(0L, 1L);
                incremental.replacePortalDelta(owner, owner.toString(), 1.0D, scan.claimDelta());
                full.replacePortalClaims(owner, owner.toString(), 1.0D, scan.claims());
                scan.commit();
                sampler.resetRemoteSampleCount();
                localView.readinessQueries = 0;
                int passes = 0;
                while (scan.hasUnresolvedOcclusion() && passes++ < 200) {
                    assertTrue(scan.canResumeOcclusion(destination, eye, frustum), normal.name());
                    scan.resumeOcclusion();
                    assertFalse(scan.canResumeOcclusion(destination, eye, frustum), normal.name());
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
                assertEquals(expected.keySet(), scan.claims().keySet(), normal.name());
                for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : expected.long2ObjectEntrySet()) {
                    ProjectedBlockClaim actual = scan.claims().get(entry.getLongKey());
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
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        Field occlusion = ProjectorCellScan.class.getDeclaredField("viewOcclusion");
        occlusion.setAccessible(true);
        occlusion.set(scan, new ProjectorViewOcclusion(ProjectorCellScanLightingRetentionTest::testOccluding, 1));
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 6.0D, 3.0D);
        scan.run(destination, null, eye, frustum, 6.0D, true, false, false,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
        scan.commit();
        assertTrue(scan.canResumeOcclusion(destination, eye, frustum));
        assertFalse(scan.canResumeOcclusion(destination, eye.clone().add(0.001D, 0.0D, 0.0D), frustum));
        assertFalse(scan.canResumeOcclusion(destination, eye, new Frustum4D(eye, structure, 3.0D, 3.0D)));
        remoteView.revision++;
        assertFalse(scan.canResumeOcclusion(destination, eye, frustum));
        remoteView.revision--;
        localView.revision++;
        assertFalse(scan.canResumeOcclusion(destination, eye, frustum));
        localView.revision--;
        destination.destView = new MutableWorldView(remoteView.data);
        assertFalse(scan.canResumeOcclusion(destination, eye, frustum));
        destination.destView = remoteView;
        enableBlackout(blackout);
        assertFalse(scan.canResumeOcclusion(destination, eye, frustum));
        blackout.disable();
        assertTrue(scan.canResumeOcclusion(destination, eye, frustum));
        scan.invalidateOcclusionContinuation();
        assertFalse(scan.canResumeOcclusion(destination, eye, frustum));
        scan.run(destination, null, eye, frustum, 6.0D, true, false, false,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
        scan.commit();
        assertTrue(scan.canResumeOcclusion(destination, eye, frustum));
        localView.ready = false;
        scan.run(destination, null, eye, frustum, 6.0D, true, false, false,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
        scan.commit();
        assertFalse(scan.canResumeOcclusion(destination, eye, frustum));
        scan.clear();
        assertFalse(scan.canResumeOcclusion(destination, eye, frustum));
    }

    @Test
    public void changedDetailCutoffResamplesRetainedRemoteClaims() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.SHORT_GRASS));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
        long initialRevision = scanRevision(structure, frame, LodPolicy.NONE, false);
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
        long targetKey = ProjectionCellKey.pack(0, 64, -3);
        assertTrue(hasRemoteClaim(scan, targetKey));
        int initialClaimCount = scan.claims().size();
        scan.commit();

        LodPolicy reducedDetail = new LodPolicy(false, Integer.MAX_VALUE, 0);
        boolean presentationChanged = initialRevision != scanRevision(structure, frame, reducedDetail, false);
        assertTrue(presentationChanged);
        scan.run(destination, null, eye, frustum, 4.0D, presentationChanged, false, true,
            false, ProjectionRenderMode.VENTICULAR, null, false, reducedDetail);

        assertFalse(hasRemoteClaim(scan, targetKey));
        assertTrue(scan.claims().size() < initialClaimCount);
    }

    @Test
    public void disablingBlockEntitiesClearsRetainedRemotePayloads() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.CHEST));
        remoteView.blockEntity = new BlockEntitySample("minecraft:chest", new byte[] {10, 0, 0, 0});
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
        long initialRevision = scanRevision(structure, frame, LodPolicy.NONE, true);
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
            false, ProjectionRenderMode.VENTICULAR, null, true, LodPolicy.NONE);
        assertFalse(scan.blockEntities().isEmpty());
        LongOpenHashSet initialKeys = new LongOpenHashSet(scan.claims().keySet());
        scan.commit();

        boolean presentationChanged = initialRevision != scanRevision(structure, frame, LodPolicy.NONE, false);
        assertTrue(presentationChanged);
        scan.run(destination, null, eye, frustum, 4.0D, presentationChanged, false, true,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);

        assertEquals(initialKeys, scan.claims().keySet());
        assertTrue(scan.blockEntities().isEmpty());
    }

    @Test
    public void cameraMovementRechecksRetainedVisibilityAndRevealsHiddenRemoteCells()
        throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
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
        long targetKey = ProjectionCellKey.pack(0, 65, -5);
        remoteView.blocks.put(ProjectionCellKey.pack(0, 65, -2), blockData(Material.STONE));
        remoteView.blocks.put(targetKey, blockData(Material.GOLD_BLOCK));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location sideEye = structure.getCenter().add(2.5D, 0.0D, 1.5D);
        Location centerEye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D sideFrustum = new Frustum4D(sideEye, structure, 6.0D, 2.0D);
        Frustum4D centerFrustum = new Frustum4D(centerEye, structure, 6.0D, 2.0D);
        scan.run(destination, null, sideEye, sideFrustum, 6.0D, true, false, false,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);

        assertTrue(hasRemoteClaim(scan, targetKey));
        scan.commit();
        memo.clearDestinationSamples();
        remoteView.readKeys.clear();
        scan.run(destination, null, centerEye, centerFrustum, 6.0D, false, false, true,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);

        assertFalse(hasRemoteClaim(scan, targetKey));
        assertTrue(scan.occlusionRejected() > 0);
        assertFalse(remoteView.readKeys.contains(targetKey), "camera-only refresh must reuse the retained target sample");
        scan.commit();
        memo.clearDestinationSamples();
        remoteView.readKeys.clear();
        scan.run(destination, null, sideEye, sideFrustum, 6.0D, false, false, true,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);

        assertTrue(hasRemoteClaim(scan, targetKey));
        assertTrue(remoteView.readKeys.contains(targetKey), "a previously hidden target must be sampled again");
    }

    @Test
    public void changedRemoteRevisionResamplesRetainedClaims() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.GLASS));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
        memo.refreshDestination(remoteView.getRevision());
        assertFalse(scan.claims().isEmpty());
        scan.commit();
        remoteView.data = blockData(Material.GOLD_BLOCK);
        remoteView.revision++;

        boolean destinationStale = memo.destinationStale(remoteView.getRevision(), false, ignored -> false);
        assertTrue(destinationStale);
        memo.clearDestinationSamples();
        scan.run(destination, null, eye, frustum, 4.0D, destinationStale, false, true,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);

        assertFalse(scan.claims().isEmpty());
        for (ProjectedBlockClaim claim : scan.claims().values()) {
            assertSame(remoteView.data, claim.getData());
        }
    }

    @Test
    public void retainedCellMappingsMatchFreshSamplesAfterOriginMirrorAndFrameChanges()
        throws ReflectiveOperationException {
        for (Direction normal : Direction.values()) {
            for (int scenario = 0; scenario < 3; scenario++) {
                PortalFrame frame = PortalFrame.canonical(normal);
                PortalStructure structure = orientedStructure(frame);
                ILocalPortal portal = portal(structure, frame);
                MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
                MutableWorldView remoteView = new MutableWorldView(blockData(Material.GLASS));
                ProjectorDestination destination = destination(portal, structure, localView, remoteView);
                destination.mirrorMode = scenario == 1;
                ProjectorSampleMemo memo = new ProjectorSampleMemo();
                ProjectorSampler sampler = withBukkitServer(
                    () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
                ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
                useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
                Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
                Frustum4D frustum = new Frustum4D(eye, structure, 6.0D, 3.0D);
                scan.run(destination, null, eye, frustum, 6.0D, true, false, false,
                    false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
                assertFalse(scan.claims().isEmpty());
                scan.commit();

                Direction right = frame.getRight();
                eye.add(right.x() * 0.15D, right.y() * 0.15D, right.z() * 0.15D);
                Frustum4D moved = new Frustum4D(eye, structure, 6.0D, 3.0D);
                scan.run(destination, null, eye, moved, 6.0D, false, false, true,
                    false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
                scan.commit();
                if (scenario == 0) {
                    destination.originX += 2.0D;
                } else if (scenario == 1) {
                    destination.mirrorRotationQuarterTurns = 1;
                } else {
                    destination.destAnchor = portal(structure, frame.rotateClockwise());
                }
                scan.run(destination, null, eye, moved, 6.0D, false, false, true,
                    false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
                Long2ObjectOpenHashMap<ProjectedBlockClaim> actual =
                    new Long2ObjectOpenHashMap<ProjectedBlockClaim>(scan.claims());
                scan.run(destination, null, eye, moved, 6.0D, true, false, true,
                    false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

                assertEquals(scan.claims().keySet(), actual.keySet());
                for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : scan.claims().long2ObjectEntrySet()) {
                    ProjectedBlockClaim retained = actual.get(entry.getLongKey());
                    assertEquals(entry.getValue().getLightRemoteKey(), retained.getLightRemoteKey(),
                        normal.name() + " scenario=" + scenario);
                    assertSame(entry.getValue().getData(), retained.getData());
                }
            }
        }
    }

    @Test
    public void changedLocalAirRemovesRemoteAirClaimsDuringCameraRefresh() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
        memo.refreshLocal(false, false, localView.getRevision(), 4096);
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
        assertFalse(scan.claims().isEmpty());
        scan.commit();
        localView.data = blockData(Material.AIR);
        localView.revision++;

        boolean localSamplesStale = memo.refreshLocal(false, false, localView.getRevision(), 4096);
        assertTrue(localSamplesStale);
        scan.run(destination, null, eye, frustum, 4.0D, localSamplesStale, false, true,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);

        assertTrue(scan.claims().isEmpty());
    }

    @Test
    public void cameraRefreshDoesNotReuseClaimsFromAnotherDestination() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.GLASS));
        MutableWorldView nextRemoteView = new MutableWorldView(blockData(Material.GOLD_BLOCK));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);
        assertFalse(scan.claims().isEmpty());
        scan.commit();
        destination.destView = nextRemoteView;
        scan.run(destination, null, eye, frustum, 4.0D, false, false, true,
            false, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);

        assertFalse(scan.claims().isEmpty());
        for (ProjectedBlockClaim claim : scan.claims().values()) {
            assertSame(nextRemoteView.data, claim.getData());
            assertSame(nextRemoteView, claim.getLightView());
        }
    }

    @Test
    public void chunkReadinessAndRequestsAreSharedWithinEachScanAndRetriedNextPass()
        throws ReflectiveOperationException {
        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            PortalStructure structure = orientedStructure(frame);
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo memo = new ProjectorSampleMemo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler sampler = withBukkitServer(
                () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
            ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
            useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
            Location eye = structure.getCenter().add(normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
            scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

            assertFalse(scan.claims().isEmpty(), normal.name());
            LongOpenHashSet chunks = new LongOpenHashSet();
            for (long key : scan.claims().keySet()) {
                int chunkX = ProjectionCellKey.unpackX(key) >> 4;
                int chunkZ = ProjectionCellKey.unpackZ(key) >> 4;
                chunks.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
            }
            assertEquals(chunks.size(), localView.readinessQueries, normal.name());
            assertTrue(localView.readinessQueries < scan.claims().size(), normal.name());
            LongOpenHashSet initialKeys = new LongOpenHashSet(scan.claims().keySet());
            scan.commit();
            localView.ready = false;
            localView.readinessQueries = 0;
            scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

            assertEquals(initialKeys, scan.claims().keySet(), normal.name());
            assertEquals(chunks.size(), localView.readinessQueries, normal.name());
            assertEquals(chunks.size(), localView.requests, normal.name());
            scan.commit();
            localView.ready = true;
            localView.readinessQueries = 0;
            localView.requests = 0;
            scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

            assertEquals(initialKeys, scan.claims().keySet(), normal.name());
            assertEquals(chunks.size(), localView.readinessQueries, normal.name());
            assertEquals(0, localView.requests, normal.name());
        }
    }

    @Test
    public void unavailableLocalAndRemoteChunksFollowCurrentBlackoutLighting() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

        assertFalse(scan.claims().isEmpty());
        assertLighting(scan, ProjectedBlockClaim.LightingPolicy.SOURCE);
        LongOpenHashSet initialKeys = new LongOpenHashSet(scan.claims().keySet());
        scan.commit();

        enableBlackout(blackout);
        localView.ready = false;
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

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
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

        assertEquals(initialKeys, scan.claims().keySet());
        assertLighting(scan, ProjectedBlockClaim.LightingPolicy.SOURCE);
        assertTrue(remoteView.reads > 0);
        assertEquals(0, remoteView.requests);
    }

    @Test
    public void venticularKeepsDestinationSurfaceAndBackingMaterialOverLocalStone() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        LayeredWorldView remoteView = new LayeredWorldView();
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo(
            ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, new ProjectorBlackoutSeal());
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, true, ProjectionRenderMode.VENTICULAR, null, false, LodPolicy.NONE);

        int grassClaims = 0;
        int foliageClaims = 0;
        int backingClaims = 0;
        int deepClaims = 0;
        for (ProjectedBlockClaim claim : scan.claims().values()) {
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
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
        int expectedFarZ = PortalProjector.minBlockForCenter(frustum.getRegion().getZa());

        for (ProjectionRenderMode renderMode : ProjectionRenderMode.values()) {
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            TransparentSkylineWorldView remoteView = new TransparentSkylineWorldView(expectedFarZ);
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo memo = new ProjectorSampleMemo(
                ProjectorCellScanLightingRetentionTest::testMaterialOccluding);
            ProjectorSampler sampler = withBukkitServer(
                () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
            useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
            boolean buriedCellCulling = renderMode.usesBuriedCellCulling();
            sampler.setBuriedCellCullingPass(buriedCellCulling);

            scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
                buriedCellCulling, renderMode, null, false, LodPolicy.NONE);

            LongOpenHashSet geometry = blackoutGeometry(scan);
            assertFalse(geometry.isEmpty(), renderMode.name());
            assertEquals(expectedFarZ, minimumBlackoutGeometryZ(scan), renderMode.name());
            Vector portalOrigin = structure.getCenter().toVector();
            PortalFrame projectionFrame = scan.localFrame();
            ProjectorPlaneWindow exactWindow = ProjectorPlaneWindow.create(
                structure, structure.getArea(), projectionFrame,
                portalOrigin.getX(), portalOrigin.getY(), portalOrigin.getZ(), 0.0D, scan.eyeDot());
            Direction projectionNormal = projectionFrame.getNormal();
            boolean foundOpaque = false;
            boolean foundSealedGlassOrWater = false;
            boolean foundSealedAir = false;
            boolean foundNearTransparent = false;
            boolean foundLateralShell = false;
            for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : scan.claims().long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                ProjectedBlockClaim claim = entry.getValue();
                int x = ProjectionCellKey.unpackX(key);
                int y = ProjectionCellKey.unpackY(key);
                int z = ProjectionCellKey.unpackZ(key);
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
                boolean transparent = !testOccluding(claim.getData());
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
                ProjectedBlockClaim claim = scan.claims().get(key);
                assertTrue(claim != null && claim.isBlackout(), renderMode.name());
            }
        }
    }

    @Test
    public void blackoutIncludesFarAirWhenTheLocalCellIsAlreadyAir() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

        LongOpenHashSet initialMask = new LongOpenHashSet(blackoutGeometry(scan));
        assertFalse(initialMask.isEmpty());
        assertEquals(initialMask, scan.claims().keySet(), "every claim is a shell cell over air");
        assertAllBlackout(scan);
        assertEquals(PortalProjector.minBlockForCenter(frustum.getRegion().getZa()),
            minimumBlackoutGeometryZ(scan));
        scan.commit();

        localView.ready = false;
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

        assertEquals(initialMask, blackoutGeometry(scan));
        assertEquals(initialMask, scan.claims().keySet(), "the shell carries over while the local chunk loads");
        assertAllBlackout(scan);
        scan.commit();

        localView.ready = true;
        remoteView.data = blockData(Material.STONE);
        memo.clearDestinationSamples();
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

        assertTrue(blackoutGeometry(scan).isEmpty());
        assertNoBlackoutClaims(scan);
        assertFalse(scan.claims().isEmpty());
        for (ProjectedBlockClaim claim : scan.claims().values()) {
            assertEquals(Material.STONE, claim.getData().getMaterial());
        }
    }

    @Test
    public void fullyOpaqueFarBoundaryCreatesNoBlackout() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.STONE));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false, false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

        assertFalse(scan.claims().isEmpty());
        assertTrue(blackoutGeometry(scan).isEmpty());
        assertNoBlackoutClaims(scan);
    }

    @Test
    public void blackoutUsesTheDeepestProjectionSlabForEveryNormal()
        throws ReflectiveOperationException {
        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            PortalStructure structure = orientedStructure(frame);
            ILocalPortal portal = portal(structure, frame);
            MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo memo = new ProjectorSampleMemo();
            ProjectorSampler sampler = withBukkitServer(
                () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
            useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
            Location eye = structure.getCenter().add(
                normal.x() * 1.5D, normal.y() * 1.5D, normal.z() * 1.5D);
            Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);
            int expectedCoordinate = farFrustumCoordinate(frustum, normal);

            scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
                false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

            LongOpenHashSet geometry = blackoutGeometry(scan);
            assertFalse(geometry.isEmpty(), normal.name()
                + " planeRejected=" + scan.planeRejected()
                + " windowRejected=" + scan.windowRejected()
                + " frustumRejected=" + scan.frustumRejected()
                + " region=" + frustum.getRegion());
            boolean farFace = false;
            boolean lateral = false;
            for (long key : geometry) {
                ProjectedBlockClaim claim = scan.claims().get(key);
                assertTrue(claim != null && claim.isBlackout(), normal.name());
                assertEquals(Material.BLACK_CONCRETE, claim.getData().getMaterial(), normal.name());
                farFace |= coordinate(key, normal) == expectedCoordinate;
                lateral |= coordinate(key, normal) != expectedCoordinate;
            }
            assertTrue(farFace, normal.name());
            assertTrue(lateral, normal.name());
            for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : scan.claims().long2ObjectEntrySet()) {
                if (!entry.getValue().isBlackout()) {
                    assertEquals(Material.AIR, entry.getValue().getData().getMaterial(), normal.name());
                    assertFalse(geometry.contains(entry.getLongKey()), normal.name());
                }
            }
        }
    }

    @Test
    public void staleShellClaimsAreResampledWhenTheCellLeavesTheShell() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D shallow = new Frustum4D(eye, structure, 4.0D, 2.0D);
        Frustum4D deep = new Frustum4D(eye, structure, 6.0D, 2.0D);
        int shallowFarZ = PortalProjector.minBlockForCenter(shallow.getRegion().getZa());
        int deepFarZ = PortalProjector.minBlockForCenter(deep.getRegion().getZa());
        assertTrue(deepFarZ < shallowFarZ);

        scan.run(destination, null, eye, shallow, 4.0D, true, false, false,
            false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
        assertEquals(shallowFarZ, minimumBlackoutGeometryZ(scan));
        scan.commit();

        scan.run(destination, null, eye, deep, 6.0D, false, false, false,
            false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

        LongOpenHashSet geometry = blackoutGeometry(scan);
        assertEquals(deepFarZ, minimumBlackoutGeometryZ(scan));
        int formerFarCells = 0;
        for (Long2ObjectMap.Entry<ProjectedBlockClaim> entry : scan.claims().long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            ProjectedBlockClaim claim = entry.getValue();
            assertEquals(geometry.contains(key), claim.isBlackout(),
                "shell membership and concrete must agree at " + ProjectionCellKey.unpackX(key)
                    + "," + ProjectionCellKey.unpackY(key) + "," + ProjectionCellKey.unpackZ(key));
            if (ProjectionCellKey.unpackZ(key) == shallowFarZ && !claim.isBlackout()) {
                assertEquals(Material.AIR, claim.getData().getMaterial());
                formerFarCells++;
            }
        }
        assertTrue(formerFarCells > 0, "the old far slab must be resampled as air once it is interior");
        ProjectionClaimSet.ClaimDelta delta = scan.claimDelta();
        for (long key : delta.changedKeys()) {
            ProjectedBlockClaim previous = delta.previousClaims().get(key);
            ProjectedBlockClaim next = delta.claims().get(key);
            assertTrue(previous == null || next == null || previous != next);
        }
    }

    @Test
    public void disablingBlackoutRestoresSampledClaims() throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
            false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
        LongOpenHashSet shell = new LongOpenHashSet(blackoutGeometry(scan));
        assertFalse(shell.isEmpty());
        LongOpenHashSet keys = new LongOpenHashSet(scan.claims().keySet());
        scan.commit();

        blackout.disable();
        scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
            false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

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
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        MutableWorldView localView = new MutableWorldView(blockData(Material.STONE));
        MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
        ProjectorDestination destination = destination(portal, structure, localView, remoteView);
        ProjectorSampleMemo memo = new ProjectorSampleMemo();
        ProjectorSampler sampler = withBukkitServer(
            () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
        ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
        enableBlackout(blackout);
        ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
        useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
            false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
        LongOpenHashSet shell = new LongOpenHashSet(blackoutGeometry(scan));
        assertFalse(shell.isEmpty());
        scan.commit();

        Field data = ProjectorBlackoutSeal.class.getDeclaredField("blackoutData");
        data.setAccessible(true);
        data.set(blackout, blockData(Material.RED_CONCRETE));
        scan.run(destination, null, eye, frustum, 4.0D, false, false, false,
            false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

        assertEquals(shell, blackoutGeometry(scan));
        ProjectionClaimSet.ClaimDelta delta = scan.claimDelta();
        for (long key : shell) {
            ProjectedBlockClaim claim = scan.claims().get(key);
            assertTrue(claim.isBlackout());
            assertEquals(Material.RED_CONCRETE, claim.getData().getMaterial());
            assertTrue(delta.changedKeys().contains(key), "recoloured shell cells must be resent");
        }
    }

    @Test
    public void unavailableMaskDoesNotCrossDestinationOrCoordinateMappings()
        throws ReflectiveOperationException {
        PortalFrame frame = PortalFrame.canonical(Direction.S);
        PortalStructure structure = structure();
        ILocalPortal portal = portal(structure, frame);
        Location eye = structure.getCenter().add(0.0D, 0.0D, 1.5D);
        Frustum4D frustum = new Frustum4D(eye, structure, 4.0D, 2.0D);

        for (int scenario = 0; scenario < 2; scenario++) {
            MutableWorldView localView = new MutableWorldView(blockData(Material.AIR));
            MutableWorldView remoteView = new MutableWorldView(blockData(Material.AIR));
            ProjectorDestination destination = destination(portal, structure, localView, remoteView);
            ProjectorSampleMemo memo = new ProjectorSampleMemo();
            ProjectorSampler sampler = withBukkitServer(
                () -> new ProjectorSampler(memo, new ProjectorRecursivePortals(), world -> remoteView));
            ProjectorBlackoutSeal blackout = new ProjectorBlackoutSeal();
            enableBlackout(blackout);
            ProjectorCellScan scan = new ProjectorCellScan(portal, sampler, memo, blackout);
            useOcclusion(scan, ProjectorCellScanLightingRetentionTest::testOccluding);

            scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
                false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);
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

            scan.run(destination, null, eye, frustum, 4.0D, true, false, false,
                false, ProjectionRenderMode.PANOPTIC, null, false, LodPolicy.NONE);

            assertTrue(blackoutGeometry(scan).isEmpty(), "scenario=" + scenario);
            assertNoBlackoutClaims(scan);
        }
    }

    private static void assertAllBlackout(ProjectorCellScan scan) {
        assertFalse(scan.claims().isEmpty());
        for (ProjectedBlockClaim claim : scan.claims().values()) {
            assertTrue(claim.isBlackout());
            assertEquals(Material.BLACK_CONCRETE, claim.getData().getMaterial());
            assertEquals(ProjectedBlockClaim.LightingPolicy.FULL_BRIGHT, claim.getLightingPolicy());
        }
    }

    private static void assertNoBlackoutClaims(ProjectorCellScan scan) {
        for (ProjectedBlockClaim claim : scan.claims().values()) {
            assertFalse(claim.isBlackout());
            assertFalse(claim.getData().getMaterial() == Material.BLACK_CONCRETE);
        }
    }

    private static void useOcclusion(ProjectorCellScan scan,
                                     ProjectorViewOcclusion.BlockOcclusion blockOcclusion)
        throws ReflectiveOperationException {
        Field field = ProjectorCellScan.class.getDeclaredField("viewOcclusion");
        field.setAccessible(true);
        field.set(scan, new ProjectorViewOcclusion(blockOcclusion));
    }

    private static boolean hasRemoteClaim(ProjectorCellScan scan, long remoteKey) {
        for (ProjectedBlockClaim claim : scan.claims().values()) {
            if (claim.getLightRemoteKey() == remoteKey) {
                return true;
            }
        }
        return false;
    }

    private static long scanRevision(PortalStructure structure, PortalFrame frame, LodPolicy lod, boolean blockEntities) {
        Location origin = structure.getCenter();
        return PortalProjector.plateTransformRevision(frame, frame,
            origin.getX(), origin.getY(), origin.getZ(), origin.getX(), origin.getY(), origin.getZ(),
            4, 2, 0.0D, false, lod, blockEntities);
    }

    private static int minimumBlackoutGeometryZ(ProjectorCellScan scan) throws ReflectiveOperationException {
        LongOpenHashSet geometry = blackoutGeometry(scan);
        int minimum = Integer.MAX_VALUE;
        for (long key : geometry) {
            minimum = Math.min(minimum, ProjectionCellKey.unpackZ(key));
        }
        return minimum;
    }

    private static LongOpenHashSet blackoutGeometry(ProjectorCellScan scan) throws ReflectiveOperationException {
        Field field = ProjectorCellScan.class.getDeclaredField("blackoutGeometry");
        field.setAccessible(true);
        return (LongOpenHashSet) field.get(scan);
    }

    private static int farFrustumCoordinate(Frustum4D frustum, Direction normal) {
        if (normal.x() > 0) {
            return PortalProjector.minBlockForCenter(frustum.getRegion().getXa());
        }
        if (normal.x() < 0) {
            return PortalProjector.maxBlockForCenter(frustum.getRegion().getXb());
        }
        if (normal.y() > 0) {
            return PortalProjector.minBlockForCenter(frustum.getRegion().getYa());
        }
        if (normal.y() < 0) {
            return PortalProjector.maxBlockForCenter(frustum.getRegion().getYb());
        }
        return normal.z() > 0
            ? PortalProjector.minBlockForCenter(frustum.getRegion().getZa())
            : PortalProjector.maxBlockForCenter(frustum.getRegion().getZb());
    }

    private static int coordinate(long key, Direction direction) {
        if (direction.x() != 0) {
            return ProjectionCellKey.unpackX(key);
        }
        if (direction.y() != 0) {
            return ProjectionCellKey.unpackY(key);
        }
        return ProjectionCellKey.unpackZ(key);
    }

    private static boolean testOccluding(BlockData data) {
        if (data == null) {
            return false;
        }
        Material material = data.getMaterial();
        return material == Material.STONE
            || material == Material.GRASS_BLOCK
            || material == Material.DIRT
            || material == Material.DEEPSLATE;
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

    private static void assertLighting(ProjectorCellScan scan, ProjectedBlockClaim.LightingPolicy expected) {
        for (ProjectedBlockClaim claim : scan.claims().values()) {
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

    private static PortalStructure orientedStructure(PortalFrame frame) {
        Direction up = frame.getUp();
        Direction right = frame.getRight();
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

    private static ILocalPortal portal(PortalStructure structure, PortalFrame frame) {
        Vector origin = structure.getCenter().toVector();
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(), new Class<?>[] { ILocalPortal.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getStructure" -> structure;
                case "getFrame" -> frame;
                case "getOrigin" -> origin;
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

    private static ProjectorSampler withBukkitServer(SamplerFactory factory) throws ReflectiveOperationException {
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
        ProjectorSampler create();
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
            long key = ProjectionCellKey.pack(x, y, z);
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
