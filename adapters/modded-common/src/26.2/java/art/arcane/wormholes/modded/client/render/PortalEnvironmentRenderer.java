package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Matrix4fStack;

final class PortalEnvironmentRenderer implements AutoCloseable {
    private final Lightmap lightmap = new Lightmap();
    private final FogRenderer fog = new FogRenderer();
    private final Matrix4f view = new Matrix4f();
    private PortalClouds clouds;
    private EnvironmentState previous;
    private long receivedTime;
    private float previousSun;
    private float previousMoon;
    private float previousStar;
    private float blendTicks;
    private CloudStatus cloudStatus;
    private FogData fogData;
    private SkyRenderState sky;
    private int cloudColor;
    private float cloudHeight;
    private int cloudRange;
    private Vec3 cloudCamera;
    private long cloudTime;
    private float cloudPartialTicks;

    void prepare(EnvironmentState environment, CameraRenderState camera) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = minecraft.level.getGameTime();
        boolean changed = environment != previous;
        if (changed) {
            previousSun = sky == null ? environment.sky().sunAngle() : sky.sunAngle;
            previousMoon = sky == null ? environment.sky().moonAngle() : sky.moonAngle;
            previousStar = sky == null ? environment.sky().starAngle() : sky.starAngle;
            blendTicks = previous == null ? 0 : Math.clamp(environment.gameTime() - previous.gameTime(), 1L, 20L);
            receivedTime = now;
        }
        LightmapRenderState local = minecraft.gameRenderer.gameRenderState().lightmapRenderState;
        if (changed || local.needsUpdate) {
            lightmap.render(PortalEnvironment.light(environment, local));
        }
        Vec3d eye = environment.transform().inverse().point(new Vec3d(camera.pos.x, camera.pos.y, camera.pos.z));
        sky = PortalEnvironment.sky(environment, eye);
        float blend = blendTicks == 0 ? 1 : Math.clamp((now - receivedTime + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)) / blendTicks, 0.0f, 1.0f);
        sky.sunAngle = PortalEnvironment.angle(previousSun, sky.sunAngle, blend);
        sky.moonAngle = PortalEnvironment.angle(previousMoon, sky.moonAngle, blend);
        sky.starAngle = PortalEnvironment.angle(previousStar, sky.starAngle, blend);
        fogData = PortalEnvironment.fog(environment, camera, minecraft.options.getEffectiveRenderDistance(), minecraft.options.cloudRange().get());
        fog.updateBuffer(fogData);
        view.set(camera.viewRotationMatrix).mul(PortalProjection.rotation(environment.transform()));
        cloudStatus = minecraft.options.cloudStatus().get();
        if (cloudStatus != CloudStatus.OFF && environment.clouds().color().alpha() > 0.0f) {
            if (clouds == null) {
                clouds = new PortalClouds(minecraft.getResourceManager());
            }
            cloudColor = ARGB.colorFromFloat(environment.clouds().color().alpha(), environment.clouds().color().red(), environment.clouds().color().green(), environment.clouds().color().blue());
            cloudHeight = environment.clouds().height();
            cloudRange = minecraft.options.cloudRange().get();
            cloudCamera = new Vec3(eye.x(), eye.y(), eye.z());
            cloudTime = environment.gameTime() + now - receivedTime;
            cloudPartialTicks = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        } else {
            cloudStatus = CloudStatus.OFF;
        }
        previous = environment;
    }

    FogData fogData() {
        return fogData;
    }

    GpuBufferSlice fogBuffer() {
        return fog.getBuffer(FogRenderer.FogMode.WORLD);
    }

    GpuTextureView lightmap() {
        return lightmap.getTextureView();
    }

    void renderSky(SkyRenderer renderer) {
        if (sky.skybox == DimensionType.Skybox.NONE) {
            return;
        }
        renderWithView(RenderSystem.getModelViewStack(), view, () -> renderSkyState(renderer));
    }

    void renderClouds() {
        if (cloudStatus != CloudStatus.OFF && clouds != null) {
            renderWithView(RenderSystem.getModelViewStack(), view, () -> clouds.render(cloudColor, cloudStatus, cloudHeight, cloudRange, cloudCamera, cloudTime, cloudPartialTicks));
        }
    }

    private void renderSkyState(SkyRenderer renderer) {
        RenderSystem.setShaderFog(fogBuffer());
        PoseStack pose = new PoseStack();
        if (sky.skybox == DimensionType.Skybox.END) {
            renderer.renderEndSky();
            if (sky.endFlashIntensity > 1.0E-5F) {
                renderer.renderEndFlash(pose, sky.endFlashIntensity, sky.endFlashXAngle, sky.endFlashYAngle);
            }
            return;
        }
        renderer.renderSkyDisc(sky.skyColor);
        renderer.renderSunriseAndSunset(pose, sky.sunAngle, sky.sunriseAndSunsetColor);
        renderer.renderSunMoonAndStars(pose, sky.sunAngle, sky.moonAngle, sky.starAngle, sky.moonPhase, sky.rainBrightness, sky.starBrightness);
        if (sky.shouldRenderDarkDisc) {
            renderer.renderDarkDisc();
        }
    }

    static void renderWithView(Matrix4fStack modelView, Matrix4fc view, Runnable rendering) {
        modelView.pushMatrix();
        try {
            modelView.set(view);
            rendering.run();
        } finally {
            modelView.popMatrix();
        }
    }

    void endFrame() {
        fog.endFrame();
        if (clouds != null) {
            clouds.endFrame();
        }
    }

    @Override
    public void close() {
        if (clouds != null) {
            clouds.close();
        }
        fog.close();
        lightmap.close();
    }

    private static final class PortalClouds extends CloudRenderer {
        private PortalClouds(ResourceManager resources) {
            apply(prepare(resources, Profiler.get()), resources, Profiler.get());
        }
    }
}
