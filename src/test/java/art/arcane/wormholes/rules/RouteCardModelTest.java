package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;

final class RouteCardModelTest {
    private World world;
    private LocalPortal portal;
    private RulesTestSupport.FakeTraveler viewer;

    @BeforeEach
    void install() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new RulesExtensionFactory()));
        world = RulesTestSupport.world("routecard");
        portal = RulesTestSupport.portal(world, PortalType.PORTAL);
        viewer = RulesTestSupport.FakeTraveler.player("viewer", new Location(world, 0.0D, 64.0D, 1.0D), Set.of());
        viewer.level(30);
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void aFreePortalWithNothingInTheWayReadsAsReady() {
        RouteCardModel.RouteCard card = build(allow(List.of()), 0L);

        assertEquals(portal.getName(), card.portal());
        assertTrue(card.free());
        assertTrue(card.affordable());
        assertEquals(RouteCardModel.RouteCard.State.READY, card.state());
    }

    @Test
    void aPricedPortalListsWhatItCostsAndWhetherTheViewerCanPayIt() {
        RouteCardModel.RouteCard affordable = build(allow(List.of(new Cost.Xp(5, true))), 0L);
        assertEquals("5 levels", affordable.price());
        assertTrue(affordable.affordable());

        viewer.level(2);
        RouteCardModel.RouteCard tooDear = build(allow(List.of(new Cost.Xp(5, true))), 0L);
        assertEquals("5 levels", tooDear.price());
        assertFalse(tooDear.affordable());
        assertEquals(RouteCardModel.RouteCard.State.READY, tooDear.state());
    }

    @Test
    void severalCostsReadAsOnePrice() {
        RouteCardModel.RouteCard card = build(allow(List.of(new Cost.Xp(5, true), new Cost.Hunger(3))), 0L);

        assertEquals("5 levels + 3 hunger", card.price());
    }

    @Test
    void aCoolingPortalReportsTheSecondsLeft() {
        RouteCardModel.RouteCard card = build(allow(List.of()), 4200L);

        assertEquals(RouteCardModel.RouteCard.State.COOLDOWN, card.state());
        assertEquals(5L, card.cooldownSeconds());
    }

    @Test
    void aRefusedPortalCarriesTheRefusalKeyAndOutranksTheCooldown() {
        CompiledRules.Match refused = new CompiledRules.Match(null,
            RuleOutcome.deny(RulesMessages.DENIED_TIME.id()), List.of(), List.of());

        RouteCardModel.RouteCard card = build(refused, 4200L);

        assertEquals(RouteCardModel.RouteCard.State.REFUSED, card.state());
        assertEquals(RulesMessages.DENIED_TIME.id(), card.refusalKey());
    }

    @Test
    void anEmptyChargePoolRefusesWithTheChargesReason() {
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        extension.setDocument(RuleDocument.EMPTY.withProfile(new TraversalProfile(0L, "", 0L, 1.0D, 1.0D, 2, 0)));
        extension.charges().consume(2, 0L);

        RouteCardModel.RouteCard card = build(allow(List.of(new Cost.Charge(1))), 0L);

        assertEquals(RouteCardModel.RouteCard.State.REFUSED, card.state());
        assertEquals(RulesMessages.DENIED_CHARGES.id(), card.refusalKey());
    }

    @Test
    void anUnlistedPortalHidesItsDestination() {
        RouteCardModel.RouteCard listed = RouteCardModel.build(portal, viewer.player(), allow(List.of()), 0L,
            portal.extension(RulesPortalExtension.class).charges(), true);
        RouteCardModel.RouteCard unlisted = RouteCardModel.build(portal, viewer.player(), allow(List.of()), 0L,
            portal.extension(RulesPortalExtension.class).charges(), false);

        assertEquals("", listed.destination());
        assertEquals("", unlisted.destination());
        assertFalse(unlisted.destinationKnown());
        assertFalse(listed.destinationKnown());
    }

    private CompiledRules.Match allow(List<Cost> costs) {
        return new CompiledRules.Match(null, RuleOutcome.allow(), costs, List.of());
    }

    private RouteCardModel.RouteCard build(CompiledRules.Match match, long cooldownRemainingMillis) {
        return RouteCardModel.build(portal, viewer.player(), match, cooldownRemainingMillis,
            portal.extension(RulesPortalExtension.class).charges(), true);
    }
}
