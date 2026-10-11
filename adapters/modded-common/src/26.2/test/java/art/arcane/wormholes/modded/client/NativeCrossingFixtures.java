package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.seamless.CrossingInterpolation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.Vec3;
import org.mockito.verification.VerificationMode;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

final class NativeCrossingFixtures {
    private NativeCrossingFixtures() {
    }

    static InterpolationHandler interpolation() {
        return mock(InterpolationHandler.class, withSettings().extraInterfaces(CrossingInterpolation.class));
    }

    static void target(InterpolationHandler interpolation, Vec3 before, Vec3 after) {
        when(interpolation.position()).thenReturn(before, after);
    }

    static void translation(InterpolationHandler interpolation, VerificationMode mode, Vec3 delta) {
        verify((CrossingInterpolation) interpolation, mode).wormholesTranslate(delta);
    }

    static void noTranslation(InterpolationHandler interpolation) {
        verify((CrossingInterpolation) interpolation, never()).wormholesTranslate(any());
    }

    static void simulated(Entity entity) {
        when(entity.canSimulateMovement()).thenReturn(true);
    }

    static void route(ClientEntityCrossing crossing, Entity entity) {
        crossing.route(entity, Optional.of(new Vec3(1.5D, 97.0D, 1.5D)), Optional.of(90.0F), Optional.of(10.0F));
        verify(entity).moveOrInterpolateTo(Optional.of(new Vec3(1.5D, 77.0D, 1.5D)), Optional.of(90.0F), Optional.of(10.0F));
        crossing.route(entity, Optional.of(new Vec3(1.5D, 96.0D, 1.5D)), Optional.empty(), Optional.empty());
        verify(entity).moveOrInterpolateTo(Optional.of(new Vec3(1.5D, 76.0D, 1.5D)), Optional.empty(), Optional.empty());
    }
}
