package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.gravity.GravityHolder;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class GravityOverlayTest extends MinecraftTestBase {
    private static final Vec3 FEET = new Vec3(10.0D, 70.0D, -3.0D);

    @Test
    public void everyProperGravityFrameKeepsAllEightEyeProbesOutOfTheFloorAtEveryTravellerScale() {
        int rotations = 0;
        for (int index = 0; index < 48; index++) {
            if (AxisPermutation.ofIndex(index).reflects()) {
                continue;
            }
            rotations++;
            GravityFrame gravity = MinecraftGravity.fromIndex(index);
            AABB floor = MinecraftGravity.transformBox(gravity, new AABB(-5.0D, -1.0D, -5.0D, 5.0D, 0.0D, 5.0D), Vec3.ZERO, false).move(FEET);
            for (float scale : new float[] {0.5F, 1.0F, 3.0F}) {
                Player player = player(gravity, scale);
                for (int corner = 0; corner < 8; corner++) {
                    Vec3 position = GravityOverlay.sample(player, vanillaSample(player, scale, corner));
                    Vec3 local = MinecraftGravity.local(gravity, position.subtract(player.getEyePosition()));
                    assertEquals(offset(scale, corner).x, local.x, 1.0E-12D);
                    assertEquals(offset(scale, corner).y, local.y, 1.0E-12D);
                    assertEquals(offset(scale, corner).z, local.z, 1.0E-12D);
                    assertFalse("Gravity floor selected as an eye overlay in " + gravity + " at " + scale, floor.contains(position));
                }
            }
        }
        assertEquals(24, rotations);
    }

    @Test
    public void aRealObstructionAroundTheGravityRelativeEyeStillSelectsTheOverlayForEveryRotation() {
        for (int index = 0; index < 48; index++) {
            if (AxisPermutation.ofIndex(index).reflects()) {
                continue;
            }
            GravityFrame gravity = MinecraftGravity.fromIndex(index);
            Player player = player(gravity, 1.0F);
            AABB obstruction = MinecraftGravity.transformBox(gravity, new AABB(-0.5D, 1.2D, -0.5D, 0.5D, 2.0D, 0.5D),
                Vec3.ZERO, false).move(FEET);
            for (int corner = 0; corner < 8; corner++) {
                assertTrue("Embedded eye failed to select its obstruction in " + gravity,
                    obstruction.contains(GravityOverlay.sample(player, vanillaSample(player, 1.0F, corner))));
            }
        }
    }

    @Test
    public void ordinaryGravityKeepsTheNativeProbeObject() {
        Player player = player(GravityFrame.WORLD, 1.0F);
        Vec3 position = vanillaSample(player, 1.0F, 0);
        assertSame(position, GravityOverlay.sample(player, position));
    }

    @Test
    public void quarterTurnGroundContactReproducesTheNativeFeetCentredFalseOverlay() {
        GravityFrame gravity = MinecraftGravity.fromIndex(24);
        Player player = player(gravity, 1.0F);
        AABB floor = new AABB(FEET.x - 1.0D, FEET.y - 5.0D, FEET.z - 5.0D, FEET.x, FEET.y + 5.0D, FEET.z + 5.0D);
        Vec3 nativePosition = vanillaSample(player, 1.0F, 0);
        assertTrue(floor.contains(nativePosition));
        assertFalse(floor.contains(GravityOverlay.sample(player, nativePosition)));
    }

    private static Player player(GravityFrame gravity, float scale) {
        Player player = mock(Player.class, withSettings().extraInterfaces(GravityHolder.class));
        Vec3 eye = MinecraftGravity.eye(gravity, FEET, 1.62F * scale);
        when(((GravityHolder) player).wormholesGravity()).thenReturn(gravity);
        when(player.getX()).thenReturn(FEET.x);
        when(player.getZ()).thenReturn(FEET.z);
        when(player.getEyeY()).thenReturn(eye.y);
        when(player.getEyePosition()).thenReturn(eye);
        return player;
    }

    private static Vec3 vanillaSample(Player player, float scale, int corner) {
        Vec3 offset = offset(scale, corner);
        return new Vec3(player.getX() + offset.x, player.getEyeY() + offset.y, player.getZ() + offset.z);
    }

    private static Vec3 offset(float scale, int corner) {
        return new Vec3(((corner >> 0) % 2 - 0.5D) * 0.6D * scale * 0.8D,
            ((corner >> 1) % 2 - 0.5D) * 0.1D * scale, ((corner >> 2) % 2 - 0.5D) * 0.6D * scale * 0.8D);
    }
}
