/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the stencil state changes of RendererUsingStencil for the 26.x OpenGL backend, which leaves
 * stencil, clip distance and front face state to the caller.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

public final class PortalStencil {
    private static final int ALL_BITS = 0xFF;
    private static final String LAYER_LABEL = "Wormholes portal layer ";
    private static final IntSet STENCIL_TEXTURES = new IntOpenHashSet();
    private static int depthRequests;

    private PortalStencil() {
    }

    public static boolean stencilTarget(RenderTarget target, String label) {
        return target instanceof MainTarget || label.startsWith(LAYER_LABEL);
    }

    public static void createTarget(boolean stencil, Runnable creation) {
        if (!stencil || !PortalBackends.stencilBuffer()) {
            creation.run();
            return;
        }
        depthRequests++;
        try {
            creation.run();
        } finally {
            depthRequests--;
        }
    }

    public static int internalFormat(TextureFormat format, int original) {
        return stencilFormat(format) ? GL30C.GL_DEPTH32F_STENCIL8 : original;
    }

    public static int externalFormat(TextureFormat format, int original) {
        return stencilFormat(format) ? GL30C.GL_DEPTH_STENCIL : original;
    }

    public static int pixelType(TextureFormat format, int original) {
        return stencilFormat(format) ? GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : original;
    }

    public static void created(GpuTexture texture) {
        if (texture instanceof GlTexture gl && stencilFormat(texture.getFormat())) {
            STENCIL_TEXTURES.add(gl.glId());
        }
    }

    public static void destroyed(int texture) {
        STENCIL_TEXTURES.remove(texture);
    }

    public static boolean available(RenderTarget main) {
        GpuTexture depth = main.getDepthTexture();
        return depth instanceof GlTexture gl && STENCIL_TEXTURES.contains(gl.glId());
    }

    public static void attach(int framebuffer, int depth) {
        if (!STENCIL_TEXTURES.contains(depth)) {
            return;
        }
        int previous = GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GlStateManager._glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT, GL11C.GL_TEXTURE_2D, depth, 0);
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

    private static boolean stencilFormat(TextureFormat format) {
        return format == TextureFormat.DEPTH32 && depthRequests > 0;
    }
}
