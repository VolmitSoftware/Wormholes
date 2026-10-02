package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesHud;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalPortalSurfaceSkinPermissionTest {
    @Test
    void ownersAndOperatorsNeedTheAdminNodeForBothSettingAndRemoval() {
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(true);
        LocalPortal portal = mock(LocalPortal.class);
        when(portal.getSurfaceSkin()).thenReturn("minecraft:stone");
        LocalPortalMenus menus = mock(LocalPortalMenus.class, CALLS_REAL_METHODS);
        doReturn(true).when(menus).ensureCanManage(player);
        LocalPortalCosmeticsMenu cosmetics = new LocalPortalCosmeticsMenu(portal, menus);
        try (MockedStatic<Wormholes> ignored = mockStatic(Wormholes.class, RETURNS_DEEP_STUBS);
             MockedStatic<WormholesHud> notices = mockStatic(WormholesHud.class)) {
            assertFalse(cosmetics.applySurfaceSkinFromInteraction(player, "minecraft:glass"));
            assertFalse(cosmetics.applySurfaceSkinFromInteraction(player, ""));
            verify(portal, never()).setSurfaceSkin(anyString());
            when(player.hasPermission("wormholes.admin")).thenReturn(true);
            assertTrue(cosmetics.applySurfaceSkinFromInteraction(player, "minecraft:glass"));
            assertTrue(cosmetics.applySurfaceSkinFromInteraction(player, ""));
            verify(portal).setSurfaceSkin("minecraft:glass");
            verify(portal).setSurfaceSkin("");
            when(player.hasPermission("wormholes.admin")).thenReturn(false);
            assertFalse(cosmetics.applySurfaceSkinFromInteraction(player, "minecraft:water"));
            verify(portal, never()).setSurfaceSkin("minecraft:water");
        }
    }
}
