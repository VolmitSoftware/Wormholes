package art.arcane.wormholes.rules;

import art.arcane.wormholes.config.toml.RulesConfig;
import java.util.Map;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pure part of the rules editor: paging, rule summaries, and the line form an operator types. A rule's
 * conditions, costs and effects are edited as the same {@code kind=...;field=value} lines the templates use, so
 * one uniform form covers every kind and every edit is validated by {@link RuleDocumentCodec} before it lands.
 */
public final class RulesMenuModel {
    public static final int ENTRIES_PER_PAGE = 45;

    private RulesMenuModel() {
    }

    public static int pageCount(int entries) {
        return Math.max(1, (entries + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    public static int clampPage(int page, int entries) {
        return Math.max(0, Math.min(page, pageCount(entries) - 1));
    }

    public static <T> List<T> page(List<T> entries, int page) {
        int from = page * ENTRIES_PER_PAGE;
        if (from >= entries.size() || from < 0) {
            return List.of();
        }
        return List.copyOf(entries.subList(from, Math.min(entries.size(), from + ENTRIES_PER_PAGE)));
    }

    /** Outcome and how many conditions, costs and effects the rule carries. */
    public static String summary(Rule rule) {
        return rule.outcome().kind().name() + " " + rule.conditions().size() + "/" + rule.costs().size()
            + "/" + rule.effects().size();
    }

    /** Every condition, then cost, then effect of a rule as an editable line. */
    public static List<String> lines(Rule rule) {
        Map<String, Object> encoded = RuleDocumentCodec.map(RuleDocumentCodec.array(RuleDocumentCodec.toJson(
            new RuleDocument(List.of(rule), RuleOutcome.allow(), TraversalProfile.DEFAULT, 0L)).get("rules")).getFirst());
        List<String> lines = new ArrayList<>();
        for (String section : List.of("conditions", "costs", "effects")) {
            for (Object entry : RuleDocumentCodec.array(encoded.get(section))) {
                lines.add(RuleLineCodec.toLine(RuleDocumentCodec.map(entry)));
            }
        }
        return lines;
    }

    /** Parses one line, routes it to the part of the rule its kind belongs to, and validates the result. */
    public static Rule addLine(Rule rule, String line, RulesConfig limits) {
        Map<String, Object> parsed = RuleLineCodec.fromLine(line);
        String kind = RuleDocumentCodec.string(parsed.get("kind"), "").trim().toUpperCase(Locale.ROOT);
        parsed.put("kind", kind);
        RuleDocumentCodec.Section section = RuleDocumentCodec.sectionOf(kind);
        if (section == null) {
            throw new RuleValidationException(List.of("unknown kind '" + kind + "'"));
        }
        Map<String, Object> encoded = RuleDocumentCodec.toJson(new RuleDocument(List.of(rule), RuleOutcome.allow(),
            TraversalProfile.DEFAULT, 0L));
        Map<String, Object> encodedRule = RuleDocumentCodec.map(RuleDocumentCodec.array(encoded.get("rules")).getFirst());
        String key = sectionKey(section);
        List<Object> entries = RuleDocumentCodec.array(encodedRule.get(key));
        entries.add(parsed);
        encodedRule.put(key, entries);
        encoded.put("rules", List.of(encodedRule));
        return RuleDocumentCodec.fromJson(encoded, limits).rules().getFirst();
    }

    /** Removes the line at {@code index} of {@link #lines(Rule)}; an index past the end changes nothing. */
    public static Rule removeLine(Rule rule, int index) {
        int conditions = rule.conditions().size();
        int costs = rule.costs().size();
        if (index < 0 || index >= conditions + costs + rule.effects().size()) {
            return rule;
        }
        if (index < conditions) {
            return new Rule(rule.id(), without(rule.conditions(), index), rule.outcome(), rule.costs(), rule.effects());
        }
        if (index < conditions + costs) {
            return new Rule(rule.id(), rule.conditions(), rule.outcome(), without(rule.costs(), index - conditions), rule.effects());
        }
        return new Rule(rule.id(), rule.conditions(), rule.outcome(), rule.costs(),
            without(rule.effects(), index - conditions - costs));
    }

    public static RuleDocument addRule(RuleDocument document, String id) {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty() || indexOf(document, trimmed) >= 0) {
            return document;
        }
        List<Rule> rules = new ArrayList<>(document.rules());
        rules.add(new Rule(trimmed, List.of(), RuleOutcome.allow(), List.of(), List.of()));
        return document.withRules(rules);
    }

    public static RuleDocument removeRule(RuleDocument document, String id) {
        int index = indexOf(document, id);
        if (index < 0) {
            return document;
        }
        return document.withRules(without(document.rules(), index));
    }

    public static RuleDocument replaceRule(RuleDocument document, Rule replacement) {
        int index = indexOf(document, replacement.id());
        if (index < 0) {
            return document;
        }
        List<Rule> rules = new ArrayList<>(document.rules());
        rules.set(index, replacement);
        return document.withRules(rules);
    }

    public static int indexOf(RuleDocument document, String id) {
        for (int i = 0; i < document.rules().size(); i++) {
            if (document.rules().get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static String sectionKey(RuleDocumentCodec.Section section) {
        return switch (section) {
            case CONDITION -> "conditions";
            case COST -> "costs";
            case EFFECT -> "effects";
        };
    }

    private static <T> List<T> without(List<T> entries, int index) {
        List<T> copy = new ArrayList<>(entries);
        copy.remove(index);
        return copy;
    }
}
