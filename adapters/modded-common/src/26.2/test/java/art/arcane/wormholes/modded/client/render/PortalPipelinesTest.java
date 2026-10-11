package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.Test;
import org.mockito.MockedStatic;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalPipelinesTest {
    @Test
    public void nativePipelineBuildersKeepCustomNamespace() {
        for (String name : new String[] {"solid", "cutout", "translucent", "solid_reflected", "cutout_reflected", "translucent_reflected"}) {
            verifyDescriptor(PortalPipelines.terrain(name, RenderPipelines.SOLID_BLOCK, false, DefaultVertexFormat.BLOCK), "portal_" + name);
            verifyDescriptor(PortalPipelines.terrain(name, RenderPipelines.SOLID_BLOCK, true, DefaultVertexFormat.BLOCK), "portal_" + name + "_rgss");
        }
        verifyDescriptor(PortalPipelines.compositePipeline(), "portal_composite");
        verifyDescriptor(PortalPipelines.layerPipeline(), "portal_layer");
    }

    @Test
    public void warmingTerrainUsesTheRetainedExtendedVertexStride() {
        RenderPipeline warming = PortalPipelines.terrain("warming", RenderPipelines.SOLID_BLOCK, false,
            PortalTerrainVertices.FORMAT);
        assertEquals(PortalTerrainVertices.FORMAT, warming.getVertexFormatBinding(0));
    }

    @Test
    public void shapedApertureDrawsTexturedTrianglesWhileFullAperturesKeepQuads() {
        RenderPipeline full = PortalPipelines.compositePipeline();
        assertEquals(DefaultVertexFormat.POSITION, full.getVertexFormatBinding(0));
        assertEquals(PrimitiveTopology.QUADS, full.getPrimitiveTopology());
        RenderPipeline shaped = PortalPipelines.compositeShapePipeline();
        verifyDescriptor(shaped, "portal_composite_shape");
        assertEquals(DefaultVertexFormat.POSITION_TEX, shaped.getVertexFormatBinding(0));
        assertEquals(PrimitiveTopology.TRIANGLES, shaped.getPrimitiveTopology());
        assertTrue(shaped.getShaderDefines().flags().contains("PORTAL_SHAPE"));
        RenderPipeline feather = PortalPipelines.featherPipeline();
        verifyDescriptor(feather, "portal_feather");
        assertEquals(DefaultVertexFormat.POSITION_TEX, feather.getVertexFormatBinding(0));
        assertEquals(PrimitiveTopology.TRIANGLES, feather.getPrimitiveTopology());
        assertFalse(feather.getDepthStencilState().writeDepth());
        assertTrue(feather.getColorTargetState().blendFunction().isPresent());
    }

    @Test
    public void completeBankPreparesBothFilteringStatesOnceAndDrawsKeepDescriptorIdentity() {
        try (PreparationFixture fixture = new PreparationFixture(-1)) {
            assertThrows(IllegalStateException.class, fixture.bank::composite);
            assertTrue(fixture.descriptors.isEmpty());
            fixture.bank.prepare();
            fixture.bank.prepare();
            assertEquals(16, fixture.descriptors.size());
            int rgss = 0;
            int reflected = 0;
            int cutout = 0;
            for (RenderPipeline descriptor : fixture.descriptors) {
                if (descriptor.getShaderDefines().flags().contains("USE_RGSS")) {
                    rgss++;
                }
                if (descriptor.getLocation().getPath().contains("_reflected")) {
                    reflected++;
                    assertFalse(descriptor.isCull());
                }
                if (descriptor.getShaderDefines().values().containsKey("ALPHA_CUTOUT")) {
                    cutout++;
                }
            }
            assertEquals(6, rgss);
            assertEquals(6, reflected);
            assertEquals(4, cutout);
            for (boolean filtering : new boolean[] {false, true, false}) {
                for (boolean reflection : new boolean[] {false, true}) {
                    for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                        String name = switch (layer) {
                            case SOLID -> "solid";
                            case CUTOUT -> "cutout";
                            case TRANSLUCENT -> "translucent";
                        };
                        String path = "portal_" + name + (reflection ? "_reflected" : "") + (filtering ? "_rgss" : "");
                        assertSame(fixture.descriptor(path), fixture.bank.terrain(layer, reflection, filtering));
                    }
                }
            }
            assertSame(fixture.descriptor("portal_composite"), fixture.bank.composite());
            assertSame(fixture.descriptor("portal_composite_shape"), fixture.bank.compositeShape());
            assertSame(fixture.descriptor("portal_feather"), fixture.bank.feather());
            assertSame(fixture.descriptor("portal_layer"), fixture.bank.layer());
            assertEquals(16, fixture.descriptors.size());
            fixture.bank.close();
            fixture.bank.close();
            verify(fixture.device, never()).clearPipelineCache();
            verify(fixture.device, never()).close();
            assertThrows(IllegalStateException.class, fixture.bank::composite);
        }
    }

    @Test
    public void failedGenerationDoesNotRetryOrClearDeviceOwnedPrograms() {
        try (PreparationFixture fixture = new PreparationFixture(3)) {
            assertThrows(IllegalStateException.class, fixture.bank::prepare);
            assertEquals(4, fixture.descriptors.size());
            assertThrows(IllegalStateException.class, fixture.bank::prepare);
            assertThrows(IllegalStateException.class, fixture.bank::layer);
            fixture.bank.close();
            assertEquals(4, fixture.descriptors.size());
            verify(fixture.device, never()).clearPipelineCache();
            verify(fixture.device, never()).close();
        }
    }

    private static final class PreparationFixture implements AutoCloseable {
        private final GpuDevice device = mock(GpuDevice.class);
        private final List<RenderPipeline> descriptors = new ArrayList<>();
        private final MockedStatic<RenderSystem> renderSystem = mockStatic(RenderSystem.class);
        private final MockedStatic<Minecraft> minecraft = mockStatic(Minecraft.class);
        private final PortalPipelines bank;

        private PreparationFixture(int failureIndex) {
            Minecraft client = mock(Minecraft.class);
            ShaderManager shaders = mock(ShaderManager.class);
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            when(client.getShaderManager()).thenReturn(shaders);
            renderSystem.when(RenderSystem::getDevice).thenReturn(device);
            when(device.precompilePipeline(any(RenderPipeline.class), any(ShaderSource.class))).thenAnswer(invocation -> {
                RenderPipeline descriptor = invocation.getArgument(0);
                int index = descriptors.size();
                descriptors.add(descriptor);
                CompiledRenderPipeline product = mock(CompiledRenderPipeline.class);
                when(product.isValid()).thenReturn(index != failureIndex);
                return product;
            });
            bank = new PortalPipelines();
        }

        private RenderPipeline descriptor(String path) {
            for (RenderPipeline descriptor : descriptors) {
                if (descriptor.getLocation().getPath().equals(path)) {
                    return descriptor;
                }
            }
            throw new IllegalArgumentException(path);
        }

        @Override
        public void close() {
            bank.close();
            minecraft.close();
            renderSystem.close();
        }
    }

    private static void verifyDescriptor(RenderPipeline pipeline, String path) {
        assertEquals("wormholes", pipeline.getLocation().getNamespace());
        assertEquals(path, pipeline.getLocation().getPath());
        for (Identifier shader : new Identifier[] {pipeline.getVertexShader(), pipeline.getFragmentShader()}) {
            assertEquals("wormholes", shader.getNamespace());
        }
    }
}
