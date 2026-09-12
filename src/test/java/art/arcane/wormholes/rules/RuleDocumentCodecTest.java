package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.localization.RulesMessages;

final class RuleDocumentCodecTest {
    @Test
    void documentRoundTripsThroughJson() {
        RuleDocument document = new RuleDocument(List.of(new Rule("vip",
            List.of(new Condition.Permission("group.vip"), new Condition.TimeWindow(0, 12000)),
            RuleOutcome.allow(), List.of(new Cost.Vault(new BigDecimal("12.50"))), List.of(new Effect.Velocity(1.5D)))),
            RuleOutcome.deny(RulesMessages.DENIED_DEFAULT.id()),
            new TraversalProfile(5000L, "hub", 3000L, 1.0D, 1.0D, 0, 0), 3L);

        RuleDocument decoded = RuleDocumentCodec.fromJson(RuleDocumentCodec.toJson(document));

        assertEquals(document, decoded);
    }

    @Test
    void everyConditionCostAndEffectSurvivesTheRoundTrip() {
        UUID keyId = UUID.fromString("6d4d4b0d-0b0a-4f2a-9e8f-1c3b5a7d9e11");
        UUID ticketId = UUID.fromString("1a2b3c4d-5e6f-4071-8283-94a5b6c7d8e9");
        UUID authorId = UUID.fromString("aabbccdd-eeff-4011-8223-334455667788");
        ItemMatcher matcher = new ItemMatcher("DIAMOND", keyId, true);
        RuleDocument document = new RuleDocument(List.of(new Rule("everything", List.of(
            new Condition.EntityClass(Set.of(TravelerClass.PLAYER, TravelerClass.MOB), true),
            new Condition.EntityTypes(Set.of("minecraft:pig", "minecraft:cow")),
            new Condition.HeldItem(matcher, Condition.Hand.OFF),
            new Condition.OwnsItem(matcher, 4),
            new Condition.KeyItem(keyId),
            new Condition.Permission("group.vip"),
            new Condition.Advancement("minecraft:story/mine_diamond"),
            new Condition.PlayerState(Condition.PlayerField.HEALTH, 4.0D, 20.0D),
            new Condition.TimeWindow(13000, 23000),
            new Condition.Weather(Condition.WeatherKind.THUNDER),
            new Condition.MoonPhase(Set.of(0, 4)),
            new Condition.Redstone(1, -2, 3, true),
            new Condition.PortalState(true, "alpha.hub"),
            new Condition.Papi("%player_level%", Condition.Comparator.GREATER_OR_EQUAL, "30")),
            RuleOutcome.deny(RulesMessages.DENIED_KEY.id()),
            List.of(new Cost.Item(matcher, 3),
                new Cost.Vault(new BigDecimal("0.25")),
                new Cost.Xp(2, true),
                new Cost.Hunger(3),
                new Cost.Health(1.5D),
                new Cost.Durability(matcher, 12),
                new Cost.Charge(1),
                new Cost.Ticket(ticketId, 2)),
            List.of(new Effect.Velocity(2.0D),
                new Effect.Potion("SPEED", 200, 1, false),
                new Effect.Command("say hello", true, authorId),
                new Effect.Message("rules.denied.default"),
                new Effect.Sound("minecraft:block.beacon.activate", 0.5F, 1.25F),
                new Effect.StampCooldown("hub", 4000L)))),
            RuleOutcome.allow(), new TraversalProfile(1000L, "", 500L, 0.5D, 0.75D, 8, 2), 9L);

        assertEquals(document, RuleDocumentCodec.fromJson(RuleDocumentCodec.toJson(document)));
    }

    @Test
    void invalidDocumentReportsEveryProblem() {
        JSONObject json = RuleDocumentCodec.toJson(RuleDocument.EMPTY);
        json.getJSONObject("profile").put("warmupMillis", -1);
        json.getJSONObject("profile").put("cooldownMillis", 99_999_999L);

        RuleValidationException failure = assertThrows(RuleValidationException.class, () -> RuleDocumentCodec.fromJson(json));

        assertEquals(2, failure.problems().size());
    }

    @Test
    void unknownConditionKindIsReportedRatherThanThrown() {
        JSONObject json = RuleDocumentCodec.toJson(new RuleDocument(
            List.of(new Rule("r", List.of(new Condition.Permission("a")), RuleOutcome.allow(), List.of(), List.of())),
            RuleOutcome.allow(), TraversalProfile.DEFAULT, 1L));
        json.getJSONArray("rules").getJSONObject(0).getJSONArray("conditions").getJSONObject(0).put("kind", "TELEPATHY");

        RuleValidationException failure = assertThrows(RuleValidationException.class, () -> RuleDocumentCodec.fromJson(json));

        assertEquals(1, failure.problems().size());
        assertTrue(failure.problems().getFirst().contains("TELEPATHY"), failure.problems().getFirst());
    }
}
