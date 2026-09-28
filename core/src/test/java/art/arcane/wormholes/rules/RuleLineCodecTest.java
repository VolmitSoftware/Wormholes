package art.arcane.wormholes.rules;

import art.arcane.wormholes.config.toml.RulesConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RuleLineCodecTest {
    @Test
    void escapedMessageSurvivesLineAndDocumentParsing() {
        String message = "path\\target;value=a=b";
        String line = RuleLineCodec.toLine(Map.of("kind", "MESSAGE", "message", message));
        Rule parsed = RulesMenuModel.addLine(empty(), line, new RulesConfig());
        assertEquals(List.of(new Effect.Message(message)), parsed.effects());
    }

    @Test
    void decimalScaleRemainsPartOfTheCost() {
        Rule parsed = RulesMenuModel.addLine(empty(), "kind=VAULT;amount=0.2500", new RulesConfig());
        assertEquals(List.of(new Cost.Vault(new BigDecimal("0.2500"))), parsed.costs());
    }

    @Test
    void nestedMatchersAndArrayValuesKeepTypedMeaning() {
        RulesConfig limits = new RulesConfig();
        Rule parsed = RulesMenuModel.addLine(empty(), "kind=HELD_ITEM;matcher.material=DIAMOND;matcher.exactMeta=true;hand=OFF", limits);
        parsed = RulesMenuModel.addLine(parsed, "kind=ENTITY_CLASS;classes=PLAYER,MOB;negate=true", limits);
        assertEquals(List.of(new Condition.HeldItem(new ItemMatcher("DIAMOND", null, true), Condition.Hand.OFF),
            new Condition.EntityClass(Set.of(TravelerClass.PLAYER, TravelerClass.MOB), true)), parsed.conditions());
    }

    private static Rule empty() {
        return new Rule("test", List.of(), RuleOutcome.allow(), List.of(), List.of());
    }
}
