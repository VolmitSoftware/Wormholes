/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the stencil state changes of RendererUsingStencil for the 26.x OpenGL backend, which leaves
 * stencil, clip distance and front face state to the caller.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.FrameBufferAttachment;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

public final class PortalStencil {
    private static final int ALL_BITS = 0xFF;

    private PortalStencil() {
    }

    public static GpuFormat mainDepthFormat(GpuFormat requested) {
        return requested == GpuFormat.D32_FLOAT && PortalBackends.stencilBuffer() ? GpuFormat.D32_FLOAT_S8_UINT : requested;
    }

    public static boolean available(RenderTarget main) {
        GpuTexture depth = main.getDepthTexture();
        return depth != null && depth.getFormat().hasStencilAspect();
    }

    public static void attach(int framebuffer, FrameBufferAttachment depth) {
        if (depth == null || !format(depth).hasStencilAspect()) {
            return;
        }
        int previous = GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth.glId(),
            depth.fboMipLevel());
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previous);
    }

    public static void clear() {
        GL11C.glStencilMask(ALL_BITS);
        GL11C.glClearStencil(0);
        GL11C.glClear(GL11C.GL_STENCIL_BUFFER_BIT);
    }

    public static void mark(int outerReference) {
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilMask(ALL_BITS);
        GL11C.glStencilFunc(GL11C.GL_EQUAL, outerReference, ALL_BITS);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_INCR);
    }

    public static void limit(int reference) {
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilFunc(GL11C.GL_EQUAL, reference, ALL_BITS);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
    }

    public static void clamp(int maximum) {
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilMask(ALL_BITS);
        GL11C.glStencilFunc(GL11C.GL_LESS, maximum, ALL_BITS);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_REPLACE, GL11C.GL_REPLACE);
    }

    public static void restore(int reference) {
        if (reference == 0) {
            GL11C.glStencilFunc(GL11C.GL_ALWAYS, 0, ALL_BITS);
            GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            return;
        }
        limit(reference);
    }

    public static void clipping(boolean enabled) {
        if (enabled) {
            GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0);
        } else {
            GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0);
        }
    }

    public static void mirrored(boolean mirrored) {
        GL11C.glFrontFace(mirrored ? GL11C.GL_CW : GL11C.GL_CCW);
    }

    private static GpuFormat format(FrameBufferAttachment attachment) {
        return switch (attachment) {
            case GlTexture texture -> texture.getFormat();
            case GlTextureView view -> view.texture().getFormat();
            default -> GpuFormat.D32_FLOAT;
        };
    }
}
