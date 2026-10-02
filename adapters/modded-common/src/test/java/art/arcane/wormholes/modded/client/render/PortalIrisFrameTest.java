package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.vertices.ImmediateState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PortalIrisFrameTest {
    @Test
    public void nestedShaderFailureRestoresPassSuppressionAndBlockEntityState() {
        PortalIrisFrame.Immediate original = PortalIrisFrame.Immediate.capture();
        try {
            PortalIrisFrame.Immediate source = new PortalIrisFrame.Immediate(true, true, false, false, true, true, true, true);
            source.apply();
            PortalIrisFrame.Immediate parent = PortalIrisFrame.Immediate.capture();
            new PortalIrisFrame.Immediate(false, false, true, true, false, false, false, false).apply();
            PortalIrisFrame.Immediate child = PortalIrisFrame.Immediate.capture();
            try {
                ImmediateState.temporarilyIgnorePass = true;
                ImmediateState.isRenderingBEs = true;
                throw new IllegalStateException("destination composite failed");
            } catch (IllegalStateException expected) {
                child.apply();
            }
            assertEquals(child, PortalIrisFrame.Immediate.capture());
            parent.apply();
            assertEquals(source, PortalIrisFrame.Immediate.capture());
        } finally {
            original.apply();
        }
    }
}
