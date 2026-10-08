package art.arcane.wormholes.modded.client.render.iris;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class IrisLayerUniformsTest {
    @Test
    public void everyLayerTransitionInAFrameLooksLikeANewFrame() {
        int main = IrisLayerUniforms.frame(41);
        assertEquals(main, IrisLayerUniforms.frame(41));
        IrisLayerUniforms.transition();
        int layer = IrisLayerUniforms.frame(41);
        assertNotEquals(main, layer);
        IrisLayerUniforms.transition();
        int outer = IrisLayerUniforms.frame(41);
        assertNotEquals(main, outer);
        assertNotEquals(layer, outer);
    }

    @Test
    public void aNewFrameStartsFromItsOwnCounterAndNeverRepeatsTheLastFrame() {
        IrisLayerUniforms.frame(7);
        IrisLayerUniforms.transition();
        int last = IrisLayerUniforms.frame(7);
        int next = IrisLayerUniforms.frame(8);
        assertNotEquals(last, next);
        assertEquals(next, IrisLayerUniforms.frame(8));
        assertNotEquals(IrisLayerUniforms.frame(720719), IrisLayerUniforms.frame(0));
    }
}
