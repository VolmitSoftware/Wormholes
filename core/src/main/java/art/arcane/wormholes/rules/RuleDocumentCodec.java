package art.arcane.wormholes.rules;

import art.arcane.wormholes.config.toml.RulesConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
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
    public enum Section {
        CONDITION,
        COST,
        EFFECT
    }

    /** The section a kind belongs to, or null when no decoder knows it. */
    public static Section sectionOf(String kind) {
        if (CONDITION_KINDS.contains(kind)) {
            return Section.CONDITION;
        }
        if (COST_KINDS.contains(kind)) {
            return Section.COST;
        }
        return EFFECT_KINDS.contains(kind) ? Section.EFFECT : null;
    }

    public static Map<String, Object> toJson(RuleDocument document) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("revision", document.revision());
        json.put("profile", profileToJson(document.profile()));
        json.put("default", outcomeToJson(document.defaultOutcome()));
        List<Object> rules = new ArrayList<>();
        for (Rule rule : document.rules()) {
            rules.add(ruleToJson(rule));
        }
        json.put("rules", rules);
        return json;
    }

    public static RuleDocument fromJson(Map<String, Object> json, RulesConfig limits) {
        List<String> problems = new ArrayList<>();
        RuleDocument document = decode(json, problems, limits);
        if (!problems.isEmpty()) {
            throw new RuleValidationException(problems);
        }
        return document;
    }

    /** Decodes without throwing, appending every problem to {@code problems}. */
    public static RuleDocument decode(Map<String, Object> json, List<String> problems, RulesConfig limits) {
        Objects.requireNonNull(limits, "limits");
        if (json == null) {
            return RuleDocument.EMPTY;
        }
        TraversalProfile profile = profileFromJson(map(json.get("profile")), problems, limits);
        RuleOutcome defaultOutcome = outcomeFromJson(map(json.get("default")), "default outcome", problems);
        List<Rule> rules = new ArrayList<>();
        List<Object> encoded = array(json.get("rules"));
        Set<String> seenIds = new LinkedHashSet<>();
        for (int index = 0; encoded != null && index < encoded.size(); index++) {
            Map<String, Object> ruleJson = map(encoded.get(index));
            if (ruleJson == null) {
                problems.add("rule " + index + " is not an object");
                continue;
            }
            Rule rule = ruleFromJson(ruleJson, index, problems, limits);
            if (!rule.id().isEmpty() && !seenIds.add(rule.id())) {
                problems.add("rule " + index + " repeats id '" + rule.id() + "'");
            }
            rules.add(rule);
        }
        return new RuleDocument(rules, defaultOutcome, profile, longValue(json.get("revision"), 0L));
    }

    private static Map<String, Object> profileToJson(TraversalProfile profile) {
        return object("cooldownMillis", profile.cooldownMillis(), "cooldownGroup", profile.cooldownGroup(), "warmupMillis", profile.warmupMillis(), "pushbackScale", profile.pushbackScale(), "soundVolume", profile.soundVolume(), "chargeCapacity", profile.chargeCapacity(), "chargeRegenPerInterval", profile.chargeRegenPerInterval());
    }

    private static TraversalProfile profileFromJson(Map<String, Object> json, List<String> problems, RulesConfig limits) {
        if (json == null) {
            return TraversalProfile.DEFAULT;
        }
        long cooldownMillis = longValue(json.get("cooldownMillis"), 0L);
        long warmupMillis = longValue(json.get("warmupMillis"), 0L);
        requireRange("profile cooldownMillis", cooldownMillis, 0L, limits.cooldownMaxSeconds * 1000L, problems);
        requireRange("profile warmupMillis", warmupMillis, 0L, limits.warmupMaxSeconds * 1000L, problems);
        double pushbackScale = doubleValue(json.get("pushbackScale"), 1.0D);
        double soundVolume = doubleValue(json.get("soundVolume"), 1.0D);
        requireRange("profile pushbackScale", pushbackScale, 0.0D, 10.0D, problems);
        requireRange("profile soundVolume", soundVolume, 0.0D, 10.0D, problems);
        int chargeCapacity = intValue(json.get("chargeCapacity"), 0);
        int chargeRegenPerInterval = intValue(json.get("chargeRegenPerInterval"), 0);
        requireRange("profile chargeCapacity", chargeCapacity, 0L, 100_000L, problems);
        requireRange("profile chargeRegenPerInterval", chargeRegenPerInterval, 0L, 100_000L, problems);
        return new TraversalProfile(cooldownMillis, string(json.get("cooldownGroup"), ""), warmupMillis,
            pushbackScale, soundVolume, chargeCapacity, chargeRegenPerInterval);
    }

    private static Map<String, Object> outcomeToJson(RuleOutcome outcome) {
        return object("kind", outcome.kind().name(), "reason", outcome.reason());
    }

    private static RuleOutcome outcomeFromJson(Map<String, Object> json, String where, List<String> problems) {
        if (json == null) {
            return RuleOutcome.allow();
        }
        String kind = string(json.get("kind"), RuleOutcome.Kind.ALLOW.name());
        RuleOutcome.Kind parsed;
        try {
            parsed = RuleOutcome.Kind.valueOf(kind);
        } catch (IllegalArgumentException unknown) {
            problems.add(where + " has unknown kind '" + kind + "'");
            return RuleOutcome.allow();
        }
        return new RuleOutcome(parsed, string(json.get("reason"), ""));
    }

    private static Map<String, Object> ruleToJson(Rule rule) {
        List<Object> conditions = new ArrayList<>();
        for (Condition condition : rule.conditions()) {
            conditions.add(conditionToJson(condition));
        }
        List<Object> costs = new ArrayList<>();
        for (Cost cost : rule.costs()) {
            costs.add(costToJson(cost));
        }
        List<Object> effects = new ArrayList<>();
        for (Effect effect : rule.effects()) {
            effects.add(effectToJson(effect));
        }
        return object("id", rule.id(), "outcome", outcomeToJson(rule.outcome()), "conditions", conditions, "costs", costs, "effects", effects);
    }

    private static Rule ruleFromJson(Map<String, Object> json, int index, List<String> problems, RulesConfig limits) {
        String id = string(json.get("id"), "");
        if (id.isBlank()) {
            problems.add("rule " + index + " has no id");
        }
        RuleOutcome outcome = outcomeFromJson(map(json.get("outcome")), "rule " + index + " outcome", problems);
        List<Condition> conditions = new ArrayList<>();
        List<Object> encodedConditions = array(json.get("conditions"));
        for (int i = 0; encodedConditions != null && i < encodedConditions.size(); i++) {
            Condition condition = conditionFromJson(map(encodedConditions.get(i)), "rule " + index + " condition " + i, problems);
            if (condition != null) {
                conditions.add(condition);
            }
        }
        List<Cost> costs = new ArrayList<>();
        List<Object> encodedCosts = array(json.get("costs"));
        for (int i = 0; encodedCosts != null && i < encodedCosts.size(); i++) {
            Cost cost = costFromJson(map(encodedCosts.get(i)), "rule " + index + " cost " + i, problems);
            if (cost != null) {
                costs.add(cost);
            }
        }
        List<Effect> effects = new ArrayList<>();
        List<Object> encodedEffects = array(json.get("effects"));
        for (int i = 0; encodedEffects != null && i < encodedEffects.size(); i++) {
            Effect effect = effectFromJson(map(encodedEffects.get(i)), "rule " + index + " effect " + i, problems, limits);
            if (effect != null) {
                effects.add(effect);
            }
        }
        return new Rule(id, conditions, outcome, costs, effects);
    }

    private static Map<String, Object> conditionToJson(Condition condition) {
        return switch (condition) {
            case Condition.EntityClass value -> object("kind", "ENTITY_CLASS", "classes", names(value.classes()), "negate", value.negate());
            case Condition.EntityTypes value -> object("kind", "ENTITY_TYPES", "keys", strings(value.keys()));
            case Condition.HeldItem value -> object("kind", "HELD_ITEM", "matcher", matcherToJson(value.matcher()), "hand", value.hand().name());
            case Condition.OwnsItem value -> object("kind", "OWNS_ITEM", "matcher", matcherToJson(value.matcher()), "count", value.count());
            case Condition.KeyItem value -> object("kind", "KEY_ITEM", "keyId", value.keyId() == null ? "" : value.keyId().toString());
            case Condition.Permission value -> object("kind", "PERMISSION", "node", value.node());
            case Condition.Advancement value -> object("kind", "ADVANCEMENT", "key", value.key());
            case Condition.PlayerState value -> object("kind", "PLAYER_STATE", "field", value.field().name(), "min", value.min(), "max", value.max());
            case Condition.TimeWindow value -> object("kind", "TIME_WINDOW", "startTick", value.startTick(), "endTick", value.endTick());
            case Condition.Weather value -> object("kind", "WEATHER", "weather", value.kind().name());
            case Condition.MoonPhase value -> object("kind", "MOON_PHASE", "phases", integers(value.phases()));
            case Condition.Redstone value -> object("kind", "REDSTONE", "dx", value.dx(), "dy", value.dy(), "dz", value.dz(), "powered", value.powered());
            case Condition.PortalState value -> object("kind", "PORTAL_STATE", "open", value.open(), "dialedAddress", value.dialedAddress());
            case Condition.Papi value -> object("kind", "PAPI", "expression", value.expression(), "operator", value.operator().name(), "value", value.value());
        };
    }

    private static Condition conditionFromJson(Map<String, Object> json, String where, List<String> problems) {
        if (json == null) {
            problems.add(where + " is not an object");
            return null;
        }
        String kind = string(json.get("kind"), "");
        return switch (kind) {
            case "ENTITY_CLASS" -> new Condition.EntityClass(
                enums(array(json.get("classes")), TravelerClass.class, where + " classes", problems),
                booleanValue(json.get("negate"), false));
            case "ENTITY_TYPES" -> new Condition.EntityTypes(stringSet(array(json.get("keys"))));
            case "HELD_ITEM" -> new Condition.HeldItem(matcherFromJson(map(json.get("matcher")), where, problems),
                singleEnum(string(json.get("hand"), Condition.Hand.EITHER.name()), Condition.Hand.class, Condition.Hand.EITHER, where + " hand", problems));
            case "OWNS_ITEM" -> {
                int count = intValue(json.get("count"), 1);
                requireRange(where + " count", count, 1L, 2304L, problems);
                yield new Condition.OwnsItem(matcherFromJson(map(json.get("matcher")), where, problems), count);
            }
            case "KEY_ITEM" -> new Condition.KeyItem(uuid(string(json.get("keyId"), ""), where + " keyId", problems));
            case "PERMISSION" -> {
                String node = string(json.get("node"), "");
                if (node.isBlank()) {
                    problems.add(where + " has an empty permission node");
                }
                yield new Condition.Permission(node);
            }
            case "ADVANCEMENT" -> new Condition.Advancement(string(json.get("key"), ""));
            case "PLAYER_STATE" -> new Condition.PlayerState(
                singleEnum(string(json.get("field"), ""), Condition.PlayerField.class, Condition.PlayerField.HEALTH, where + " field", problems),
                doubleValue(json.get("min"), 0.0D), doubleValue(json.get("max"), 0.0D));
            case "TIME_WINDOW" -> {
                int startTick = intValue(json.get("startTick"), 0);
                int endTick = intValue(json.get("endTick"), 0);
                requireRange(where + " startTick", startTick, 0L, 24000L, problems);
                requireRange(where + " endTick", endTick, 0L, 24000L, problems);
                yield new Condition.TimeWindow(startTick, endTick);
            }
            case "WEATHER" -> new Condition.Weather(singleEnum(string(json.get("weather"), ""), Condition.WeatherKind.class,
                Condition.WeatherKind.CLEAR, where + " weather", problems));
            case "MOON_PHASE" -> new Condition.MoonPhase(integerSet(array(json.get("phases"))));
            case "REDSTONE" -> new Condition.Redstone(intValue(json.get("dx"), 0), intValue(json.get("dy"), 0), intValue(json.get("dz"), 0),
                booleanValue(json.get("powered"), true));
            case "PORTAL_STATE" -> new Condition.PortalState(booleanValue(json.get("open"), true), string(json.get("dialedAddress"), ""));
            case "PAPI" -> new Condition.Papi(string(json.get("expression"), ""),
                singleEnum(string(json.get("operator"), ""), Condition.Comparator.class, Condition.Comparator.EQUALS, where + " operator", problems),
                string(json.get("value"), ""));
            default -> {
                problems.add(where + " has unknown kind '" + kind + "'");
                yield null;
            }
        };
    }

    private static Map<String, Object> costToJson(Cost cost) {
        return switch (cost) {
            case Cost.Item value -> object("kind", "ITEM", "matcher", matcherToJson(value.matcher()), "quantity", value.quantity());
            case Cost.Vault value -> object("kind", "VAULT", "amount", value.amount().toPlainString());
            case Cost.Xp value -> object("kind", "XP", "amount", value.amount(), "levels", value.levels());
            case Cost.Hunger value -> object("kind", "HUNGER", "points", value.points());
            case Cost.Health value -> object("kind", "HEALTH", "points", value.points());
            case Cost.Durability value -> object("kind", "DURABILITY", "matcher", matcherToJson(value.matcher()), "points", value.points());
            case Cost.Charge value -> object("kind", "CHARGE", "count", value.count());
            case Cost.Ticket value -> object("kind", "TICKET", "ticketId", value.ticketId() == null ? "" : value.ticketId().toString(), "uses", value.uses());
        };
    }

    private static Cost costFromJson(Map<String, Object> json, String where, List<String> problems) {
        if (json == null) {
            problems.add(where + " is not an object");
            return null;
        }
        String kind = string(json.get("kind"), "");
        return switch (kind) {
            case "ITEM" -> {
                int quantity = intValue(json.get("quantity"), 1);
                requireRange(where + " quantity", quantity, 1L, 2304L, problems);
                yield new Cost.Item(matcherFromJson(map(json.get("matcher")), where, problems), quantity);
            }
            case "VAULT" -> {
                BigDecimal amount = decimal(string(json.get("amount"), "0"), where + " amount", problems);
                if (amount.signum() < 0) {
                    problems.add(where + " amount is negative");
                }
                yield new Cost.Vault(amount);
            }
            case "XP" -> {
                int amount = intValue(json.get("amount"), 0);
                requireRange(where + " amount", amount, 1L, 100_000L, problems);
                yield new Cost.Xp(amount, booleanValue(json.get("levels"), false));
            }
            case "HUNGER" -> {
                int points = intValue(json.get("points"), 0);
                requireRange(where + " points", points, 1L, 20L, problems);
                yield new Cost.Hunger(points);
            }
            case "HEALTH" -> {
                double points = doubleValue(json.get("points"), 0.0D);
                requireRange(where + " points", points, 0.5D, 1024.0D, problems);
                yield new Cost.Health(points);
            }
            case "DURABILITY" -> {
                int points = intValue(json.get("points"), 1);
                requireRange(where + " points", points, 1L, 100_000L, problems);
                yield new Cost.Durability(matcherFromJson(map(json.get("matcher")), where, problems), points);
            }
            case "CHARGE" -> {
                int count = intValue(json.get("count"), 1);
                requireRange(where + " count", count, 1L, 100_000L, problems);
                yield new Cost.Charge(count);
            }
            case "TICKET" -> {
                int uses = intValue(json.get("uses"), 1);
                requireRange(where + " uses", uses, 1L, 100_000L, problems);
                yield new Cost.Ticket(uuid(string(json.get("ticketId"), ""), where + " ticketId", problems), uses);
            }
            default -> {
                problems.add(where + " has unknown kind '" + kind + "'");
                yield null;
            }
        };
    }

    private static Map<String, Object> effectToJson(Effect effect) {
        return switch (effect) {
            case Effect.Velocity value -> object("kind", "VELOCITY", "multiplier", value.multiplier());
            case Effect.Potion value -> object("kind", "POTION", "type", value.type(), "ticks", value.ticks(), "amplifier", value.amplifier(), "clear", value.clear());
            case Effect.Command value -> object("kind", "COMMAND", "line", value.line(), "asConsole", value.asConsole(), "authorId", value.authorId() == null ? "" : value.authorId().toString());
            case Effect.Message value -> object("kind", "MESSAGE", "message", value.message());
            case Effect.Sound value -> object("kind", "SOUND", "key", value.key(), "volume", value.volume(), "pitch", value.pitch());
            case Effect.StampCooldown value -> object("kind", "STAMP_COOLDOWN", "group", value.group(), "millis", value.millis());
        };
    }

    private static Effect effectFromJson(Map<String, Object> json, String where, List<String> problems, RulesConfig limits) {
        if (json == null) {
            problems.add(where + " is not an object");
            return null;
        }
        String kind = string(json.get("kind"), "");
        return switch (kind) {
            case "VELOCITY" -> {
                double multiplier = doubleValue(json.get("multiplier"), 1.0D);
                requireRange(where + " multiplier", multiplier, 0.0D, 16.0D, problems);
                yield new Effect.Velocity(multiplier);
            }
            case "POTION" -> {
                int ticks = intValue(json.get("ticks"), 0);
                int amplifier = intValue(json.get("amplifier"), 0);
                requireRange(where + " ticks", ticks, 0L, 1_728_000L, problems);
                requireRange(where + " amplifier", amplifier, 0L, 255L, problems);
                yield new Effect.Potion(string(json.get("type"), ""), ticks, amplifier, booleanValue(json.get("clear"), false));
            }
            case "COMMAND" -> {
                String line = string(json.get("line"), "");
                if (line.isBlank()) {
                    problems.add(where + " has an empty command line");
                }
                yield new Effect.Command(line, booleanValue(json.get("asConsole"), false),
                    uuid(string(json.get("authorId"), ""), where + " authorId", problems));
            }
            case "MESSAGE" -> new Effect.Message(string(json.get("message"), ""));
            case "SOUND" -> {
                double volume = doubleValue(json.get("volume"), 1.0D);
                double pitch = doubleValue(json.get("pitch"), 1.0D);
                requireRange(where + " volume", volume, 0.0D, 10.0D, problems);
                requireRange(where + " pitch", pitch, 0.5D, 2.0D, problems);
                yield new Effect.Sound(string(json.get("key"), ""), (float) volume, (float) pitch);
            }
            case "STAMP_COOLDOWN" -> {
                long millis = longValue(json.get("millis"), 0L);
                requireRange(where + " millis", millis, 0L, limits.cooldownMaxSeconds * 1000L, problems);
                yield new Effect.StampCooldown(string(json.get("group"), ""), millis);
            }
            default -> {
                problems.add(where + " has unknown kind '" + kind + "'");
                yield null;
            }
        };
    }

    private static Map<String, Object> matcherToJson(ItemMatcher matcher) {
        ItemMatcher value = matcher == null ? ItemMatcher.material("") : matcher;
        return object("material", value.material(), "identity", value.identity() == null ? "" : value.identity().toString(), "exactMeta", value.exactMeta());
    }

    private static ItemMatcher matcherFromJson(Map<String, Object> json, String where, List<String> problems) {
        if (json == null) {
            problems.add(where + " has no item matcher");
            return ItemMatcher.material("");
        }
        String material = string(json.get("material"), "");
        UUID identity = uuid(string(json.get("identity"), ""), where + " matcher identity", problems);
        if (material.isBlank() && identity == null) {
            problems.add(where + " matcher names neither a material nor an identity");
        }
        return new ItemMatcher(material, identity, booleanValue(json.get("exactMeta"), false));
    }

    private static List<Object> names(Set<? extends Enum<?>> values) {
        List<Object> array = new ArrayList<>();
        for (Enum<?> value : values) {
            array.add(value.name());
        }
        return array;
    }

    private static List<Object> strings(Set<String> values) {
        List<Object> array = new ArrayList<>();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<Object> integers(Set<Integer> values) {
        List<Object> array = new ArrayList<>();
        for (Integer value : values) {
            array.add(value.intValue());
        }
        return array;
    }

    private static Set<String> stringSet(List<Object> array) {
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.size(); i++) {
            values.add(string(array.get(i), ""));
        }
        return values;
    }

    private static Set<Integer> integerSet(List<Object> array) {
        Set<Integer> values = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.size(); i++) {
            values.add(Integer.valueOf(intValue(array.get(i), 0)));
        }
        return values;
    }

    private static <E extends Enum<E>> Set<E> enums(List<Object> array, Class<E> type, String where, List<String> problems) {
        Set<E> values = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.size(); i++) {
            String name = string(array.get(i), "");
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
    private static Map<String, Object> object(Object... fields) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            object.put((String) fields[index], fields[index + 1]);
        }
        return object;
    }

    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> document)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : document.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    static List<Object> array(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : null;
    }

    static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value instanceof String string ? Long.parseLong(string) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value instanceof String string ? Integer.parseInt(string) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value instanceof String string ? Double.parseDouble(string) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            if (string.equalsIgnoreCase("true")) {
                return true;
            }
            if (string.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return fallback;
    }

}
