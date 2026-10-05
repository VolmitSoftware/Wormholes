package art.arcane.wormholes.modded;

import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.rules.RuleCostReservation;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.junit.BeforeClass;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftTravelCostsTest {
    @BeforeClass
    public static void bootstrap() {
        MinecraftTestBase.bootstrap();
        Items.DIAMOND.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
    }

    @Test
    public void exactItemNbtRoundTripPreservesTypedCustomDataAndRefundedInventory() {
        Fixture fixture = fixture();
        ItemStack template = new ItemStack(Items.DIAMOND);
        template.set(DataComponents.CUSTOM_NAME, Component.literal("Exact cost"));
        CompoundTag data = new CompoundTag();
        data.putByte("byte", (byte) 3);
        data.putInt("integer", 9);
        template.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        String encoded = MinecraftItemEncoding.encode(template, fixture.registries());
        ItemStack decoded = MinecraftItemEncoding.decode(encoded, fixture.registries());
        assertTrue(ItemStack.isSameItemSameComponents(template, decoded));
        fixture.inventory().setItem(0, template.copyWithCount(5));
        fixture.inventory().setItem(1, new ItemStack(Items.DIAMOND, 8));
        when(fixture.portal().setting("travelCost")).thenReturn(Map.of("type", "VANILLA", "item", encoded, "quantity", 3));

        MinecraftTravelCosts.Admission payment = fixture.costs().open(fixture.context());
        assertTrue(payment.allowed());
        assertEquals(2, fixture.inventory().getItem(0).getCount());
        payment.refund(TraversalRefundReason.TELEPORT_FAILED);
        payment.refund(TraversalRefundReason.TELEPORT_FAILED);
        assertEquals(5, fixture.inventory().getItem(0).getCount());
        assertEquals(8, fixture.inventory().getItem(1).getCount());
        fixture.costs().close();
    }

    @Test
    public void currencyDepartureCommitsOnceAndShutdownRefundsUnsettledDeparture() {
        Fixture fixture = fixture();
        MinecraftRuleCostSubject.Currency currency = mock(MinecraftRuleCostSubject.Currency.class);
        RuleCostReservation.CurrencyCharge first = mock(RuleCostReservation.CurrencyCharge.class);
        RuleCostReservation.CurrencyCharge second = mock(RuleCostReservation.CurrencyCharge.class);
        when(currency.canAfford(fixture.player(), BigDecimal.ONE)).thenReturn(true);
        when(currency.withdraw(fixture.player(), BigDecimal.ONE)).thenReturn(first, second);
        fixture.costs().currency(currency);
        when(fixture.portal().setting("travelCost")).thenReturn(Map.of("type", "VAULT", "amount", "1"));
        MinecraftTravelCosts.Admission successful = fixture.costs().open(fixture.context());
        successful.commit();
        successful.commit();
        successful.refund(TraversalRefundReason.TELEPORT_FAILED);
        verify(first).commit();
        verify(first, never()).refund();
        MinecraftTravelCosts.Admission abandoned = fixture.costs().open(fixture.context());
        assertTrue(abandoned.allowed());
        fixture.costs().close();
        verify(second).refund();
        verify(second, never()).commit();
    }

    @Test
    public void unaffordableBuiltInPriceRollsBackExternalProviders() {
        Fixture fixture = fixture();
        MinecraftTraversalCostProvider provider = mock(MinecraftTraversalCostProvider.class);
        TraversalQuote quote = TraversalQuote.payable("ticket");
        TraversalReceipt receipt = TraversalReceipt.of("ticket");
        when(provider.quote(any())).thenReturn(quote);
        when(provider.reserve(any(), any())).thenReturn(TraversalReservation.reserved(receipt));
        fixture.costs().register(new MinecraftTravelCosts.Registration(provider, "test", "test", 0, () -> true));
        when(fixture.portal().setting("travelCost")).thenReturn(Map.of("type", "VAULT", "amount", "1"));
        assertFalse(fixture.costs().open(fixture.context()).allowed());
        verify(provider).refund(receipt, TraversalRefundReason.CHARGE_ROLLBACK);
        verify(provider, never()).commit(any());
        fixture.costs().close();
    }

    private static Fixture fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        RegistryAccess.Frozen registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        MinecraftPortal portal = mock(MinecraftPortal.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        UUID playerId = UUID.randomUUID();
        UUID portalId = UUID.randomUUID();
        when(runtime.server()).thenReturn(server);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.configuration()).thenReturn(configuration);
        when(runtime.localization()).thenReturn(localization);
        when(localization.text(any(), any(), any())).thenReturn(Component.empty());
        WormholesSettings settings = mock(WormholesSettings.class);
        when(settings.getMain()).thenReturn(new MainConfig());
        when(configuration.settings()).thenReturn(settings);
        when(server.isSameThread()).thenReturn(true);
        when(server.registryAccess()).thenReturn(registries);
        when(player.getUUID()).thenReturn(playerId);
        when(portal.getId()).thenReturn(portalId);
        when(portals.get(portalId)).thenReturn(portal);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        when(player.getInventory()).thenReturn(inventory);
        MinecraftTraversalContext context = new MinecraftTraversalContext(UUID.randomUUID(), TraversalKind.LOCAL,
            player, portalId, "Test", new MinecraftTraversalContext.Location(level, Vec3.ZERO, 0, 0), Optional.empty());
        MinecraftTravelCosts costs = new MinecraftTravelCosts(runtime);
        costs.start();
        return new Fixture(costs, player, portal, inventory, context, registries);
    }

    private record Fixture(MinecraftTravelCosts costs, ServerPlayer player, MinecraftPortal portal, Inventory inventory,
                           MinecraftTraversalContext context, RegistryAccess registries) {
    }
}
