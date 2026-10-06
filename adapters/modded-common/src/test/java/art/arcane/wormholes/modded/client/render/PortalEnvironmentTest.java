package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Face;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PortalEnvironmentTest {
    @Test
    public void celestialInterpolationCrossesTheAngleBoundaryWithoutReversing() {
        float result = PortalEnvironment.angle((float) Math.toRadians(359), (float) Math.toRadians(1), 0.5f);
        assertEquals(2 * Math.PI, result, 0.00001);
    }

    @Test
    public void destinationLightKeepsClientVisionSettingsWithoutLocalSkyColors() {
        LightmapRenderState local = new LightmapRenderState();
        local.blockFactor = 1.45f;
        local.brightness = 0.6f;
        local.darknessEffectScale = 0.2f;
        local.nightVisionEffectIntensity = 0.4f;
        local.nightVisionColor = new Vector3f(0.3f, 0.5f, 0.7f);
        local.skyFactor = 0.1f;
        local.skyLightColor = new Vector3f(1, 0, 0);
        LightmapRenderState result = PortalEnvironment.light(environment(identity()), local);
        assertEquals(0.85f, result.skyFactor, 0);
        assertEquals(new Vector3f(0.7f, 0.8f, 1.0f), result.skyLightColor);
        assertEquals(1.45f, result.blockFactor, 0);
        assertEquals(0.6f, result.brightness, 0);
        assertEquals(0.2f, result.darknessEffectScale, 0);
        assertEquals(0.4f, result.nightVisionEffectIntensity, 0);
        assertSame(local.nightVisionColor, result.nightVisionColor);
        assertEquals(0.1f, local.skyFactor, 0);
    }

    @Test
    public void skyAndFogUseDestinationMetadataAndRequestedDistance() {
        ProjectionEnvironment environment = environment(identity());
        SkyRenderState sky = PortalEnvironment.sky(environment, new Vec3(0, 80, 0));
        assertEquals(new Vector3f(0.2f, 0.4f, 0.8f), sky.skyColor);
        assertEquals(MoonPhase.THIRD_QUARTER, sky.moonPhase);
        assertEquals(0.7f, sky.rainBrightness, 0.0001f);
        assertFalse(sky.shouldRenderDarkDisc);
        assertTrue(PortalEnvironment.sky(environment, new Vec3(0, 40, 0)).shouldRenderDarkDisc);
        CameraRenderState camera = new CameraRenderState();
        FogData fog = PortalEnvironment.fog(environment, camera, 13, 12);
        assertEquals(208, fog.renderDistanceEnd, 0);
        assertEquals(187.2f, fog.renderDistanceStart, 0.001f);
        assertEquals(30, fog.environmentalStart, 0);
        assertEquals(900, fog.environmentalEnd, 0);
        assertEquals(208, fog.skyEnd, 0);
        assertEquals(192, fog.cloudEnd, 0);
    }

    @Test
    public void skyDirectionUsesTheSameSignedAxesAsDestinationGeometry() {
        ProjectionEnvironment.Transform transform = new ProjectionEnvironment.Transform(Face.U, Face.E, Face.S,
            new Vec3(100, 200, 300));
        Vector3f mapped = PortalProjection.rotation(transform).transformDirection(new Vector3f(2, 3, 4));
        assertEquals(new Vector3f(3, 2, 4), mapped);
        assertEquals(new Vec3(2, 3, 4), transform.destinationPoint(103, 202, 304));
    }

    public static ProjectionEnvironment.Transform identity() {
        return new ProjectionEnvironment.Transform(Face.E, Face.U, Face.S, new Vec3(0, 0, 0));
    }

    public static ProjectionEnvironment environment(ProjectionEnvironment.Transform transform) {
        ProjectionEnvironment.Color sky = new ProjectionEnvironment.Color(0.2f, 0.4f, 0.8f);
        ProjectionEnvironment.Color white = new ProjectionEnvironment.Color(1, 1, 1);
        return new ProjectionEnvironment(6000,
            new ProjectionEnvironment.Sky(ProjectionEnvironment.Skybox.OVERWORLD, 1, 2, 3, 0.2f,
                new ProjectionEnvironment.ColorAlpha(1, 0.5f, 0.1f, 0), sky, 2, 0.3f, 0.1f),
            new ProjectionEnvironment.Fog(sky, 30, 900, 800, 700, sky, -8, 96),
            new ProjectionEnvironment.Lighting(white, 0.85f, new ProjectionEnvironment.Color(0.7f, 0.8f, 1),
                new ProjectionEnvironment.Color(0, 0, 0)),
            new ProjectionEnvironment.Clouds(new ProjectionEnvironment.ColorAlpha(1, 1, 1, 1), 192), transform,
            new ProjectionEnvironment.Dimension(-64, 384, true, ProjectionEnvironment.CardinalLighting.DEFAULT, 63, false),
            new ProjectionEnvironment.World("minecraft:overworld", 6000, "minecraft:plains", 63, 7, 15, 256, true, 0.1F, ProjectionEnvironment.EyeMedium.NONE, false));
    }
}
