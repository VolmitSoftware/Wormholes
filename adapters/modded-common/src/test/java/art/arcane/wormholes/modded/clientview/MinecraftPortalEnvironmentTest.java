package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftPortalEnvironmentTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void samplesDestinationAttributesAndDimensionInsteadOfClientWorld() {
        ServerLevel world = mock(ServerLevel.class);
        DimensionType dimension = mock(DimensionType.class);
        EnvironmentAttributeSystem attributes = mock(EnvironmentAttributeSystem.class);
        when(world.environmentAttributes()).thenReturn(attributes);
        when(world.dimensionType()).thenReturn(dimension);
        when(world.getGameTime()).thenReturn(18000L);
        when(world.getRainLevel(1.0F)).thenReturn(0.6F);
        when(dimension.skybox()).thenReturn(DimensionType.Skybox.END);
        when(dimension.cardinalLightType()).thenReturn(CardinalLighting.Type.NETHER);
        when(dimension.minY()).thenReturn(-96);
        when(dimension.height()).thenReturn(512);
        when(attributes.getValue(any(EnvironmentAttribute.class), any(Vec3.class))).thenAnswer(invocation -> {
            EnvironmentAttribute<?> attribute = invocation.getArgument(0);
            return attribute.defaultValue();
        });
        Vec3 point = new Vec3(128.5D, 92.0D, -32.25D);
        when(attributes.getValue(eq(EnvironmentAttributes.SKY_COLOR), eq(point))).thenReturn(new Vector3f(1.25F, 0.4F, 0.8F));
        when(attributes.getValue(eq(EnvironmentAttributes.SUN_ANGLE), eq(point))).thenReturn(90.0F);
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.S,
            new GeometryVector(-128, 0, 0));
        ClientViewEnvironment result = MinecraftPortalEnvironment.capture(world, new GeometryVector(point.x, point.y, point.z), transform);
        assertEquals(ClientViewEnvironment.Skybox.END, result.sky().skybox());
        assertEquals(1.25F, result.sky().color().red(), 0.0001F);
        assertEquals((float) (Math.PI / 2), result.sky().sunAngle(), 0.0001F);
        assertEquals(0.6F, result.sky().rain(), 0.0001F);
        assertEquals(-96, result.dimension().minY());
        assertEquals(ClientViewEnvironment.CardinalLighting.NETHER, result.dimension().cardinalLighting());
        assertFalse(result.dimension().hasSkyLight());
        assertEquals(18000L, result.gameTime());
    }
}
