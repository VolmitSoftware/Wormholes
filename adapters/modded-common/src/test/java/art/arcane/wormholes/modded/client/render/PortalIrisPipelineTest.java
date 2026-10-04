package art.arcane.wormholes.modded.client.render;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PortalIrisPipelineTest {
    @Test
    public void pendingResizeDefersRenderPassesUntilShadersAreReady() throws ReflectiveOperationException {
        PortalIrisPipeline pipeline = mock(PortalIrisPipeline.class);
        PortalShaderContext.View view = mock(PortalShaderContext.View.class);
        Field lastView = PortalIrisPipeline.class.getDeclaredField("lastView");
        lastView.setAccessible(true);
        lastView.set(pipeline, view);
        doCallRealMethod().when(pipeline).resize();

        pipeline.resize();

        verify(pipeline, never()).begin(view);
        verify(pipeline, never()).prepare();
        verify(pipeline, never()).finish();

        when(pipeline.ready()).thenReturn(true);
        pipeline.resize();

        verify(pipeline).begin(view);
        verify(pipeline).prepare();
        verify(pipeline).finish();
    }
}
