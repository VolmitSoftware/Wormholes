/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the per-layer deferred framebuffers of IrisPortalRenderer on the 26.x render targets, copying finished
 * layer images, marking portal openings by stencil and compositing inner layers under that stencil.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.client.render.PortalGpuMesh;
import art.arcane.wormholes.modded.client.render.PortalShaderScope;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

final class DeferredLayers implements AutoCloseable {
    private static final int UNIFORM_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;
    private static final Vector4fc NO_PLANE = new Vector4f();

    private final TextureTarget[] targets;
    private final boolean[] captured;
    private GpuBuffer projection;
    private int readFramebuffer;
    private int drawFramebuffer;

    DeferredLayers(int maxDepth) {
        targets = new TextureTarget[maxDepth + 1];
        captured = new boolean[maxDepth + 1];
    }

    void capture(RenderTarget main, int depth) {
        TextureTarget layer = target(main, depth);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), layer.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        encoder.copyTextureToTexture(main.getDepthTexture(), layer.getDepthTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        captured[depth] = true;
    }

    void forget(int depth) {
        captured[depth] = false;
    }

    void mark(int depth, PortalGpuMesh mesh, boolean shaped, Matrix4f aperture, Matrix4fc projectionMatrix, Vector4fc clipPlane) {
        TextureTarget layer = targets[depth];
        resetStencil(depth);
        GpuBufferSlice previousProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType previousType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projection(projectionMatrix, clipPlane == null ? NO_PLANE : clipPlane), ProjectionType.PERSPECTIVE);
        PortalStencil.clipping(clipPlane != null);
        try (PortalShaderScope scope = PortalShaderScope.rendering();
             RenderPass pass = open(layer, "Wormholes deferred portal mark")) {
            PortalStencil.mark(depth);
            pass.setPipeline(RenderSystem.getCompiledPipeline(StencilPipelines.mask(shaped)));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(aperture));
            mesh.draw(pass);
        } finally {
            PortalStencil.clipping(false);
            PortalStencil.restore(0);
            RenderSystem.setProjectionMatrix(previousProjection, previousType);
        }
    }

    void composite(RenderTarget main, int inner, int outer) {
        GpuTextureView source = captured[inner] ? targets[inner].getColorTextureView() : main.getColorTextureView();
        try (PortalShaderScope scope = PortalShaderScope.rendering();
             RenderPass pass = open(targets[outer], "Wormholes deferred portal composite")) {
            PortalStencil.limit(inner);
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("InSampler", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        } finally {
            PortalStencil.restore(0);
            captured[inner] = false;
        }
    }

    void finish(RenderTarget main) {
        TextureTarget layer = targets[0];
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(layer.getColorTexture(), main.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        encoder.copyTextureToTexture(layer.getDepthTexture(), main.getDepthTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        captured[0] = false;
    }

    @Override
    public void close() {
        for (int depth = 0; depth < targets.length; depth++) {
            if (targets[depth] != null) {
                targets[depth].destroyBuffers();
                targets[depth] = null;
            }
            captured[depth] = false;
        }
        if (projection != null) {
            projection.close();
            projection = null;
        }
        if (readFramebuffer != 0) {
            GlStateManager._glDeleteFramebuffers(readFramebuffer);
            readFramebuffer = 0;
        }
        if (drawFramebuffer != 0) {
            GlStateManager._glDeleteFramebuffers(drawFramebuffer);
            drawFramebuffer = 0;
        }
    }

    private TextureTarget target(RenderTarget main, int depth) {
        TextureTarget layer = targets[depth];
        GpuTexture color = main.getColorTexture();
        GpuTexture depthTexture = main.getDepthTexture();
        if (layer != null && layer.width == main.width && layer.height == main.height
            && layer.getColorTexture().getFormat() == color.getFormat() && layer.getDepthTexture().getFormat() == depthTexture.getFormat()) {
            return layer;
        }
        if (layer != null) {
            layer.destroyBuffers();
        }
        layer = new TextureTarget("Wormholes portal layer " + depth, main.width, main.height, color.getFormat(), depthTexture.getFormat());
        targets[depth] = layer;
        return layer;
    }

    private void resetStencil(int depth) {
        if (depth == 0) {
            clearStencil(targets[0]);
        } else {
            copyStencil(targets[depth - 1], targets[depth]);
        }
    }

    private void clearStencil(TextureTarget target) {
        int previous = GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER);
        attach(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer(), target.getDepthTexture());
        GlStateManager._disableScissorTest();
        PortalStencil.clear();
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previous);
    }

    private void copyStencil(TextureTarget source, TextureTarget destination) {
        int previousRead = GlStateManager.getFrameBuffer(GL30C.GL_READ_FRAMEBUFFER);
        int previousDraw = GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER);
        attach(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer(), source.getDepthTexture());
        attach(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer(), destination.getDepthTexture());
        GlStateManager._disableScissorTest();
        GlStateManager._glBlitFrameBuffer(0, 0, source.width, source.height, 0, 0, destination.width, destination.height,
            GL11C.GL_STENCIL_BUFFER_BIT, GL11C.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
    }

    private static void attach(int binding, int framebuffer, GpuTexture depth) {
        GlStateManager._glBindFramebuffer(binding, framebuffer);
        GlStateManager._glFramebufferTexture2D(binding, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL11C.GL_TEXTURE_2D, ((GlTexture) depth).glId(), 0);
    }

    private int readFramebuffer() {
        if (readFramebuffer == 0) {
            readFramebuffer = depthOnlyFramebuffer();
        }
        return readFramebuffer;
    }

    private int drawFramebuffer() {
        if (drawFramebuffer == 0) {
            drawFramebuffer = depthOnlyFramebuffer();
        }
        return drawFramebuffer;
    }

    private static int depthOnlyFramebuffer() {
        int previousRead = GlStateManager.getFrameBuffer(GL30C.GL_READ_FRAMEBUFFER);
        int previousDraw = GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER);
        int framebuffer = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL11C.glDrawBuffer(GL11C.GL_NONE);
        GL11C.glReadBuffer(GL11C.GL_NONE);
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
        return framebuffer;
    }

    private GpuBufferSlice projection(Matrix4fc matrix, Vector4fc clipPlane) {
        if (projection == null) {
            projection = RenderSystem.getDevice().createBuffer(() -> "Wormholes deferred portal projection", UNIFORM_USAGE,
                PortalClipShaders.PROJECTION_UBO_SIZE);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, PortalClipShaders.PROJECTION_UBO_SIZE).putMat4f(matrix).putVec4(clipPlane).get();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(projection.slice(), data);
        }
        return projection.slice();
    }

    private static RenderPass open(TextureTarget target, String label) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, target.getColorTextureView(), Optional.empty(),
            target.getDepthTextureView(), OptionalDouble.empty());
    }
}
