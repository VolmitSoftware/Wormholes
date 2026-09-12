package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalGate;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;

final class RulesGateTest {
    private FakeRulesEnvironment environment;
    private WarmupTracker warmups;
    private RuleTraversalLedger ledger;
    private RulesGate gate;
    private World world;
    private LocalPortal portal;

    @BeforeEach
    void install() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new RulesExtensionFactory()));
        PortalCooldowns.clear();
        ConditionCache.clear();
        environment = new FakeRulesEnvironment();
        warmups = new WarmupTracker(new NoopPinner());
        ledger = new RuleTraversalLedger();
        gate = new RulesGate(environment, warmups, ledger);
        world = RulesTestSupport.world("gate");
        portal = RulesTestSupport.portal(world, PortalType.PORTAL);
    }

    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
        PortalCooldowns.clear();
    }

    @Test
    void theGateRunsAtTheRulesOrder() {
        assertEquals(TraversalGate.ORDER_RULES, gate.order());
    }

    @Test
    void aPortalWithoutRulesIsTheAllowIdentity() {
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("nobody", origin(), Set.of());

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(depart(player.entity(), 1000L)));
    }

    @Test
    void aTravelerClassFilterDeniesWithItsOwnReason() {
        document(new RuleDocument(List.of(new Rule("no_mobs",
            List.of(new Condition.EntityClass(Set.of(TravelerClass.MOB), false)),
            RuleOutcome.deny(RulesMessages.DENIED_ENTITY_CLASS.id()), List.of(), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));
        RulesTestSupport.FakeTraveler zombie = RulesTestSupport.FakeTraveler.entity("zombie", origin(), Mob.class);

        TraversalVerdict verdict = gate.evaluate(depart(zombie.entity(), 1000L));

        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, verdict);
        assertEquals(RulesMessages.DENIED_ENTITY_CLASS, deny.reason());
        assertEquals(Set.of("mode"), deny.args().names());
        assertEquals("mob", deny.args().require("mode").value());
    }

    @Test
    void aProfileCooldownDeniesWithTheSecondsLeft() {
        document(RuleDocument.EMPTY.withProfile(new TraversalProfile(5000L, "", 0L, 1.0D, 1.0D, 0, 0)));
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("waiting", origin(), Set.of());
        PortalCooldowns.stamp(player.id(), portal.getId(), "", 5000L, 1000L);

        TraversalVerdict verdict = gate.evaluate(depart(player.entity(), 2500L));

        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, verdict);
        assertEquals(RulesMessages.DENIED_COOLDOWN, deny.reason());
        assertEquals(Long.valueOf(4L), deny.args().require("seconds").value());
        assertEquals(portal.getName(), deny.args().require("portal").value());
    }

    @Test
    void anEmptyChargePoolDeniesBeforeAnythingIsSpent() {
        document(new RuleDocument(List.of(new Rule("charged", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Charge(2)), List.of())),
            RuleOutcome.allow(), new TraversalProfile(0L, "", 0L, 1.0D, 1.0D, 3, 0), 0L));
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        extension.charges().consume(2, 0L);
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("spender", origin(), Set.of());

        TraversalVerdict verdict = gate.evaluate(depart(player.entity(), 1000L));

        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, verdict);
        assertEquals(RulesMessages.DENIED_CHARGES, deny.reason());
        assertEquals(1, extension.charges().count());
    }

    @Test
    void theBypassPermissionSkipsEveryRule() {
        document(RuleDocument.EMPTY.withDefaultOutcome(RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id())));
        environment.permission(RulesGate.BYPASS_PERMISSION);
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("admin", origin(), Set.of());

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(depart(player.entity(), 1000L)));
    }

    @Test
    void arrivalGatingIgnoresRulesThatAreNotTravelerFilters() {
        document(new RuleDocument(List.of(
            new Rule("vip_only", List.of(new Condition.Permission("group.vip")),
                RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()), List.of(), List.of()),
            new Rule("no_mobs", List.of(new Condition.EntityClass(Set.of(TravelerClass.MOB), false)),
                RuleOutcome.deny(RulesMessages.DENIED_ENTITY_CLASS.id()), List.of(), List.of())),
            RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()), TraversalProfile.DEFAULT, 0L));
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("arriver", origin(), Set.of());
        RulesTestSupport.FakeTraveler zombie = RulesTestSupport.FakeTraveler.entity("zombie", origin(), Mob.class);

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(arrive(player.entity(), 1000L)));
        assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(arrive(zombie.entity(), 1000L)));
    }

    @Test
    void theDefaultDenialAppliesWhenNoRuleMatches() {
        document(RuleDocument.EMPTY.withDefaultOutcome(RuleOutcome.deny(RulesMessages.DENIED_TIME.id())));
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("late", origin(), Set.of());

        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(depart(player.entity(), 1000L)));

        assertEquals(RulesMessages.DENIED_TIME, deny.reason());
        assertEquals(portal.getName(), deny.args().require("portal").value());
    }

    @Test
    void aWarmupDefersUntilItElapsesAndThenAllows() {
        document(RuleDocument.EMPTY.withProfile(new TraversalProfile(0L, "", 3000L, 1.0D, 1.0D, 0, 0)));
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("patient", origin(), Set.of());

        assertInstanceOf(TraversalVerdict.Defer.class, gate.evaluate(depart(player.entity(), 1000L)));
        assertInstanceOf(TraversalVerdict.Defer.class, gate.evaluate(depart(player.entity(), 2000L)));
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(depart(player.entity(), 4000L)));
    }

    @Test
    void aCancelledWarmupRefusesTheNextCrossing() {
        document(RuleDocument.EMPTY.withProfile(new TraversalProfile(0L, "", 3000L, 1.0D, 1.0D, 0, 0)));
        RulesTestSupport.FakeTraveler player = RulesTestSupport.FakeTraveler.player("restless", origin(), Set.of());
        gate.evaluate(depart(player.entity(), 1000L));

        warmups.cancelOnMove(player.id(), new Location(world, 4.0D, 64.0D, 1.0D), 0.5D, 1500L);

        TraversalVerdict.Deny deny = assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(depart(player.entity(), 1600L)));
        assertEquals(RulesMessages.WARMUP_CANCELLED, deny.reason());
    }

    /**
     * The convoy case from the headline review: a rig member is screened with the whole gate chain, so a
     * costed portal used to refuse every vehicle and leashed mob, charge a player passenger for a crossing
     * they never make, and make a warmup portal impossible for a rig.
     */
    @Test
    void screeningARigMemberChargesNothingAndStartsNoWarmup() {
        document(new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Xp(5, true)), List.of())),
            RuleOutcome.allow(), new TraversalProfile(5000L, "", 3000L, 1.0D, 1.0D, 0, 0), 0L));
        RulesTestSupport.FakeTraveler passenger = RulesTestSupport.FakeTraveler.player("passenger", origin(), Set.of());
        passenger.level(30);
        RulesTestSupport.FakeTraveler horse = RulesTestSupport.FakeTraveler.entity("horse", origin(), Mob.class);
        PortalCooldowns.stamp(passenger.id(), portal.getId(), "", 5000L, 1000L);

        assertSame(TraversalVerdict.ALLOW, gate.evaluate(screen(passenger.entity(), 1500L)));
        assertSame(TraversalVerdict.ALLOW, gate.evaluate(screen(horse.entity(), 1500L)));

        assertEquals(30, passenger.level());
        assertEquals(0, ledger.size());
    }

    @Test
    void screeningARigMemberStillHonoursWhoTheMemberIs() {
        document(new RuleDocument(List.of(new Rule("no_mobs",
            List.of(new Condition.EntityClass(Set.of(TravelerClass.MOB), false)),
            RuleOutcome.deny(RulesMessages.DENIED_ENTITY_CLASS.id()), List.of(), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L));
        RulesTestSupport.FakeTraveler zombie = RulesTestSupport.FakeTraveler.entity("zombie", origin(), Mob.class);

        assertInstanceOf(TraversalVerdict.Deny.class, gate.evaluate(screen(zombie.entity(), 1000L)));
    }

    private void document(RuleDocument replacement) {
        portal.extension(RulesPortalExtension.class).setDocument(replacement);
    }

    private TraversalAttempt depart(Entity traveler, long nowMillis) {
        return new TraversalAttempt(TraversalPhase.DEPART, portal, traveler, null, null, nowMillis);
    }

    private TraversalAttempt screen(Entity traveler, long nowMillis) {
        return new TraversalAttempt(TraversalPhase.DEPART, portal, traveler, null, null, nowMillis, true);
    }

    private TraversalAttempt arrive(Entity traveler, long nowMillis) {
        return new TraversalAttempt(TraversalPhase.ARRIVE, portal, traveler, null, null, nowMillis);
    }

    private Location origin() {
        return new Location(world, 0.0D, 64.0D, 1.0D);
    }

    private static final class NoopPinner implements WarmupTracker.Pinner {
        @Override
        public void schedule(UUID playerId) {
        }

        @Override
        public void pin(UUID playerId, long secondsLeft) {
        }

        @Override
        public void cancelled(UUID playerId) {
        }
    }
}
