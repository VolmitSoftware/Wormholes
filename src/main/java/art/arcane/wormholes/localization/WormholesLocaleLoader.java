package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.config.TomlCodec;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LanguageFileEditor;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.PluralValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.RemoteLanguageCatalog;
import art.arcane.volmlib.util.localization.VolmitLocales;
import com.moandjiezana.toml.Toml;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class WormholesLocaleLoader {
    static final int SCHEMA = 1;

    private static final Pattern LOCALE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Set<String> ROOT_KEYS = Set.of("schema", "locale", "text", "lines", "plural");

    private WormholesLocaleLoader() {
    }

    static LocalizationCandidate load(Path dataFolder, String locale, String fallbackLocales) throws IOException {
        Path languageFolder = dataFolder.resolve("languages");
        List<String> requestedLocales = requestedLocales(locale, fallbackLocales);
        List<LocaleOverlay> overlays = new ArrayList<>(requestedLocales.size() * 2);
        for (String requestedLocale : requestedLocales) {
            Path override = overrideFile(dataFolder, requestedLocale);
            if (Files.exists(override)) {
                requireLanguageFile(override);
                overlays.add(loadFileOverlay(override, requestedLocale));
            }
            if (requestedLocale.equalsIgnoreCase(WormholesMessages.ENGLISH_LOCALE)) {
                continue;
            }

            Path file = languageFile(languageFolder, requestedLocale);
            if (!Files.isRegularFile(file) && VolmitLocales.isBundled(requestedLocale)) {
                installLanguage(dataFolder, file, requestedLocale);
            }
            if (!Files.isRegularFile(file)) {
                throw new IOException("Language file does not exist: " + requestedLocale);
            }
            overlays.add(loadFileOverlay(file, requestedLocale));
        }
        return new LocalizationCandidate(WormholesMessages.catalog(), overlays, PluralSelector.oneOther());
    }

    static LocalizationSnapshot edit(Path dataFolder, PluginLanguageEditor.Edit edit, String fallbacks) throws IOException {
        LocalizationCandidate current = load(dataFolder, edit.locale(), fallbacks);
        Path path = overrideFile(dataFolder, edit.locale());
        List<LocaleOverlay> base = new ArrayList<>(current.overlays().size());
        for (LocaleOverlay overlay : current.overlays()) {
            if (!overlay.source().equals(path.toString())) {
                base.add(overlay);
            }
        }
        return LanguageFileEditor.update(path, raw -> {
            JsonObject document;
            if (raw.isBlank()) {
                document = new JsonObject();
                document.addProperty("schema", SCHEMA);
                document.addProperty("locale", edit.locale());
            } else {
                JsonElement parsed = TomlCodec.toJsonElement(raw);
                if (!parsed.isJsonObject()) {
                    throw new IOException("Language override must be a TOML table: " + path);
                }
                document = parsed.getAsJsonObject();
            }
            LocalizationSnapshot before = editedSnapshot(document, path, edit.locale(), base);
            MessageKey key = WormholesMessages.catalog().key(edit.key());
            if (key == null || !before.value(key).equals(edit.expected())) {
                throw new IOException("Language message changed while it was being edited: " + edit.key());
            }
            if (edit.value() instanceof TextValue text) {
                table(document, "text").addProperty(edit.key(), text.template());
            } else if (edit.value() instanceof LinesValue lines) {
                JsonArray values = new JsonArray();
                for (String line : lines.lines()) {
                    values.add(line);
                }
                table(document, "lines").add(edit.key(), values);
            } else if (edit.value() instanceof PluralValue plural) {
                JsonObject forms = new JsonObject();
                for (Map.Entry<String, String> form : plural.forms().entrySet()) {
                    forms.addProperty(form.getKey(), form.getValue());
                }
                table(document, "plural").add(edit.key(), forms);
            }
            String content = TomlCodec.toToml(document);
            if (!TomlCodec.toJsonElement(content).equals(document)) {
                throw new IOException("Language overrides could not be serialized without changing their values");
            }
            return new LanguageFileEditor.Prepared<>(content, editedSnapshot(document, path, edit.locale(), base));
        });
    }

    private static JsonObject table(JsonObject root, String key) throws IOException {
        JsonElement existing = root.get(key);
        if (existing == null) {
            JsonObject table = new JsonObject();
            root.add(key, table);
            return table;
        }
        if (!existing.isJsonObject()) {
            throw new IOException("Language section must be a table: " + key);
        }
        return existing.getAsJsonObject();
    }

    private static LocalizationSnapshot editedSnapshot(JsonObject document, Path path, String locale,
                                                       List<LocaleOverlay> base) {
        List<LocaleOverlay> overlays = new ArrayList<>(base.size() + 1);
        overlays.add(loadOverlay(new Toml().read(TomlCodec.toToml(document)), path.toString(), locale));
        overlays.addAll(base);
        return LocalizationSnapshot.create(new LocalizationCandidate(
                WormholesMessages.catalog(), overlays, PluralSelector.oneOther()));
    }

    private static Path overrideFile(Path dataFolder, String locale) {
        return languageFile(dataFolder.resolve("languages/overrides"), requireLocale(locale));
    }

    private static void requireLanguageFile(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 2L * 1024L * 1024L) {
            throw new IOException("Language override is not a regular file within the size limit: " + path);
        }
    }

    private static List<String> requestedLocales(String locale, String fallbackLocales) {
        LinkedHashSet<String> locales = new LinkedHashSet<>();
        locales.add(requireLocale(locale));
        if (fallbackLocales != null && !fallbackLocales.isBlank()) {
            for (String fallback : fallbackLocales.split(",")) {
                if (!fallback.isBlank()) {
                    locales.add(requireLocale(fallback));
                }
            }
        }
        return List.copyOf(locales);
    }

    private static Path languageFile(Path languageFolder, String locale) {
        Path file = languageFolder.resolve(locale + ".toml").normalize();
        if (!file.getParent().equals(languageFolder.normalize())) {
            throw new IllegalArgumentException("Language file must stay inside the languages directory: " + locale);
        }
        return file;
    }

    private static LocaleOverlay loadFileOverlay(Path file, String locale) throws IOException {
        Toml toml;
        try {
            toml = new Toml().read(file.toFile());
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse language file: " + file, exception);
        }
        return loadOverlay(toml, file.toString(), locale);
    }

    private static void installLanguage(Path dataFolder, Path destination, String locale) throws IOException {
        try (RemoteLanguageCatalog remote = RemoteLanguageCatalog.load(new RemoteLanguageCatalog.Options(
                "Wormholes",
                URI.create("https://raw.githubusercontent.com/VolmitSoftware/WormholesPlugin/"),
                "src/main/resources/languages",
                ".toml",
                "wormholes-language-source.properties",
                dataFolder.resolve(".language-cache"),
                WormholesLocaleLoader.class.getClassLoader()))) {
            remote.readOrInstall(locale, destination, (selected, content) -> {
                LocaleOverlay overlay = loadOverlay(new Toml().read(content), destination.toString(), selected);
                LocalizationSnapshot.create(new LocalizationCandidate(
                        WormholesMessages.catalog(), List.of(overlay), PluralSelector.oneOther()));
            });
        } catch (Exception exception) {
            throw new IOException("Could not install Wormholes language " + locale, exception);
        }
    }

    private static LocaleOverlay loadOverlay(Toml toml, String source, String locale) {
        validateRoot(toml, source, locale);
        LocaleOverlay.Builder overlay = LocaleOverlay.builder(source, locale);
        readText(toml.getTable("text"), overlay, source);
        readLines(toml.getTable("lines"), overlay, source);
        readPlurals(toml.getTable("plural"), overlay, source);
        return overlay.build();
    }

    private static void validateRoot(Toml toml, String source, String locale) {
        Long schema = toml.getLong("schema");
        if (schema == null || schema.longValue() != SCHEMA) {
            throw new IllegalArgumentException("Unsupported language schema in " + source + "; expected " + SCHEMA);
        }
        String declaredLocale = toml.getString("locale");
        if (declaredLocale == null || !declaredLocale.equalsIgnoreCase(locale)) {
            throw new IllegalArgumentException("Language source " + source + " must declare locale = \"" + locale + "\"");
        }
        for (Map.Entry<String, Object> entry : toml.entrySet()) {
            if (!ROOT_KEYS.contains(entry.getKey())) {
                throw new IllegalArgumentException("Unknown language root key in " + source + ": " + entry.getKey());
            }
        }
    }

    private static void readText(Toml table, LocaleOverlay.Builder overlay, String source) {
        if (table == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            if (!(entry.getValue() instanceof String template)) {
                throw invalidValue(source, "text", entry.getKey(), "a string");
            }
            overlay.text(messageId(entry.getKey()), template);
        }
    }

    private static void readLines(Toml table, LocaleOverlay.Builder overlay, String source) {
        if (table == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            if (!(entry.getValue() instanceof List<?> rawLines)) {
                throw invalidValue(source, "lines", entry.getKey(), "an array of strings");
            }
            List<String> lines = new ArrayList<>(rawLines.size());
            for (Object rawLine : rawLines) {
                if (!(rawLine instanceof String line)) {
                    throw invalidValue(source, "lines", entry.getKey(), "an array of strings");
                }
                lines.add(line);
            }
            overlay.lines(messageId(entry.getKey()), lines);
        }
    }

    private static void readPlurals(Toml table, LocaleOverlay.Builder overlay, String source) {
        if (table == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            Map<?, ?> rawForms;
            if (entry.getValue() instanceof Toml formTable) {
                rawForms = formTable.toMap();
            } else if (entry.getValue() instanceof Map<?, ?> formMap) {
                rawForms = formMap;
            } else {
                throw invalidValue(source, "plural", entry.getKey(), "a table of plural forms");
            }
            LinkedHashMap<String, String> forms = new LinkedHashMap<>();
            for (Map.Entry<?, ?> rawForm : rawForms.entrySet()) {
                if (!(rawForm.getKey() instanceof String category) || !(rawForm.getValue() instanceof String template)) {
                    throw invalidValue(source, "plural", entry.getKey(), "a table of string plural forms");
                }
                forms.put(category, template);
            }
            overlay.plural(messageId(entry.getKey()), forms);
        }
    }

    private static String messageId(String rawKey) {
        if (rawKey.length() >= 2) {
            char first = rawKey.charAt(0);
            char last = rawKey.charAt(rawKey.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return rawKey.substring(1, rawKey.length() - 1);
            }
        }
        return rawKey;
    }

    private static IllegalArgumentException invalidValue(String source, String section, String key, String expected) {
        return new IllegalArgumentException("Language value " + section + ".\"" + key + "\" in " + source + " must be " + expected);
    }

    private static String requireLocale(String locale) {
        if (locale == null || !LOCALE_PATTERN.matcher(locale.trim()).matches()) {
            throw new IllegalArgumentException("Invalid language locale: " + locale);
        }
        return locale.trim();
    }
}
