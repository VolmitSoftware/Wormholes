package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.junit.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GravityCameraScaleHistoryTest extends MinecraftTestBase {
    @Test
    public void shrinkAndRollbackRescaleBothEyeHistorySamplesBeforeAlignmentOnlyOnce() throws ReflectiveOperationException {
        GravityCameraMixin camera = new GravityCameraMixin() { };
        LivingEntity player = mock(LivingEntity.class);
        when(player.getScale()).thenReturn(3.0F);
        when(player.getEyeHeight()).thenReturn(4.86F);
        entity(camera, player);
        tick(camera);
        height(camera, "eyeHeightOld", 4.3F);
        height(camera, "eyeHeight", 4.6F);

        when(player.getScale()).thenReturn(1.0F);
        when(player.getEyeHeight()).thenReturn(1.62F);
        align(camera);
        assertEquals(4.3F / 3.0F, height(camera, "eyeHeightOld"), 1.0E-6F);
        assertEquals(4.6F / 3.0F, height(camera, "eyeHeight"), 1.0E-6F);
        tick(camera);
        align(camera);
        assertEquals(4.3F / 3.0F, height(camera, "eyeHeightOld"), 1.0E-6F);
        assertEquals(4.6F / 3.0F, height(camera, "eyeHeight"), 1.0E-6F);

        when(player.getScale()).thenReturn(3.0F);
        tick(camera);
        assertEquals(4.3F, height(camera, "eyeHeightOld"), 1.0E-6F);
        assertEquals(4.6F, height(camera, "eyeHeight"), 1.0E-6F);
    }

    @Test
    public void poseEyeHeightChangesKeepTheirNativeInterpolationWhenScaleIsUnchanged() throws ReflectiveOperationException {
        GravityCameraMixin camera = new GravityCameraMixin() { };
        LivingEntity player = mock(LivingEntity.class);
        when(player.getScale()).thenReturn(1.0F);
        when(player.getEyeHeight()).thenReturn(1.62F);
        entity(camera, player);
        align(camera);
        height(camera, "eyeHeightOld", 1.6F);
        height(camera, "eyeHeight", 1.5F);

        when(player.getEyeHeight()).thenReturn(1.27F);
        tick(camera);
        align(camera);
        assertEquals(1.6F, height(camera, "eyeHeightOld"), 0.0F);
        assertEquals(1.5F, height(camera, "eyeHeight"), 0.0F);
    }

    @Test
    public void changingCameraEntityInitializesItsOwnEyeHistoryWithoutUsingThePreviousScale() throws ReflectiveOperationException {
        GravityCameraMixin camera = new GravityCameraMixin() { };
        LivingEntity player = mock(LivingEntity.class);
        when(player.getScale()).thenReturn(3.0F);
        when(player.getEyeHeight()).thenReturn(4.86F);
        entity(camera, player);
        tick(camera);

        LivingEntity small = mock(LivingEntity.class);
        when(small.getScale()).thenReturn(0.25F);
        when(small.getEyeHeight()).thenReturn(0.405F);
        entity(camera, small);
        align(camera);
        assertEquals(0.405F, height(camera, "eyeHeightOld"), 0.0F);
        assertEquals(0.405F, height(camera, "eyeHeight"), 0.0F);

        entity(camera, null);
        tick(camera);
        Entity ordinary = mock(Entity.class);
        when(ordinary.getEyeHeight()).thenReturn(0.7F);
        entity(camera, ordinary);
        tick(camera);
        assertEquals(0.7F, height(camera, "eyeHeightOld"), 0.0F);
        assertEquals(0.7F, height(camera, "eyeHeight"), 0.0F);
    }

    @Test
    public void tickSeesTheEffectiveNativeScaleIncludingClampsAndOtherModifiers() throws ReflectiveOperationException {
        GravityCameraMixin camera = new GravityCameraMixin() { };
        LivingEntity player = mock(LivingEntity.class);
        when(player.getScale()).thenReturn(2.0F);
        when(player.getEyeHeight()).thenReturn(3.24F);
        entity(camera, player);
        align(camera);
        height(camera, "eyeHeightOld", 3.0F);
        height(camera, "eyeHeight", 3.2F);

        when(player.getScale()).thenReturn(0.25F);
        tick(camera);
        assertEquals(0.375F, height(camera, "eyeHeightOld"), 0.0F);
        assertEquals(0.4F, height(camera, "eyeHeight"), 0.0F);
    }

    private static void entity(GravityCameraMixin camera, Entity entity) throws ReflectiveOperationException {
        Field field = GravityCameraMixin.class.getDeclaredField("entity");
        field.setAccessible(true);
        field.set(camera, entity);
    }

    private static void height(GravityCameraMixin camera, String name, float value) throws ReflectiveOperationException {
        Field field = GravityCameraMixin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setFloat(camera, value);
    }

    private static float height(GravityCameraMixin camera, String name) throws ReflectiveOperationException {
        Field field = GravityCameraMixin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(camera);
    }

    private static void tick(GravityCameraMixin camera) throws ReflectiveOperationException {
        Method method = GravityCameraMixin.class.getDeclaredMethod("wormholes$beforeEyeHeightTick", CallbackInfo.class);
        method.setAccessible(true);
        method.invoke(camera, new CallbackInfo("tick", false));
    }

    private static void align(GravityCameraMixin camera) throws ReflectiveOperationException {
        Method method = GravityCameraMixin.class.getDeclaredMethod("wormholes$beginGravityAlignment", float.class, CallbackInfo.class);
        method.setAccessible(true);
        method.invoke(camera, 0.5F, new CallbackInfo("alignWithEntity", false));
    }
}
