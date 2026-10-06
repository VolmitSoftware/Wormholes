package art.arcane.optics.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntityRenderSpoofedMotionTest {
    @Test
    void motionKeepsExistingRelativeTeleportAndSubpixelThresholds() {
        SpoofedEntity state = SpoofedEntity.create(false, false, true);
        assertFalse(state.updatePosition(1.0D, 2.0D, 3.0D).relative);
        SpoofedEntity.Move relative = state.updatePosition(1.25D, 2.5D, 2.0D);
        assertTrue(relative.relative);
        assertEquals(0.25D, relative.deltaX);
        assertEquals(0.5D, relative.deltaY);
        assertEquals(-1.0D, relative.deltaZ);
        assertFalse(state.updatePosition(1.250001D, 2.5D, 2.0D).moved);
        assertFalse(state.updatePosition(20.0D, 2.5D, 2.0D).relative);
    }

    @Test
    void wrappedYawAndVelocityNoiseDoNotProduceRedundantPackets() {
        SpoofedEntity state = SpoofedEntity.create(true, false, true);
        assertTrue(state.updateRotation(359.9F, 0.0F));
        assertFalse(state.updateRotation(0.1F, 0.1F));
        assertTrue(state.updateRotation(1.0F, 0.1F));
        assertTrue(state.updateVelocity(0.1D, 0.2D, 0.3D, 0.001D));
        assertFalse(state.updateVelocity(0.1001D, 0.2D, 0.3D, 0.001D));
        assertTrue(state.updateVelocity(0.2D, 0.2D, 0.3D, 0.001D));
        assertTrue(state.labelFakeId != state.fakeId);
    }

    @Test
    void velocityChangesInsideTheEpsilonAreSkippedAgainstTheLastSentValue() {
        SpoofedEntity state = SpoofedEntity.create(false, false, true);
        assertTrue(state.updateVelocity(0.1D, -0.0784D, 0.0D, 0.005D));
        assertFalse(state.updateVelocity(0.104D, -0.0784D, 0.003D, 0.005D));
        assertFalse(state.updateVelocity(0.1049D, -0.08D, 0.0049D, 0.005D));
        assertTrue(state.updateVelocity(0.106D, -0.0784D, 0.0D, 0.005D));
    }

    @Test
    void stoppingAlwaysSendsTheZeroVelocity() {
        SpoofedEntity state = SpoofedEntity.create(false, false, true);
        assertTrue(state.updateVelocity(0.002D, 0.0D, -0.001D, 0.005D));
        assertTrue(state.updateVelocity(0.0D, 0.0D, 0.0D, 0.005D));
        assertFalse(state.updateVelocity(0.0D, 0.0D, 0.0D, 0.005D));
    }
}
