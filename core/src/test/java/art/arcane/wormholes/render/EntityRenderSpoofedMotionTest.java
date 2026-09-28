package art.arcane.wormholes.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntityRenderSpoofedMotionTest {
    @Test
    void motionKeepsExistingRelativeTeleportAndSubpixelThresholds() {
        EntityRenderSpoofedEntity state = EntityRenderSpoofedEntity.create(false, false, true);
        assertFalse(state.updatePosition(1.0D, 2.0D, 3.0D).relative);
        EntityRenderSpoofedEntity.Move relative = state.updatePosition(1.25D, 2.5D, 2.0D);
        assertTrue(relative.relative);
        assertEquals(0.25D, relative.deltaX);
        assertEquals(0.5D, relative.deltaY);
        assertEquals(-1.0D, relative.deltaZ);
        assertFalse(state.updatePosition(1.250001D, 2.5D, 2.0D).moved);
        assertFalse(state.updatePosition(20.0D, 2.5D, 2.0D).relative);
    }

    @Test
    void wrappedYawAndVelocityNoiseDoNotProduceRedundantPackets() {
        EntityRenderSpoofedEntity state = EntityRenderSpoofedEntity.create(true, false, true);
        assertTrue(state.updateRotation(359.9F, 0.0F));
        assertFalse(state.updateRotation(0.1F, 0.1F));
        assertTrue(state.updateRotation(1.0F, 0.1F));
        assertTrue(state.updateVelocity(0.1D, 0.2D, 0.3D));
        assertFalse(state.updateVelocity(0.1001D, 0.2D, 0.3D));
        assertTrue(state.updateVelocity(0.2D, 0.2D, 0.3D));
        assertTrue(state.labelFakeId != state.fakeId);
    }
}
