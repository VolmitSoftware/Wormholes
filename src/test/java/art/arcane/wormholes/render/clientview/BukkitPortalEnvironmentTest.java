package art.arcane.wormholes.render.clientview;

import art.arcane.volmlib.nativelib.environment.WorldEnvironment;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
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
            new WorldEnvironment.Dimension(-64, 384, true, WorldEnvironment.CardinalLighting.NETHER, 63, true));
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.U, Direction.E, Direction.S,
            new GeometryVector(100, 200, 300));

        ClientViewEnvironment result = BukkitPortalEnvironment.convert(source, transform);

        ClientViewEnvironment.Color expectedColor = new ClientViewEnvironment.Color(0.2f, 0.4f, 0.6f);
        ClientViewEnvironment.ColorAlpha expectedAlpha = new ClientViewEnvironment.ColorAlpha(0.1f, 0.3f, 0.5f, 0.7f);
        assertEquals(1234L, result.gameTime());
        assertEquals(new ClientViewEnvironment.Sky(ClientViewEnvironment.Skybox.END, (float) Math.PI / 2, (float) Math.PI,
            (float) (Math.PI * 1.5), 0.3f, expectedAlpha, expectedColor, 2, 0.4f, 0.5f), result.sky());
        assertEquals(new ClientViewEnvironment.Fog(expectedColor, 16, 128, 192, 256, expectedColor, -8, 96), result.fog());
        assertEquals(new ClientViewEnvironment.Lighting(expectedColor, 0.8f, expectedColor, expectedColor), result.lighting());
        assertEquals(new ClientViewEnvironment.Clouds(expectedAlpha, 192), result.clouds());
        assertEquals(new ClientViewEnvironment.Dimension(-64, 384, true, ClientViewEnvironment.CardinalLighting.NETHER, 63, true), result.dimension());
        assertSame(transform, result.transform());
    }
}
