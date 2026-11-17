package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.client.render.PortalGpuMesh;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL32C;

/**
 * The fixed draws of the stencil pass on 1.20.1: the aperture mask, the aperture depth write-back and three
 * full-screen fills. 26.x expressed these as render pipelines; here they are vanilla core shaders plus explicit GL
 * state. Aperture draws use {@code position_color}, which receives the clip plane, so a nested aperture is cut by
 * its parent layer; fills use {@code position}, which is unclipped. Depth clamping stands in for the 26.x shader
 * that pinned aperture depth, so an aperture right at the near plane still marks the stencil.
 */
public final class StencilPipelines {
    private static final Matrix4f IDENTITY = new Matrix4f();

    private StencilPipelines() {
    }

    /** Increments the stencil where the aperture passes the depth test, without touching colour or depth. */
    public static void mask(PortalGpuMesh mesh, Matrix4fc apertureView) {
        aperture(mesh, apertureView, false);
    }

    /** Writes the aperture plane depth wherever the stencil test passes, sealing the opened view. */
    public static void depth(PortalGpuMesh mesh, Matrix4fc apertureView) {
        aperture(mesh, apertureView, true);
    }

    /** Fills far depth inside the stencil so the destination draws over everything behind the opening. */
    public static void far() {
        fill(false, true, 1.0F, 1.0F, 1.0F);
    }

    /** Runs the stencil operation over the screen without writing colour or depth. */
    public static void clamp() {
        fill(false, false, 1.0F, 1.0F, 1.0F);
    }

    /** Fills colour and far depth inside the stencil; replaces the full-target clear inside a layer. */
    public static void clear(float red, float green, float blue) {
        fill(true, true, red, green, blue);
    }

    private static void aperture(PortalGpuMesh mesh, Matrix4fc apertureView, boolean writeDepth) {
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(writeDepth ? GL11C.GL_ALWAYS : GL11C.GL_LEQUAL);
        RenderSystem.depthMask(writeDepth);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        GL11C.glEnable(GL32C.GL_DEPTH_CLAMP);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        try {
            modelView.last().pose().set(apertureView);
            RenderSystem.applyModelViewMatrix();
            mesh.draw();
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            GL11C.glDisable(GL32C.GL_DEPTH_CLAMP);
            restoreDefaults();
        }
    }

    private static void fill(boolean color, boolean depth, float red, float green, float blue) {
        RenderSystem.colorMask(color, color, color, color);
        if (depth) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11C.GL_ALWAYS);
            RenderSystem.depthMask(true);
        } else {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
        }
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        boolean clipping = PortalStencil.clipping();
        PortalStencil.clipping(false);
        GL11C.glEnable(GL32C.GL_DEPTH_CLAMP);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();
        float[] shaderColor = RenderSystem.getShaderColor().clone();
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        try {
            modelView.setIdentity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f(IDENTITY), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.setShaderColor(red, green, blue, 1.0F);
            RenderSystem.setShader(GameRenderer::getPositionShader);
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            builder.vertex(-1.0D, -1.0D, 1.0D).endVertex();
            builder.vertex(1.0D, -1.0D, 1.0D).endVertex();
            builder.vertex(1.0D, 1.0D, 1.0D).endVertex();
            builder.vertex(-1.0D, 1.0D, 1.0D).endVertex();
            BufferUploader.drawWithShader(builder.end());
        } finally {
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            RenderSystem.setProjectionMatrix(projection, sorting);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            GL11C.glDisable(GL32C.GL_DEPTH_CLAMP);
            PortalStencil.clipping(clipping);
            restoreDefaults();
        }
    }

    private static void restoreDefaults() {
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11C.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }
}
