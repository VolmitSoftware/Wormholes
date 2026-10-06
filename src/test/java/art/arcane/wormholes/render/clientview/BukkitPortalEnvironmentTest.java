package art.arcane.wormholes.render.clientview;

import art.arcane.volmlib.nativelib.environment.WorldEnvironment;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class BukkitPortalEnvironmentTest {
    @Test
    public void nativeEnvironmentRetainsDestinationValuesAndConvertsAnglesToRadians() {
        WorldEnvironment.Color color = new WorldEnvironment.Color(0.2f, 0.4f, 0.6f);
        WorldEnvironment.ColorAlpha alpha = new WorldEnvironment.ColorAlpha(0.1f, 0.3f, 0.5f, 0.7f);
        WorldEnvironment source = new WorldEnvironment(1234L,
            new WorldEnvironment.Sky(WorldEnvironment.Skybox.END, 90, 180, 270, 0.3f, alpha, color, 2, 0.4f, 0.5f),
            new WorldEnvironment.Fog(color, 16, 128, 192, 256, color, -8, 96),
            new WorldEnvironment.Lighting(color, 0.8f, color, color), new WorldEnvironment.Clouds(alpha, 192),
            new WorldEnvironment.Dimension(-64, 384, true, WorldEnvironment.CardinalLighting.NETHER, 63, true, true, 256, true, 0.1F), WorldEnvironment.EyeMedium.WATER);
        OpticTransform transform = OpticTransform.of(AxisPermutation.of(Face.U, Face.E, Face.S), 100, 200, 300);

        ProjectionEnvironment.World world = new ProjectionEnvironment.World("test:destination", 72000L, "minecraft:plains", 63, 7, 15, 256, true, 0.1F, ProjectionEnvironment.EyeMedium.WATER, true);
        ProjectionEnvironment result = BukkitPortalEnvironment.convert(source, transform, world);

        ProjectionEnvironment.Color expectedColor = new ProjectionEnvironment.Color(0.2f, 0.4f, 0.6f);
        ProjectionEnvironment.ColorAlpha expectedAlpha = new ProjectionEnvironment.ColorAlpha(0.1f, 0.3f, 0.5f, 0.7f);
        assertEquals(1234L, result.gameTime());
        assertEquals(new ProjectionEnvironment.Sky(ProjectionEnvironment.Skybox.END, (float) Math.PI / 2, (float) Math.PI,
            (float) (Math.PI * 1.5), 0.3f, expectedAlpha, expectedColor, 2, 0.4f, 0.5f), result.sky());
        assertEquals(new ProjectionEnvironment.Fog(expectedColor, 16, 128, 192, 256, expectedColor, -8, 96), result.fog());
        assertEquals(new ProjectionEnvironment.Lighting(expectedColor, 0.8f, expectedColor, expectedColor), result.lighting());
        assertEquals(new ProjectionEnvironment.Clouds(expectedAlpha, 192), result.clouds());
        assertEquals(new ProjectionEnvironment.Dimension(-64, 384, true, ProjectionEnvironment.CardinalLighting.NETHER, 63, true), result.dimension());
        assertSame(transform, result.transform());
        assertSame(world, result.world());
    }
}
