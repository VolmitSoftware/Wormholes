package art.arcane.wormholes.modded.client.render.stencil;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StencilLayersTest {
    @Test
    public void outerLayerUsesReferenceZeroAndEachPortalAddsOne() {
        StencilLayers layers = new StencilLayers(3);
        assertEquals(0, layers.reference());
        assertFalse(layers.nested());
        assertEquals(1, layers.enter(false));
        assertEquals(2, layers.enter(false));
        assertTrue(layers.nested());
        assertEquals(1, layers.outerReference());
        layers.exit();
        assertEquals(1, layers.reference());
        layers.exit();
        assertEquals(0, layers.reference());
    }

    @Test
    public void recursionStopsAtTheConfiguredAndRequestedDepth() {
        StencilLayers layers = new StencilLayers(2);
        assertTrue(layers.canEnter(4));
        layers.enter(false);
        assertFalse(layers.canEnter(1));
        assertTrue(layers.canEnter(4));
        layers.enter(false);
        assertFalse(layers.canEnter(4));
        assertThrows(IllegalStateException.class, () -> layers.enter(false));
    }

    @Test
    public void mirrorParityFlipsWindingOnlyForAnOddNumberOfReflections() {
        StencilLayers layers = new StencilLayers(4);
        layers.enter(true);
        assertTrue(layers.mirrored());
        layers.enter(false);
        assertTrue(layers.mirrored());
        layers.enter(true);
        assertFalse(layers.mirrored());
        layers.exit();
        assertTrue(layers.mirrored());
        layers.exit();
        layers.exit();
        assertFalse(layers.mirrored());
    }

    @Test
    public void exitingTheOuterLayerIsRejected() {
        StencilLayers layers = new StencilLayers(2);
        assertThrows(IllegalStateException.class, layers::exit);
    }

    @Test
    public void frameBudgetLimitsViewsAndClearsStencilOnce() {
        StencilLayers layers = new StencilLayers(2);
        layers.beginFrame(2);
        assertTrue(layers.claimStencilClear());
        assertFalse(layers.claimStencilClear());
        assertTrue(layers.claimView());
        assertTrue(layers.claimView());
        assertFalse(layers.claimView());
        layers.beginFrame(1);
        assertTrue(layers.claimStencilClear());
        assertTrue(layers.claimView());
        assertFalse(layers.claimView());
    }

    @Test
    public void beginningAFrameWhileNestedIsRejected() {
        StencilLayers layers = new StencilLayers(2);
        layers.enter(false);
        assertThrows(IllegalStateException.class, () -> layers.beginFrame(4));
    }

    @Test
    public void depthIsBoundedByTheStencilBits() {
        assertThrows(IllegalArgumentException.class, () -> new StencilLayers(0));
        assertThrows(IllegalArgumentException.class, () -> new StencilLayers(StencilLayers.MAX_REFERENCE + 1));
    }
}
