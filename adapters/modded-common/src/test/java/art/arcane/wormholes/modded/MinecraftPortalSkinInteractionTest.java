package art.arcane.wormholes.modded;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalSkinInteractionTest extends MinecraftTestBase {
    @Test
    public void adminSkinRemovalConsumesTheFrameGestureBeforeOpeningMenus() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class, RETURNS_DEEP_STUBS);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        MinecraftPortal portal = mock(MinecraftPortal.class, RETURNS_DEEP_STUBS);
        when(player.isShiftKeyDown()).thenReturn(true);
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(player.isWithinBlockInteractionRange(BlockPos.ZERO, 0)).thenReturn(true);
        when(player.level()).thenReturn(level);
        when(runtime.portals().snapshot()).thenReturn(List.of(portal));
        when(runtime.portals().resolveLevel(portal)).thenReturn(level);
        when(runtime.portals().canManage(player, portal)).thenReturn(true);
        when(portal.getGeometry().containsOrAdjoinsBlock(0, 0, 0)).thenReturn(true);
        when(portal.getSurfaceSkin()).thenReturn("minecraft:stone");
        when(runtime.access().permission(player, "wormholes.admin")).thenReturn(true);
        assertTrue(MinecraftPortalInteractions.frame(runtime, player, InteractionHand.MAIN_HAND, BlockPos.ZERO));
        verify(runtime.menus().cosmetics()).applySurfaceSkinFromInteraction(player, portal, "");
        verify(runtime.menus(), never()).open(any(), any());
        assertTrue(MinecraftPortalInteractions.frame(runtime, player, InteractionHand.OFF_HAND, BlockPos.ZERO));
        verify(runtime.menus().cosmetics()).applySurfaceSkinFromInteraction(player, portal, "");
        when(portal.getSurfaceSkin()).thenReturn("");
        assertTrue(MinecraftPortalInteractions.frame(runtime, player, InteractionHand.MAIN_HAND, BlockPos.ZERO));
        verify(runtime.menus()).open(player, portal.getId());
    }

    @Test
    public void managementAccessAloneCannotSetOrRemoveSurfaceSkins() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class, RETURNS_DEEP_STUBS);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        when(portal.getSurfaceSkin()).thenReturn("minecraft:stone");
        MinecraftPortalMenus menus = spy(new MinecraftPortalMenus(runtime));
        doReturn(true).when(menus).ensureCanManage(player, portal);
        doReturn(false).when(menus).update(eq(player), eq(portal), any());
        MinecraftPortalCosmeticsMenu cosmetics = new MinecraftPortalCosmeticsMenu(menus);
        try (MockedStatic<MinecraftMenuText> notices = mockStatic(MinecraftMenuText.class)) {
            assertFalse(cosmetics.applySurfaceSkinFromInteraction(player, portal, "minecraft:glass"));
            assertFalse(cosmetics.applySurfaceSkinFromInteraction(player, portal, ""));
            verify(menus, never()).update(any(), any(), any());
            when(runtime.access().permission(player, "wormholes.admin")).thenReturn(true);
            assertTrue(cosmetics.applySurfaceSkinFromInteraction(player, portal, ""));
            verify(menus).update(eq(player), eq(portal), any());
            when(runtime.access().permission(player, "wormholes.admin")).thenReturn(false);
            assertFalse(cosmetics.applySurfaceSkinFromInteraction(player, portal, "minecraft:water"));
            verify(menus).update(eq(player), eq(portal), any());
        }
    }
}
