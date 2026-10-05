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
import java.util.List;
import java.nio.ByteBuffer;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;


public class PortalIrisResourcesTest {
    @Test
    public void fixedCostsAreReusedWhileRelativeDimensionsAndAllocationGrowthStayExact() {
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
        for (int width : new int[] {64, 128}) {
            when(directives.getTextureScaleOverride(0, width, 32)).thenReturn(new Vector2i(width, 32));
            when(directives.getTextureScaleOverride(1, width, 32)).thenReturn(new Vector2i(width, 32));
        }
        ImageInformation fixedImage = mock(ImageInformation.class);
        when(fixedImage.width()).thenReturn(8);
        when(fixedImage.height()).thenReturn(4);
        when(fixedImage.depth()).thenReturn(2);
        when(fixedImage.internalTextureFormat()).thenReturn(InternalTextureFormat.RGBA16F);
        ImageInformation relativeImage = mock(ImageInformation.class);
        when(relativeImage.isRelative()).thenReturn(true);
        when(relativeImage.relativeWidth()).thenReturn(0.5f);
        when(relativeImage.relativeHeight()).thenReturn(0.25f);
        when(relativeImage.internalTextureFormat()).thenReturn(InternalTextureFormat.RGBA8);
        when(pack.getIrisCustomImages()).thenReturn(List.of(fixedImage, relativeImage));
        Int2ObjectArrayMap<BuiltShaderStorageInfo> buffers = new Int2ObjectArrayMap<>();
        buffers.put(0, new BuiltShaderStorageInfo(37, false, 0, 0, null));
        buffers.put(1, new BuiltShaderStorageInfo(3, true, 0.5f, 0.25f, null));
        when(pack.getBufferObjects()).thenReturn(buffers);
        CustomTextureData.PngData png = mock(CustomTextureData.PngData.class);
        byte[] header = ByteBuffer.allocate(24).putLong(0x89504E470D0A1A0AL).putLong(0).putInt(16).putInt(8).array();
        when(png.getContent()).thenReturn(header);
        Object2ObjectArrayMap<String, CustomTextureData> custom = new Object2ObjectArrayMap<>();
        custom.put("sample", png);
        when(pack.getIrisCustomTextureDataMap()).thenReturn(custom);
        when(pack.getCustomTextureDataMap()).thenReturn(new EnumMap<>(TextureStage.class));
        when(directives.getNoiseTextureResolution()).thenReturn(4);
        long fixed = PortalIrisResources.texture(8, 4, 2, InternalTextureFormat.RGBA16F)
            + 37 + PortalIrisResources.texture(16, 8, 1, InternalTextureFormat.RGBA8) + 4L * 4 * 4;
        RenderTargets targets = mock(RenderTargets.class);
        when(targets.getRenderTargetCount()).thenReturn(2);
        long before = PortalIrisResources.revision();
        for (int allocated = 0; allocated <= 2; allocated++) {
            for (int width : new int[] {64, 128}) {
                long terrain = PortalIrisResources.texture(width, 32, 1, InternalTextureFormat.RGBA8) * 2;
                long relative = PortalIrisResources.texture(width / 2, 8, 1, InternalTextureFormat.RGBA8) + 3L * (width / 2) * 8;
                assertEquals(width * 32L * 16 + fixed + relative + terrain * (allocated == 0 ? 2 : allocated),
                    PortalIrisResources.targets(programs, width, 32));
            }
            if (allocated < 2) {
                when(targets.get(allocated)).thenReturn(mock(RenderTarget.class));
                PortalIrisResources.allocated(programs, targets);
            }
        }
        assertEquals(before + 2, PortalIrisResources.revision());
        verify(png, times(1)).getContent();
    }

    @Test
    public void initiallyEmptyAllocationsRefineOnceAndRetainNewTargetsAfterTheyDisappear() {
        ProgramSet programs = mock(ProgramSet.class);
        RenderTargets targets = mock(RenderTargets.class);
        when(targets.getRenderTargetCount()).thenReturn(3);
        long before = PortalIrisResources.revision();
        PortalIrisResources.allocated(programs, targets);
        assertEquals(before + 1, PortalIrisResources.revision());
        PortalIrisResources.allocated(programs, targets);
        assertEquals(before + 1, PortalIrisResources.revision());
        when(targets.get(0)).thenReturn(mock(RenderTarget.class));
        when(targets.get(2)).thenReturn(mock(RenderTarget.class));
        PortalIrisResources.allocated(programs, targets);
        assertEquals(before + 2, PortalIrisResources.revision());
        when(targets.get(0)).thenReturn(null);
        when(targets.get(2)).thenReturn(null);
        PortalIrisResources.allocated(programs, targets);
        assertEquals(before + 2, PortalIrisResources.revision());
        when(targets.get(0)).thenReturn(mock(RenderTarget.class));
        when(targets.get(2)).thenReturn(mock(RenderTarget.class));
        PortalIrisResources.allocated(programs, targets);
        assertEquals(before + 2, PortalIrisResources.revision());
    }

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
