package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientReflectionEntityTest extends MinecraftTestBase {
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

    @Test
    public void clearingRemovesEachReflectionFromTheLevelItWasSpawnedInEvenAfterALevelSwitch() throws ReflectiveOperationException {
        ClientReflectionEntity reflections = new ClientReflectionEntity();
        ClientLevel previous = mock(ClientLevel.class);
        Mannequin stale = mannequin(previous, ClientEntityIds.REFLECTION_MIN);
        Mannequin replaced = mannequin(previous, ClientEntityIds.REFLECTION_MIN + 1);
        when(previous.getEntity(ClientEntityIds.REFLECTION_MIN)).thenReturn(stale);
        track(reflections, 7, stale);
        track(reflections, 8, replaced);
        reflections.clear();
        verify(previous).removeEntity(ClientEntityIds.REFLECTION_MIN, Entity.RemovalReason.DISCARDED);
        verify(previous, never()).removeEntity(ClientEntityIds.REFLECTION_MIN + 1, Entity.RemovalReason.DISCARDED);
        assertEquals(0, reflections.size());
    }

    private static Mannequin mannequin(ClientLevel level, int id) {
        Mannequin mannequin = mock(Mannequin.class);
        when(mannequin.getId()).thenReturn(id);
        when(mannequin.level()).thenReturn(level);
        return mannequin;
    }

    @SuppressWarnings("unchecked")
    private static void track(ClientReflectionEntity reflections, int portalKey, Mannequin mannequin) throws ReflectiveOperationException {
        Class<?> type = Class.forName(ClientReflectionEntity.class.getName() + "$Reflection");
        Constructor<?> constructor = type.getDeclaredConstructor(Mannequin.class);
        constructor.setAccessible(true);
        Field field = ClientReflectionEntity.class.getDeclaredField("reflections");
        field.setAccessible(true);
        ((Int2ObjectOpenHashMap<Object>) field.get(reflections)).put(portalKey, constructor.newInstance(mannequin));
    }
}
