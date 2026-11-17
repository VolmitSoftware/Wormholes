/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the stencil state changes of RendererUsingStencil, plus the MixinRenderTarget depth-stencil
 * upgrade for 1.20.1 render targets, which allocate plain depth textures on Fabric and on Forge without enableStencil.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.slf4j.Logger;

public final class PortalStencil {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ALL_BITS = 0xFF;
    /** Set on pixels a top-level view opened this frame; the low bits hold the layer depth. */
    public static final int COVERED = 0x80;
    private static final int DEPTH_BITS = 0x7F;
    private static final IntSet STENCIL_TEXTURES = new IntOpenHashSet();
    private static boolean reported;

    private PortalStencil() {
    }

    /** Whether the depth attachment of {@code target} carries stencil bits. Render thread only. */
    public static boolean available(RenderTarget target) {
        return target != null && target.useDepth && STENCIL_TEXTURES.contains(target.getDepthTextureId());
    }

    /**
     * Reallocates the depth texture of a freshly created target as {@code DEPTH32F_STENCIL8} and attaches it to both
     * the depth and stencil points. Every target is upgraded so depth blits between targets keep matching formats.
     */
    public static void ensure(RenderTarget target) {
        if (!target.useDepth || target.getDepthTextureId() < 0 || target.frameBufferId < 0) {
            return;
        }
        int depth = target.getDepthTextureId();
        GlStateManager._bindTexture(depth);
        if (GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_TEXTURE_STENCIL_SIZE) > 0) {
            STENCIL_TEXTURES.add(depth);
            report(target);
            return;
        }
        int previous = GlStateManager._getInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        GlStateManager._texImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_DEPTH32F_STENCIL8, target.width, target.height, 0, GL30C.GL_DEPTH_STENCIL,
            GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV, null);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target.frameBufferId);
        GlStateManager._glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.warn("Portal stencil unavailable: depth-stencil attachment left the framebuffer incomplete (0x{})", Integer.toHexString(status));
            GlStateManager._glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL11C.GL_TEXTURE_2D, 0, 0);
            GlStateManager._bindTexture(depth);
            GlStateManager._texImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_DEPTH_COMPONENT, target.width, target.height, 0, GL11C.GL_DEPTH_COMPONENT,
                GL11C.GL_FLOAT, null);
            GlStateManager._glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
            STENCIL_TEXTURES.remove(depth);
        } else {
            STENCIL_TEXTURES.add(depth);
            clearAttached();
            report(target);
        }
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, previous);
    }

    /** Forgets a target's depth texture before it is released, since GL may reuse the name for a plain texture. */
    public static void forget(RenderTarget target) {
        if (target.getDepthTextureId() >= 0) {
            STENCIL_TEXTURES.remove(target.getDepthTextureId());
        }
    }

    public static void clear() {
        GL11C.glStencilMask(ALL_BITS);
        GL11C.glClearStencil(0);
        GL11C.glClear(GL11C.GL_STENCIL_BUFFER_BIT);
    }

    public static void mark(int outerReference) {
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilMask(ALL_BITS);
        GL11C.glStencilFunc(GL11C.GL_EQUAL, outerReference, DEPTH_BITS);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_INCR);
    }

    public static void limit(int reference) {
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilFunc(GL11C.GL_EQUAL, reference, DEPTH_BITS);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
    }

    /**
     * Returns every value deeper than {@code maximum} to it. Closing a top-level view instead stamps {@link #COVERED},
     * which reads as depth 0 to every later test but keeps the opening marked for the rest of the frame.
     */
    public static void clamp(int maximum) {
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilMask(maximum == 0 ? ALL_BITS : DEPTH_BITS);
        GL11C.glStencilFunc(GL11C.GL_LESS, maximum == 0 ? COVERED : maximum, DEPTH_BITS);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_REPLACE, GL11C.GL_REPLACE);
    }

    /** Restores the full write mask after a clamp. */
    public static void release() {
        GL11C.glStencilMask(ALL_BITS);
    }

    public static void restore(int reference) {
        GL11C.glStencilMask(ALL_BITS);
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

    public static boolean clipping() {
        return GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0);
    }

    public static void mirrored(boolean mirrored) {
        GL11C.glFrontFace(mirrored ? GL11C.GL_CW : GL11C.GL_CCW);
    }

    private static void clearAttached() {
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glStencilMask(ALL_BITS);
        GL11C.glClearStencil(0);
        GL11C.glClear(GL11C.GL_STENCIL_BUFFER_BIT);
        if (scissor) {
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        }
    }

    private static void report(RenderTarget target) {
        if (reported) {
            return;
        }
        reported = true;
        int previous = GlStateManager._getInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target.frameBufferId);
        int bits = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT,
            GL30C.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, previous);
        LOGGER.info("Portal stencil: {} stencil bits on {}x{} render targets", bits, target.width, target.height);
    }
}
