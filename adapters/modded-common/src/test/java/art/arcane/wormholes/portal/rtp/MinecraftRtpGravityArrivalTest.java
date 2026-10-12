package art.arcane.wormholes.portal.rtp;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.gravity.GravityHolder;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import art.arcane.wormholes.modded.gravity.MinecraftGravityTransit;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static net.minecraft.world.entity.Pose.STANDING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MinecraftRtpGravityArrivalTest extends MinecraftTestBase {
    @Test
    public void worldDownSafetyPreviewAndLookPlanningLeaveTheSourceFrameUntouched() {
        for (int index = 0; index < 48; index++) {
            if (AxisPermutation.ofIndex(index).reflects()) {
                continue;
            }
            GravityFrame gravity = MinecraftGravity.fromIndex(index);
            Entity entity = mock(Entity.class, withSettings().extraInterfaces(GravityHolder.class));
            when(((GravityHolder) entity).wormholesGravity()).thenReturn(gravity);
            when(entity.getYRot()).thenReturn(37.0F);
            when(entity.getXRot()).thenReturn(-21.0F);
            when(entity.position()).thenReturn(new Vec3(10.0D, 64.0D, 20.0D));
            when(entity.getPose()).thenReturn(STANDING);
            EntityDimensions dimensions = EntityDimensions.scalable(1.2F, 3.6F);
            when(entity.getDimensions(STANDING)).thenReturn(dimensions);
            AABB sourceBounds = MinecraftGravity.bounds(entity, entity.position());
            when(entity.getBoundingBox()).thenReturn(sourceBounds);
            Frame portal = Frame.canonical(Face.N);
            Vec3d point = new Vec3d(10.0D, 64.0D, 20.0D);
            Vec3d expected = gravity.toWorld(Angles.direction(37.0F, -21.0F));
            PlaneCrossing crossing = new PlaneCrossing(portal, point, point, new Vec3d(0.0D, 0.0D, -0.4D), expected, true);
            TravelMessage.ArrivalRules rules = TravelMessage.ArrivalRules.of(OrientationPolicy.LOOK,
                MomentumPolicy.of(MomentumPolicy.Mode.PRESERVE), false, 0.0D, ScaleRule.OFF);
            Pose arrival = MinecraftRtpRuntime.arrivalPose(entity,
                new MinecraftGravityTransit.Passage(crossing, crossing.toward(portal, point, 1.0D), portal, rules));
            Vec3d actual = Angles.direction(arrival.yaw(), arrival.pitch());
            assertEquals(expected.x(), actual.x(), 1.0E-5D);
            assertEquals(expected.y(), actual.y(), 1.0E-5D);
            assertEquals(expected.z(), actual.z(), 1.0E-5D);
            RtpValidationRequest.EntityEnvelope envelope = MinecraftRtpRuntime.envelope(entity);
            assertEquals(-0.6D, envelope.minimumXOffset(), 1.0E-5D);
            assertEquals(0.6D, envelope.maximumXOffset(), 1.0E-5D);
            assertEquals(0.0D, envelope.minimumYOffset(), 0.0D);
            assertEquals(3.6D, envelope.maximumYOffset(), 1.0E-5D);
            verify((GravityHolder) entity, never()).wormholesGravity(any(GravityFrame.class));
        }
    }
}
