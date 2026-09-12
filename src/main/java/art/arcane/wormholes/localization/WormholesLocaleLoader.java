package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.localization.LanguageFileEditor;
import art.arcane.volmlib.util.localization.LanguageReferenceRenderer;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.RemoteLanguageCatalog;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.localization.VolmitLocales;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

final class WormholesLocaleLoader {
    private static final Logger LOGGER = Logger.getLogger("Wormholes");

    private WormholesLocaleLoader() {
    }

    static LocalizationCandidate load(Path dataFolder, String locale, String fallbackLocales) throws IOException {
        Path languageFolder = dataFolder.resolve("languages");
        Files.createDirectories(languageFolder);
        createEnglishLanguageIfMissing(languageFolder);
        List<String> requestedLocales = requestedLocales(locale, fallbackLocales);
        List<LocaleOverlay> overlays = new ArrayList<>(requestedLocales.size());
        for (String requestedLocale : requestedLocales) {
            Path file = languageFile(languageFolder, requestedLocale);
            try {
                if (!Files.exists(file) && VolmitLocales.isBundled(requestedLocale)
                        && !requestedLocale.equalsIgnoreCase(WormholesMessages.ENGLISH_LOCALE)) {
                    installLanguage(file, requestedLocale);
                }
                requireLanguageFile(file);
                overlays.add(loadFileOverlay(file, requestedLocale));
            } catch (IOException | RuntimeException exception) {
                LOGGER.log(Level.WARNING, "Using English fallback for unreadable language file " + file, exception);
            }
        }
        return new LocalizationCandidate(WormholesMessages.catalog(), overlays, PluralSelector.oneOther());
    }

    static LocalizationSnapshot edit(Path dataFolder, PluginLanguageEditor.Edit edit, String fallbacks) throws IOException {
        LocalizationCandidate current = load(dataFolder, edit.locale(), fallbacks);
        Path path = languageFile(dataFolder.resolve("languages"), WormholesLocales.require(edit.locale()));
        if (hasBlankTranslation(current.catalog().require(edit.key()), edit.value())) {
            throw new IllegalArgumentException("Language message cannot be blank: " + edit.key());
        }
        List<LocaleOverlay> base = new ArrayList<>(current.overlays().size());
        for (LocaleOverlay overlay : current.overlays()) {
            if (!overlay.source().equals(path.toString())) {
                base.add(overlay);
            }
        }
        LocalizationSnapshot.create(new LocalizationCandidate(WormholesMessages.catalog(),
                List.of(LocaleOverlay.builder("editor", edit.locale()).put(edit.key(), edit.value()).build()),
                PluralSelector.oneOther()));
        return LanguageFileEditor.update(path, raw -> {
            LocalizationSnapshot before = editedSnapshot(raw, path, edit.locale(), base);
            MessageKey key = WormholesMessages.catalog().key(edit.key());
            if (key == null || !before.value(key).equals(edit.expected())) {
                throw new IOException("Language message changed while it was being edited: " + edit.key());
            }
            TomlLanguageEditor.EditResult updated = TomlLanguageEditor.upsert(raw, edit.key(), edit.value());
            return new LanguageFileEditor.Prepared<>(updated.content(),
                    editedSnapshot(updated.content(), path, edit.locale(), base));
        });
    }

    private static LocalizationSnapshot editedSnapshot(String content, Path path, String locale,
                                                       List<LocaleOverlay> base) throws IOException {
        List<LocaleOverlay> overlays = new ArrayList<>(base.size() + 1);
        overlays.add(loadOverlay(content, path.toString(), locale));
        overlays.addAll(base);
        return LocalizationSnapshot.create(new LocalizationCandidate(
                WormholesMessages.catalog(), overlays, PluralSelector.oneOther()));
    }

    private static void createEnglishLanguageIfMissing(Path languageFolder) throws IOException {
        Path file = languageFile(languageFolder, WormholesMessages.ENGLISH_LOCALE);
        if (Files.exists(file)) {
            return;
        }
        AtomicFileIO.writeString(file, LanguageReferenceRenderer.render(
                WormholesMessages.catalog(), WormholesLanguageHeader.english()));
    }

    private static void requireLanguageFile(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > 2L * 1024L * 1024L) {
            throw new IOException("Language file is not a regular file within the size limit: " + path);
        }
    }

    private static List<String> requestedLocales(String locale, String fallbackLocales) {
        LinkedHashSet<String> locales = new LinkedHashSet<>();
        String selected = WormholesLocales.normalize(locale);
        locales.add(selected);
        if (!WormholesMessages.ENGLISH_LOCALE.equals(selected)
                && fallbackLocales != null && !fallbackLocales.isBlank()) {
            for (String fallback : fallbackLocales.split(",")) {
                if (!fallback.isBlank()) {
                    try {
                        locales.add(WormholesLocales.require(fallback));
                    } catch (IllegalArgumentException exception) {
                        LOGGER.log(Level.WARNING, "Ignoring invalid fallback language locale: " + fallback, exception);
                    }
                }
            }
        }
        locales.add(WormholesMessages.ENGLISH_LOCALE);
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
        return loadOverlay(Files.readString(file), file.toString(), locale);
    }

    private static void installLanguage(Path destination, String locale) throws IOException {
        try (RemoteLanguageCatalog remote = RemoteLanguageCatalog.load(new RemoteLanguageCatalog.Options(
                "Wormholes",
                URI.create("https://raw.githubusercontent.com/VolmitSoftware/WormholesPlugin/"),
                "src/main/resources/languages",
                ".toml",
                "wormholes-language-source.properties",
                WormholesLocaleLoader.class.getClassLoader()))) {
            remote.readOrInstall(locale, destination, (selected, content) -> {
                LocaleOverlay overlay = loadOverlay(content, destination.toString(), selected);
                LocalizationSnapshot.create(new LocalizationCandidate(
                        WormholesMessages.catalog(), List.of(overlay), PluralSelector.oneOther()));
            });
        } catch (Exception exception) {
            throw new IOException("Could not install Wormholes language " + locale, exception);
        }
    }

    private static LocaleOverlay loadOverlay(String content, String source, String locale) throws IOException {
        MessageCatalog catalog = WormholesMessages.catalog();
        LocaleOverlay.Builder overlay = LocaleOverlay.builder(source, locale);
        for (Map.Entry<String, MessageValue> entry : TomlLanguageParser.parseValidValues(
                content, catalog).entrySet()) {
            if (!hasBlankTranslation(catalog.require(entry.getKey()), entry.getValue())) {
                overlay.put(entry.getKey(), entry.getValue());
            }
        }
        return overlay.build();
    }

    private static boolean hasBlankTranslation(MessageKey key, MessageValue value) {
        if (key instanceof TextKey textKey && value instanceof TextValue textValue) {
            return !textKey.english().isBlank() && textValue.template().isBlank();
        }
        if (key instanceof LinesKey linesKey && value instanceof LinesValue linesValue) {
            for (int index = 0; index < Math.min(linesKey.english().size(), linesValue.lines().size()); index++) {
                if (!linesKey.english().get(index).isBlank() && linesValue.lines().get(index).isBlank()) {
                    return true;
                }
            }
        }
        if (key instanceof PluralKey pluralKey && value instanceof PluralValue pluralValue) {
            for (Map.Entry<String, String> form : pluralKey.english().entrySet()) {
                String translated = pluralValue.forms().get(form.getKey());
                if (!form.getValue().isBlank() && translated != null && translated.isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

}
