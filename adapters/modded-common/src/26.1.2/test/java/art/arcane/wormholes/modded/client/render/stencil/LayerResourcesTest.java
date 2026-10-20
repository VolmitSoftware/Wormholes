package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.client.PortalBufferSourceAccess;
import art.arcane.wormholes.modded.mixin.client.PortalOutlineBufferAccess;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class LayerResourcesTest extends MinecraftTestBase {
    @Test
    public void everyLayerResetReleasesItsOwnedFixedBufferPackOnce() throws ReflectiveOperationException {
        LayerResources resources = mock(LayerResources.class, CALLS_REAL_METHODS);
        for (int reset = 0; reset < 3; reset++) {
            SectionBufferBuilderPack pack = mock(SectionBufferBuilderPack.class);
            RenderBuffers buffers = mock(RenderBuffers.class);
            FeatureRenderDispatcher features = mock(FeatureRenderDispatcher.class);
            OutlineBufferSource outline = mock(OutlineBufferSource.class, withSettings().extraInterfaces(PortalOutlineBufferAccess.class));
            MultiBufferSource.BufferSource source = source();
            MultiBufferSource.BufferSource crumbling = source();
            MultiBufferSource.BufferSource outlineSource = source();
            when(buffers.fixedBufferPack()).thenReturn(pack);
            when(buffers.bufferSource()).thenReturn(source);
            when(buffers.crumblingBufferSource()).thenReturn(crumbling);
            when(buffers.outlineBufferSource()).thenReturn(outline);
            when(((PortalOutlineBufferAccess) outline).wormholes$buffers()).thenReturn(outlineSource);
            field("buffers").set(resources, buffers);
            field("features").set(resources, features);

            resources.close();
            resources.close();

            verify(features, times(1)).close();
            verify(pack, times(1)).close();
            for (MultiBufferSource.BufferSource released : new MultiBufferSource.BufferSource[] {source, crumbling, outlineSource}) {
                verify(((PortalBufferSourceAccess) released).wormholes$sharedBuffer(), times(1)).close();
            }
        }
    }

    private static MultiBufferSource.BufferSource source() {
        MultiBufferSource.BufferSource source = mock(MultiBufferSource.BufferSource.class,
            withSettings().extraInterfaces(PortalBufferSourceAccess.class));
        PortalBufferSourceAccess access = (PortalBufferSourceAccess) source;
        when(access.wormholes$fixedBuffers()).thenReturn(new LinkedHashMap<>());
        when(access.wormholes$sharedBuffer()).thenReturn(mock(ByteBufferBuilder.class));
        return source;
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = LayerResources.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
