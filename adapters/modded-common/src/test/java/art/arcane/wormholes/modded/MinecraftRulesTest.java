package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.rules.RuleOutcome;
import art.arcane.wormholes.rules.RuleAdmission;
import art.arcane.wormholes.rules.CompiledRules;
import art.arcane.wormholes.rules.Cost;
import art.arcane.wormholes.rules.Rule;
import art.arcane.wormholes.rules.Condition;
import art.arcane.wormholes.rules.TravelerClass;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.PluralSelector;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.PortalCooldowns;
import art.arcane.wormholes.rules.TraversalProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerPlayer;
import art.arcane.wormholes.util.Direction;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;

public class MinecraftRulesTest extends MinecraftTestBase {
    @Test
    public void everyRefusalRendersOnlyItsDeclaredArguments() {
        LocalizationSnapshot snapshot = LocalizationSnapshot.create(LocalizationCandidate.english(WormholesMessages.catalog(), PluralSelector.oneOther()));
        MinecraftPortal portal = portal(Map.of());
        portal.setName("Station");
        for (String reason : List.of("rules.denied.default", "rules.denied.entity_class", "rules.denied.key", "rules.denied.time", "unknown")) {
            Rule rule = new Rule("filter", List.of(new Condition.EntityClass(Set.of(TravelerClass.PLAYER), false)),
                RuleOutcome.deny(reason), List.of(), List.of());
            RuleAdmission.Decision decision = new RuleAdmission.DeniedOutcome(new CompiledRules.Match(rule, rule.outcome(), List.of(), List.of()));
            String message = MinecraftRules.refusalMessage(snapshot, portal, decision).getString();
            assertFalse(message.isBlank());
            if (reason.equals("rules.denied.entity_class")) {
                assertTrue(message.contains("player"));
            }
        }
        for (RuleAdmission.Decision decision : List.of(new RuleAdmission.DeniedCost(new Cost.Xp(3, true)),
            new RuleAdmission.DeniedCooldown(1200L), new RuleAdmission.DeniedCharges(null), RuleAdmission.CancelledWarmup.INSTANCE)) {
            assertFalse(MinecraftRules.refusalMessage(snapshot, portal, decision).getString().isBlank());
        }
    }

    @Test
    public void disconnectedPlayerReleasesCommittedCooldowns() {
        MinecraftRules rules = service();
        ServerPlayer player = mock(ServerPlayer.class);
        UUID playerId = UUID.randomUUID();
        UUID portalId = UUID.randomUUID();
        when(player.getUUID()).thenReturn(playerId);
        PortalCooldowns.stamp(playerId, portalId, "shared", 60000L, System.currentTimeMillis());
        rules.disconnected(player);
        assertEquals(0L, PortalCooldowns.remainingMillis(playerId, portalId, "shared", System.currentTimeMillis()));
    }

    @Test
    public void changingWarmupPortalRetriesOnlyTheNewPortal() throws InterruptedException {
        WormholesModRuntime runtime = runtime();
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        MinecraftTravelCosts costs = mock(MinecraftTravelCosts.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.level()).thenReturn(level);
        when(player.isAlive()).thenReturn(true);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.costs()).thenReturn(costs);
        when(runtime.access()).thenReturn(access);
        when(costs.ruleSubject(player)).thenReturn(mock(MinecraftRuleCostSubject.class));
        MinecraftPortal first = portal(Map.of());
        MinecraftPortal second = portal(Map.of());
        first.setRuleDocument(RuleDocument.EMPTY.withProfile(new TraversalProfile(0, "", 30000, 1, 1, 0, 0)));
        second.setRuleDocument(RuleDocument.EMPTY.withProfile(new TraversalProfile(0, "", 1, 1, 1, 0, 0)));
        when(portals.get(first.getId())).thenReturn(first);
        when(portals.get(second.getId())).thenReturn(second);
        MinecraftRules rules = new MinecraftRules(runtime);
        AtomicInteger firstRetries = new AtomicInteger();
        AtomicInteger secondRetries = new AtomicInteger();
        assertFalse(rules.depart(player, first, firstRetries::incrementAndGet));
        assertFalse(rules.depart(player, second, secondRetries::incrementAndGet));
        Thread.sleep(10L);
        rules.tick();
        assertEquals(0, firstRetries.get());
        assertEquals(1, secondRetries.get());
    }

    @Test
    public void deniedEntityIsPushedTowardItsApproachSideWithProfileScale() {
        WormholesModRuntime runtime = runtime();
        MinecraftRules rules = new MinecraftRules(runtime);
        when(runtime.rules()).thenReturn(rules);
        MainConfig main = runtime.configuration().settings().getMain();
        main.portalPushbackMultiplier = 2.0D;
        main.enableParticles = false;
        Entity traveler = mock(Entity.class);
        ServerLevel level = mock(ServerLevel.class);
        when(traveler.getUUID()).thenReturn(UUID.randomUUID());
        when(traveler.level()).thenReturn(level);
        traveler.zo = 2.0D;
        MinecraftPortal portal = portal(Map.of());
        portal.setRuleDocument(RuleDocument.EMPTY.withDefaultOutcome(RuleOutcome.deny("rules.denied.default"))
            .withProfile(new TraversalProfile(0, "", 0, 0.5D, 0, 0, 0)));
        assertFalse(rules.depart(traveler, portal, () -> { }));
        ArgumentCaptor<Vec3> velocity = ArgumentCaptor.forClass(Vec3.class);
        verify(traveler).setDeltaMovement(velocity.capture());
        ArgumentCaptor<MinecraftWormholesApi.Event> event = ArgumentCaptor.forClass(MinecraftWormholesApi.Event.class);
        verify(runtime.api()).emit(event.capture());
        assertEquals(MinecraftWormholesApi.Kind.HANDOFF_DENIED, event.getValue().kind());
        assertEquals(0.0D, velocity.getValue().x, 0.0D);
        assertEquals(0.0D, velocity.getValue().y, 0.0D);
        assertEquals(3.0D, velocity.getValue().z, 0.0D);
    }

    @Test
    public void defaultPortalNeedsNoStoredRuleObjects() {
        MinecraftPortal portal = portal(Map.of());
        assertEquals(RuleDocument.EMPTY, portal.ruleDocument(new RulesConfig()));
        assertTrue(portal.ruleCharges().isEmpty());
        MinecraftRules rules = service();
        assertEquals(RuleDocument.EMPTY, rules.document(portal));
        assertEquals(0, rules.charges(portal));
    }

    @Test
    public void malformedRuleShapeDeniesWithoutThrowingDuringAdmission() {
        MinecraftRules rules = service();
        MinecraftPortal portal = portal(Map.of("rules.document", "invalid"));
        assertFalse(rules.document(portal).defaultOutcome().allowed());
        assertFalse(rules.document(portal).defaultOutcome().allowed());
    }

    @Test
    public void invalidRuleDocumentDeniesWithoutThrowingDuringAdmission() {
        MinecraftRules rules = service();
        MinecraftPortal portal = portal(Map.of("rules.document", Map.of("rules", List.of(Map.of("id", "bad",
            "conditions", List.of(Map.of("kind", "NOT_A_CONDITION")))))));
        assertFalse(rules.document(portal).defaultOutcome().allowed());
    }

    @Test
    public void malformedChargeShapeDeniesWithoutThrowingDuringAdmission() {
        MinecraftRules rules = service();
        assertFalse(rules.document(portal(Map.of("rules.charges", "invalid"))).defaultOutcome().allowed());
    }

    private static MinecraftRules service() {
        return new MinecraftRules(runtime());
    }

    private static WormholesModRuntime runtime() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = mock(WormholesSettings.class);
        when(runtime.configuration()).thenReturn(configuration);
        when(runtime.api()).thenReturn(mock(MinecraftWormholesApi.class));
        MinecraftNexus nexus = mock(MinecraftNexus.class);
        when(runtime.nexus()).thenReturn(nexus);
        when(nexus.dialedAddress(any())).thenReturn("");
        when(configuration.settings()).thenReturn(settings);
        when(settings.getRules()).thenReturn(new RulesConfig());
        when(settings.getMain()).thenReturn(new MainConfig());
        return runtime;
    }

    private static MinecraftPortal portal(Map<String, Object> rules) {
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(List.of(new GeometryVector(0, 64, 0), new GeometryVector(0, 65, 0)));
        UUID id = UUID.randomUUID();
        Map<String, Object> values = new HashMap<>(rules);
        values.put("owner", id.toString());
        values.put("type", "PORTAL");
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, geometry.getApertureCenter(),
            "Rules", PortalFrame.canonical(Direction.N), true), geometry, "minecraft:overworld", values));
    }
}
