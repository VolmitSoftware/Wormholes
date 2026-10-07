package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
        StraddleTracker.Straddle straddle = StraddleTracker.create(new StraddleTracker.Endpoint(source, NORTH, source.getApertureCenter()),
            new StraddleTracker.Endpoint(destination, NORTH, destination.getApertureCenter()), null, new Vec3d(0.5D, 65.6D, 1.2D));

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
    public void reverseApproachUsesTheOtherSideOfThePlane() {
        ApertureCells source = aperture(0, 64, 0);
        StraddleTracker.Straddle straddle = StraddleTracker.create(new StraddleTracker.Endpoint(source, NORTH, source.getApertureCenter()),
            new StraddleTracker.Endpoint(source, NORTH, source.getApertureCenter()), null, new Vec3d(0.5D, 65.6D, -0.4D));

        assertTrue(straddle.frontSide());
        assertTrue(straddle.excludes(0, 64, 1, 1, 65, 2));
        assertFalse(straddle.excludes(0, 64, -1, 1, 65, 0));
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
