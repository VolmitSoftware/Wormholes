package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.config.toml.RulesConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes {@link RuleDocument} as the portal-JSON object stored under {@code rules.document}.
 * Decoding is validating: every problem in a document is collected and reported together through
 * {@link RuleValidationException} so an operator fixes one document once instead of one field per attempt.
 */
public final class RuleDocumentCodec {
    private static final Set<String> CONDITION_KINDS = Set.of("ENTITY_CLASS", "ENTITY_TYPES", "HELD_ITEM", "OWNS_ITEM",
        "KEY_ITEM", "PERMISSION", "ADVANCEMENT", "PLAYER_STATE", "TIME_WINDOW", "WEATHER", "MOON_PHASE", "REDSTONE",
        "PORTAL_STATE", "PAPI");
    private static final Set<String> COST_KINDS = Set.of("ITEM", "VAULT", "XP", "HUNGER", "HEALTH", "DURABILITY",
        "CHARGE", "TICKET");
    private static final Set<String> EFFECT_KINDS = Set.of("VELOCITY", "POTION", "COMMAND", "MESSAGE", "SOUND",
        "STAMP_COOLDOWN");

    private RuleDocumentCodec() {
    }

    /** Which part of a rule a kind belongs to. Kinds are unique across the three, so one line names one place. */
    enum Section {
        CONDITION,
        COST,
        EFFECT
    }

    /** The section a kind belongs to, or null when no decoder knows it. */
    static Section sectionOf(String kind) {
        if (CONDITION_KINDS.contains(kind)) {
            return Section.CONDITION;
        }
        if (COST_KINDS.contains(kind)) {
            return Section.COST;
        }
        return EFFECT_KINDS.contains(kind) ? Section.EFFECT : null;
    }

    public static JSONObject toJson(RuleDocument document) {
        JSONObject json = new JSONObject();
        json.put("revision", document.revision());
        json.put("profile", profileToJson(document.profile()));
        json.put("default", outcomeToJson(document.defaultOutcome()));
        JSONArray rules = new JSONArray();
        for (Rule rule : document.rules()) {
            rules.put(ruleToJson(rule));
        }
        json.put("rules", rules);
        return json;
    }

    public static RuleDocument fromJson(JSONObject json) {
        List<String> problems = new ArrayList<>();
        RuleDocument document = decode(json, problems);
        if (!problems.isEmpty()) {
            throw new RuleValidationException(problems);
        }
        return document;
    }

    /** Decodes without throwing, appending every problem to {@code problems}. */
    public static RuleDocument decode(JSONObject json, List<String> problems) {
        if (json == null) {
            return RuleDocument.EMPTY;
        }
        TraversalProfile profile = profileFromJson(json.optJSONObject("profile"), problems);
        RuleOutcome defaultOutcome = outcomeFromJson(json.optJSONObject("default"), "default outcome", problems);
        List<Rule> rules = new ArrayList<>();
        JSONArray encoded = json.optJSONArray("rules");
        Set<String> seenIds = new LinkedHashSet<>();
        for (int index = 0; encoded != null && index < encoded.length(); index++) {
            JSONObject ruleJson = encoded.optJSONObject(index);
            if (ruleJson == null) {
                problems.add("rule " + index + " is not an object");
                continue;
            }
            Rule rule = ruleFromJson(ruleJson, index, problems);
            if (!rule.id().isEmpty() && !seenIds.add(rule.id())) {
                problems.add("rule " + index + " repeats id '" + rule.id() + "'");
            }
            rules.add(rule);
        }
        return new RuleDocument(rules, defaultOutcome, profile, json.optLong("revision", 0L));
    }

    private static JSONObject profileToJson(TraversalProfile profile) {
        return new JSONObject()
            .put("cooldownMillis", profile.cooldownMillis())
            .put("cooldownGroup", profile.cooldownGroup())
            .put("warmupMillis", profile.warmupMillis())
            .put("pushbackScale", profile.pushbackScale())
            .put("soundVolume", profile.soundVolume())
            .put("chargeCapacity", profile.chargeCapacity())
            .put("chargeRegenPerInterval", profile.chargeRegenPerInterval());
    }

    private static TraversalProfile profileFromJson(JSONObject json, List<String> problems) {
        if (json == null) {
            return TraversalProfile.DEFAULT;
        }
        RulesConfig limits = RulesLimits.config();
        long cooldownMillis = json.optLong("cooldownMillis", 0L);
        long warmupMillis = json.optLong("warmupMillis", 0L);
        requireRange("profile cooldownMillis", cooldownMillis, 0L, limits.cooldownMaxSeconds * 1000L, problems);
        requireRange("profile warmupMillis", warmupMillis, 0L, limits.warmupMaxSeconds * 1000L, problems);
        double pushbackScale = json.optDouble("pushbackScale", 1.0D);
        double soundVolume = json.optDouble("soundVolume", 1.0D);
        requireRange("profile pushbackScale", pushbackScale, 0.0D, 10.0D, problems);
        requireRange("profile soundVolume", soundVolume, 0.0D, 10.0D, problems);
        int chargeCapacity = json.optInt("chargeCapacity", 0);
        int chargeRegenPerInterval = json.optInt("chargeRegenPerInterval", 0);
        requireRange("profile chargeCapacity", chargeCapacity, 0L, 100_000L, problems);
        requireRange("profile chargeRegenPerInterval", chargeRegenPerInterval, 0L, 100_000L, problems);
        return new TraversalProfile(cooldownMillis, json.optString("cooldownGroup", ""), warmupMillis,
            pushbackScale, soundVolume, chargeCapacity, chargeRegenPerInterval);
    }

    private static JSONObject outcomeToJson(RuleOutcome outcome) {
        return new JSONObject().put("kind", outcome.kind().name()).put("reason", outcome.reason());
    }

    private static RuleOutcome outcomeFromJson(JSONObject json, String where, List<String> problems) {
        if (json == null) {
            return RuleOutcome.allow();
        }
        String kind = json.optString("kind", RuleOutcome.Kind.ALLOW.name());
        RuleOutcome.Kind parsed;
        try {
            parsed = RuleOutcome.Kind.valueOf(kind);
        } catch (IllegalArgumentException unknown) {
            problems.add(where + " has unknown kind '" + kind + "'");
            return RuleOutcome.allow();
        }
        return new RuleOutcome(parsed, json.optString("reason", ""));
    }

    private static JSONObject ruleToJson(Rule rule) {
        JSONArray conditions = new JSONArray();
        for (Condition condition : rule.conditions()) {
            conditions.put(conditionToJson(condition));
        }
        JSONArray costs = new JSONArray();
        for (Cost cost : rule.costs()) {
            costs.put(costToJson(cost));
        }
        JSONArray effects = new JSONArray();
        for (Effect effect : rule.effects()) {
            effects.put(effectToJson(effect));
        }
        return new JSONObject()
            .put("id", rule.id())
            .put("outcome", outcomeToJson(rule.outcome()))
            .put("conditions", conditions)
            .put("costs", costs)
            .put("effects", effects);
    }

    private static Rule ruleFromJson(JSONObject json, int index, List<String> problems) {
        String id = json.optString("id", "");
        if (id.isBlank()) {
            problems.add("rule " + index + " has no id");
        }
        RuleOutcome outcome = outcomeFromJson(json.optJSONObject("outcome"), "rule " + index + " outcome", problems);
        List<Condition> conditions = new ArrayList<>();
        JSONArray encodedConditions = json.optJSONArray("conditions");
        for (int i = 0; encodedConditions != null && i < encodedConditions.length(); i++) {
            Condition condition = conditionFromJson(encodedConditions.optJSONObject(i), "rule " + index + " condition " + i, problems);
            if (condition != null) {
                conditions.add(condition);
            }
        }
        List<Cost> costs = new ArrayList<>();
        JSONArray encodedCosts = json.optJSONArray("costs");
        for (int i = 0; encodedCosts != null && i < encodedCosts.length(); i++) {
            Cost cost = costFromJson(encodedCosts.optJSONObject(i), "rule " + index + " cost " + i, problems);
            if (cost != null) {
                costs.add(cost);
            }
        }
        List<Effect> effects = new ArrayList<>();
        JSONArray encodedEffects = json.optJSONArray("effects");
        for (int i = 0; encodedEffects != null && i < encodedEffects.length(); i++) {
            Effect effect = effectFromJson(encodedEffects.optJSONObject(i), "rule " + index + " effect " + i, problems);
            if (effect != null) {
                effects.add(effect);
            }
        }
        return new Rule(id, conditions, outcome, costs, effects);
    }

    private static JSONObject conditionToJson(Condition condition) {
        return switch (condition) {
            case Condition.EntityClass value -> new JSONObject().put("kind", "ENTITY_CLASS")
                .put("classes", names(value.classes())).put("negate", value.negate());
            case Condition.EntityTypes value -> new JSONObject().put("kind", "ENTITY_TYPES")
                .put("keys", strings(value.keys()));
            case Condition.HeldItem value -> new JSONObject().put("kind", "HELD_ITEM")
                .put("matcher", matcherToJson(value.matcher())).put("hand", value.hand().name());
            case Condition.OwnsItem value -> new JSONObject().put("kind", "OWNS_ITEM")
                .put("matcher", matcherToJson(value.matcher())).put("count", value.count());
            case Condition.KeyItem value -> new JSONObject().put("kind", "KEY_ITEM")
                .put("keyId", value.keyId() == null ? "" : value.keyId().toString());
            case Condition.Permission value -> new JSONObject().put("kind", "PERMISSION").put("node", value.node());
            case Condition.Advancement value -> new JSONObject().put("kind", "ADVANCEMENT").put("key", value.key());
            case Condition.PlayerState value -> new JSONObject().put("kind", "PLAYER_STATE")
                .put("field", value.field().name()).put("min", value.min()).put("max", value.max());
            case Condition.TimeWindow value -> new JSONObject().put("kind", "TIME_WINDOW")
                .put("startTick", value.startTick()).put("endTick", value.endTick());
            case Condition.Weather value -> new JSONObject().put("kind", "WEATHER").put("weather", value.kind().name());
            case Condition.MoonPhase value -> new JSONObject().put("kind", "MOON_PHASE").put("phases", integers(value.phases()));
            case Condition.Redstone value -> new JSONObject().put("kind", "REDSTONE")
                .put("dx", value.dx()).put("dy", value.dy()).put("dz", value.dz()).put("powered", value.powered());
            case Condition.PortalState value -> new JSONObject().put("kind", "PORTAL_STATE")
                .put("open", value.open()).put("dialedAddress", value.dialedAddress());
            case Condition.Papi value -> new JSONObject().put("kind", "PAPI")
                .put("expression", value.expression()).put("operator", value.operator().name()).put("value", value.value());
        };
    }

    private static Condition conditionFromJson(JSONObject json, String where, List<String> problems) {
        if (json == null) {
            problems.add(where + " is not an object");
            return null;
        }
        String kind = json.optString("kind", "");
        return switch (kind) {
            case "ENTITY_CLASS" -> new Condition.EntityClass(
                enums(json.optJSONArray("classes"), TravelerClass.class, where + " classes", problems),
                json.optBoolean("negate", false));
            case "ENTITY_TYPES" -> new Condition.EntityTypes(stringSet(json.optJSONArray("keys")));
            case "HELD_ITEM" -> new Condition.HeldItem(matcherFromJson(json.optJSONObject("matcher"), where, problems),
                singleEnum(json.optString("hand", Condition.Hand.EITHER.name()), Condition.Hand.class, Condition.Hand.EITHER, where + " hand", problems));
            case "OWNS_ITEM" -> {
                int count = json.optInt("count", 1);
                requireRange(where + " count", count, 1L, 2304L, problems);
                yield new Condition.OwnsItem(matcherFromJson(json.optJSONObject("matcher"), where, problems), count);
            }
            case "KEY_ITEM" -> new Condition.KeyItem(uuid(json.optString("keyId", ""), where + " keyId", problems));
            case "PERMISSION" -> {
                String node = json.optString("node", "");
                if (node.isBlank()) {
                    problems.add(where + " has an empty permission node");
                }
                yield new Condition.Permission(node);
            }
            case "ADVANCEMENT" -> new Condition.Advancement(json.optString("key", ""));
            case "PLAYER_STATE" -> new Condition.PlayerState(
                singleEnum(json.optString("field", ""), Condition.PlayerField.class, Condition.PlayerField.HEALTH, where + " field", problems),
                json.optDouble("min", 0.0D), json.optDouble("max", 0.0D));
            case "TIME_WINDOW" -> {
                int startTick = json.optInt("startTick", 0);
                int endTick = json.optInt("endTick", 0);
                requireRange(where + " startTick", startTick, 0L, 24000L, problems);
                requireRange(where + " endTick", endTick, 0L, 24000L, problems);
                yield new Condition.TimeWindow(startTick, endTick);
            }
            case "WEATHER" -> new Condition.Weather(singleEnum(json.optString("weather", ""), Condition.WeatherKind.class,
                Condition.WeatherKind.CLEAR, where + " weather", problems));
            case "MOON_PHASE" -> new Condition.MoonPhase(integerSet(json.optJSONArray("phases")));
            case "REDSTONE" -> new Condition.Redstone(json.optInt("dx", 0), json.optInt("dy", 0), json.optInt("dz", 0),
                json.optBoolean("powered", true));
            case "PORTAL_STATE" -> new Condition.PortalState(json.optBoolean("open", true), json.optString("dialedAddress", ""));
            case "PAPI" -> new Condition.Papi(json.optString("expression", ""),
                singleEnum(json.optString("operator", ""), Condition.Comparator.class, Condition.Comparator.EQUALS, where + " operator", problems),
                json.optString("value", ""));
            default -> {
                problems.add(where + " has unknown kind '" + kind + "'");
                yield null;
            }
        };
    }

    private static JSONObject costToJson(Cost cost) {
        return switch (cost) {
            case Cost.Item value -> new JSONObject().put("kind", "ITEM")
                .put("matcher", matcherToJson(value.matcher())).put("quantity", value.quantity());
            case Cost.Vault value -> new JSONObject().put("kind", "VAULT").put("amount", value.amount().toPlainString());
            case Cost.Xp value -> new JSONObject().put("kind", "XP").put("amount", value.amount()).put("levels", value.levels());
            case Cost.Hunger value -> new JSONObject().put("kind", "HUNGER").put("points", value.points());
            case Cost.Health value -> new JSONObject().put("kind", "HEALTH").put("points", value.points());
            case Cost.Durability value -> new JSONObject().put("kind", "DURABILITY")
                .put("matcher", matcherToJson(value.matcher())).put("points", value.points());
            case Cost.Charge value -> new JSONObject().put("kind", "CHARGE").put("count", value.count());
            case Cost.Ticket value -> new JSONObject().put("kind", "TICKET")
                .put("ticketId", value.ticketId() == null ? "" : value.ticketId().toString()).put("uses", value.uses());
        };
    }

    private static Cost costFromJson(JSONObject json, String where, List<String> problems) {
        if (json == null) {
            problems.add(where + " is not an object");
            return null;
        }
        String kind = json.optString("kind", "");
        return switch (kind) {
            case "ITEM" -> {
                int quantity = json.optInt("quantity", 1);
                requireRange(where + " quantity", quantity, 1L, 2304L, problems);
                yield new Cost.Item(matcherFromJson(json.optJSONObject("matcher"), where, problems), quantity);
            }
            case "VAULT" -> {
                BigDecimal amount = decimal(json.optString("amount", "0"), where + " amount", problems);
                if (amount.signum() < 0) {
                    problems.add(where + " amount is negative");
                }
                yield new Cost.Vault(amount);
            }
            case "XP" -> {
                int amount = json.optInt("amount", 0);
                requireRange(where + " amount", amount, 1L, 100_000L, problems);
                yield new Cost.Xp(amount, json.optBoolean("levels", false));
            }
            case "HUNGER" -> {
                int points = json.optInt("points", 0);
                requireRange(where + " points", points, 1L, 20L, problems);
                yield new Cost.Hunger(points);
            }
            case "HEALTH" -> {
                double points = json.optDouble("points", 0.0D);
                requireRange(where + " points", points, 0.5D, 1024.0D, problems);
                yield new Cost.Health(points);
            }
            case "DURABILITY" -> {
                int points = json.optInt("points", 1);
                requireRange(where + " points", points, 1L, 100_000L, problems);
                yield new Cost.Durability(matcherFromJson(json.optJSONObject("matcher"), where, problems), points);
            }
            case "CHARGE" -> {
                int count = json.optInt("count", 1);
                requireRange(where + " count", count, 1L, 100_000L, problems);
                yield new Cost.Charge(count);
            }
            case "TICKET" -> {
                int uses = json.optInt("uses", 1);
                requireRange(where + " uses", uses, 1L, 100_000L, problems);
                yield new Cost.Ticket(uuid(json.optString("ticketId", ""), where + " ticketId", problems), uses);
            }
            default -> {
                problems.add(where + " has unknown kind '" + kind + "'");
                yield null;
            }
        };
    }

    private static JSONObject effectToJson(Effect effect) {
        return switch (effect) {
            case Effect.Velocity value -> new JSONObject().put("kind", "VELOCITY").put("multiplier", value.multiplier());
            case Effect.Potion value -> new JSONObject().put("kind", "POTION").put("type", value.type())
                .put("ticks", value.ticks()).put("amplifier", value.amplifier()).put("clear", value.clear());
            case Effect.Command value -> new JSONObject().put("kind", "COMMAND").put("line", value.line())
                .put("asConsole", value.asConsole()).put("authorId", value.authorId() == null ? "" : value.authorId().toString());
            case Effect.Message value -> new JSONObject().put("kind", "MESSAGE").put("message", value.message());
            case Effect.Sound value -> new JSONObject().put("kind", "SOUND").put("key", value.key())
                .put("volume", value.volume()).put("pitch", value.pitch());
            case Effect.StampCooldown value -> new JSONObject().put("kind", "STAMP_COOLDOWN")
                .put("group", value.group()).put("millis", value.millis());
        };
    }

    private static Effect effectFromJson(JSONObject json, String where, List<String> problems) {
        if (json == null) {
            problems.add(where + " is not an object");
            return null;
        }
        String kind = json.optString("kind", "");
        return switch (kind) {
            case "VELOCITY" -> {
                double multiplier = json.optDouble("multiplier", 1.0D);
                requireRange(where + " multiplier", multiplier, 0.0D, 16.0D, problems);
                yield new Effect.Velocity(multiplier);
            }
            case "POTION" -> {
                int ticks = json.optInt("ticks", 0);
                int amplifier = json.optInt("amplifier", 0);
                requireRange(where + " ticks", ticks, 0L, 1_728_000L, problems);
                requireRange(where + " amplifier", amplifier, 0L, 255L, problems);
                yield new Effect.Potion(json.optString("type", ""), ticks, amplifier, json.optBoolean("clear", false));
            }
            case "COMMAND" -> {
                String line = json.optString("line", "");
                if (line.isBlank()) {
                    problems.add(where + " has an empty command line");
                }
                yield new Effect.Command(line, json.optBoolean("asConsole", false),
                    uuid(json.optString("authorId", ""), where + " authorId", problems));
            }
            case "MESSAGE" -> new Effect.Message(json.optString("message", ""));
            case "SOUND" -> {
                double volume = json.optDouble("volume", 1.0D);
                double pitch = json.optDouble("pitch", 1.0D);
                requireRange(where + " volume", volume, 0.0D, 10.0D, problems);
                requireRange(where + " pitch", pitch, 0.5D, 2.0D, problems);
                yield new Effect.Sound(json.optString("key", ""), (float) volume, (float) pitch);
            }
            case "STAMP_COOLDOWN" -> {
                long millis = json.optLong("millis", 0L);
                requireRange(where + " millis", millis, 0L, RulesLimits.config().cooldownMaxSeconds * 1000L, problems);
                yield new Effect.StampCooldown(json.optString("group", ""), millis);
            }
            default -> {
                problems.add(where + " has unknown kind '" + kind + "'");
                yield null;
            }
        };
    }

    private static JSONObject matcherToJson(ItemMatcher matcher) {
        ItemMatcher value = matcher == null ? ItemMatcher.material("") : matcher;
        return new JSONObject()
            .put("material", value.material())
            .put("identity", value.identity() == null ? "" : value.identity().toString())
            .put("exactMeta", value.exactMeta());
    }

    private static ItemMatcher matcherFromJson(JSONObject json, String where, List<String> problems) {
        if (json == null) {
            problems.add(where + " has no item matcher");
            return ItemMatcher.material("");
        }
        String material = json.optString("material", "");
        UUID identity = uuid(json.optString("identity", ""), where + " matcher identity", problems);
        if (material.isBlank() && identity == null) {
            problems.add(where + " matcher names neither a material nor an identity");
        }
        return new ItemMatcher(material, identity, json.optBoolean("exactMeta", false));
    }

    private static JSONArray names(Set<? extends Enum<?>> values) {
        JSONArray array = new JSONArray();
        for (Enum<?> value : values) {
            array.put(value.name());
        }
        return array;
    }

    private static JSONArray strings(Set<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static JSONArray integers(Set<Integer> values) {
        JSONArray array = new JSONArray();
        for (Integer value : values) {
            array.put(value.intValue());
        }
        return array;
    }

    private static Set<String> stringSet(JSONArray array) {
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            values.add(array.optString(i, ""));
        }
        return values;
    }

    private static Set<Integer> integerSet(JSONArray array) {
        Set<Integer> values = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            values.add(Integer.valueOf(array.optInt(i, 0)));
        }
        return values;
    }

    private static <E extends Enum<E>> Set<E> enums(JSONArray array, Class<E> type, String where, List<String> problems) {
        Set<E> values = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            String name = array.optString(i, "");
            try {
                values.add(Enum.valueOf(type, name));
            } catch (IllegalArgumentException unknown) {
                problems.add(where + " has unknown value '" + name + "'");
            }
        }
        return values;
    }

    private static <E extends Enum<E>> E singleEnum(String name, Class<E> type, E fallback, String where, List<String> problems) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            problems.add(where + " has unknown value '" + name + "'");
            return fallback;
        }
    }

    private static UUID uuid(String value, String where, List<String> problems) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            problems.add(where + " is not a uuid: '" + value + "'");
            return null;
        }
    }

    private static BigDecimal decimal(String value, String where, List<String> problems) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException malformed) {
            problems.add(where + " is not a number: '" + value + "'");
            return BigDecimal.ZERO;
        }
    }

    private static void requireRange(String where, long value, long min, long max, List<String> problems) {
        if (value < min || value > max) {
            problems.add(where + " must be between " + min + " and " + max + " but is " + value);
        }
    }

    private static void requireRange(String where, double value, double min, double max, List<String> problems) {
        if (Double.isNaN(value) || value < min || value > max) {
            problems.add(where + " must be between " + min + " and " + max + " but is " + value);
        }
    }
}
