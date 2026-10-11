package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.Test;
import org.mockito.MockedStatic;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
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
        assertTrue(feather.getColorTargetStates().getFirst().blendFunction().isPresent());
    }

    @Test
    public void completeBankPreparesBothFilteringStatesOnceAndDrawsUseExactProducts() {
        try (PreparationFixture fixture = new PreparationFixture(-1)) {
            assertThrows(IllegalStateException.class, fixture.bank::composite);
            assertTrue(fixture.descriptors.isEmpty());
            fixture.bank.prepare();
            fixture.bank.prepare();
            assertEquals(16, fixture.descriptors.size());
            assertEquals(16, fixture.products.size());
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
                        assertSame(fixture.product(path), fixture.bank.terrain(layer, reflection, filtering));
                    }
                }
            }
            assertSame(fixture.product("portal_composite"), fixture.bank.composite());
            assertSame(fixture.product("portal_composite_shape"), fixture.bank.compositeShape());
            assertSame(fixture.product("portal_feather"), fixture.bank.feather());
            assertSame(fixture.product("portal_layer"), fixture.bank.layer());
            assertEquals(16, fixture.descriptors.size());
            fixture.bank.close();
            fixture.bank.close();
            for (CompiledRenderPipeline product : fixture.products.values()) {
                verify(product, times(1)).close();
            }
            verify(fixture.include, times(1)).close();
            assertThrows(IllegalStateException.class, fixture.bank::composite);
        }
    }

    @Test
    public void failedPreparationDisposesPartialProductsAndCannotCompileAgain() {
        try (PreparationFixture fixture = new PreparationFixture(3)) {
            assertThrows(IllegalStateException.class, fixture.bank::prepare);
            assertEquals(4, fixture.descriptors.size());
            assertEquals(3, fixture.products.size());
            for (CompiledRenderPipeline product : fixture.products.values()) {
                verify(product, times(1)).close();
            }
            verify(fixture.include, times(1)).close();
            assertThrows(IllegalStateException.class, fixture.bank::prepare);
            assertThrows(IllegalStateException.class, fixture.bank::layer);
            fixture.bank.close();
            assertEquals(4, fixture.descriptors.size());
            verify(fixture.include, times(1)).close();
        }
    }

    @Test
    public void closeFailureStillDisposesEveryOtherProgramAndShaderIncludeOnce() {
        try (PreparationFixture fixture = new PreparationFixture(-1)) {
            fixture.bank.prepare();
            CompiledRenderPipeline first = fixture.products.values().iterator().next();
            doThrow(new IllegalStateException("program close failure")).when(first).close();
            assertThrows(IllegalStateException.class, fixture.bank::close);
            fixture.bank.close();
            for (CompiledRenderPipeline product : fixture.products.values()) {
                verify(product, times(1)).close();
            }
            verify(fixture.include, times(1)).close();
        }
    }

    private static final class PreparationFixture implements AutoCloseable {
        private final GpuDevice device = mock(GpuDevice.class);
        private final ShaderSource.CachedIncludeSource include = mock(ShaderSource.CachedIncludeSource.class);
        private final List<RenderPipeline> descriptors = new ArrayList<>();
        private final Map<RenderPipeline, CompiledRenderPipeline> products = new IdentityHashMap<>();
        private final MockedStatic<RenderSystem> renderSystem = mockStatic(RenderSystem.class);
        private final MockedStatic<Minecraft> minecraft = mockStatic(Minecraft.class);
        private final MockedStatic<ShaderManager> shaderManager = mockStatic(ShaderManager.class);
        private final PortalPipelines bank;

        private PreparationFixture(int failureIndex) {
            Minecraft client = mock(Minecraft.class);
            ResourceManager resources = mock(ResourceManager.class);
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            when(client.getResourceManager()).thenReturn(resources);
            renderSystem.when(RenderSystem::getDevice).thenReturn(device);
            Map<Identifier, ShaderSource.CachedIncludeSource> includes = new HashMap<>();
            includes.put(Identifier.fromNamespaceAndPath("wormholes", "test"), include);
            shaderManager.when(() -> ShaderManager.listAllIncludes(resources)).thenReturn(includes);
            when(device.compilePipeline(any(RenderPipeline.class), any(ShaderSource.class), any(Executor.class))).thenAnswer(invocation -> {
                RenderPipeline descriptor = invocation.getArgument(0);
                int index = descriptors.size();
                descriptors.add(descriptor);
                CompiledRenderPipeline.Pending pending = mock(CompiledRenderPipeline.Pending.class);
                if (index != failureIndex) {
                    CompiledRenderPipeline product = mock(CompiledRenderPipeline.class);
                    products.put(descriptor, product);
                    when(pending.finishCompile()).thenReturn(product);
                }
                return CompletableFuture.completedFuture(pending);
            });
            bank = new PortalPipelines();
        }

        private CompiledRenderPipeline product(String path) {
            for (RenderPipeline descriptor : descriptors) {
                if (descriptor.getLocation().getPath().equals(path)) {
                    return products.get(descriptor);
                }
            }
            throw new IllegalArgumentException(path);
        }

        @Override
        public void close() {
            bank.close();
            shaderManager.close();
            minecraft.close();
            renderSystem.close();
        }
    }

    private static void verifyDescriptor(RenderPipeline pipeline, String path) {
        assertEquals("wormholes", pipeline.getLocation().getNamespace());
        assertEquals(path, pipeline.getLocation().getPath());
        for (Identifier shader : pipeline.getShaders().values()) {
            assertEquals("wormholes", shader.getNamespace());
        }
    }
}
