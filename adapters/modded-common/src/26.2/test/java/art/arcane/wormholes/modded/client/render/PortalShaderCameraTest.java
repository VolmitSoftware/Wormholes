package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class PortalShaderCameraTest extends MinecraftTestBase {
    @Test
    public void shaderCameraProvidesPackedNativeColorAttributesWithAlpha() {
        EnvironmentAttributeProbe probe = probe();
        Map<EnvironmentAttribute<Integer>, Integer> expected = Map.of(
            EnvironmentAttributes.SKY_COLOR, 0xFF3366CC,
            EnvironmentAttributes.SUNRISE_SUNSET_COLOR, 0x00FF7F19,
            EnvironmentAttributes.FOG_COLOR, 0xFF3366CC,
            EnvironmentAttributes.WATER_FOG_COLOR, 0xFF3366CC,
            EnvironmentAttributes.BLOCK_LIGHT_TINT, 0xFFFFFFFF,
            EnvironmentAttributes.SKY_LIGHT_COLOR, 0xFFB2CCFF,
            EnvironmentAttributes.AMBIENT_LIGHT_COLOR, 0xFF000000,
            EnvironmentAttributes.CLOUD_COLOR, 0xFFFFFFFF);
        for (Map.Entry<EnvironmentAttribute<Integer>, Integer> entry : expected.entrySet()) {
            Integer actual = probe.getValue(entry.getKey(), 0.5F);
            assertEquals(entry.getKey().toString(), entry.getValue(), actual);
        }
    }

    @Test
    public void shaderCameraRetainsDestinationAnglesDistancesAndDefaultAttributes() {
        EnvironmentAttributeProbe probe = probe();
        assertEquals((float) Math.toDegrees(1.0D), probe.getValue(EnvironmentAttributes.SUN_ANGLE, 0.5F), 0.0F);
        assertEquals((float) Math.toDegrees(2.0D), probe.getValue(EnvironmentAttributes.MOON_ANGLE, 0.5F), 0.0F);
        assertEquals((float) Math.toDegrees(3.0D), probe.getValue(EnvironmentAttributes.STAR_ANGLE, 0.5F), 0.0F);
        assertEquals(MoonPhase.THIRD_QUARTER, probe.getValue(EnvironmentAttributes.MOON_PHASE, 0.5F));
        assertEquals(0.2F, probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, 0.5F), 0.0F);
        assertEquals(0.85F, probe.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, 0.5F), 0.0F);
        assertEquals(30.0F, probe.getValue(EnvironmentAttributes.FOG_START_DISTANCE, 0.5F), 0.0F);
        assertEquals(900.0F, probe.getValue(EnvironmentAttributes.FOG_END_DISTANCE, 0.5F), 0.0F);
        assertEquals(-8.0F, probe.getValue(EnvironmentAttributes.WATER_FOG_START_DISTANCE, 0.5F), 0.0F);
        assertEquals(96.0F, probe.getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, 0.5F), 0.0F);
        assertEquals(EnvironmentAttributes.NIGHT_VISION_COLOR.defaultValue(),
            probe.getValue(EnvironmentAttributes.NIGHT_VISION_COLOR, 0.5F));
    }

    private static EnvironmentAttributeProbe probe() {
        Minecraft minecraft = mock(Minecraft.class);
        EnvironmentState environment = PortalEnvironmentTest.environment(PortalEnvironmentTest.identity());
        try (MockedStatic<Minecraft> singleton = mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            return new PortalShaderCamera(environment, new CameraRenderState()).attributeProbe();
        }
    }
}
