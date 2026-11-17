package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.effect.MobEffects;
import org.joml.Matrix4f;

/**
 * A streamed view's environment: its own lightmap texture built from the destination's light, its fog, and its sky,
 * with sky angles blended between environment updates so the sun does not jump.
 */
final class PortalEnvironmentRenderer implements AutoCloseable {
    private final DynamicTexture lightmap = new DynamicTexture(16, 16, false);
    private final Matrix4f view = new Matrix4f();
    private EnvironmentState previous;
    private long receivedTime;
    private float previousSun;
    private float previousStar;
    private float blendTicks;
    private float sunAngle;
    private float starAngle;
    private float lightBrightness = Float.NaN;
    private float lightNightVision = Float.NaN;
    private PortalEnvironment.Fog fog;
    private EnvironmentState environment;
    private boolean belowHorizon;

    void prepare(EnvironmentState environment, PortalViewCamera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        boolean changed = environment != previous;
        if (changed) {
            previousSun = this.environment == null ? environment.sky().sunAngle() : sunAngle;
            previousStar = this.environment == null ? environment.sky().starAngle() : starAngle;
            blendTicks = previous == null ? 0 : Math.clamp(environment.gameTime() - previous.gameTime(), 1L, 20L);
            receivedTime = now;
        }
        this.environment = environment;
        float brightness = minecraft.options.gamma().get().floatValue();
        float nightVision = nightVision(minecraft.player, camera.partialTicks());
        if (changed || brightness != lightBrightness || nightVision != lightNightVision) {
            NativeImage pixels = lightmap.getPixels();
            if (pixels != null) {
                PortalEnvironment.lightmap(pixels, environment, brightness, nightVision);
                lightmap.upload();
            }
            lightBrightness = brightness;
            lightNightVision = nightVision;
        }
        Vec3d eye = environment.transform().inverse().point(new Vec3d(camera.pos().x, camera.pos().y, camera.pos().z));
        belowHorizon = eye.y() < environment.dimension().horizonHeight();
        float blend = blendTicks == 0 ? 1 : Math.clamp((now - receivedTime + camera.partialTicks()) / blendTicks, 0.0F, 1.0F);
        sunAngle = PortalEnvironment.angle(previousSun, environment.sky().sunAngle(), blend);
        starAngle = PortalEnvironment.angle(previousStar, environment.sky().starAngle(), blend);
        fog = PortalEnvironment.fog(environment, camera.viewRotation(), minecraft.options.getEffectiveRenderDistance());
        view.set(camera.viewRotation()).mul(PortalProjection.rotation(environment.transform()));
        previous = environment;
    }

    private static float nightVision(LocalPlayer player, float partialTicks) {
        return player != null && player.hasEffect(MobEffects.NIGHT_VISION) ? GameRenderer.getNightVisionScale(player, partialTicks) : 0.0F;
    }

    PortalEnvironment.Fog fog() {
        return fog;
    }

    int lightmap() {
        return lightmap.getId();
    }

    /** Whether the destination has a sky to draw behind its terrain. */
    boolean skybox() {
        return environment.sky().skybox() != EnvironmentState.Skybox.NONE;
    }

    /** Sets the game's fog uniforms to this view's fog. */
    void applyFog() {
        RenderSystem.setShaderFogStart(fog.start());
        RenderSystem.setShaderFogEnd(fog.end());
        RenderSystem.setShaderFogShape(fog.shape());
        RenderSystem.setShaderFogColor(fog.color().x, fog.color().y, fog.color().z, 1.0F);
    }

    void renderSky(Matrix4f projection) {
        PortalSkyRenderer.render(environment.sky(), sunAngle, starAngle, view, projection, fog, belowHorizon);
    }

    @Override
    public void close() {
        lightmap.close();
    }
}
