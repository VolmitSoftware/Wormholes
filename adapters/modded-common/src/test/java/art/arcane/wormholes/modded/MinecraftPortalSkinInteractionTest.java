package art.arcane.wormholes.modded;

import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.modded.menu.host.MinecraftPortalMenuHost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalSkinInteractionTest extends MinecraftTestBase {
    private static final UUID PLAYER = UUID.fromString("c1b2c3d4-0000-4000-8000-000000000001");
    private static final UUID PORTAL = UUID.fromString("c1b2c3d4-0000-4000-8000-0000000000aa");

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
        when(portal.getBuiltGeometry().containsOrAdjoinsBlock(0, 0, 0)).thenReturn(true);
        when(portal.getSurfaceSkin()).thenReturn("minecraft:stone");
        when(runtime.access().permission(player, "wormholes.admin")).thenReturn(true);
        assertTrue(MinecraftPortalInteractions.frame(runtime, player, InteractionHand.MAIN_HAND, BlockPos.ZERO));
        verify(runtime.portalMenuHost()).applySurfaceSkin(player, portal, "");
        verify(runtime.menus(), never()).open(any(), any());
        assertTrue(MinecraftPortalInteractions.frame(runtime, player, InteractionHand.OFF_HAND, BlockPos.ZERO));
        verify(runtime.portalMenuHost()).applySurfaceSkin(player, portal, "");
        when(portal.getSurfaceSkin()).thenReturn("");
        when(player.getUUID()).thenReturn(PLAYER);
        when(portal.getId()).thenReturn(PORTAL);
        assertTrue(MinecraftPortalInteractions.frame(runtime, player, InteractionHand.MAIN_HAND, BlockPos.ZERO));
        verify(runtime.menus()).open(PLAYER, PortalRoutes.home(PORTAL));
    }

    @Test
    public void managementAccessAloneCannotSetOrRemoveSurfaceSkins() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class, RETURNS_DEEP_STUBS);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        when(portal.getId()).thenReturn(PORTAL);
        when(portal.getSurfaceSkin()).thenReturn("minecraft:stone");
        when(runtime.portals().get(PORTAL)).thenReturn(portal);
        when(runtime.portals().canManage(player, portal)).thenReturn(true);
        when(runtime.portals().update(eq(player), eq(PORTAL), any())).thenReturn(false);
        MinecraftPortalMenuHost host = new MinecraftPortalMenuHost(runtime);
        try (MockedStatic<MinecraftMenuText> notices = mockStatic(MinecraftMenuText.class)) {
            assertFalse(host.applySurfaceSkin(player, portal, "minecraft:glass"));
            assertFalse(host.applySurfaceSkin(player, portal, ""));
            verify(runtime.portals(), never()).update(any(), any(), any());
            when(runtime.access().permission(player, "wormholes.admin")).thenReturn(true);
            assertTrue(host.applySurfaceSkin(player, portal, ""));
            verify(runtime.portals()).update(eq(player), eq(PORTAL), any());
            when(runtime.access().permission(player, "wormholes.admin")).thenReturn(false);
            assertFalse(host.applySurfaceSkin(player, portal, "minecraft:water"));
            verify(runtime.portals()).update(eq(player), eq(PORTAL), any());
            when(runtime.access().permission(player, "wormholes.admin")).thenReturn(true);
            when(runtime.portals().canManage(player, portal)).thenReturn(false);
            assertFalse(host.applySurfaceSkin(player, portal, "minecraft:glass"));
            verify(player).closeContainer();
            verify(runtime.portals()).update(eq(player), eq(PORTAL), any());
        }
    }
}
