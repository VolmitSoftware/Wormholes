package art.arcane.wormholes.modded.gravity;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class MinecraftGravityResetTest extends MinecraftTestBase {
    @Test
    public void returningToWorldGravityPreservesGlobalLookAndWorldMotion() {
        GravityFrame frame = new GravityFrame(AxisPermutation.of(Face.D, Face.E, Face.S));
        Entity entity = mock(Entity.class, withSettings().extraInterfaces(GravityHolder.class));
        when(((GravityHolder) entity).wormholesGravity()).thenReturn(frame, frame, GravityFrame.WORLD);
        when(entity.getYRot()).thenReturn(30.0F);
        when(entity.getXRot()).thenReturn(-15.0F);
        when(entity.level()).thenReturn(mock(Level.class));
        when(entity.position()).thenReturn(new Vec3(7.0D, 70.0D, -3.0D));
        when(entity.getPose()).thenReturn(Pose.STANDING);
        when(entity.getDimensions(Pose.STANDING)).thenReturn(EntityDimensions.scalable(0.6F, 1.8F));
        entity.yRotO = 22.0F;
        entity.xRotO = -13.0F;
        Vec3 expected = MinecraftGravity.world(frame, lookVector(30.0F, -15.0F));
        MinecraftGravity.reset(entity);
        ArgumentCaptor<Float> yaw = ArgumentCaptor.forClass(Float.class);
        ArgumentCaptor<Float> pitch = ArgumentCaptor.forClass(Float.class);
        verify(entity).setYRot(yaw.capture());
        verify(entity).setXRot(pitch.capture());
        Vec3 actual = lookVector(yaw.getValue(), pitch.getValue());
        assertEquals(expected.x, actual.x, 1.0E-4D);
        assertEquals(expected.y, actual.y, 1.0E-4D);
        assertEquals(expected.z, actual.z, 1.0E-4D);
        verify((GravityHolder) entity).wormholesGravity(GravityFrame.WORLD);
        verify(entity, never()).setDeltaMovement(any(Vec3.class));
        verify(entity, never()).setPos(any(Vec3.class));
    }
}
