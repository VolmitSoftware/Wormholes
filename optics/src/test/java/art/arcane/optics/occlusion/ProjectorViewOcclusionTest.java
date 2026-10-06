package art.arcane.optics.occlusion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.state.StateProperties;
import art.arcane.optics.view.BlockStates;
import art.arcane.optics.view.BlockView;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.CellKeys;

public final class ProjectorViewOcclusionTest {
    @Test
    public void rayOcclusionFollowsTheBlockStatesOcclusionRule() {
        ProjectorViewOcclusion<FakeBlock> occlusion = new ProjectorViewOcclusion<FakeBlock>(new FakeBlocks(data -> data == FakeBlock.GLASS));

        assertTrue(occlusion.isOccluding(FakeBlock.GLASS));
        assertFalse(occlusion.isOccluding(FakeBlock.STONE));
    }

    @Test
    public void raysBeforeDistantBlockersStayVisibleWithoutSpendingTheTraceBudget() {
        FakeWorldView view = new FakeWorldView();
        for (Face normal : Face.values()) {
            LongOpenHashSet blockers = new LongOpenHashSet();
            blockers.add(CellKeys.pack(normal.x() * -60, normal.y() * -60, normal.z() * -60));
            ProjectorViewOcclusion<FakeBlock> occlusion = new ProjectorViewOcclusion<FakeBlock>(new FakeBlocks(data -> true), 1);
            occlusion.setRevealMarginDegrees(2.0D);
            occlusion.beginPass(0.5D, 0.5D, 0.5D, normal, blockers);
            for (int depth = 1; depth < 60; depth++) {
                assertEquals(ProjectorViewOcclusion.Visibility.VISIBLE,
                    occlusion.visibility(view, normal.x() * -depth, normal.y() * -depth, normal.z() * -depth,
                        0.5D + normal.x() * 2.0D, 0.5D + normal.y() * 2.0D, 0.5D + normal.z() * 2.0D),
                    normal.name() + " depth=" + depth);
            }
            assertEquals(0, occlusion.voxelSteps(), normal.name());
            assertFalse(occlusion.budgetExhausted(), normal.name());
        }
    }

    @Test
    public void disjointRaysCanResolveAfterOtherRaysExhaustTheBudget() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = new ProjectorViewOcclusion<FakeBlock>(new FakeBlocks(data -> true), 1);
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertEquals(ProjectorViewOcclusion.Visibility.UNRESOLVED,
            occlusion.visibility(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertTrue(occlusion.budgetExhausted());
        assertEquals(ProjectorViewOcclusion.Visibility.VISIBLE,
            occlusion.visibility(view, 40, 10, 10, 0.5D, 10.5D, 10.5D));
        assertEquals(1, occlusion.voxelSteps());
    }

    @Test
    public void blockerBoundsMatchDenseVisibilityAcrossMovingEyesAndAllPortalAxes() {
        for (Face normal : Face.values()) {
            FakeWorldView view = new FakeWorldView();
            LongOpenHashSet blockers = new LongOpenHashSet();
            int axis = normal.x() != 0 ? 0 : normal.y() != 0 ? 1 : 2;
            int rightAxis = (axis + 1) % 3;
            int upAxis = (axis + 2) % 3;
            int sign = -(normal.x() + normal.y() + normal.z());
            int[] cell = new int[3];
            cell[axis] = sign * 12;
            for (int r = -2; r <= 2; r++) {
                cell[rightAxis] = r;
                for (int u = -2; u <= 2; u++) {
                    cell[upAxis] = u;
                    view.put(cell[0], cell[1], cell[2], FakeBlock.STONE);
                    blockers.add(CellKeys.pack(cell[0], cell[1], cell[2]));
                }
            }
            ProjectorViewOcclusion<FakeBlock> dense = occlusion();
            ProjectorViewOcclusion<FakeBlock> bounded = occlusion();
            dense.setRevealMarginDegrees(2.0D);
            bounded.setRevealMarginDegrees(2.0D);
            for (int offset = -2; offset <= 2; offset++) {
                dense.beginPass(0.5D, 0.5D, 0.5D, normal);
                bounded.beginPass(0.5D, 0.5D, 0.5D, normal, blockers);
                double[] eye = {0.5D + normal.x() * 2.0D, 0.5D + normal.y() * 2.0D,
                    0.5D + normal.z() * 2.0D};
                eye[rightAxis] += offset * 2.0D;
                for (int depth = 1; depth <= 18; depth++) {
                    cell[axis] = sign * depth;
                    for (int r = -3; r <= 3; r++) {
                        cell[rightAxis] = r;
                        for (int u = -3; u <= 3; u++) {
                            cell[upAxis] = u;
                            assertEquals(dense.visibility(view, cell[0], cell[1], cell[2], eye[0], eye[1], eye[2]),
                                bounded.visibility(view, cell[0], cell[1], cell[2], eye[0], eye[1], eye[2]),
                                normal.name() + " offset=" + offset + " depth=" + depth + " r=" + r + " u=" + u);
                        }
                    }
                }
                assertFalse(dense.budgetExhausted());
                assertFalse(bounded.budgetExhausted());
            }
        }
    }

    @Test
    public void hiddenVerdictsReportTheBlockersThatProveThem() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(3, 1, 1));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.setRevealMarginDegrees(1.0D);
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertEquals(ProjectorViewOcclusion.Visibility.HIDDEN, occlusion.visibility(view, 8, 1, 1, 0.5D, 1.5D, 1.5D));
        assertEquals(LongArrayList.of(CellKeys.pack(3, 1, 1)), occlusion.hiddenBlockers(), "a single blocker proves the target");
        assertEquals(ProjectorViewOcclusion.Visibility.HIDDEN, occlusion.visibility(view, 8, 1, 1, 0.5D, 1.5D, 1.5D));
        assertEquals(LongArrayList.of(CellKeys.pack(3, 1, 1)), occlusion.hiddenBlockers(), "a cached verdict keeps its proof");
        assertEquals(ProjectorViewOcclusion.Visibility.VISIBLE, occlusion.visibility(view, 8, 4, 4, 0.5D, 1.5D, 1.5D));
        assertTrue(occlusion.hiddenBlockers().isEmpty());

        LongOpenHashSet wall = new LongOpenHashSet();
        for (int y = -2; y <= 6; y++) {
            for (int z = -2; z <= 6; z++) {
                wall.add(CellKeys.pack(3, y, z));
            }
        }
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, wall);
        assertEquals(ProjectorViewOcclusion.Visibility.HIDDEN, occlusion.visibility(view, 9, 3, 3, 0.5D, 1.5D, 1.5D));
        LongArrayList plane = occlusion.hiddenBlockers();
        assertTrue(plane.size() > 1, "an oblique target is proven by several wall cells");
        for (int index = 0; index < plane.size(); index++) {
            assertTrue(wall.contains(plane.getLong(index)));
        }
        LongArrayList firstProof = new LongArrayList(plane);
        assertEquals(ProjectorViewOcclusion.Visibility.HIDDEN, occlusion.visibility(view, 9, 3, 3, 0.5D, 1.5D, 1.5D));
        assertEquals(firstProof, occlusion.hiddenBlockers(), "a cached multi-cell proof keeps its blockers");
        assertEquals(ProjectorViewOcclusion.Visibility.HIDDEN, occlusion.visibility(view, 9, 3, 3, 0.5D, 1.6D, 1.5D));
        assertFalse(occlusion.hiddenBlockers().isEmpty(), "a moved eye traces the proof again");
    }

    @Test
    public void flatOpaqueWallCullsEverythingBehindItsVisibleSurface() {
        FakeWorldView view = new FakeWorldView();
        fillPlane(view, 2, -2, 4, -2, 4, FakeBlock.STONE);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 2, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(occlusion.visible(view, 4, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(occlusion.visible(view, 7, 3, -1, 0.5D, 1.5D, 1.5D));
        assertFalse(view.wasRead(4, 1, 1), "visibility checks must not sample hidden target geometry");
    }

    @Test
    public void caveWallsCullRockBehindThemButLeaveTheOpeningVisible() {
        FakeWorldView sealed = new FakeWorldView();
        fillPlane(sealed, 3, -3, 4, -3, 4, FakeBlock.DEEPSLATE);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertFalse(occlusion.visible(sealed, 6, 0, 0, 0.5D, 0.5D, 0.5D));

        FakeWorldView opening = new FakeWorldView();
        fillPlane(opening, 3, -3, 4, -3, 4, FakeBlock.DEEPSLATE);
        opening.put(3, 0, 0, FakeBlock.AIR);
        beginPass(occlusion);

        assertTrue(occlusion.visible(opening, 6, 0, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void anObliqueFlatWallStillCullsTheFullyCoveredTarget() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 2, 0, FakeBlock.STONE);
        view.put(2, 3, 0, FakeBlock.STONE);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertFalse(occlusion.visible(view, 4, 6, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void steppedHillWithoutAnExactCoverageProofFailsOpen() {
        FakeWorldView view = new FakeWorldView();
        view.put(3, 0, 0, FakeBlock.STONE);
        view.put(2, 1, 0, FakeBlock.STONE);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 6, 1, 0, 0.5D, 0.5D, 0.5D));

        FakeWorldView opening = new FakeWorldView();
        opening.put(3, 0, 0, FakeBlock.STONE);
        beginPass(occlusion);
        assertTrue(occlusion.visible(opening, 6, 1, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void glassWaterAndPartialBlocksDoNotHideGeometry() {
        FakeBlock[] transparent = new FakeBlock[] { FakeBlock.GLASS, FakeBlock.WATER, FakeBlock.OAK_SLAB };
        for (FakeBlock material : transparent) {
            FakeWorldView view = new FakeWorldView();
            view.put(2, 1, 1, material);
            ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
            beginPass(occlusion);

            assertTrue(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D), material.name());
        }
    }

    @Test
    public void anExposedCornerKeepsAPartiallyVisibleBlock() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 1, 1, FakeBlock.STONE);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 4, 2, 1, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void unsampledFaceGapKeepsAPartiallyVisibleOpaqueTarget() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        int[][] coordinates = new int[][] {{3, 1, 0}, {4, 0, 1}, {4, 1, 1}};
        for (int[] coordinate : coordinates) {
            view.put(coordinate[0], coordinate[1], coordinate[2], FakeBlock.STONE);
            blockers.add(CellKeys.pack(coordinate[0], coordinate[1], coordinate[2]));
        }
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertTrue(occlusion.visible(view, 6, 0, 0, 0.5D, 2.3D, 2.1D));
    }

    @Test
    public void blockerOutsideTheAcceptedProjectionSetCannotHideATarget() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 1, 1, FakeBlock.STONE);
        LongOpenHashSet accepted = new LongOpenHashSet();
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, accepted);

        assertTrue(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));

        accepted.add(CellKeys.pack(2, 1, 1));
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, accepted);
        assertFalse(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(view.wasRead(2, 1, 1));
    }

    @Test
    public void completeSameSlabEligibilityRemovesTraversalOrderDependence() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet priorSlabOnly = new LongOpenHashSet();
        LongOpenHashSet complete = new LongOpenHashSet();
        for (int x = 1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                view.put(x, 3, z, FakeBlock.STONE);
                complete.add(CellKeys.pack(x, 3, z));
                if (x == 1) {
                    priorSlabOnly.add(CellKeys.pack(x, 3, z));
                }
            }
        }
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, priorSlabOnly);

        assertTrue(occlusion.visible(view, 2, 2, 0, 0.5D, 8.5D, 0.5D));

        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, complete);
        assertFalse(occlusion.visible(view, 2, 2, 0, 0.5D, 8.5D, 0.5D));
    }

    @Test
    public void opaqueTargetWithNoExposedEyeFacingFaceIsCulled() {
        FakeWorldView view = new FakeWorldView();
        view.put(3, 1, 1, FakeBlock.STONE);
        view.put(4, 0, 1, FakeBlock.STONE);
        view.put(4, 2, 1, FakeBlock.STONE);
        view.put(4, 1, 0, FakeBlock.STONE);
        view.put(4, 1, 2, FakeBlock.STONE);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertFalse(occlusion.visible(view, 4, 1, 1, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void rearSurfaceWithOnlyAnAwayFacingOpeningUsesNoRayBudget() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(3, 0, 0));
        blockers.add(CellKeys.pack(4, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertFalse(occlusion.visible(view, 4, 0, 0, 0.5D, 0.5D, 0.5D));
        assertEquals(1, occlusion.adjacentOcclusionHits());
        assertEquals(0, occlusion.voxelSteps());
    }

    @Test
    public void everyEyeFacingSideNeedsAnAcceptedOpaqueNeighbor() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet incomplete = new LongOpenHashSet();
        incomplete.add(CellKeys.pack(3, 0, 0));
        incomplete.add(CellKeys.pack(4, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 3.5D, 0.5D, Face.W, incomplete);

        assertTrue(occlusion.visible(view, 4, 0, 0, 0.5D, 3.5D, 0.5D));
        assertEquals(0, occlusion.adjacentOcclusionHits());

        LongOpenHashSet complete = new LongOpenHashSet(incomplete);
        complete.add(CellKeys.pack(4, 1, 0));
        occlusion.beginPass(0.5D, 3.5D, 0.5D, Face.W, complete);

        assertFalse(occlusion.visible(view, 4, 0, 0, 0.5D, 3.5D, 0.5D));
        assertEquals(1, occlusion.adjacentOcclusionHits());
        assertEquals(0, occlusion.voxelSteps());
    }

    @Test
    public void aGrazingCaveWallFaceIsNotEclipsed() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 1, 1, FakeBlock.STONE);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 4, 2, 1, 0.5D, 1.35D, 1.5D));
    }

    @Test
    public void fartherBlockOnTheSameFloorPlaneRemainsVisible() {
        FakeWorldView view = new FakeWorldView();
        for (int x = 1; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) {
                view.put(x, 0, z, FakeBlock.STONE);
            }
        }
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 4, 0, 0, 0.5D, 1.5D, 0.5D));
    }

    @Test
    public void fartherBlockOnTheSameWallPlaneRemainsVisible() {
        FakeWorldView view = new FakeWorldView();
        for (int x = 1; x <= 8; x++) {
            for (int y = -1; y <= 3; y++) {
                view.put(x, y, 0, FakeBlock.STONE);
            }
        }
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 4, 1, 0, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void frontSideTerrainIsIgnoredButBehindPortalTerrainStillOccludes() {
        FakeWorldView view = new FakeWorldView();
        for (int x = 1; x <= 4; x++) {
            view.put(x, 0, 0, FakeBlock.STONE);
        }
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.E);

        assertTrue(occlusion.visible(view, -4, 0, 0, 4.5D, 0.5D, 0.5D));

        view.put(-2, 0, 0, FakeBlock.STONE);
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.E);
        assertFalse(occlusion.visible(view, -4, 0, 0, 4.5D, 0.5D, 0.5D));
    }

    @Test
    public void firstVoxelBehindEitherPortalFaceOccludesDeeperGeometry() {
        FakeWorldView positive = new FakeWorldView();
        positive.put(1, 0, 0, FakeBlock.STONE);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W);

        assertFalse(occlusion.visible(positive, 3, 0, 0, 0.5D, 0.5D, 0.5D));

        FakeWorldView negative = new FakeWorldView();
        negative.put(-1, 0, 0, FakeBlock.STONE);
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.E);

        assertFalse(occlusion.visible(negative, -3, 0, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void eligibleBlockerOctreeMatchesDenseWorldSampling() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        fillPlane(view, 2, -8, 8, -8, 8, FakeBlock.STONE);
        for (int y = -8; y <= 8; y++) {
            for (int z = -8; z <= 8; z++) {
                blockers.add(CellKeys.pack(2, y, z));
            }
        }
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 1.5D, 1.5D, Face.W, blockers);

        assertTrue(occlusion.visible(view, 2, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(occlusion.visible(view, 20, 1, 1, 0.5D, 1.5D, 1.5D));
        assertFalse(occlusion.visible(view, 40, 3, -4, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void eligibleBlockerOctreeSkipsEmptyCubeInteriorsInConstantWork() {
        FakeWorldView view = new FakeWorldView();
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, new LongOpenHashSet());

        assertTrue(occlusion.visible(view, 300, 0, 0, 0.5D, 0.5D, 0.5D));
        assertTrue(occlusion.voxelSteps() <= 20,
            "empty occupancy cubes should be traversed once instead of once per voxel");
        assertFalse(occlusion.budgetExhausted());
    }

    @Test
    public void eligibleBlockerOctreeStillFindsSparseBlockersAcrossCubeBoundaries() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        for (int y = -8; y <= 8; y++) {
            for (int z = -8; z <= 8; z++) {
                blockers.add(CellKeys.pack(34, y, z));
            }
        }
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertFalse(occlusion.visible(view, 40, 0, 0, 0.5D, 0.5D, 0.5D));
        assertTrue(occlusion.voxelSteps() < 20);
    }

    @Test
    public void sparseEmptyHierarchyCubesAvoidExactMembershipProbes() {
        FakeWorldView view = new FakeWorldView();
        CountingLongOpenHashSet blockers = new CountingLongOpenHashSet();
        blockers.add(CellKeys.pack(34, 20, 20));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        blockers.resetContainsCalls();

        assertTrue(occlusion.visible(view, 40, 0, 0, 0.5D, 0.5D, 0.5D));
        assertEquals(0, blockers.containsCalls());
        assertFalse(occlusion.budgetExhausted());
    }

    @Test
    public void occupiedLeafFallsBackToExactMembership() {
        FakeWorldView view = new FakeWorldView();
        CountingLongOpenHashSet blockers = new CountingLongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        blockers.resetContainsCalls();

        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertTrue(blockers.containsCalls() > 0);
        assertFalse(occlusion.budgetExhausted());
    }

    @Test
    public void hierarchyFirstTraversalMatchesDenseSamplingForNegativeTieRay() {
        FakeWorldView view = new FakeWorldView();
        view.put(-2, -2, 0, FakeBlock.STONE);
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(-2, -2, 0));
        ProjectorViewOcclusion<FakeBlock> dense = occlusion();
        ProjectorViewOcclusion<FakeBlock> sparse = occlusion();
        dense.beginPass(0.5D, 0.5D, 0.5D, Face.E);
        sparse.beginPass(0.5D, 0.5D, 0.5D, Face.E, blockers);

        boolean denseVisible = dense.visible(view, -5, -5, 0, 0.5D, 0.5D, 0.5D);
        boolean sparseVisible = sparse.visible(view, -5, -5, 0, 0.5D, 0.5D, 0.5D);

        assertEquals(denseVisible, sparseVisible);
        assertFalse(sparse.budgetExhausted());
    }

    @Test
    public void emptyCubeSkipNeverStepsPastABoundaryBlocker() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet positiveBlockers = new LongOpenHashSet();
        positiveBlockers.add(CellKeys.pack(48, 0, 0));
        ProjectorViewOcclusion<FakeBlock> positive = occlusion();
        positive.beginPass(0.5D, 0.5D, 0.5D, Face.W, positiveBlockers);

        LongOpenHashSet negativeBlockers = new LongOpenHashSet();
        negativeBlockers.add(CellKeys.pack(-48, 0, 0));
        ProjectorViewOcclusion<FakeBlock> negative = occlusion();
        negative.beginPass(0.5D, 0.5D, 0.5D, Face.E, negativeBlockers);

        for (int targetX = 49; targetX <= 256; targetX++) {
            assertFalse(positive.visible(view, targetX, 0, 0, 0.5D, 0.5D, 0.5D),
                "positive empty-volume skips must stop before the blocker for target x=" + targetX);
            assertFalse(negative.visible(view, -targetX, 0, 0, 0.5D, 0.5D, 0.5D),
                "negative empty-volume skips must stop before the blocker for target x=" + -targetX);
        }
        assertFalse(positive.budgetExhausted());
        assertFalse(negative.budgetExhausted());
    }

    @Test
    public void stationaryEyeReusesExactHiddenBlockerProofAcrossPasses() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertTrue(occlusion.voxelSteps() > 0);

        LongOpenHashSet expandedBlockers = new LongOpenHashSet(blockers);
        expandedBlockers.add(CellKeys.pack(20, 3, 4));
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, expandedBlockers);

        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertEquals(1, occlusion.hiddenProofHits());
        assertEquals(0, occlusion.voxelSteps());
    }

    @Test
    public void cachedBlockerProofCannotHideWhenItsBlockerLeavesTheEligibleSet() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));

        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, new LongOpenHashSet());

        assertTrue(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertEquals(0, occlusion.hiddenProofHits());
    }

    @Test
    public void staleBlockerProofIsDiscardedWhenItsBlockerLeavesTheEligibleSet() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));

        LongOpenHashSet irrelevantBlockers = new LongOpenHashSet();
        irrelevantBlockers.add(CellKeys.pack(8, 0, 0));
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, irrelevantBlockers);
        assertTrue(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertEquals(0, occlusion.hiddenProofHits());
        assertEquals(1, occlusion.hiddenProofInvalidations());

        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertTrue(occlusion.voxelSteps() > 0);
        assertEquals(0, occlusion.hiddenProofHits());
    }

    @Test
    public void eyeMovementRevalidatesCachedHiddenProofsWithoutVoxelTraversal() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));

        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertFalse(occlusion.visible(view, 5, 0, 0, 0.6D, 0.5D, 0.5D));

        assertEquals(1, occlusion.hiddenProofHits());
        assertEquals(1, occlusion.hiddenProofRevalidations());
        assertEquals(0, occlusion.hiddenProofInvalidations());
        assertEquals(0, occlusion.voxelSteps());
    }

    @Test
    public void movedEyeFallsBackWhenCachedBlockerNoLongerCoversTheTarget() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));

        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertTrue(occlusion.visible(view, 5, 0, 0, 0.5D, 4.5D, 0.5D));

        assertEquals(0, occlusion.hiddenProofHits());
        assertEquals(0, occlusion.hiddenProofRevalidations());
        assertEquals(1, occlusion.hiddenProofInvalidations());
        assertTrue(occlusion.voxelSteps() > 0);
    }

    @Test
    public void angularRevealMarginKeepsNearEdgeGeometryReadyBeforeItBleedsIn() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> exact = occlusion();
        exact.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertFalse(exact.visible(view, 5, 0, 0, 0.5D, 0.95D, 0.5D));

        ProjectorViewOcclusion<FakeBlock> guarded = occlusion();
        guarded.setRevealMarginDegrees(2.0D);
        guarded.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertTrue(guarded.visible(view, 5, 0, 0, 0.5D, 0.95D, 0.5D));
    }

    @Test
    public void exactHiddenProofsRemainUsableAfterTheTraversalBudgetIsExhausted() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));

        for (int i = 0; i < 100_000 && !occlusion.budgetExhausted(); i++) {
            assertTrue(occlusion.visible(view, 300, 300 + (i >> 2), i & 3, 0.5D, 0.5D, 0.5D));
        }

        assertTrue(occlusion.budgetExhausted());
        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertEquals(1, occlusion.hiddenProofHits() + occlusion.verdictHits());
    }

    @Test
    public void adjacentRearSurfaceCullingContinuesAfterTheRayBudgetIsExhausted() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(4, 0, 0));
        blockers.add(CellKeys.pack(5, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        for (int index = 0; index < 100_000 && !occlusion.budgetExhausted(); index++) {
            assertTrue(occlusion.visible(view, 300, 300 + (index >> 2), index & 3, 0.5D, 0.5D, 0.5D));
        }
        assertTrue(occlusion.budgetExhausted());

        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertEquals(1, occlusion.adjacentOcclusionHits());
    }

    @Test
    public void cachedProofFailsOpenWhenTheViewRevisionChangesDuringReuse() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertFalse(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));

        view.changeRevisionOnRead(view.revisionReads() + 2);
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertTrue(occlusion.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertEquals(0, occlusion.hiddenProofHits());
    }

    @Test
    public void eligibleBlockerOctreeMatchesDenseTraversalAcrossObliqueTargets() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        fillPlane(view, 18, -6, 6, -6, 6, FakeBlock.STONE);
        for (int y = -6; y <= 6; y++) {
            for (int z = -6; z <= 6; z++) {
                blockers.add(CellKeys.pack(18, y, z));
            }
        }
        ProjectorViewOcclusion<FakeBlock> dense = occlusion();
        ProjectorViewOcclusion<FakeBlock> sparse = occlusion();
        dense.beginPass(0.5D, 0.5D, 0.5D, Face.W);
        sparse.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        for (int targetX = 19; targetX <= 40; targetX += 3) {
            for (int targetY = -5; targetY <= 5; targetY += 2) {
                for (int targetZ = -5; targetZ <= 5; targetZ += 2) {
                    assertEquals(
                        dense.visible(view, targetX, targetY, targetZ, 0.5D, 0.5D, 0.5D),
                        sparse.visible(view, targetX, targetY, targetZ, 0.5D, 0.5D, 0.5D),
                        targetX + ":" + targetY + ":" + targetZ);
                }
            }
        }
        assertFalse(dense.budgetExhausted());
        assertFalse(sparse.budgetExhausted());
        assertTrue(sparse.voxelSteps() < dense.voxelSteps());
    }

    @Test
    public void unavailableSnapshotCellFailsOpenAndRequestsCapture() {
        FakeWorldView view = new FakeWorldView();
        view.unknown(2, 1, 1);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertTrue(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));
        assertTrue(view.wasRequested(2, 1));
        assertFalse(view.wasRead(5, 1, 1));
    }


    @Test
    public void aRevisionChangeDuringThePassFailsOpen() {
        FakeWorldView view = new FakeWorldView();
        view.put(2, 1, 1, FakeBlock.STONE);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        assertFalse(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));
        view.revision++;
        assertTrue(occlusion.visible(view, 5, 1, 1, 0.5D, 1.5D, 1.5D));
    }

    @Test
    public void largeVolumeWorkAndRetainedCacheAreStrictlyBounded() {
        FakeWorldView view = new FakeWorldView();
        view.fillXPlane = 2;
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);
        int candidates = 250_000;
        int hidden = 0;

        for (int i = 0; i < candidates; i++) {
            if (!occlusion.visible(view, 4, 0, 0, 0.5D, 0.5D, 0.5D)) {
                hidden++;
            }
        }

        assertEquals(candidates, hidden, "the flat-wall fast path must stay inside the pass budget");
        assertTrue(occlusion.voxelSteps() <= candidates * 2,
            "the center-blocker coverage fast path must use at most two voxel steps per candidate");
        assertTrue(occlusion.voxelSteps() <= ProjectorViewOcclusion.MAX_VOXEL_STEPS_PER_PASS);
        assertFalse(occlusion.budgetExhausted());
        assertTrue(occlusion.opacityCacheSize() <= ProjectorViewOcclusion.MAX_OPACITY_CACHE_CELLS);
    }

    @Test
    public void opacityMemoNeverRetainsMoreThanItsFixedCellCap() {
        FakeWorldView view = new FakeWorldView();
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        for (int i = 0; i < ProjectorViewOcclusion.MAX_OPACITY_CACHE_CELLS + 1_000; i++) {
            int baseX = i * 3;
            assertTrue(occlusion.visible(view, baseX + 2, 0, 0, baseX + 0.5D, 0.5D, 0.5D));
        }

        assertEquals(ProjectorViewOcclusion.MAX_OPACITY_CACHE_CELLS, occlusion.opacityCacheSize());
    }

    @Test
    public void exhaustedBudgetFailsOpenAndNeverExceedsTheHardStepLimit() {
        FakeWorldView view = new FakeWorldView();
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        beginPass(occlusion);

        boolean visible = false;
        for (int i = 0; i < 100_000 && !occlusion.budgetExhausted(); i++) {
            visible = occlusion.visible(view, 300, i & 3, 0, 0.5D, 0.5D, 0.5D);
        }

        assertTrue(occlusion.budgetExhausted());
        assertTrue(visible);
        assertEquals(ProjectorViewOcclusion.MAX_VOXEL_STEPS_PER_PASS, occlusion.voxelSteps());
    }

    @Test
    public void exhaustedTargetsRemainVisibleButReportThatTheyNeedAnotherPass() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> limited = new ProjectorViewOcclusion<FakeBlock>(new FakeBlocks(data -> data != null && data == FakeBlock.STONE), 1);
        limited.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertEquals(ProjectorViewOcclusion.Visibility.UNRESOLVED,
            limited.visibility(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
        assertTrue(limited.visible(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));

        ProjectorViewOcclusion<FakeBlock> complete = new ProjectorViewOcclusion<FakeBlock>(new FakeBlocks(data -> data != null && data == FakeBlock.STONE), 8);
        complete.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        assertEquals(ProjectorViewOcclusion.Visibility.HIDDEN,
            complete.visibility(view, 5, 0, 0, 0.5D, 0.5D, 0.5D));
    }

    @Test
    public void stationaryPassesReuseEveryVerdictWithoutTracing() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = wall(6, 3);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        Map<Long, ProjectorViewOcclusion.Visibility> first = sweep(occlusion, view, 0.5D);
        assertTrue(occlusion.voxelSteps() > 0);
        assertTrue(first.containsValue(ProjectorViewOcclusion.Visibility.HIDDEN));
        assertTrue(first.containsValue(ProjectorViewOcclusion.Visibility.VISIBLE));

        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, new LongOpenHashSet(blockers));
        Map<Long, ProjectorViewOcclusion.Visibility> second = sweep(occlusion, view, 0.5D);

        assertEquals(first, second);
        assertEquals(0, occlusion.voxelSteps());
        assertEquals(first.size(), occlusion.verdictHits());
    }

    @Test
    public void changedBlockersNeverServeAStaleVerdict() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = wall(6, 3);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertEquals(ProjectorViewOcclusion.Visibility.HIDDEN, occlusion.visibility(view, 12, 0, 0, 0.5D, 0.5D, 0.5D));

        LongOpenHashSet opened = new LongOpenHashSet(blockers);
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                opened.remove(CellKeys.pack(6, y, z));
            }
        }
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, opened);

        assertEquals(ProjectorViewOcclusion.Visibility.VISIBLE, occlusion.visibility(view, 12, 0, 0, 0.5D, 0.5D, 0.5D));
        assertEquals(0, occlusion.verdictHits());
    }

    @Test
    public void movedEyeRecomputesInsteadOfReusingVerdicts() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = wall(6, 3);
        ProjectorViewOcclusion<FakeBlock> occlusion = occlusion();
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        sweep(occlusion, view, 0.5D);

        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        Map<Long, ProjectorViewOcclusion.Visibility> moved = sweep(occlusion, view, 0.75D);

        ProjectorViewOcclusion<FakeBlock> fresh = occlusion();
        fresh.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        assertEquals(sweep(fresh, view, 0.75D), moved);
        assertEquals(0, occlusion.verdictHits());
    }

    @Test
    public void budgetStarvedTargetsConvergeAcrossPassesWithoutRetracingResolvedOnes() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = wall(6, 3);
        ProjectorViewOcclusion<FakeBlock> exact = occlusion();
        exact.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
        Map<Long, ProjectorViewOcclusion.Visibility> expected = sweep(exact, view, 0.5D);
        ProjectorViewOcclusion<FakeBlock> limited = new ProjectorViewOcclusion<FakeBlock>(new FakeBlocks(data -> true), 256);
        Map<Long, ProjectorViewOcclusion.Visibility> resolved = new HashMap<Long, ProjectorViewOcclusion.Visibility>();
        int passes = 0;
        int previousUnresolved = Integer.MAX_VALUE;

        while (resolved.size() < expected.size()) {
            assertTrue(++passes <= expected.size(), "the verdicts must converge");
            limited.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);
            int resolvedBefore = resolved.size();
            int unresolved = 0;
            for (Map.Entry<Long, ProjectorViewOcclusion.Visibility> entry : sweep(limited, view, 0.5D).entrySet()) {
                if (entry.getValue() == ProjectorViewOcclusion.Visibility.UNRESOLVED) {
                    unresolved++;
                } else {
                    resolved.put(entry.getKey(), entry.getValue());
                }
            }
            assertTrue(unresolved < previousUnresolved || unresolved == 0, "every pass must resolve more targets");
            assertEquals(resolvedBefore, limited.verdictHits(), "targets resolved on earlier passes are never traced again");
            previousUnresolved = unresolved;
        }

        assertEquals(expected, resolved);
        assertTrue(passes > 1);
    }

    @Test
    public void verdictMemoNeverRetainsMoreThanItsCap() {
        FakeWorldView view = new FakeWorldView();
        LongOpenHashSet blockers = new LongOpenHashSet();
        blockers.add(CellKeys.pack(2, 0, 0));
        ProjectorViewOcclusion<FakeBlock> occlusion = new ProjectorViewOcclusion<FakeBlock>(new FakeBlocks(data -> true), Integer.MAX_VALUE);
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W, blockers);

        for (int i = 0; i < ProjectorViewOcclusion.MAX_VERDICT_CELLS + 1_000; i++) {
            occlusion.visibility(view, 400 + (i & 63), 400 + ((i >> 6) & 63), i >> 12, 0.5D, 0.5D, 0.5D);
        }

        assertEquals(ProjectorViewOcclusion.MAX_VERDICT_CELLS, occlusion.verdictCacheSize());
    }

    private static LongOpenHashSet wall(int x, int radius) {
        LongOpenHashSet blockers = new LongOpenHashSet();
        for (int y = -radius; y <= radius; y++) {
            for (int z = -radius; z <= radius; z++) {
                blockers.add(CellKeys.pack(x, y, z));
            }
        }
        return blockers;
    }

    private static Map<Long, ProjectorViewOcclusion.Visibility> sweep(ProjectorViewOcclusion<FakeBlock> occlusion,
                                                                     FakeWorldView view, double eyeY) {
        Map<Long, ProjectorViewOcclusion.Visibility> verdicts = new HashMap<Long, ProjectorViewOcclusion.Visibility>();
        for (int x = 8; x <= 14; x += 2) {
            for (int y = -6; y <= 6; y += 2) {
                for (int z = -6; z <= 6; z += 3) {
                    verdicts.put(Long.valueOf(CellKeys.pack(x, y, z)),
                        occlusion.visibility(view, x, y, z, 0.5D, eyeY, 0.5D));
                }
            }
        }
        return verdicts;
    }

    private static void fillPlane(FakeWorldView view, int x, int minY, int maxY, int minZ, int maxZ, FakeBlock material) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                view.put(x, y, z, material);
            }
        }
    }

    private static ProjectorViewOcclusion<FakeBlock> occlusion() {
        return new ProjectorViewOcclusion<FakeBlock>(new FakeBlocks(data -> data != null
            && (data == FakeBlock.STONE || data == FakeBlock.DEEPSLATE)));
    }

    private static void beginPass(ProjectorViewOcclusion<FakeBlock> occlusion) {
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W);
    }

    private enum FakeBlock {
        AIR, STONE, DEEPSLATE, GLASS, WATER, OAK_SLAB
    }

    private static FakeBlock blockData(FakeBlock material) {
        return material;
    }

    private record FakeBlocks(Predicate<FakeBlock> occluding) implements BlockStates<FakeBlock, FakeBlock> {
        @Override
        public FakeBlock air() {
            return FakeBlock.AIR;
        }

        @Override
        public FakeBlock occluded() {
            return FakeBlock.STONE;
        }

        @Override
        public boolean isOccluded(FakeBlock block) {
            return false;
        }

        @Override
        public FakeBlock material(FakeBlock block) {
            return block;
        }

        @Override
        public String materialName(FakeBlock material) {
            return material.name();
        }

        @Override
        public boolean blockEntityCandidate(FakeBlock material) {
            return false;
        }

        @Override
        public boolean isAir(FakeBlock material) {
            return material == FakeBlock.AIR;
        }

        @Override
        public boolean isOccluding(FakeBlock material) {
            throw new UnsupportedOperationException("ray occlusion reads occludes");
        }

        @Override
        public boolean occludes(FakeBlock block) {
            return block != null && occluding.test(block);
        }

        @Override
        public boolean requiresTransform(FakeBlock block) {
            return false;
        }

        @Override
        public FakeBlock transform(FakeBlock block, AxisPermutation permutation) {
            return block;
        }

        @Override
        public StateProperties properties(FakeBlock block) {
            return StateProperties.EMPTY;
        }

        @Override
        public FakeBlock withProperties(FakeBlock block, StateProperties properties) {
            return block;
        }
    }

    private static final class CountingLongOpenHashSet extends LongOpenHashSet {
        private int containsCalls;

        @Override
        public boolean contains(long key) {
            containsCalls++;
            return super.contains(key);
        }

        private int containsCalls() {
            return containsCalls;
        }

        private void resetContainsCalls() {
            containsCalls = 0;
        }
    }

    private static final class FakeWorldView implements BlockView<FakeBlock> {
        private final Map<String, FakeBlock> blocks = new HashMap<String, FakeBlock>();
        private final Map<String, Integer> reads = new HashMap<String, Integer>();
        private final Map<String, Boolean> unknown = new HashMap<String, Boolean>();
        private final Map<String, Boolean> requested = new HashMap<String, Boolean>();
        private final FakeBlock air = blockData(FakeBlock.AIR);
        private int fillXPlane = Integer.MIN_VALUE;
        private long revision;
        private int revisionReads;
        private int revisionChangeRead = Integer.MAX_VALUE;

        private void put(int x, int y, int z, FakeBlock material) {
            blocks.put(key(x, y, z), blockData(material));
        }

        private void unknown(int x, int y, int z) {
            unknown.put(key(x, y, z), Boolean.TRUE);
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
        public FakeBlock sampleBlockData(int x, int y, int z) {
            String key = key(x, y, z);
            reads.merge(key, Integer.valueOf(1), Integer::sum);
            if (unknown.containsKey(key)) {
                return null;
            }
            if (x == fillXPlane) {
                return blockData(FakeBlock.STONE);
            }
            return blocks.getOrDefault(key, air);
        }

        @Override
        public boolean isChunkReady(int x, int z) {
            return true;
        }

        @Override
        public long getRevision() {
            revisionReads++;
            if (revisionReads == revisionChangeRead) {
                revision++;
            }
            return revision;
        }

        @Override
        public void requestChunk(int x, int z) {
            requested.put(x + ":" + z, Boolean.TRUE);
        }

        private boolean wasRead(int x, int y, int z) {
            return reads.containsKey(key(x, y, z));
        }

        private boolean wasRequested(int x, int z) {
            return requested.containsKey(x + ":" + z);
        }

        private int revisionReads() {
            return revisionReads;
        }

        private void changeRevisionOnRead(int read) {
            revisionChangeRead = read;
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}
