package art.arcane.wormholes.rules;

import art.arcane.wormholes.config.toml.RulesConfig;
import java.util.Map;
import art.arcane.wormholes.util.project.config.TomlCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Rule templates stored as TOML under {@code <data>/rules/templates/}. Saving turns a portal's document into a
 * hand-editable file; loading validates it through {@link RuleDocumentCodec} and reports every problem at once.
 */
public final class RuleTemplates {
    private static final String EXTENSION = ".toml";

    private final Path folder;
    private final RulesConfig limits;

    public RuleTemplates(Path dataFolder, RulesConfig limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.folder = Objects.requireNonNull(dataFolder, "dataFolder").resolve("rules").resolve("templates");
    }

    public void save(String name, RuleDocument document) throws IOException {
        Path file = resolve(name);
        Files.createDirectories(folder);
        TomlCodec.writeCanonical(file.toFile(), toFile(document));
    }

    /** The stored template, or null when there is none by that name. */
    public RuleDocument load(String name) {
        Path file = resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        TomlCodec.LoadResult<RuleTemplateFile> result = TomlCodec.readExisting(file.toFile(), RuleTemplateFile.class);
        if (!result.isSuccess()) {
            throw new RuleValidationException(List.of(String.valueOf(result.error().getMessage())));
        }
        return RuleDocumentCodec.fromJson(toJson(result.value()), limits);
    }

    public List<String> list() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(file -> file.endsWith(EXTENSION))
                .map(file -> file.substring(0, file.length() - EXTENSION.length()))
                .sorted()
                .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private Path resolve(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Template name cannot be empty");
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("Template name may only contain letters, digits, dashes and underscores: " + name);
        }
        return folder.resolve(normalized + EXTENSION);
    }

    private static RuleTemplateFile toFile(RuleDocument document) {
        RuleTemplateFile file = new RuleTemplateFile();
        TraversalProfile profile = document.profile();
        file.defaultOutcome = document.defaultOutcome().kind().name();
        file.defaultReason = document.defaultOutcome().reason();
        file.cooldownMillis = profile.cooldownMillis();
        file.cooldownGroup = profile.cooldownGroup();
        file.warmupMillis = profile.warmupMillis();
        file.pushbackScale = profile.pushbackScale();
        file.soundVolume = profile.soundVolume();
        file.chargeCapacity = profile.chargeCapacity();
        file.chargeRegenPerInterval = profile.chargeRegenPerInterval();
        Map<String, Object> encoded = RuleDocumentCodec.toJson(document);
        for (Object rule : RuleDocumentCodec.array(encoded.get("rules"))) {
            file.rules.add(toEntry(RuleDocumentCodec.map(rule)));
        }
        return file;
    }

    private static RuleTemplateFile.RuleEntry toEntry(Map<String, Object> rule) {
        RuleTemplateFile.RuleEntry entry = new RuleTemplateFile.RuleEntry();
        entry.id = RuleDocumentCodec.string(rule.get("id"), "");
        Map<String, Object> outcome = RuleDocumentCodec.map(rule.get("outcome"));
        entry.outcome = outcome == null ? RuleOutcome.Kind.ALLOW.name() : RuleDocumentCodec.string(outcome.get("kind"), RuleOutcome.Kind.ALLOW.name());
        entry.reason = outcome == null ? "" : RuleDocumentCodec.string(outcome.get("reason"), "");
        entry.conditions = lines(RuleDocumentCodec.array(rule.get("conditions")));
        entry.costs = lines(RuleDocumentCodec.array(rule.get("costs")));
        entry.effects = lines(RuleDocumentCodec.array(rule.get("effects")));
        return entry;
    }

    private static List<String> lines(List<Object> array) {
        List<String> encoded = new ArrayList<>();
        if (array != null) {
            for (Object entry : array) {
                encoded.add(RuleLineCodec.toLine(RuleDocumentCodec.map(entry)));
            }
        }
        return encoded;
    }

    private static Map<String, Object> toJson(RuleTemplateFile file) {
        Map<String, Object> profile = Map.of("cooldownMillis", file.cooldownMillis,
            "cooldownGroup", file.cooldownGroup, "warmupMillis", file.warmupMillis, "pushbackScale", file.pushbackScale,
            "soundVolume", file.soundVolume, "chargeCapacity", file.chargeCapacity, "chargeRegenPerInterval", file.chargeRegenPerInterval);
        List<Object> rules = new ArrayList<>();
        for (RuleTemplateFile.RuleEntry entry : file.rules) {
            rules.add(Map.of("id", entry.id, "outcome", Map.of("kind", entry.outcome, "reason", entry.reason),
                "conditions", parsed(entry.conditions), "costs", parsed(entry.costs), "effects", parsed(entry.effects)));
        }
        return Map.of("revision", 0L, "profile", profile,
            "default", Map.of("kind", file.defaultOutcome, "reason", file.defaultReason), "rules", rules);
    }

    private static List<Object> parsed(List<String> encoded) {
        List<Object> array = new ArrayList<>();
        for (String line : encoded) {
            if (!line.isBlank()) {
                array.add(RuleLineCodec.fromLine(line));
            }
        }
        return array;
    }
}
