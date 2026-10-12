package art.arcane.wormholes.modded;

import art.arcane.optics.aperture.SizeRatio;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftSeamlessGravityAdmissionTest extends MinecraftTestBase {
    @Test
    public void halfScaleQuarterTurnCanLandAgainstAWestWallThatIntersectsTheDepartureBody() {
        LivingEntity player = worldGravity(mock(LivingEntity.class));
        AttributeInstance scale = new AttributeInstance(Attributes.SCALE, changed -> {
        });
        when(player.getAttribute(Attributes.SCALE)).thenReturn(scale);
        when(player.getPose()).thenReturn(Pose.STANDING);
        EntityDimensions dimensions = EntityDimensions.scalable(0.6F, 1.8F);
        when(player.getDimensions(Pose.STANDING)).thenReturn(dimensions);
        MinecraftPortal source = mock(MinecraftPortal.class);
        when(source.isGravityRotationEnabled()).thenReturn(true);
        AxisPermutation rotation = AxisPermutation.of(Face.D, Face.E, Face.S);
        MinecraftPortalRegistry.Passage passage = new MinecraftPortalRegistry.Passage(
            Similarity.of(OpticTransform.of(rotation, 0.0D, 0.0D, 0.0D), 0.5D), ScaleRule.ratio(0.25D, 4.0D), new SizeRatio(0.5D, true));
        Vec3d arrival = new Vec3d(440.0D, 101.0D, 400.5D);
        AABB wall = new AABB(439.0D, 94.0D, 388.0D, 440.0D, 114.0D, 414.0D);
        AABB departureBody = dimensions.makeBoundingBox(new Vec3(arrival.x(), arrival.y(), arrival.z()));

        AABB admittedBody = MinecraftPortalRegistry.arrivalBounds(player, source, passage, arrival);

        assertTrue(wall.intersects(departureBody));
        assertFalse(wall.intersects(admittedBody));
        assertEquals(wall.maxX, admittedBody.minX, 0.0D);
        assertEquals(0.9D, admittedBody.getXsize(), 1.0E-6D);
        assertEquals(0.3D, admittedBody.getYsize(), 1.0E-6D);
        assertEquals(0.3D, admittedBody.getZsize(), 1.0E-6D);
        assertEquals(1.0D, scale.getValue(), 0.0D);
    }
}
