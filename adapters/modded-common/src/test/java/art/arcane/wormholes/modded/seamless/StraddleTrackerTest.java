package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StraddleTrackerTest extends MinecraftTestBase {
    private static final Frame NORTH = Frame.canonical(Face.N);

    @Test
    public void playerBoxTouchingTheApertureFootprintIsRegistered() {
        ApertureCells aperture = aperture(0, 64, 0);
        Box standing = new Box(0.2D, 0.8D, 64.0D, 65.8D, 0.7D, 1.3D);

        assertTrue(StraddleTracker.qualifies(StraddleTracker.stretched(standing, zero(), zero()), aperture));
        assertFalse(StraddleTracker.qualifies(StraddleTracker.stretched(shifted(standing, 0, 0, 3), zero(), zero()), aperture));
    }

    @Test
    public void velocityAndThePreviousTickStretchTheBoxForFastMovers() {
        ApertureCells aperture = aperture(0, 64, 0);
        Box ahead = shifted(new Box(0.2D, 0.8D, 64.0D, 65.8D, 0.7D, 1.3D), 0, 0, 1.5D);

        assertFalse(StraddleTracker.qualifies(StraddleTracker.stretched(ahead, zero(), zero()), aperture));
        assertTrue(StraddleTracker.qualifies(StraddleTracker.stretched(ahead, new Vec3d(0, 0, -1.5D), zero()), aperture));
        assertTrue(StraddleTracker.qualifies(StraddleTracker.stretched(ahead, zero(), new Vec3d(0, 0, -1.5D)), aperture));
    }

    @Test
    public void straddleKeepsTheEyeSideAndExcludesOnlyShapesFullyBehindThePlane() {
        ApertureCells source = aperture(0, 64, 0);
        ApertureCells destination = aperture(100, 64, 100);
        StraddleTracker.Straddle straddle = StraddleTracker.create(new StraddleTracker.Endpoint(source, NORTH, source.getApertureCenter(), null),
            new StraddleTracker.Endpoint(destination, NORTH, destination.getApertureCenter(), null), null, new Vec3d(0.5D, 65.6D, 1.2D), 1.0D);

        assertFalse(straddle.frontSide());
        List<VoxelShape> shapes = new ArrayList<>();
        shapes.add(Shapes.block().move(0, 64, -1));
        shapes.add(Shapes.block().move(0, 64, 1));
        shapes.add(Shapes.block().move(0, 64, 0));
        shapes.add(Shapes.block().move(5, 64, -1));
        int kept = 0;
        for (VoxelShape ignored : StraddleCollision.thisSide(shapes, straddle)) {
            kept++;
        }
        assertEquals(3, kept);
    }

    @Test
    public void aStraddleIsKeptWhileItsPortalsLevelAndEyeSideAreUnchanged() {
        ApertureCells source = aperture(0, 64, 0);
        ApertureCells destination = aperture(100, 64, 100);
        Level level = mock(Level.class);
        StraddleTracker.Endpoint from = new StraddleTracker.Endpoint(source, NORTH, source.getApertureCenter(), null);
        StraddleTracker.Endpoint to = new StraddleTracker.Endpoint(destination, NORTH, destination.getApertureCenter(), null);
        StraddleTracker.Straddle straddle = StraddleTracker.create(from, to, level, new Vec3d(0.5D, 65.6D, 1.2D), 1.0D);

        assertTrue(straddle.matches(new StraddleTracker.Endpoint(source, NORTH, source.getApertureCenter(), null), to, level, new Vec3d(0.6D, 65.6D, 1.1D)));
        assertFalse(straddle.matches(from, to, level, new Vec3d(0.5D, 65.6D, -0.4D)));
        assertFalse(straddle.matches(new StraddleTracker.Endpoint(aperture(0, 64, 0), NORTH, source.getApertureCenter(), null), to, level,
            new Vec3d(0.5D, 65.6D, 1.2D)));
        assertFalse(straddle.matches(from, to, mock(Level.class), new Vec3d(0.5D, 65.6D, 1.2D)));
        assertFalse(straddle.matches(from, new StraddleTracker.Endpoint(destination, NORTH, new Vec3d(101.5D, 65.0D, 100.5D), null), level,
            new Vec3d(0.5D, 65.6D, 1.2D)));
    }

    @Test
    public void reverseApproachUsesTheOtherSideOfThePlane() {
        ApertureCells source = aperture(0, 64, 0);
        StraddleTracker.Straddle straddle = StraddleTracker.create(new StraddleTracker.Endpoint(source, NORTH, source.getApertureCenter(), null),
            new StraddleTracker.Endpoint(source, NORTH, source.getApertureCenter(), null), null, new Vec3d(0.5D, 65.6D, -0.4D), 1.0D);

        assertTrue(straddle.frontSide());
        assertTrue(straddle.excludes(0, 64, 1, 1, 65, 2));
        assertFalse(straddle.excludes(0, 64, -1, 1, 65, 0));
    }

    @Test
    public void aGroundedWalkerReachingThePlaneIsNotStoppedByTheDestinationFloor() {
        ApertureCells source = aperture(0, 64, 0);
        ApertureCells destination = aperture(100, 64, 100);
        Level destinationLevel = mock(Level.class);
        when(destinationLevel.hasChunksAt(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
        when(destinationLevel.getBlockCollisions(isNull(), any(AABB.class)))
            .thenReturn(List.of(Shapes.block().move(100, 63, 100), Shapes.block().move(100, 63, 101)));
        StraddleTracker.Straddle straddle = StraddleTracker.create(new StraddleTracker.Endpoint(source, NORTH, source.getApertureCenter(), null),
            new StraddleTracker.Endpoint(destination, NORTH, destination.getApertureCenter(), null), destinationLevel, new Vec3d(0.5D, 65.62D, 0.8D), 1.0D);
        Entity walker = mock(Entity.class);
        when(walker.getBoundingBox()).thenReturn(new AABB(0.2D, 64.0D, 0.5D, 0.8D, 65.8D, 1.1D));
        Vec3 grounded = new Vec3(0.0D, 0.0D, -0.1D);

        assertEquals(grounded, StraddleCollision.otherSide(walker, straddle, grounded));
    }

    private static ApertureCells aperture(int x, int y, int z) {
        ApertureCells cells = new ApertureCells();
        cells.setBlocks(List.of(new Vec3d(x, y, z), new Vec3d(x, y + 1, z)));
        return cells;
    }

    private static Box shifted(Box box, double x, double y, double z) {
        return new Box(box.getXa() + x, box.getXb() + x, box.getYa() + y, box.getYb() + y, box.getZa() + z, box.getZb() + z);
    }

    private static Vec3d zero() {
        return new Vec3d(0, 0, 0);
    }
}
