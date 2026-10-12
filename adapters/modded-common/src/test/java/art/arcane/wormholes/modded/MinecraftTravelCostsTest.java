package art.arcane.wormholes.modded;

import art.arcane.wormholes.api.traversal.TraversalKind;
import art.arcane.wormholes.api.traversal.TraversalOutcome;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.api.traversal.internal.TraversalCostEngine;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

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

    @Test
    public void traversalListenersIsolateFailuresReportDenialsAndUnsubscribe() throws Exception {
        Fixture fixture = fixture();
        MinecraftTravelCosts.Listener broken = mock(MinecraftTravelCosts.Listener.class);
        when(broken.before(any())).thenThrow(new IllegalStateException("before failure"));
        doThrow(new IllegalStateException("completion failure")).when(broken).completed(any());
        fixture.costs().listen(broken);
        List<MinecraftTraversalCompletion> completed = new ArrayList<>();
        MinecraftTravelCosts.Listener denying = new MinecraftTravelCosts.Listener() {
            @Override
            public String before(MinecraftTraversalContext context) {
                return "Travel denied";
            }

            @Override
            public void completed(MinecraftTraversalCompletion completion) {
                completed.add(completion);
            }
        };
        AutoCloseable unsubscribe = fixture.costs().listen(denying);
        assertFalse(fixture.costs().open(fixture.context()).allowed());
        assertEquals(1, completed.size());
        assertEquals(MinecraftTraversalCompletion.Status.DENIED, completed.getFirst().status());
        assertEquals(TraversalOutcome.DENIED_BY_LISTENER, completed.getFirst().outcome());
        unsubscribe.close();
        MinecraftTravelCosts.Admission allowed = fixture.costs().open(fixture.context());
        assertTrue(allowed.allowed());
        allowed.commit();
        assertEquals(1, completed.size());
        fixture.costs().close();
    }

    @Test
    public void completionReportsCommittedRefundedAndShutdownSettlementsOnce() {
        Fixture fixture = fixture();
        List<MinecraftTraversalCompletion> completed = new ArrayList<>();
        fixture.costs().listen(new MinecraftTravelCosts.Listener() {
            @Override
            public String before(MinecraftTraversalContext context) {
                return null;
            }

            @Override
            public void completed(MinecraftTraversalCompletion completion) {
                completed.add(completion);
            }
        });
        MinecraftTravelCosts.Admission committed = fixture.costs().open(fixture.context());
        committed.commit();
        committed.commit();
        assertEquals(MinecraftTraversalCompletion.Status.COMMITTED, completed.getFirst().status());
        assertTrue(completed.getFirst().refundReason().isEmpty());
        MinecraftTravelCosts.Admission failed = fixture.costs().open(fixture.context());
        failed.refund(TraversalRefundReason.TELEPORT_FAILED);
        failed.refund(TraversalRefundReason.TELEPORT_FAILED);
        assertEquals(2, completed.size());
        assertEquals(MinecraftTraversalCompletion.Status.REFUNDED, completed.get(1).status());
        assertEquals(TraversalRefundReason.TELEPORT_FAILED, completed.get(1).refundReason().orElseThrow());
        fixture.costs().open(fixture.context());
        fixture.costs().close();
        assertEquals(3, completed.size());
        assertEquals(TraversalRefundReason.SERVER_SHUTDOWN, completed.get(2).refundReason().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> completed.getFirst().chargedProviderIds().add("other"));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftTraversalCompletion(fixture.context(),
            MinecraftTraversalCompletion.Status.REFUNDED, TraversalOutcome.ALLOWED_FREE, List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftTraversalCompletion(fixture.context(),
            MinecraftTraversalCompletion.Status.COMMITTED, TraversalOutcome.DENIED_BY_LISTENER, List.of(), Optional.empty()));
    }

    @Test
    public void externalExpiryRefundsTheBuiltInPriceAndPreventsLateCommitAfterReservationDelay() {
        AtomicLong clock = new AtomicLong(1_000L);
        Fixture fixture = fixture(clock::get, new MainConfig());
        MinecraftTraversalCostProvider provider = mock(MinecraftTraversalCostProvider.class);
        TraversalReceipt receipt = TraversalReceipt.of("external");
        when(provider.quote(any())).thenReturn(TraversalQuote.payable("external"));
        when(provider.reserve(any(), any())).thenReturn(TraversalReservation.reserved(receipt));
        fixture.costs().register(new MinecraftTravelCosts.Registration(provider, "external", "external", 0, () -> true));
        MinecraftRuleCostSubject.Currency currency = mock(MinecraftRuleCostSubject.Currency.class);
        RuleCostReservation.CurrencyCharge charge = mock(RuleCostReservation.CurrencyCharge.class);
        when(currency.canAfford(fixture.player(), BigDecimal.ONE)).thenReturn(true);
        when(currency.withdraw(fixture.player(), BigDecimal.ONE)).thenAnswer(invocation -> {
            clock.addAndGet(TraversalCostEngine.TICKET_TTL_MILLIS - 1L);
            return charge;
        });
        fixture.costs().currency(currency);
        when(fixture.portal().setting("travelCost")).thenReturn(Map.of("type", "VAULT", "amount", "1"));
        List<MinecraftTraversalCompletion> completed = completions(fixture.costs());
        MinecraftTravelCosts.Admission admission = fixture.costs().open(fixture.context());
        clock.addAndGet(2L);
        fixture.costs().tick();
        admission.commit();
        admission.commit();
        admission.refund(TraversalRefundReason.TELEPORT_FAILED);
        admission.refund(TraversalRefundReason.TELEPORT_FAILED);
        fixture.costs().close();
        assertEquals(1, completed.size());
        assertEquals(MinecraftTraversalCompletion.Status.REFUNDED, completed.getFirst().status());
        assertEquals(TraversalRefundReason.EXPIRED, completed.getFirst().refundReason().orElseThrow());
        verify(provider).refund(receipt, TraversalRefundReason.EXPIRED);
        verify(provider, never()).commit(any());
        verify(charge).refund();
        verify(charge, never()).commit();
    }

    @Test
    public void disabledExternalPricingStillReportsCommitRefundAndExpiryOnce() {
        AtomicLong clock = new AtomicLong(1_000L);
        MainConfig config = new MainConfig();
        config.traversalApiEnabled = false;
        Fixture fixture = fixture(clock::get, config);
        List<MinecraftTraversalCompletion> completed = completions(fixture.costs());
        MinecraftTravelCosts.Admission committed = fixture.costs().open(fixture.context());
        committed.commit();
        committed.commit();
        MinecraftTravelCosts.Admission refunded = fixture.costs().open(fixture.context());
        refunded.refund(TraversalRefundReason.TELEPORT_FAILED);
        refunded.refund(TraversalRefundReason.TELEPORT_FAILED);
        MinecraftTravelCosts.Admission expired = fixture.costs().open(fixture.context());
        clock.addAndGet(TraversalCostEngine.TICKET_TTL_MILLIS);
        fixture.costs().tick();
        expired.commit();
        fixture.costs().close();
        assertEquals(3, completed.size());
        assertEquals(MinecraftTraversalCompletion.Status.COMMITTED, completed.getFirst().status());
        assertEquals(TraversalRefundReason.TELEPORT_FAILED, completed.get(1).refundReason().orElseThrow());
        assertEquals(TraversalRefundReason.EXPIRED, completed.get(2).refundReason().orElseThrow());
        for (MinecraftTraversalCompletion completion : completed) {
            assertEquals(TraversalOutcome.DISABLED, completion.outcome());
        }
    }

    @Test
    public void expiryDuringBuiltInReservationRefundsTheLatePriceWithoutDuplicatingCompletion() {
        AtomicLong clock = new AtomicLong(1_000L);
        Fixture fixture = fixture(clock::get, new MainConfig());
        MinecraftRuleCostSubject.Currency currency = mock(MinecraftRuleCostSubject.Currency.class);
        RuleCostReservation.CurrencyCharge charge = mock(RuleCostReservation.CurrencyCharge.class);
        when(currency.canAfford(fixture.player(), BigDecimal.ONE)).thenReturn(true);
        when(currency.withdraw(fixture.player(), BigDecimal.ONE)).thenAnswer(invocation -> {
            clock.addAndGet(TraversalCostEngine.TICKET_TTL_MILLIS);
            fixture.costs().tick();
            return charge;
        });
        fixture.costs().currency(currency);
        when(fixture.portal().setting("travelCost")).thenReturn(Map.of("type", "VAULT", "amount", "1"));
        List<MinecraftTraversalCompletion> completed = completions(fixture.costs());
        MinecraftTravelCosts.Admission admission = fixture.costs().open(fixture.context());
        assertFalse(admission.allowed());
        admission.commit();
        admission.refund(TraversalRefundReason.TELEPORT_FAILED);
        fixture.costs().close();
        assertEquals(1, completed.size());
        assertEquals(TraversalRefundReason.EXPIRED, completed.getFirst().refundReason().orElseThrow());
        verify(charge).refund();
        verify(charge, never()).commit();
    }

    @Test
    public void shutdownRefundCallbacksCannotCreateNewAdmissions() {
        Fixture fixture = fixture();
        List<MinecraftTraversalCompletion> completed = new ArrayList<>();
        AtomicBoolean rejected = new AtomicBoolean();
        fixture.costs().listen(new MinecraftTravelCosts.Listener() {
            @Override
            public String before(MinecraftTraversalContext context) {
                return null;
            }

            @Override
            public void completed(MinecraftTraversalCompletion completion) {
                completed.add(completion);
                try {
                    fixture.costs().open(fixture.context());
                } catch (IllegalStateException expected) {
                    rejected.set(true);
                }
            }
        });
        fixture.costs().open(fixture.context());
        fixture.costs().close();
        assertEquals(1, completed.size());
        assertTrue(rejected.get());
        assertEquals(TraversalRefundReason.SERVER_SHUTDOWN, completed.getFirst().refundReason().orElseThrow());
    }

    private static List<MinecraftTraversalCompletion> completions(MinecraftTravelCosts costs) {
        List<MinecraftTraversalCompletion> completed = new ArrayList<>();
        costs.listen(new MinecraftTravelCosts.Listener() {
            @Override
            public String before(MinecraftTraversalContext context) {
                return null;
            }

            @Override
            public void completed(MinecraftTraversalCompletion completion) {
                completed.add(completion);
            }
        });
        return completed;
    }

    private static Fixture fixture() {
        return fixture(System::currentTimeMillis, new MainConfig());
    }

    private static Fixture fixture(LongSupplier clock, MainConfig config) {
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
        when(settings.getMain()).thenReturn(config);
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
        MinecraftTravelCosts costs = new MinecraftTravelCosts(runtime, clock);
        costs.start();
        return new Fixture(costs, player, portal, inventory, context, registries);
    }

    private record Fixture(MinecraftTravelCosts costs, ServerPlayer player, MinecraftPortal portal, Inventory inventory,
                           MinecraftTraversalContext context, RegistryAccess registries) {
    }
}
