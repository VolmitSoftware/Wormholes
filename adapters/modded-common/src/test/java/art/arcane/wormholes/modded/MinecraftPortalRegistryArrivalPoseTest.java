package art.arcane.wormholes.modded;

import art.arcane.optics.aperture.SizeRatio;
import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.PoseTransform;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalRegistryArrivalPoseTest extends MinecraftTestBase {
    private static final Vec3d ENTRY = new Vec3d(10.5D, 64.0D, 20.5D);
    private static final Vec3d EXIT = new Vec3d(-40.5D, 90.0D, 300.5D);
    private static final MomentumRule PRESERVE = new MomentumRule(MomentumRule.Mode.PRESERVE, 1.0D, 0.0D, null);

    @Test
    public void aMobLookingStraightDownIntoAnUpwardExitArrivesLookingStraightUpWithItsBodyUnderItsHead() {
        LivingEntity mob = mock(LivingEntity.class);
        when(mob.getYRot()).thenReturn(30.0F);
        when(mob.getXRot()).thenReturn(90.0F);
        mob.yRotO = 30.0F;
        mob.xRotO = 89.0F;
        mob.yBodyRot = 10.0F;
        mob.yBodyRotO = 10.0F;
        mob.yHeadRot = 30.0F;
        mob.yHeadRotO = 30.0F;
        PlaneCrossing crossing = new PlaneCrossing(Frame.canonical(Face.U), ENTRY, ENTRY, new Vec3d(0.0D, -0.4D, 0.0D),
            Angles.direction(30.0F, 90.0F), true);
        Frame exit = Frame.canonical(Face.D);

        Pose landed = PoseTransform.arrive(MinecraftArrivalPose.departure(mob, crossing), crossing, crossing.toward(exit, EXIT, 1.0D), exit,
            OrientationRule.FRAME, false, PRESERVE, 0.0D);
        LookTransfer predicted = ArrivalOrientation.transfer(crossing, LookTransfer.cameraUp(30.0F, 90.0F), exit, OrientationRule.FRAME, false);
        MinecraftArrivalPose.apply(mob, landed);

        verify(mob).setXRot(-90.0F);
        verify(mob).setYRot(landed.yaw());
        assertEquals(predicted.yaw(), Angles.unwrap(landed.yaw(), predicted.yaw()), 1.0E-3F);
        assertEquals(-90.0F, mob.xRotO, 0.0F);
        assertEquals(landed.previousYaw(), mob.yRotO, 0.0F);
        assertEquals(20.0F, Math.abs(Angles.unwrap(mob.yBodyRot - landed.yaw(), 0.0F)), 1.0E-3F);
        verify(mob).setYHeadRot(landed.headYaw());
        assertEquals(landed.previousHeadYaw(), mob.yHeadRotO, 0.0F);
        assertTrue(Math.abs(landed.yaw() - landed.previousYaw()) <= 1.0F);
    }

    @Test
    public void lookingStraightDownIntoAWallExitArrivesLevelAlongTheExit() {
        Entity armorStand = mock(Entity.class);
        when(armorStand.getYRot()).thenReturn(0.0F);
        when(armorStand.getXRot()).thenReturn(90.0F);
        armorStand.yRotO = 0.0F;
        armorStand.xRotO = 90.0F;
        PlaneCrossing crossing = new PlaneCrossing(Frame.canonical(Face.U), ENTRY, ENTRY, new Vec3d(0.0D, -0.4D, 0.0D),
            Angles.direction(0.0F, 90.0F), true);
        Frame exit = Frame.canonical(Face.N);

        Pose landed = PoseTransform.arrive(MinecraftArrivalPose.departure(armorStand, crossing), crossing, crossing.toward(exit, EXIT, 1.0D), exit,
            OrientationRule.FRAME, false, PRESERVE, 0.0D);
        Vec3d look = Angles.direction(landed.yaw(), landed.pitch());

        assertEquals(0.0F, landed.pitch(), 1.0E-3F);
        assertEquals(0.0D, look.x(), 1.0E-5D);
        assertEquals(1.0D, look.z(), 1.0E-5D);
        assertEquals(landed.yaw(), landed.bodyYaw(), 1.0E-3F);
    }

    @Test
    public void wireCrossingsWithoutAnEntityUseTheLookVector() {
        PlaneCrossing crossing = new PlaneCrossing(Frame.canonical(Face.N), ENTRY, ENTRY, new Vec3d(0.0D, 0.0D, -0.4D),
            Angles.direction(30.0F, 20.0F), true);

        Pose departed = MinecraftArrivalPose.departure(crossing);

        assertEquals(30.0F, Angles.unwrap(departed.yaw(), 30.0F), 1.0E-3F);
        assertEquals(20.0F, departed.pitch(), 1.0E-3F);
        assertEquals(ENTRY, departed.position());
    }

    @Test
    public void ratioPassagesScaleAboutTheSourceOrigin() {
        Frame north = Frame.canonical(Face.N);
        SizeRatio ratio = new SizeRatio(3.0D, true);
        Similarity toward = new PlaneCrossing(north, ENTRY, ENTRY, new Vec3d(0.0D, 0.0D, -0.4D), Angles.direction(180.0F, 0.0F), true)
            .toward(north, EXIT, ScaleRule.ratio(0.25D, 4.0D).travelScale(ratio));

        Vec3d mapped = toward.point(ENTRY.add(new Vec3d(1.0D, 0.5D, 0.0D)));

        assertEquals(EXIT.x() + 3.0D, mapped.x(), 1.0E-9D);
        assertEquals(EXIT.y() + 1.5D, mapped.y(), 1.0E-9D);
        assertEquals(EXIT.z(), mapped.z(), 1.0E-9D);
    }
}
