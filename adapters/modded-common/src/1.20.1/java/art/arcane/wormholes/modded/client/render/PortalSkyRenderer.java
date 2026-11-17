package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.EnvironmentState;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;

/**
 * Draws a destination's sky from its streamed {@link EnvironmentState.Sky}, following the game's own sky pass: the
 * coloured dome, sunrise fan, sun, moon, stars and dark lower disc for an overworld sky, the textured box for an End sky.
 */
final class PortalSkyRenderer {
    private static final ResourceLocation SUN = new ResourceLocation("textures/environment/sun.png");
    private static final ResourceLocation MOON = new ResourceLocation("textures/environment/moon_phases.png");
    private static final ResourceLocation END_SKY = new ResourceLocation("textures/environment/end_sky.png");
    private static VertexBuffer light;
    private static VertexBuffer dark;
    private static VertexBuffer stars;

    private PortalSkyRenderer() {
    }

    static void render(EnvironmentState.Sky sky, float sunAngle, float starAngle, Matrix4f view, Matrix4f projection,
                       PortalEnvironment.Fog fog, boolean belowHorizon) {
        if (sky.skybox() == EnvironmentState.Skybox.NONE) {
            return;
        }
        PoseStack pose = new PoseStack();
        pose.last().pose().set(view);
        RenderSystem.depthMask(false);
        try {
            if (sky.skybox() == EnvironmentState.Skybox.END) {
                renderEnd(pose);
                return;
            }
            prepare();
            RenderSystem.setShaderFogStart(0.0F);
            RenderSystem.setShaderFogEnd(fog.end());
            RenderSystem.setShaderFogColor(fog.color().x, fog.color().y, fog.color().z, 1.0F);
            RenderSystem.setShader(GameRenderer::getPositionShader);
            RenderSystem.setShaderColor(sky.color().red(), sky.color().green(), sky.color().blue(), 1.0F);
            light.bind();
            light.drawWithShader(pose.last().pose(), projection, RenderSystem.getShader());
            VertexBuffer.unbind();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            if (sky.sunrise().alpha() > 0.0F) {
                sunrise(pose, sky.sunrise(), sunAngle);
            }
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            float rain = 1.0F - sky.rain();
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
            pose.mulPose(Axis.XP.rotation(sunAngle));
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, rain);
            sunAndMoon(pose.last().pose(), sky.moonPhase());
            pose.popPose();
            float starBrightness = sky.starBrightness() * rain;
            if (starBrightness > 0.0F) {
                pose.pushPose();
                pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
                pose.mulPose(Axis.XP.rotation(starAngle));
                RenderSystem.setShaderColor(starBrightness, starBrightness, starBrightness, starBrightness);
                RenderSystem.setShaderFogStart(Float.MAX_VALUE);
                stars.bind();
                stars.drawWithShader(pose.last().pose(), projection, GameRenderer.getPositionShader());
                VertexBuffer.unbind();
                pose.popPose();
            }
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            if (belowHorizon) {
                RenderSystem.setShaderColor(0.0F, 0.0F, 0.0F, 1.0F);
                pose.pushPose();
                pose.translate(0.0F, 12.0F, 0.0F);
                dark.bind();
                dark.drawWithShader(pose.last().pose(), projection, GameRenderer.getPositionShader());
                VertexBuffer.unbind();
                pose.popPose();
            }
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
        }
    }

    private static void sunrise(PoseStack pose, EnvironmentState.ColorAlpha color, float sunAngle) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        pose.pushPose();
        pose.mulPose(Axis.XP.rotationDegrees(90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(Mth.sin(sunAngle) < 0.0F ? 180.0F : 0.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
        Matrix4f matrix = pose.last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        builder.vertex(matrix, 0.0F, 100.0F, 0.0F).color(color.red(), color.green(), color.blue(), color.alpha()).endVertex();
        for (int step = 0; step <= 16; step++) {
            float angle = step * (float) (Math.PI * 2) / 16.0F;
            float sin = Mth.sin(angle);
            float cos = Mth.cos(angle);
            builder.vertex(matrix, sin * 120.0F, cos * 120.0F, -cos * 40.0F * color.alpha())
                .color(color.red(), color.green(), color.blue(), 0.0F).endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
        pose.popPose();
    }

    private static void sunAndMoon(Matrix4f matrix, int moonPhase) {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, SUN);
        float size = 30.0F;
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(matrix, -size, 100.0F, -size).uv(0.0F, 0.0F).endVertex();
        builder.vertex(matrix, size, 100.0F, -size).uv(1.0F, 0.0F).endVertex();
        builder.vertex(matrix, size, 100.0F, size).uv(1.0F, 1.0F).endVertex();
        builder.vertex(matrix, -size, 100.0F, size).uv(0.0F, 1.0F).endVertex();
        BufferUploader.drawWithShader(builder.end());
        size = 20.0F;
        RenderSystem.setShaderTexture(0, MOON);
        int column = moonPhase % 4;
        int row = moonPhase / 4 % 2;
        float u0 = column / 4.0F;
        float v0 = row / 2.0F;
        float u1 = (column + 1) / 4.0F;
        float v1 = (row + 1) / 2.0F;
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(matrix, -size, -100.0F, size).uv(u1, v1).endVertex();
        builder.vertex(matrix, size, -100.0F, size).uv(u0, v1).endVertex();
        builder.vertex(matrix, size, -100.0F, -size).uv(u0, v0).endVertex();
        builder.vertex(matrix, -size, -100.0F, -size).uv(u1, v0).endVertex();
        BufferUploader.drawWithShader(builder.end());
    }

    private static void renderEnd(PoseStack pose) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, END_SKY);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        for (int face = 0; face < 6; face++) {
            pose.pushPose();
            switch (face) {
                case 1 -> pose.mulPose(Axis.XP.rotationDegrees(90.0F));
                case 2 -> pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
                case 3 -> pose.mulPose(Axis.XP.rotationDegrees(180.0F));
                case 4 -> pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
                case 5 -> pose.mulPose(Axis.ZP.rotationDegrees(-90.0F));
                default -> {
                }
            }
            Matrix4f matrix = pose.last().pose();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            builder.vertex(matrix, -100.0F, -100.0F, -100.0F).uv(0.0F, 0.0F).color(40, 40, 40, 255).endVertex();
            builder.vertex(matrix, -100.0F, -100.0F, 100.0F).uv(0.0F, 16.0F).color(40, 40, 40, 255).endVertex();
            builder.vertex(matrix, 100.0F, -100.0F, 100.0F).uv(16.0F, 16.0F).color(40, 40, 40, 255).endVertex();
            builder.vertex(matrix, 100.0F, -100.0F, -100.0F).uv(16.0F, 0.0F).color(40, 40, 40, 255).endVertex();
            BufferUploader.drawWithShader(builder.end());
            pose.popPose();
        }
    }

    private static void prepare() {
        if (light != null) {
            return;
        }
        light = disc(16.0F);
        dark = disc(-16.0F);
        stars = upload(starField());
    }

    private static VertexBuffer disc(float height) {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        float radius = Math.signum(height) * 512.0F;
        builder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION);
        builder.vertex(0.0, height, 0.0).endVertex();
        for (int degrees = -180; degrees <= 180; degrees += 45) {
            builder.vertex(radius * Mth.cos(degrees * Mth.DEG_TO_RAD), height, 512.0F * Mth.sin(degrees * Mth.DEG_TO_RAD)).endVertex();
        }
        return upload(builder.end());
    }

    private static VertexBuffer upload(BufferBuilder.RenderedBuffer rendered) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(rendered);
        VertexBuffer.unbind();
        return buffer;
    }

    private static BufferBuilder.RenderedBuffer starField() {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        RandomSource random = RandomSource.create(10842L);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        for (int star = 0; star < 1500; star++) {
            double x = random.nextFloat() * 2.0F - 1.0F;
            double y = random.nextFloat() * 2.0F - 1.0F;
            double z = random.nextFloat() * 2.0F - 1.0F;
            double size = 0.15F + random.nextFloat() * 0.1F;
            double length = x * x + y * y + z * z;
            if (length < 1.0 && length > 0.01) {
                length = 1.0 / Math.sqrt(length);
                x *= length;
                y *= length;
                z *= length;
                double yaw = Math.atan2(x, z);
                double yawSin = Math.sin(yaw);
                double yawCos = Math.cos(yaw);
                double pitch = Math.atan2(Math.sqrt(x * x + z * z), y);
                double pitchSin = Math.sin(pitch);
                double pitchCos = Math.cos(pitch);
                double roll = random.nextDouble() * Math.PI * 2.0;
                double rollSin = Math.sin(roll);
                double rollCos = Math.cos(roll);
                for (int corner = 0; corner < 4; corner++) {
                    double cornerX = ((corner & 2) - 1) * size;
                    double cornerY = ((corner + 1 & 2) - 1) * size;
                    double rolledX = cornerX * rollCos - cornerY * rollSin;
                    double rolledY = cornerY * rollCos + cornerX * rollSin;
                    double pitchedY = rolledX * pitchSin;
                    double pitchedZ = -rolledX * pitchCos;
                    builder.vertex(x * 100.0 + pitchedZ * yawSin - rolledY * yawCos, y * 100.0 + pitchedY,
                        z * 100.0 + rolledY * yawSin + pitchedZ * yawCos).endVertex();
                }
            }
        }
        return builder.end();
    }
}
