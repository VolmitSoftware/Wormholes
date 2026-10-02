package art.arcane.wormholes.modded.client.render;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.targets.RenderTarget;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.joml.Vector2i;
import java.util.EnumMap;
import java.util.Map;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class PortalIrisResourcesTest {
    @Test
    public void reservesPingPongAndMipChainsWithConservativeRgbPadding() {
        assertEquals(4, PortalIrisResources.pixelBytes(InternalTextureFormat.R11F_G11F_B10F));
        assertEquals(4, PortalIrisResources.pixelBytes(InternalTextureFormat.RGB8_SNORM));
        assertEquals(8, PortalIrisResources.pixelBytes(InternalTextureFormat.RGB16F));
        assertEquals(16, PortalIrisResources.pixelBytes(InternalTextureFormat.RGBA32F));
        assertEquals(256L * 128 * 8 * 4 / 3, PortalIrisResources.texture(256, 128, 1, InternalTextureFormat.RGB16F));
        assertThrows(IllegalArgumentException.class, () -> PortalIrisResources.texture(0, 128, 1, InternalTextureFormat.RGBA));
    }

    @Test
    public void actualAllocationRefinesUnusedDeclarationsWithoutForgettingLaterTargets() {
        ProgramSet programs = mock(ProgramSet.class);
        ShaderPack pack = mock(ShaderPack.class);
        PackDirectives directives = mock(PackDirectives.class);
        PackRenderTargetDirectives render = mock(PackRenderTargetDirectives.class);
        PackRenderTargetDirectives.RenderTargetSettings format = mock(PackRenderTargetDirectives.RenderTargetSettings.class);
        when(programs.getPack()).thenReturn(pack);
        when(programs.getPackDirectives()).thenReturn(directives);
        when(directives.getRenderTargetDirectives()).thenReturn(render);
        when(render.getRenderTargetSettings()).thenReturn(Map.of(0, format, 1, format));
        when(format.getInternalFormat()).thenReturn(InternalTextureFormat.RGBA8);
        when(directives.getTextureScaleOverride(0, 64, 32)).thenReturn(new Vector2i(64, 32));
        when(directives.getTextureScaleOverride(1, 64, 32)).thenReturn(new Vector2i(64, 32));
        when(pack.getCustomTextureDataMap()).thenReturn(new EnumMap<>(TextureStage.class));
        when(pack.getIrisCustomTextureDataMap()).thenReturn(new Object2ObjectArrayMap<>());
        when(pack.getBufferObjects()).thenReturn(new Int2ObjectArrayMap<>());
        RenderTargets targets = mock(RenderTargets.class);
        when(targets.getRenderTargetCount()).thenReturn(2);
        when(targets.get(0)).thenReturn(mock(RenderTarget.class));
        long conservative = PortalIrisResources.targets(programs, 64, 32);
        long before = PortalIrisResources.revision();
        PortalIrisResources.allocated(programs, targets);
        long refined = PortalIrisResources.targets(programs, 64, 32);
        assertEquals(conservative - PortalIrisResources.texture(64, 32, 1, InternalTextureFormat.RGBA8) * 2, refined);
        assertEquals(before + 1, PortalIrisResources.revision());
        PortalIrisResources.allocated(programs, targets);
        assertEquals(before + 1, PortalIrisResources.revision());
        when(targets.get(1)).thenReturn(mock(RenderTarget.class));
        PortalIrisResources.allocated(programs, targets);
        assertEquals(conservative, PortalIrisResources.targets(programs, 64, 32));
        when(targets.get(1)).thenReturn(null);
        PortalIrisResources.allocated(programs, targets);
        assertEquals(conservative, PortalIrisResources.targets(programs, 64, 32));
        assertEquals(before + 2, PortalIrisResources.revision());
    }

    @Test
    public void anyRetainedShadowColorHistoryRequiresPrivateTargets() {
        ProgramSet programs = mock(ProgramSet.class);
        PackDirectives pack = mock(PackDirectives.class);
        PackShadowDirectives shadows = mock(PackShadowDirectives.class);
        PackShadowDirectives.SamplingSettings first = mock(PackShadowDirectives.SamplingSettings.class);
        PackShadowDirectives.SamplingSettings second = mock(PackShadowDirectives.SamplingSettings.class);
        when(programs.getPackDirectives()).thenReturn(pack);
        when(pack.getShadowDirectives()).thenReturn(shadows);
        Int2ObjectOpenHashMap<PackShadowDirectives.SamplingSettings> colors = new Int2ObjectOpenHashMap<>();
        colors.put(0, first);
        colors.put(1, second);
        when(shadows.getColorSamplingSettings()).thenReturn(colors);
        when(first.getClear()).thenReturn(true);
        when(second.getClear()).thenReturn(true);
        assertTrue(PortalIrisResources.shareShadows(programs));
        when(second.getClear()).thenReturn(false);
        assertFalse(PortalIrisResources.shareShadows(programs));
    }
}
