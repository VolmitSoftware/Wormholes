package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.BukkitCapturedItem;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.CapturedItem;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.cost.CostEdit;
import art.arcane.wormholes.portal.LocalPortal;

class BukkitCostMenuHostTest {
    private final UUID portalId = UUID.randomUUID();
    private VaultEconomy previousEconomy;
    private LocalPortal portal;
    private MenuViewer viewer;
    private BukkitCostMenuHost host;

    @BeforeEach
    void setUp() {
        previousEconomy = Wormholes.vaultEconomy;
        portal = mock(LocalPortal.class);
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(portalId)).thenReturn(portal);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        host = new BukkitCostMenuHost(portals);
    }

    @AfterEach
    void tearDown() {
        Wormholes.vaultEconomy = previousEconomy;
    }

    @Test
    void currencyIsAvailableOnlyWithAWorkingVaultEconomy() {
        Wormholes.vaultEconomy = null;
        assertFalse(host.currencyAvailable());

        VaultEconomy economy = mock(VaultEconomy.class);
        Wormholes.vaultEconomy = economy;
        assertFalse(host.currencyAvailable());

        when(economy.isAvailable()).thenReturn(true);
        assertTrue(host.currencyAvailable());
    }

    @Test
    void clearQuantityAndCurrencyEditsGoThroughThePortalSetters() {
        assertTrue(host.edit(viewer, portalId, new CostEdit.Clear()));
        assertTrue(host.edit(viewer, portalId, new CostEdit.Quantity(12)));
        assertTrue(host.edit(viewer, portalId, new CostEdit.Currency("25")));

        verify(portal).clearTravelCost();
        verify(portal).setVanillaTravelCostQuantity(12);
        verify(portal).setVaultTravelCost("25");
    }

    @Test
    void aCapturedItemBecomesTheSingleItemTemplate() {
        ItemStack dropped = mock(ItemStack.class);
        ItemStack single = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        when(dropped.clone()).thenReturn(single);
        when(single.clone()).thenReturn(copy);
        when(single.getType()).thenReturn(Material.DIAMOND);

        assertTrue(host.edit(viewer, portalId, new CostEdit.Item(new BukkitCapturedItem(dropped))));

        verify(single).setAmount(1);
        verify(portal).setVanillaTravelCostItem(copy);
    }

    @Test
    void rejectedValuesAndForeignItemsReportFailure() {
        doThrow(new IllegalArgumentException("too large")).when(portal).setVaultTravelCost("1e99");
        CapturedItem foreign = mock(CapturedItem.class);

        assertFalse(host.edit(viewer, portalId, new CostEdit.Currency("1e99")));
        assertFalse(host.edit(viewer, portalId, new CostEdit.Item(foreign)));

        verify(portal, never()).setVanillaTravelCostItem(any());
    }

    @Test
    void missingAndDestroyedPortalsAreNeverEdited() {
        LocalPortal destroyed = mock(LocalPortal.class);
        when(destroyed.isDestroyed()).thenReturn(true);
        UUID destroyedId = UUID.randomUUID();
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(destroyedId)).thenReturn(destroyed);
        BukkitCostMenuHost destroyedHost = new BukkitCostMenuHost(portals);

        assertFalse(host.edit(viewer, UUID.randomUUID(), new CostEdit.Quantity(3)));
        assertFalse(destroyedHost.edit(viewer, destroyedId, new CostEdit.Quantity(3)));

        verify(portal, never()).setVanillaTravelCostQuantity(anyInt());
        verify(destroyed, never()).setVanillaTravelCostQuantity(anyInt());
    }
}
