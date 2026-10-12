package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class GravitySuffocationTest extends MinecraftTestBase {
    private static final Vec3 FEET = new Vec3(10.0D, 70.0D, -3.0D);
    private static final EntityDimensions DIMENSIONS = EntityDimensions.scalable(0.6F, 1.8F);

    @Test
    public void everyGravityBasisKeepsFloorContactFromCreatingAnEscapeOrAnUpwardImpulse() {
        for (int index = 0; index < 48; index++) {
            if (AxisPermutation.ofIndex(index).reflects()) {
                continue;
            }
            GravityFrame gravity = MinecraftGravity.fromIndex(index);
            AABB body = MinecraftGravity.bounds(gravity, DIMENSIONS, FEET);
            AABB floor = MinecraftGravity.transformBox(gravity, new AABB(-5.0D, -1.0D, -5.0D, 5.0D, 0.0D, 5.0D), Vec3.ZERO, false).move(FEET);
            Vec3 velocity = MinecraftGravity.world(gravity, new Vec3(0.03D, -0.0784D, 0.07D));
            for (double x : new double[] {-0.21D, 0.21D}) {
                for (double z : new double[] {-0.21D, 0.21D}) {
                    Vec3 escaped = GravitySuffocation.escape(new GravitySuffocation.Probe(gravity, FEET, body, new Vec3(x, 0.0D, z)),
                        velocity, floor::intersects);
                    assertSame(velocity, escaped);
                }
            }
        }
    }

    @Test
    public void everyGravityBasisEscapesTheClosestOpenTangentCellWithoutChangingJumpVelocity() {
        for (int index = 0; index < 48; index++) {
            if (AxisPermutation.ofIndex(index).reflects()) {
                continue;
            }
            GravityFrame gravity = MinecraftGravity.fromIndex(index);
            AABB body = MinecraftGravity.bounds(gravity, DIMENSIONS, FEET);
            AABB obstruction = MinecraftGravity.transformBox(gravity, new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D), Vec3.ZERO, false).move(FEET);
            Vec3 velocity = MinecraftGravity.world(gravity, new Vec3(0.025D, 0.42D, 0.045D));
            Vec3 escaped = GravitySuffocation.escape(new GravitySuffocation.Probe(gravity, FEET, body, new Vec3(0.1D, 0.0D, 0.2D)),
                velocity, obstruction::intersects);
            Vec3 local = MinecraftGravity.local(gravity, escaped);
            assertEquals(-0.1D, local.x, 1.0E-12D);
            assertEquals(0.42D, local.y, 1.0E-12D);
            assertEquals(0.045D, local.z, 1.0E-12D);
        }
    }

    @Test
    public void aFullyBlockedBodyKeepsItsExistingVelocity() {
        GravityFrame gravity = GravityFrame.WORLD;
        Vec3 velocity = new Vec3(0.025D, 0.42D, 0.045D);
        assertSame(velocity, GravitySuffocation.escape(new GravitySuffocation.Probe(gravity, FEET,
            MinecraftGravity.bounds(gravity, DIMENSIONS, FEET), Vec3.ZERO), velocity, box -> true));
    }
}
