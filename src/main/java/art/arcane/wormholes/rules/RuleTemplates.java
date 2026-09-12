package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
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

    public RuleTemplates(Path dataFolder) {
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
        return RuleDocumentCodec.fromJson(toJson(result.value()));
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
        JSONObject encoded = RuleDocumentCodec.toJson(document);
        JSONArray rules = encoded.getJSONArray("rules");
        for (int i = 0; i < rules.length(); i++) {
            file.rules.add(toEntry(rules.getJSONObject(i)));
        }
        return file;
    }

    private static RuleTemplateFile.RuleEntry toEntry(JSONObject rule) {
        RuleTemplateFile.RuleEntry entry = new RuleTemplateFile.RuleEntry();
        entry.id = rule.optString("id", "");
        JSONObject outcome = rule.optJSONObject("outcome");
        entry.outcome = outcome == null ? RuleOutcome.Kind.ALLOW.name() : outcome.optString("kind", RuleOutcome.Kind.ALLOW.name());
        entry.reason = outcome == null ? "" : outcome.optString("reason", "");
        entry.conditions = lines(rule.optJSONArray("conditions"));
        entry.costs = lines(rule.optJSONArray("costs"));
        entry.effects = lines(rule.optJSONArray("effects"));
        return entry;
    }

    private static List<String> lines(JSONArray array) {
        List<String> encoded = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            encoded.add(RuleLineCodec.toLine(array.getJSONObject(i)));
        }
        return encoded;
    }

    private static JSONObject toJson(RuleTemplateFile file) {
        JSONObject profile = new JSONObject()
            .put("cooldownMillis", file.cooldownMillis)
            .put("cooldownGroup", file.cooldownGroup)
            .put("warmupMillis", file.warmupMillis)
            .put("pushbackScale", file.pushbackScale)
            .put("soundVolume", file.soundVolume)
            .put("chargeCapacity", file.chargeCapacity)
            .put("chargeRegenPerInterval", file.chargeRegenPerInterval);
        JSONArray rules = new JSONArray();
        for (RuleTemplateFile.RuleEntry entry : file.rules) {
            rules.put(new JSONObject()
                .put("id", entry.id)
                .put("outcome", new JSONObject().put("kind", entry.outcome).put("reason", entry.reason))
                .put("conditions", parsed(entry.conditions))
                .put("costs", parsed(entry.costs))
                .put("effects", parsed(entry.effects)));
        }
        return new JSONObject()
            .put("revision", 0L)
            .put("profile", profile)
            .put("default", new JSONObject().put("kind", file.defaultOutcome).put("reason", file.defaultReason))
            .put("rules", rules);
    }

    private static JSONArray parsed(List<String> encoded) {
        JSONArray array = new JSONArray();
        for (String line : encoded) {
            if (!line.isBlank()) {
                array.put(RuleLineCodec.fromLine(line));
            }
        }
        return array;
    }
}
