package art.arcane.wormholes.modded.gravity;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftGravityArrivalBoundsTest extends MinecraftTestBase {
    @Test
    public void projectedArrivalUsesTheNativeScaleClampWithoutMutatingTheAttribute() {
        AttributeInstance scale = new AttributeInstance(Attributes.SCALE, changed -> {
        });
        scale.setBaseValue(8.0D);
        scale.addPermanentModifier(new AttributeModifier(MinecraftScaleAccess.ID, 3.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        double current = scale.getValue();
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getAttribute(Attributes.SCALE)).thenReturn(scale);
        when(entity.getPose()).thenReturn(Pose.STANDING);
        when(entity.getDimensions(Pose.STANDING)).thenReturn(EntityDimensions.scalable((float) (0.6D * current), (float) (1.8D * current)));
        GravityFrame gravity = new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S));
        AABB arrival = MinecraftGravity.arrivalBounds(entity, new Vec3(5.0D, 6.0D, 7.0D), gravity, 0.5D);
        double target = Attributes.SCALE.value().sanitizeValue(4.0D);
        assertEquals(5.0D, arrival.minX, 1.0E-5D);
        assertEquals(5.0D + 1.8D * target, arrival.maxX, 1.0E-5D);
        assertEquals(0.6D * target, arrival.getYsize(), 1.0E-5D);
        assertEquals(0.6D * target, arrival.getZsize(), 1.0E-5D);
        assertEquals(current, scale.getValue(), 0.0D);
        assertEquals(3.0D, scale.getModifier(MinecraftScaleAccess.ID).amount(), 0.0D);
    }
}
