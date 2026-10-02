package art.arcane.wormholes.modded.client;

import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientReflectionEntityTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void nativeSelfModelKeepsSourceFeetBodyHeadAndHandBeforeRenderReflection() {
        LocalPlayer player = mock(LocalPlayer.class);
        Mannequin reflection = mock(Mannequin.class);
        player.xOld = -12.25D;
        player.yOld = 64;
        player.zOld = 30.5D;
        player.yRotO = 25;
        player.xRotO = -10;
        player.yHeadRotO = 40;
        player.yHeadRot = 45;
        player.yBodyRotO = 20;
        player.yBodyRot = 30;
        when(player.getX()).thenReturn(-12.75D);
        when(player.getY()).thenReturn(64.25D);
        when(player.getZ()).thenReturn(30.75D);
        when(player.getYRot()).thenReturn(35F);
        when(player.getXRot()).thenReturn(-15F);
        when(player.getMainArm()).thenReturn(HumanoidArm.RIGHT);
        ClientReflectionEntity.followNativePose(reflection, player);
        verify(reflection).setOldPosAndRot(new Vec3(-12.25D, 64, 30.5D), 25, -10);
        verify(reflection).setPos(-12.75D, 64.25D, 30.75D);
        verify(reflection).setYRot(35);
        verify(reflection).setXRot(-15);
        verify(reflection).setMainArm(HumanoidArm.RIGHT);
        assertEquals(40, reflection.yHeadRotO, 0);
        assertEquals(45, reflection.yHeadRot, 0);
        assertEquals(20, reflection.yBodyRotO, 0);
        assertEquals(30, reflection.yBodyRot, 0);
    }
}
