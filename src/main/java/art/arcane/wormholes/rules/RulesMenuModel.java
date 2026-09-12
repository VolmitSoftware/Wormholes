package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pure part of the rules editor: paging, rule summaries, and the line form an operator types. A rule's
 * conditions, costs and effects are edited as the same {@code kind=...;field=value} lines the templates use, so
 * one uniform form covers every kind and every edit is validated by {@link RuleDocumentCodec} before it lands.
 */
final class RulesMenuModel {
    static final int ENTRIES_PER_PAGE = 45;

    private RulesMenuModel() {
    }

    static int pageCount(int entries) {
        return Math.max(1, (entries + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    static int clampPage(int page, int entries) {
        return Math.max(0, Math.min(page, pageCount(entries) - 1));
    }

    static <T> List<T> page(List<T> entries, int page) {
        int from = page * ENTRIES_PER_PAGE;
        if (from >= entries.size() || from < 0) {
            return List.of();
        }
        return List.copyOf(entries.subList(from, Math.min(entries.size(), from + ENTRIES_PER_PAGE)));
    }

    /** Outcome and how many conditions, costs and effects the rule carries. */
    static String summary(Rule rule) {
        return rule.outcome().kind().name() + " " + rule.conditions().size() + "/" + rule.costs().size()
            + "/" + rule.effects().size();
    }

    /** Every condition, then cost, then effect of a rule as an editable line. */
    static List<String> lines(Rule rule) {
        JSONObject encoded = RuleDocumentCodec.toJson(new RuleDocument(List.of(rule), RuleOutcome.allow(),
            TraversalProfile.DEFAULT, 0L)).getJSONArray("rules").getJSONObject(0);
        List<String> lines = new ArrayList<>();
        for (String section : List.of("conditions", "costs", "effects")) {
            for (int i = 0; i < encoded.getJSONArray(section).length(); i++) {
                lines.add(RuleLineCodec.toLine(encoded.getJSONArray(section).getJSONObject(i)));
            }
        }
        return lines;
    }

    /** Parses one line, routes it to the part of the rule its kind belongs to, and validates the result. */
    static Rule addLine(Rule rule, String line) {
        JSONObject parsed = RuleLineCodec.fromLine(line);
        String kind = parsed.optString("kind", "").trim().toUpperCase(Locale.ROOT);
        parsed.put("kind", kind);
        RuleDocumentCodec.Section section = RuleDocumentCodec.sectionOf(kind);
        if (section == null) {
            throw new RuleValidationException(List.of("unknown kind '" + kind + "'"));
        }
        JSONObject encoded = RuleDocumentCodec.toJson(new RuleDocument(List.of(rule), RuleOutcome.allow(),
            TraversalProfile.DEFAULT, 0L));
        encoded.getJSONArray("rules").getJSONObject(0).getJSONArray(sectionKey(section)).put(parsed);
        return RuleDocumentCodec.fromJson(encoded).rules().getFirst();
    }

    /** Removes the line at {@code index} of {@link #lines(Rule)}; an index past the end changes nothing. */
    static Rule removeLine(Rule rule, int index) {
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

    static RuleDocument addRule(RuleDocument document, String id) {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty() || indexOf(document, trimmed) >= 0) {
            return document;
        }
        List<Rule> rules = new ArrayList<>(document.rules());
        rules.add(new Rule(trimmed, List.of(), RuleOutcome.allow(), List.of(), List.of()));
        return document.withRules(rules);
    }

    static RuleDocument removeRule(RuleDocument document, String id) {
        int index = indexOf(document, id);
        if (index < 0) {
            return document;
        }
        return document.withRules(without(document.rules(), index));
    }

    static RuleDocument replaceRule(RuleDocument document, Rule replacement) {
        int index = indexOf(document, replacement.id());
        if (index < 0) {
            return document;
        }
        List<Rule> rules = new ArrayList<>(document.rules());
        rules.set(index, replacement);
        return document.withRules(rules);
    }

    static int indexOf(RuleDocument document, String id) {
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
