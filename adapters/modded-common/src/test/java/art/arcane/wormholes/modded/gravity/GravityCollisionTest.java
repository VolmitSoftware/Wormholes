package art.arcane.wormholes.modded.gravity;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class GravityCollisionTest extends MinecraftTestBase {
    private static final Vec3 ORIGIN = new Vec3(37.0D, 73.0D, -19.0D);
    private static final AABB BODY = new AABB(-0.3D, 0.0D, -0.3D, 0.3D, 1.8D, 0.3D);
    private static final AABB FLOOR = new AABB(-10.0D, -1.0D, -10.0D, 10.0D, 0.0D, 10.0D);

    @Test
    public void allRotationsLandOnTheirLocalFloorAndRetainTangentMovement() {
        for (int index = 0; index < 48; index++) {
            AxisPermutation orientation = AxisPermutation.ofIndex(index);
            if (orientation.reflects()) {
                continue;
            }
            GravityFrame frame = MinecraftGravity.fromIndex(index);
            Vec3 resolved = resolve(frame, new Vec3(0.2D, -0.4D, -0.1D), List.of(FLOOR), 0.0F, false);
            assertVector(new Vec3(0.2D, 0.0D, -0.1D), MinecraftGravity.local(frame, resolved));
        }
    }

    @Test
    public void allRotationsStopAtAStandingHeightObstacle() {
        AABB obstacle = new AABB(0.6D, 0.0D, -1.0D, 2.0D, 3.0D, 1.0D);
        for (int index = 0; index < 48; index++) {
            AxisPermutation orientation = AxisPermutation.ofIndex(index);
            if (orientation.reflects()) {
                continue;
            }
            GravityFrame frame = MinecraftGravity.fromIndex(index);
            Vec3 resolved = resolve(frame, new Vec3(1.0D, -0.08D, 0.0D), List.of(FLOOR, obstacle), 0.6F, true);
            assertVector(new Vec3(0.3D, 0.0D, 0.0D), MinecraftGravity.local(frame, resolved));
        }
    }

    @Test
    public void allRotationsStepOntoAHalfBlockFromTheirLocalFloor() {
        AABB slab = new AABB(0.6D, 0.0D, -1.0D, 2.0D, 0.5D, 1.0D);
        for (int index = 0; index < 48; index++) {
            AxisPermutation orientation = AxisPermutation.ofIndex(index);
            if (orientation.reflects()) {
                continue;
            }
            GravityFrame frame = MinecraftGravity.fromIndex(index);
            Vec3 resolved = resolve(frame, new Vec3(1.0D, -0.08D, 0.0D), List.of(FLOOR, slab), 0.6F, true);
            assertVector(new Vec3(1.0D, 0.5D, 0.0D), MinecraftGravity.local(frame, resolved));
        }
    }

    @Test
    public void aLowCeilingPreventsSidewaysStepUp() {
        GravityFrame frame = MinecraftGravity.fromIndex(AxisPermutation.of(Face.D, Face.E, Face.S).index());
        AABB slab = new AABB(0.6D, 0.0D, -1.0D, 2.0D, 0.5D, 1.0D);
        AABB ceiling = new AABB(-2.0D, 2.0D, -2.0D, 2.0D, 3.0D, 2.0D);
        Vec3 resolved = resolve(frame, new Vec3(1.0D, -0.08D, 0.0D), List.of(FLOOR, slab, ceiling), 0.6F, true);
        assertVector(new Vec3(0.3D, 0.0D, 0.0D), MinecraftGravity.local(frame, resolved));
    }

    @Test
    public void gravityFramesRejectReflectionsAndUseCachedWorldBasis() {
        assertSame(GravityFrame.WORLD, MinecraftGravity.fromIndex(AxisPermutation.IDENTITY.index()));
        assertThrows(IllegalArgumentException.class,
            () -> MinecraftGravity.fromIndex(AxisPermutation.of(Face.W, Face.U, Face.S).index()));
    }

    @Test
    public void unboundedWorldBorderCoordinatesRemainValidAfterAxisRotation() {
        GravityFrame frame = new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S));
        AABB border = new AABB(Double.NEGATIVE_INFINITY, -1.0D, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, 0.0D, Double.POSITIVE_INFINITY);
        AABB rotated = MinecraftGravity.transformBox(frame, border, Vec3.ZERO, false);
        assertEquals(-1.0D, rotated.minX, 0.0D);
        assertEquals(0.0D, rotated.maxX, 0.0D);
        assertEquals(Double.NEGATIVE_INFINITY, rotated.minY, 0.0D);
        assertEquals(Double.POSITIVE_INFINITY, rotated.maxY, 0.0D);
        assertEquals(Double.NEGATIVE_INFINITY, rotated.minZ, 0.0D);
        assertEquals(Double.POSITIVE_INFINITY, rotated.maxZ, 0.0D);
    }

    private static Vec3 resolve(GravityFrame frame, Vec3 movement, List<AABB> localShapes, float step, boolean grounded) {
        List<VoxelShape> shapes = localShapes.stream().map(box -> Shapes.create(world(frame, box))).toList();
        return GravityCollision.resolve(frame, ORIGIN, world(frame, BODY), MinecraftGravity.world(frame, movement), shapes, step, grounded);
    }

    private static AABB world(GravityFrame frame, AABB box) {
        return MinecraftGravity.transformBox(frame, box, Vec3.ZERO, false).move(ORIGIN);
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1.0E-6D);
        assertEquals(expected.y, actual.y, 1.0E-6D);
        assertEquals(expected.z, actual.z, 1.0E-6D);
    }
}
