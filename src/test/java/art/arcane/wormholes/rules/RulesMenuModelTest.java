package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class RulesMenuModelTest {
    @Test
    void pagingMatchesTheDestinationMenuMath() {
        assertEquals(1, RulesMenuModel.pageCount(0));
        assertEquals(1, RulesMenuModel.pageCount(45));
        assertEquals(2, RulesMenuModel.pageCount(46));
        assertEquals(3, RulesMenuModel.pageCount(91));

        assertEquals(0, RulesMenuModel.clampPage(-4, 10));
        assertEquals(0, RulesMenuModel.clampPage(3, 10));
        assertEquals(1, RulesMenuModel.clampPage(1, 50));
        assertEquals(1, RulesMenuModel.clampPage(9, 50));
    }

    @Test
    void aPageHoldsAtMostOneScreenOfEntries() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 47; i++) {
            entries.add("rule" + i);
        }

        assertEquals(45, RulesMenuModel.page(entries, 0).size());
        assertEquals(List.of("rule45", "rule46"), RulesMenuModel.page(entries, 1));
        assertEquals(List.of(), RulesMenuModel.page(entries, 5));
    }

    @Test
    void aRuleSummaryCountsWhatItCarries() {
        Rule rule = new Rule("vip", List.of(new Condition.Permission("group.vip"), new Condition.Weather(Condition.WeatherKind.RAIN)),
            RuleOutcome.deny("rules.denied.default"), List.of(new Cost.Hunger(2)), List.of());

        assertEquals("DENY 2/1/0", RulesMenuModel.summary(rule));
    }

    @Test
    void aLineIsRoutedToTheSectionItsKindBelongsTo() {
        Rule rule = new Rule("vip", List.of(), RuleOutcome.allow(), List.of(), List.of());

        Rule withCondition = RulesMenuModel.addLine(rule, "kind=PERMISSION;node=group.vip");
        Rule withCost = RulesMenuModel.addLine(withCondition, "kind=HUNGER;points=3");
        Rule withEffect = RulesMenuModel.addLine(withCost, "kind=VELOCITY;multiplier=1.5");

        assertEquals(1, withEffect.conditions().size());
        assertEquals(1, withEffect.costs().size());
        assertEquals(1, withEffect.effects().size());
        assertInstanceOf(Condition.Permission.class, withEffect.conditions().getFirst());
        assertInstanceOf(Cost.Hunger.class, withEffect.costs().getFirst());
        assertInstanceOf(Effect.Velocity.class, withEffect.effects().getFirst());
    }

    @Test
    void anUnknownOrInvalidLineIsRefusedWithItsProblems() {
        Rule rule = new Rule("vip", List.of(), RuleOutcome.allow(), List.of(), List.of());

        RuleValidationException unknown = assertThrows(RuleValidationException.class,
            () -> RulesMenuModel.addLine(rule, "kind=TELEPATHY;node=x"));
        assertTrue(unknown.problems().getFirst().contains("TELEPATHY"), unknown.problems().getFirst());

        assertThrows(RuleValidationException.class, () -> RulesMenuModel.addLine(rule, "kind=HUNGER;points=99"));
    }

    @Test
    void everyLineOfARuleIsListedInSectionOrder() {
        Rule rule = new Rule("vip", List.of(new Condition.Permission("group.vip")), RuleOutcome.allow(),
            List.of(new Cost.Hunger(3)), List.of(new Effect.Velocity(1.5D)));

        List<String> lines = RulesMenuModel.lines(rule);

        assertEquals(3, lines.size());
        assertTrue(lines.get(0).startsWith("kind=PERMISSION"), lines.get(0));
        assertTrue(lines.get(1).startsWith("kind=HUNGER"), lines.get(1));
        assertTrue(lines.get(2).startsWith("kind=VELOCITY"), lines.get(2));
    }

    @Test
    void removingALineDropsExactlyThatEntry() {
        Rule rule = new Rule("vip", List.of(new Condition.Permission("a"), new Condition.Permission("b")),
            RuleOutcome.allow(), List.of(new Cost.Hunger(3)), List.of());

        Rule trimmed = RulesMenuModel.removeLine(rule, 1);

        assertEquals(List.of(new Condition.Permission("a")), trimmed.conditions());
        assertEquals(1, trimmed.costs().size());
        assertEquals(rule, RulesMenuModel.removeLine(rule, 9));
    }

    @Test
    void rulesAreAddedRenamedAndRemovedOnTheDocument() {
        RuleDocument document = RuleDocument.EMPTY;

        RuleDocument withRule = RulesMenuModel.addRule(document, "vip");
        assertEquals(List.of("vip"), ids(withRule));

        RuleDocument withBoth = RulesMenuModel.addRule(withRule, "mobs");
        assertEquals(List.of("vip", "mobs"), ids(withBoth));

        assertEquals(withBoth, RulesMenuModel.addRule(withBoth, "vip"));
        assertEquals(List.of("mobs"), ids(RulesMenuModel.removeRule(withBoth, "vip")));
        assertEquals(withBoth, RulesMenuModel.removeRule(withBoth, "absent"));
    }

    @Test
    void replacingARuleKeepsItsPlaceInTheOrder() {
        RuleDocument document = RulesMenuModel.addRule(RulesMenuModel.addRule(RuleDocument.EMPTY, "first"), "second");
        Rule replacement = new Rule("first", List.of(new Condition.EntityClass(Set.of(TravelerClass.MOB), false)),
            RuleOutcome.deny("rules.denied.entity_class"), List.of(), List.of());

        RuleDocument updated = RulesMenuModel.replaceRule(document, replacement);

        assertEquals(List.of("first", "second"), ids(updated));
        assertEquals(replacement, updated.rules().getFirst());
    }

    private static List<String> ids(RuleDocument document) {
        return document.rules().stream().map(Rule::id).toList();
    }
}
