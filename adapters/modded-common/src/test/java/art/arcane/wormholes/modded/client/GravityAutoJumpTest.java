package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.gravity.GravityHolder;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class GravityAutoJumpTest extends MinecraftTestBase {
    @Test
    public void everyGravityRotationAutoJumpsTowardItsLocalObstacle() {
        for (int index = 0; index < 48; index++) {
            AxisPermutation orientation = AxisPermutation.ofIndex(index);
            if (orientation.reflects()) {
                continue;
            }
            LocalPlayer player = player(new GravityFrame(orientation), 1.0D);
            assertTrue(GravityAutoJump.shouldJump(player, 0.2F, 0.0F));
        }
    }

    @Test
    public void aHalfSlabNeedsOnlyNormalSteppingAndATallWallCannotBeAutoJumped() {
        GravityFrame frame = MinecraftGravity.fromIndex(0);
        assertFalse(GravityAutoJump.shouldJump(player(frame, 0.5D), 0.2F, 0.0F));
        assertFalse(GravityAutoJump.shouldJump(player(frame, 2.0D), 0.2F, 0.0F));
    }

    @Test
    public void blockedHeadroomAndMovingBackwardsPreventAutoJumping() {
        LocalPlayer player = player(GravityFrame.WORLD, 1.0D);
        when(player.level().noCollision(eq(player), any(AABB.class))).thenReturn(false);
        assertFalse(GravityAutoJump.shouldJump(player, 0.2F, 0.0F));
        player = player(GravityFrame.WORLD, 1.0D);
        when(player.getYRot()).thenReturn(90.0F);
        assertFalse(GravityAutoJump.shouldJump(player, 0.2F, 0.0F));
    }

    private static LocalPlayer player(GravityFrame frame, double height) {
        LocalPlayer player = mock(LocalPlayer.class, withSettings().extraInterfaces(GravityHolder.class));
        Level level = mock(Level.class);
        WorldBorder border = mock(WorldBorder.class);
        when(((GravityHolder) player).wormholesGravity()).thenReturn(frame);
        when(player.level()).thenReturn(level);
        when(player.position()).thenReturn(Vec3.ZERO);
        when(player.getSpeed()).thenReturn(0.13F);
        when(player.getYRot()).thenReturn(270.0F);
        when(player.getBbWidth()).thenReturn(0.6F);
        when(player.getBbHeight()).thenReturn(1.8F);
        AABB localBody = new AABB(-0.3D, 0.0D, -0.3D, 0.3D, 1.8D, 0.3D);
        when(player.getBoundingBox()).thenReturn(MinecraftGravity.transformBox(frame, localBody, Vec3.ZERO, false));
        when(level.getWorldBorder()).thenReturn(border);
        when(level.getEntityCollisions(any(Entity.class), any(AABB.class))).thenReturn(List.of());
        AABB obstacle = new AABB(0.6D, 0.0D, -2.0D, 3.0D, height, 2.0D);
        when(level.getBlockCollisions(any(Entity.class), any(AABB.class))).thenReturn(List.of(
            Shapes.create(MinecraftGravity.transformBox(frame, obstacle, Vec3.ZERO, false))));
        when(level.noCollision(any(Entity.class), any(AABB.class))).thenReturn(true);
        return player;
    }
}
