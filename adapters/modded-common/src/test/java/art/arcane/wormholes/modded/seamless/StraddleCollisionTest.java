package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StraddleCollisionTest extends MinecraftTestBase {
    private static final double EPSILON = 1.0E-9D;
    private static final Frame DOWN = Frame.canonical(Face.D);

    @Test
    public void touchingEitherBoundaryOfTheHorizontalPortalCellArmsTheStraddle() {
        ApertureCells aperture = cells(new Vec3d(0, 70, 0));
        assertTrue(StraddleTracker.qualifies(new Box(0.2D, 0.8D, 71.0D, 72.8D, 0.2D, 0.8D), aperture));
        assertTrue(StraddleTracker.qualifies(new Box(0.2D, 0.8D, 68.2D, 70.0D, 0.2D, 0.8D), aperture));
        assertFalse(StraddleTracker.qualifies(new Box(0.2D, 0.8D, 71.001D, 72.8D, 0.2D, 0.8D), aperture));
    }

    @Test
    public void horizontalOpeningRemovesItsPlaneCellAndBackingFromEitherFace() {
        ApertureCells aperture = cells(new Vec3d(0, 70, 0));
        for (boolean above : new boolean[]{true, false}) {
            StraddleTracker.Straddle straddle = horizontal(aperture, above, null);
            VoxelShape planeCell = Shapes.block().move(0, 70, 0);
            VoxelShape backing = Shapes.block().move(0, above ? 69 : 71, 0);
            VoxelShape neighbor = Shapes.block().move(1, 70, 0);
            VoxelShape actual = kept(List.of(planeCell, backing, neighbor), straddle);
            assertSameShape(neighbor, actual);
        }
    }

    @Test
    public void partialCollisionOutsideTheOpeningRemainsSolid() {
        ApertureCells aperture = cells(new Vec3d(0, 70, 0));
        StraddleTracker.Straddle straddle = horizontal(aperture, true, null);
        VoxelShape ground = Shapes.create(new AABB(-1, 69, -1, 2, 71, 2));
        VoxelShape opening = Shapes.create(new AABB(0, 69, 0, 1, 71, 1));
        VoxelShape expected = Shapes.join(ground, opening, BooleanOp.ONLY_FIRST);
        VoxelShape actual = kept(List.of(ground), straddle);
        assertFalse(actual.isEmpty());
        assertSameShape(expected, actual);
    }

    @Test
    public void closedCellsInsideASparseApertureRemainSolidOnBothFaces() {
        ApertureCells aperture = cells(new Vec3d(0, 70, 0), new Vec3d(2, 70, 0));
        for (boolean above : new boolean[]{true, false}) {
            StraddleTracker.Straddle straddle = horizontal(aperture, above, null);
            VoxelShape hole = Shapes.block().move(1, 70, 0);
            VoxelShape backingHole = Shapes.block().move(1, above ? 69 : 71, 0);
            VoxelShape actual = kept(List.of(Shapes.block().move(0, 70, 0), hole,
                Shapes.block().move(2, 70, 0), backingHole), straddle);
            assertSameShape(Shapes.or(hole, backingHole), actual);
        }
    }

    @Test
    public void wallOpeningKeepsTheApproachHalfOfItsPlaneCell() {
        ApertureCells aperture = cells(new Vec3d(0, 64, 0), new Vec3d(0, 65, 0));
        Frame frame = Frame.canonical(Face.N);
        StraddleTracker.Endpoint endpoint = new StraddleTracker.Endpoint(aperture, frame, aperture.getApertureCenter(), null);
        StraddleTracker.Straddle straddle = StraddleTracker.create(endpoint, endpoint, null, new Vec3d(0.5D, 65.6D, 1.2D), 1.0D);
        VoxelShape approach = Shapes.block().move(0, 64, 1);
        VoxelShape expected = Shapes.or(approach, Shapes.create(new AABB(0, 64, 0.5D, 1, 65, 1)));
        VoxelShape actual = kept(List.of(Shapes.block().move(0, 64, -1), Shapes.block().move(0, 64, 0), approach), straddle);
        assertSameShape(expected, actual);
    }

    @Test
    public void carvedPortalCellsDoNotSuffocateButAdjacentSourceBlocksDo() {
        Level source = mock(Level.class);
        BlockState solid = mock(BlockState.class);
        when(solid.isSuffocating(eq(source), any(BlockPos.class))).thenReturn(true);
        when(solid.getCollisionShape(eq(source), any(BlockPos.class))).thenReturn(Shapes.block());
        when(source.getBlockState(any(BlockPos.class))).thenReturn(solid);
        StraddleTracker.Straddle straddle = horizontal(cells(new Vec3d(0, 70, 0)), true, null);
        Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(source);
        when(entity.getBbWidth()).thenReturn(0.6F);
        when(entity.getEyePosition()).thenReturn(new Vec3(0.5D, 70.75D, 0.5D));
        assertFalse(StraddleCollision.inWall(entity, straddle));
        when(entity.getEyePosition()).thenReturn(new Vec3(1.5D, 70.75D, 0.5D));
        assertTrue(StraddleCollision.inWall(entity, straddle));
    }

    @Test
    public void aPoseFitsTheCarvedCellButCannotExtendIntoItsSolidNeighbor() {
        Level source = mock(Level.class);
        Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(source);
        when(source.noEntityCollision(eq(entity), any(AABB.class))).thenReturn(true);
        when(source.noBorderCollision(eq(entity), any(AABB.class))).thenReturn(true);
        when(source.getBlockCollisions(eq(entity), any(AABB.class))).thenReturn(List.of(Shapes.block().move(0, 70, 0)));
        StraddleTracker.Straddle straddle = horizontal(cells(new Vec3d(0, 70, 0)), true, null);
        AABB standing = new AABB(0.2D, 70.25D, 0.2D, 0.8D, 72.05D, 0.8D);
        assertTrue(StraddleCollision.fits(entity, standing, straddle));
        when(source.getBlockCollisions(eq(entity), any(AABB.class)))
            .thenReturn(List.of(Shapes.block().move(0, 70, 0), Shapes.block().move(1, 70, 0)));
        assertFalse(StraddleCollision.fits(entity, new AABB(0.8D, 70.25D, 0.2D, 1.4D, 72.05D, 0.8D), straddle));
    }

    @Test
    public void carvingBlocksDoesNotBypassEntityOrWorldBorderPoseCollisions() {
        Level source = mock(Level.class);
        Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(source);
        when(source.getBlockCollisions(eq(entity), any(AABB.class))).thenReturn(List.of(Shapes.block().move(0, 70, 0)));
        when(source.noEntityCollision(eq(entity), any(AABB.class))).thenReturn(true);
        when(source.noBorderCollision(eq(entity), any(AABB.class))).thenReturn(true);
        StraddleTracker.Straddle straddle = horizontal(cells(new Vec3d(0, 70, 0)), true, null);
        AABB standing = new AABB(0.2D, 70.25D, 0.2D, 0.8D, 72.05D, 0.8D);
        assertTrue(StraddleCollision.fits(entity, standing, straddle));
        when(source.noEntityCollision(eq(entity), any(AABB.class))).thenReturn(false);
        assertFalse(StraddleCollision.fits(entity, standing, straddle));
        when(source.noEntityCollision(eq(entity), any(AABB.class))).thenReturn(true);
        when(source.noBorderCollision(eq(entity), any(AABB.class))).thenReturn(false);
        assertFalse(StraddleCollision.fits(entity, standing, straddle));
    }

    @Test
    public void changingTheOpenMaskInvalidatesAnOtherwiseUnchangedStraddle() {
        ApertureCells aperture = cells(new Vec3d(0, 70, 0), new Vec3d(1, 70, 0), new Vec3d(2, 70, 0));
        Vec3d origin = aperture.getApertureCenter();
        StraddleTracker.Endpoint source = new StraddleTracker.Endpoint(aperture, DOWN, origin, null);
        StraddleTracker.Endpoint destination = new StraddleTracker.Endpoint(aperture, DOWN, origin.add(new Vec3d(0, 30, 0)), null);
        Vec3d eye = origin.add(new Vec3d(0, 2, 0));
        StraddleTracker.Straddle straddle = StraddleTracker.create(source, destination, null, eye, 1.0D);
        assertTrue(straddle.matches(source, destination, null, eye));
        aperture.restore(aperture.getArea(), List.of(new Vec3d(0, 70, 0), new Vec3d(2, 70, 0)));
        assertFalse(straddle.matches(source, destination, null, eye));
    }

    @Test
    public void destinationSupportStillStopsDownwardMovement() {
        Level destination = mock(Level.class);
        when(destination.hasChunksAt(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
        when(destination.getBlockCollisions(isNull(), any(AABB.class))).thenReturn(List.of(Shapes.block().move(0, 99, 0)));
        StraddleTracker.Straddle straddle = horizontal(cells(new Vec3d(0, 70, 0)), true, destination);
        Entity entity = mock(Entity.class);
        when(entity.getBoundingBox()).thenReturn(new AABB(0.2D, 70.2D, 0.2D, 0.8D, 72.0D, 0.8D));
        Vec3 actual = StraddleCollision.otherSide(entity, straddle, new Vec3(0, -0.6D, 0));
        assertEquals(0, actual.x, EPSILON);
        assertEquals(-0.2D, actual.y, EPSILON);
        assertEquals(0, actual.z, EPSILON);
    }

    @Test
    public void missingDestinationChunksPushTheTravellerBackToTheSource() {
        Level destination = mock(Level.class);
        StraddleTracker.Straddle straddle = horizontal(cells(new Vec3d(0, 70, 0)), true, destination);
        Entity entity = mock(Entity.class);
        when(entity.getBoundingBox()).thenReturn(new AABB(0.2D, 70.2D, 0.2D, 0.8D, 72.0D, 0.8D));
        Vec3 actual = StraddleCollision.otherSide(entity, straddle, new Vec3(0.2D, -0.6D, 0.1D));
        assertEquals(0.2D, actual.x, EPSILON);
        assertEquals(0.1D, actual.y, EPSILON);
        assertEquals(0.1D, actual.z, EPSILON);
    }

    private static StraddleTracker.Straddle horizontal(ApertureCells aperture, boolean above, Level destination) {
        Vec3d origin = aperture.getApertureCenter();
        StraddleTracker.Endpoint source = new StraddleTracker.Endpoint(aperture, DOWN, origin, null);
        ApertureCells target = cells(new Vec3d(0, 100, 0));
        StraddleTracker.Endpoint exit = new StraddleTracker.Endpoint(target, DOWN,
            new Vec3d(origin.x(), 100.5D, origin.z()), null);
        return StraddleTracker.create(source, exit, destination,
            new Vec3d(origin.x(), above ? 72.0D : 69.0D, origin.z()), 1.0D);
    }

    private static ApertureCells cells(Vec3d... positions) {
        ApertureCells aperture = new ApertureCells();
        aperture.setBlocks(List.of(positions));
        return aperture;
    }

    private static VoxelShape kept(List<VoxelShape> shapes, StraddleTracker.Straddle straddle) {
        VoxelShape kept = Shapes.empty();
        for (VoxelShape shape : StraddleCollision.thisSide(shapes, straddle)) {
            kept = Shapes.or(kept, shape);
        }
        return kept;
    }

    private static void assertSameShape(VoxelShape expected, VoxelShape actual) {
        assertFalse(Shapes.joinIsNotEmpty(expected, actual, BooleanOp.NOT_SAME));
    }
}
