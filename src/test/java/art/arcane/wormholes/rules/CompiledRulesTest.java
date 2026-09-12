package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;

final class CompiledRulesTest {
    private static final RuleDocument DOCUMENT = new RuleDocument(List.of(
        new Rule("mobs", List.of(new Condition.EntityClass(Set.of(TravelerClass.MOB), false)),
            RuleOutcome.deny(RulesMessages.DENIED_ENTITY_CLASS.id()), List.of(), List.of()),
        new Rule("vip", List.of(new Condition.Permission("group.vip")),
            RuleOutcome.allow(), List.of(new Cost.Vault(new BigDecimal("5.00"))), List.of(new Effect.Velocity(1.5D)))),
        RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()), TraversalProfile.DEFAULT, 4L);

    private World world;
    private LocalPortal portal;

    @BeforeEach
    void freshWorld() {
        ConditionCache.clear();
        world = RulesTestSupport.world("compiled");
        portal = RulesTestSupport.portal(world, PortalType.PORTAL);
    }

    @Test
    void firstMatchingRuleDecidesAndTheDefaultCoversTheRest() {
        CompiledRules compiled = CompiledRules.compile(DOCUMENT);
        FakeRulesEnvironment environment = new FakeRulesEnvironment().permission("group.vip");
        RulesTestSupport.FakeTraveler vip = RulesTestSupport.FakeTraveler.player("vip", origin(), Set.of());

        CompiledRules.Match allowed = compiled.evaluate(context(vip.player(), vip.player(), environment, 1000L));
        assertEquals("vip", allowed.rule().id());
        assertTrue(allowed.outcome().allowed());
        assertEquals(1, allowed.costs().size());
        assertEquals(1, allowed.effects().size());

        RulesTestSupport.FakeTraveler zombie = RulesTestSupport.FakeTraveler.entity("zombie", origin(), Mob.class);
        CompiledRules.Match mob = compiled.evaluate(context(zombie.entity(), null, environment, 1000L));
        assertEquals("mobs", mob.rule().id());
        assertEquals(RulesMessages.DENIED_ENTITY_CLASS.id(), mob.outcome().reason());

        RulesTestSupport.FakeTraveler stranger = RulesTestSupport.FakeTraveler.player("stranger", origin(), Set.of());
        CompiledRules.Match fallback = compiled.evaluate(context(stranger.player(), stranger.player(),
            new FakeRulesEnvironment(), 1000L));
        assertNull(fallback.rule());
        assertEquals(RulesMessages.DENIED_DEFAULT.id(), fallback.outcome().reason());
    }

    @Test
    void expensiveConditionsEvaluateOncePerCacheWindow() {
        CompiledRules compiled = CompiledRules.compile(new RuleDocument(List.of(
            new Rule("level", List.of(new Condition.Papi("%player_level%", Condition.Comparator.GREATER_OR_EQUAL, "30")),
                RuleOutcome.allow(), List.of(), List.of())),
            RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()), TraversalProfile.DEFAULT, 1L));
        FakeRulesEnvironment environment = new FakeRulesEnvironment().placeholder("%player_level%", "42");
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("papi", origin(), Set.of());

        assertTrue(compiled.evaluate(context(player.player(), player.player(), environment, 5000L)).outcome().allowed());
        assertTrue(compiled.evaluate(context(player.player(), player.player(), environment, 5500L)).outcome().allowed());
        assertEquals(1, environment.placeholderCalls());

        assertTrue(compiled.evaluate(context(player.player(), player.player(), environment, 9000L)).outcome().allowed());
        assertEquals(2, environment.placeholderCalls());
    }

    @Test
    void cheapConditionsRunFirstSoExpensiveOnesNeverSeeAMismatch() {
        CompiledRules compiled = CompiledRules.compile(new RuleDocument(List.of(
            new Rule("players_only", List.of(
                new Condition.Papi("%player_level%", Condition.Comparator.EQUALS, "1"),
                new Condition.Advancement("minecraft:story/mine_diamond"),
                new Condition.EntityClass(Set.of(TravelerClass.PLAYER), false)),
                RuleOutcome.allow(), List.of(), List.of())),
            RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()), TraversalProfile.DEFAULT, 1L));
        FakeRulesEnvironment environment = new FakeRulesEnvironment();
        RulesTestSupport.FakeTraveler zombie = RulesTestSupport.FakeTraveler.entity("zombie", origin(), Mob.class);

        assertEquals(RulesMessages.DENIED_DEFAULT.id(),
            compiled.evaluate(context(zombie.entity(), null, environment, 1000L)).outcome().reason());
        assertEquals(0, environment.placeholderCalls());
        assertEquals(0, environment.advancementCalls());
    }

    @Test
    void arrivalGatingOnlyConsidersTravelerClassAndTypeRules() {
        CompiledRules compiled = CompiledRules.compile(new RuleDocument(List.of(
            new Rule("no_permission", List.of(new Condition.Permission("group.vip")),
                RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()), List.of(), List.of()),
            new Rule("no_mobs", List.of(new Condition.EntityClass(Set.of(TravelerClass.MOB), false)),
                RuleOutcome.deny(RulesMessages.DENIED_ENTITY_CLASS.id()), List.of(), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 1L));
        FakeRulesEnvironment environment = new FakeRulesEnvironment().permission("group.vip");
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("arriver", origin(), Set.of());
        RulesTestSupport.FakeTraveler zombie = RulesTestSupport.FakeTraveler.entity("zombie", origin(), Mob.class);

        CompiledRules.Match arrivingPlayer = compiled.evaluateTravelerFilters(context(player.player(), player.player(), environment, 1L));
        assertNull(arrivingPlayer.rule());
        assertTrue(arrivingPlayer.outcome().allowed());

        CompiledRules.Match arrivingMob = compiled.evaluateTravelerFilters(
            context(zombie.entity(), null, new FakeRulesEnvironment(), 1L));
        assertEquals("no_mobs", arrivingMob.rule().id());
    }

    /**
     * The arrival-order case from the headline review: a rule that already settled the traveler's case
     * must not be skipped, or a lower catch-all refuses someone an earlier rule allowed.
     */
    @Test
    void arrivalStopsAtTheFirstMatchingRuleSoALowerCatchAllCannotOverruleIt() {
        CompiledRules compiled = CompiledRules.compile(new RuleDocument(List.of(
            new Rule("vip", List.of(new Condition.Permission("group.vip")),
                RuleOutcome.allow(), List.of(), List.of()),
            new Rule("no_players", List.of(new Condition.EntityClass(Set.of(TravelerClass.PLAYER), false)),
                RuleOutcome.deny(RulesMessages.DENIED_ENTITY_CLASS.id()), List.of(), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 1L));
        RulesTestSupport.FakeTraveler holder = RulesTestSupport.FakeTraveler.player("holder", origin(), Set.of());
        RulesTestSupport.FakeTraveler stranger = RulesTestSupport.FakeTraveler.player("stranger", origin(), Set.of());

        CompiledRules.Match allowed = compiled.evaluateTravelerFilters(
            context(holder.player(), holder.player(), new FakeRulesEnvironment().permission("group.vip"), 1L));
        assertNull(allowed.rule());
        assertTrue(allowed.outcome().allowed());

        CompiledRules.Match refused = compiled.evaluateTravelerFilters(
            context(stranger.player(), stranger.player(), new FakeRulesEnvironment(), 1L));
        assertEquals("no_players", refused.rule().id());
    }

    @Test
    void compilingKeepsTheDocumentRevisionAndProfile() {
        CompiledRules compiled = CompiledRules.compile(DOCUMENT);

        assertEquals(4L, compiled.revision());
        assertSame(TraversalProfile.DEFAULT, compiled.profile());
    }

    private Location origin() {
        return new Location(world, 0.0D, 64.0D, 1.0D);
    }

    private RuleContext context(org.bukkit.entity.Entity traveler, org.bukkit.entity.Player player,
                                RulesEnvironment environment, long nowMillis) {
        return new RuleContext(portal, traveler, player, nowMillis, false, environment);
    }
}
