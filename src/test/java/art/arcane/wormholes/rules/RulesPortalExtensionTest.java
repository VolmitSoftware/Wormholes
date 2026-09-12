package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;

final class RulesPortalExtensionTest {
    @AfterEach
    void clearHooks() {
        WormholesHooks.clear();
    }

    @Test
    void documentAndChargesSurviveTheSaveLoadRoundTrip() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new RulesExtensionFactory()));
        World world = RulesTestSupport.world("extension");
        LocalPortal source = RulesTestSupport.portal(world, PortalType.PORTAL);
        RulesPortalExtension extension = source.extension(RulesPortalExtension.class);
        assertNotNull(extension);

        extension.setDocument(new RuleDocument(
            List.of(new Rule("vip", List.of(new Condition.Permission("group.vip")), RuleOutcome.allow(), List.of(), List.of())),
            RuleOutcome.deny("rules.denied.default"),
            new TraversalProfile(5000L, "hub", 3000L, 1.0D, 1.0D, 6, 1), 0L));
        assertTrue(extension.charges().consume(2, 0L));

        JSONObject encoded = source.toJSON();
        LocalPortal target = RulesTestSupport.portal(world, PortalType.PORTAL);
        target.extension(RulesPortalExtension.class).load(encoded);

        assertEquals(extension.document(), target.extension(RulesPortalExtension.class).document());
        assertEquals(4, target.extension(RulesPortalExtension.class).charges().count());
    }

    @Test
    void settingDocumentBumpsTheRevision() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new RulesExtensionFactory()));
        LocalPortal portal = RulesTestSupport.portal(RulesTestSupport.world("revision"), PortalType.PORTAL);
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);

        assertEquals(0L, extension.document().revision());
        extension.setDocument(RuleDocument.EMPTY.withDefaultOutcome(RuleOutcome.deny("rules.denied.default")));
        assertEquals(1L, extension.document().revision());
        extension.setDocument(extension.document());
        assertEquals(2L, extension.document().revision());
    }

    @Test
    void onlyTheProfileAndCooldownGroupReplicate() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new RulesExtensionFactory()));
        World world = RulesTestSupport.world("sync");
        LocalPortal source = RulesTestSupport.portal(world, PortalType.PORTAL);
        source.extension(RulesPortalExtension.class).setDocument(RuleDocument.EMPTY
            .withProfile(new TraversalProfile(5000L, "hub", 3000L, 0.5D, 0.25D, 0, 0))
            .withRules(List.of(new Rule("secret", List.of(), RuleOutcome.deny("rules.denied.default"), List.of(), List.of()))));

        Map<String, String> settings = new LinkedHashMap<>();
        source.extensions().collectSync(settings);

        assertEquals(Map.of("rules.cooldownGroup", "hub", "rules.profile", "5000,3000,0.5,0.25"), settings);

        LocalPortal target = RulesTestSupport.portal(world, PortalType.PORTAL);
        target.extensions().applySync(settings);
        assertEquals("hub", target.extension(RulesPortalExtension.class).mirroredCooldownGroup());
        assertEquals(3000L, target.extension(RulesPortalExtension.class).mirroredProfile().warmupMillis());
        assertTrue(target.extension(RulesPortalExtension.class).document().rules().isEmpty());
    }

    @Test
    void invalidStoredDocumentFallsBackToEmptyRatherThanFailingTheLoad() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(new RulesExtensionFactory()));
        LocalPortal portal = RulesTestSupport.portal(RulesTestSupport.world("broken"), PortalType.PORTAL);
        JSONObject broken = new JSONObject().put("rules.document",
            new JSONObject().put("profile", new JSONObject().put("warmupMillis", -5L)));

        portal.extension(RulesPortalExtension.class).load(broken);

        assertEquals(RuleDocument.EMPTY, portal.extension(RulesPortalExtension.class).document());
    }
}
