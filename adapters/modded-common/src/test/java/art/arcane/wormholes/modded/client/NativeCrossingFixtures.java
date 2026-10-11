package art.arcane.wormholes.modded.client;

import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.MoveSimulationType;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.phys.Vec3;
import org.mockito.verification.VerificationMode;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class NativeCrossingFixtures {
    private NativeCrossingFixtures() {
    }

    static InterpolationHandler interpolation() {
        return mock(InterpolationHandler.class);
    }

    static void target(InterpolationHandler interpolation, Vec3 before, Vec3 after) {
        when(interpolation.target()).thenReturn(PositionAndRotation.of(before, 0.0F, 0.0F), PositionAndRotation.of(after, 0.0F, 0.0F));
    }

    static void translation(InterpolationHandler interpolation, VerificationMode mode, Vec3 delta) {
        verify(interpolation, mode).applyPredictedMovement(delta);
    }

    static void noTranslation(InterpolationHandler interpolation) {
        verify(interpolation, never()).applyPredictedMovement(any());
    }

    static void simulated(Entity entity) {
        when(entity.getMoveSimulationType()).thenReturn(MoveSimulationType.SERVER_AND_CLIENT);
    }

    static void route(ClientEntityCrossing crossing, Entity entity) {
        crossing.route(entity, PositionPath.stepped(List.of(new PositionStep(new Vec3(1.5D, 97.0D, 1.5D), 1),
            new PositionStep(new Vec3(1.5D, 93.0D, 1.5D), 2))), 90.0F, 10.0F, true);
        verify(entity).moveOrInterpolateTo(PositionPath.stepped(List.of(new PositionStep(new Vec3(1.5D, 77.0D, 1.5D), 1),
            new PositionStep(new Vec3(1.5D, 73.0D, 1.5D), 2))), 90.0F, 10.0F, true);
        crossing.route(entity, PositionPath.of(new Vec3(1.5D, 96.0D, 1.5D)), 0.0F, 0.0F, false);
        verify(entity).moveOrInterpolateTo(PositionPath.of(new Vec3(1.5D, 76.0D, 1.5D)), 0.0F, 0.0F, false);
    }
}
