package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LayerResourcesTest extends MinecraftTestBase {
    @Test
    public void everyLayerResetReleasesItsOwnedFixedBufferPackOnce() throws ReflectiveOperationException {
        LayerResources resources = mock(LayerResources.class, CALLS_REAL_METHODS);
        for (int reset = 0; reset < 3; reset++) {
            SectionBufferBuilderPack pack = mock(SectionBufferBuilderPack.class);
            RenderBuffers buffers = mock(RenderBuffers.class);
            FeatureRenderDispatcher features = mock(FeatureRenderDispatcher.class);
            when(buffers.fixedBufferPack()).thenReturn(pack);
            field("buffers").set(resources, buffers);
            field("features").set(resources, features);

            resources.close();
            resources.close();

            verify(features, times(1)).close();
            verify(pack, times(1)).close();
            verify(buffers, times(1)).close();
        }
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = LayerResources.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
