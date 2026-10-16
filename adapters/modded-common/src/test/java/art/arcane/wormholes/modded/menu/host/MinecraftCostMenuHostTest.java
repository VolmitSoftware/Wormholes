package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.CapturedItem;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.cost.CostEdit;
import art.arcane.wormholes.modded.MinecraftItemEncoding;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.MinecraftTravelCosts;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftCapturedItem;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftCostMenuHostTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("c0570000-0000-4000-8000-0000000000aa");

    private RegistryAccess.Frozen registries;
    private WormholesModRuntime runtime;
    private MinecraftPortalRegistry portals;
    private MinecraftTravelCosts costs;
    private MenuService menus;
    private ServerPlayer player;
    private MinecraftPortal portal;
    private MenuViewer viewer;
    private MinecraftCostMenuHost host;

    @Before
    public void setUp() {
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        runtime = mock(WormholesModRuntime.class);
        portals = mock(MinecraftPortalRegistry.class);
        costs = mock(MinecraftTravelCosts.class);
        menus = mock(MenuService.class);
        MinecraftServer server = mock(MinecraftServer.class);
        player = mock(ServerPlayer.class);
        portal = mock(MinecraftPortal.class);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.costs()).thenReturn(costs);
        when(runtime.menus()).thenReturn(menus);
        when(runtime.server()).thenReturn(server);
        when(server.registryAccess()).thenReturn(registries);
        when(portals.update(eq(player), eq(PORTAL), any())).thenAnswer(invocation -> {
            Consumer<MinecraftPortal> mutation = invocation.getArgument(2);
            mutation.accept(portal);
            return true;
        });
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftCostMenuHost(runtime);
    }

    @Test
    public void currencyAvailabilityComesFromTheTravelCosts() {
        assertFalse(host.currencyAvailable());

        when(costs.currencyAvailable()).thenReturn(true);

        assertTrue(host.currencyAvailable());
    }

    @Test
    public void clearQuantityAndCurrencyEditsGoThroughTheRegistryAndRefreshThePortalMenus() {
        assertTrue(host.edit(viewer, PORTAL, new CostEdit.Clear()));
        assertTrue(host.edit(viewer, PORTAL, new CostEdit.Quantity(12)));
        assertTrue(host.edit(viewer, PORTAL, new CostEdit.Currency("25")));

        verify(portal).clearTravelCost();
        verify(portal).setTravelCostQuantity(12);
        verify(portal).setCurrencyTravelCost("25");
        verify(menus, times(3)).invalidate(new MenuScope.Portal(PORTAL));
    }

    @Test
    public void aCapturedItemIsStoredAsTheEncodedSingleItemTemplate() {
        ItemStack held = new ItemStack(Items.DIAMOND, 7);

        assertTrue(host.edit(viewer, PORTAL, new CostEdit.Item(new MinecraftCapturedItem(held))));

        verify(portal).setItemTravelCost(MinecraftItemEncoding.encode(new ItemStack(Items.DIAMOND), registries));
    }

    @Test
    public void rejectedValuesForeignItemsAndRefusedUpdatesReportFailure() {
        doThrow(new IllegalArgumentException("too large")).when(portal).setCurrencyTravelCost("1e99");
        UUID foreignPortal = UUID.randomUUID();

        assertFalse(host.edit(viewer, PORTAL, new CostEdit.Currency("1e99")));
        assertFalse(host.edit(viewer, PORTAL, new CostEdit.Item(mock(CapturedItem.class))));
        assertFalse(host.edit(viewer, foreignPortal, new CostEdit.Quantity(3)));

        verify(portal, never()).setItemTravelCost(anyString());
        verify(portal, never()).setTravelCostQuantity(anyInt());
        verify(menus, never()).invalidate(any());
    }
}
