package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeMessages;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesLocalizationTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void installRepositoryTranslations() throws IOException {
        Path installed = Files.createDirectories(tempDir.resolve("languages"));
        for (String locale : VolmitLocales.nonEnglish()) {
            Files.copy(Path.of("src/main/resources/languages", locale + ".toml"), installed.resolve(locale + ".toml"));
        }
    }

    @Test
    void editorPersistsEnglishTextLinesAndPluralFormsInTheLanguageFile() throws Exception {
        WormholesLocalization localization = new WormholesLocalization();
        PluginLanguageEditor.Options editor = localization.editorOptions(tempDir, () -> "missing_custom");
        LocalizationSnapshot initial = editor.loader().load("en_US");
        List<MessageKey> changed = new ArrayList<>();
        for (MessageKey key : initial.catalog().keys()) {
            MessageValue value = initial.value(key);
            if (value instanceof TextValue && changed.stream().noneMatch(TextKey.class::isInstance)) {
                TextValue text = (TextValue) value;
                editor.writer().write(new PluginLanguageEditor.Edit("en_US", key.id(), value,
                        new TextValue(text.template() + " edited")));
                changed.add(key);
            } else if (value instanceof LinesValue lines && changed.stream().noneMatch(LinesKey.class::isInstance)) {
                List<String> updated = new ArrayList<>(lines.lines());
                updated.set(0, updated.getFirst() + " edited");
                editor.writer().write(new PluginLanguageEditor.Edit("en_US", key.id(), value, new LinesValue(updated)));
                changed.add(key);
            } else if (value instanceof PluralValue plural && changed.stream().noneMatch(PluralKey.class::isInstance)) {
                Map<String, String> updated = new LinkedHashMap<>();
                for (Map.Entry<String, String> form : plural.forms().entrySet()) {
                    updated.put(form.getKey(), form.getValue() + " edited");
                }
                editor.writer().write(new PluginLanguageEditor.Edit("en_US", key.id(), value, new PluralValue(updated)));
                changed.add(key);
            }
        }
        assertEquals(3, changed.size());
        LocalizationSnapshot loaded = editor.loader().load("en_US");
        for (MessageKey key : changed) {
            assertFalse(initial.value(key).equals(loaded.value(key)));
            assertEquals(loaded.value(key), localization.defaultSnapshot().value(key));
        }
        assertTrue(Files.isRegularFile(tempDir.resolve("languages/en_US.toml")));
    }

    @Test
    void editorLeavesActiveSelectionUnchangedAndRejectsInvalidOrStaleValues() throws Exception {
        WormholesLocalization localization = new WormholesLocalization();
        PluginLanguageEditor.Options editor = localization.editorOptions(tempDir, () -> "");
        Files.writeString(tempDir.resolve("languages/fr_FR.toml"), """
                "command.unknown" = "Commande inconnue"
                """);
        TextKey key = WormholesMessages.COMMAND_UNKNOWN;
        MessageValue original = editor.loader().load("fr_FR").value(key);
        Path file = tempDir.resolve("languages/fr_FR.toml");
        Path installed = tempDir.resolve("languages/fr_FR.toml");
        byte[] base = Files.readAllBytes(installed);
        assertThrows(IllegalArgumentException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
                "fr_FR", key.id(), original, new TextValue("{unexpected}"))));
        assertArrayEquals(base, Files.readAllBytes(file));
        assertThrows(IllegalArgumentException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
                "fr_FR", key.id(), original, new TextValue(" \t"))));
        assertArrayEquals(base, Files.readAllBytes(file));
        TextValue replacement = new TextValue("Commande inconnue modifiée");
        editor.writer().write(new PluginLanguageEditor.Edit("fr_FR", key.id(), original, replacement));
        byte[] saved = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
                "fr_FR", key.id(), original, new TextValue("Stale"))));
        assertArrayEquals(saved, Files.readAllBytes(file));
        assertArrayEquals(saved, Files.readAllBytes(installed));
        assertEquals(key.englishValue(), localization.defaultSnapshot().value(key));
        assertEquals(replacement, editor.loader().load("fr_FR").value(key));
    }

    @Test
    void everyInstalledLocaleFullyCoversTheTypedCatalog() {
        WormholesLocalization localization = new WormholesLocalization();
        for (String locale : VolmitLocales.nonEnglish()) {
            LocalizationReloadResult result = localization.reload(tempDir, locale, "");

            assertTrue(result.applied(), locale + ": " + result.failure());
            for (MessageKey key : WormholesMessages.catalog().keys()) {
                assertEquals(locale, localization.snapshot().sourceLocale(key), locale + ":" + key.id());
                MessageArgs arguments = renderArguments(key);
                assertDoesNotThrow(() -> render(localization, key, arguments), locale + ":" + key.id());
            }
            assertDoorTitleFormatting(localization, locale);
        }
    }

    @Test
    void everyLocaleDocumentsEveryCatalogPlaceholder() throws IOException {
        Set<String> expected = new HashSet<>();
        for (MessageKey key : WormholesMessages.catalog().keys()) {
            expected.addAll(key.placeholders());
        }
        for (String locale : VolmitLocales.nonEnglish()) {
            String content = Files.readString(Path.of("src/main/resources/languages", locale + ".toml"));
            String header = content.substring(0, content.indexOf("\n["));
            Set<String> documented = new HashSet<>();
            Matcher matcher = Pattern.compile("\\{([A-Za-z_]+)}").matcher(header);
            while (matcher.find()) {
                documented.add(matcher.group(1));
            }
            assertEquals(expected, documented, locale);
            assertTrue(header.contains("&0-&f"), locale);
            assertTrue(header.contains("&k-&r"), locale);
        }
    }

    @Test
    void repositoryResourceSetExactlyMatchesSharedManifest() throws IOException {
        Set<String> expected = VolmitLocales.nonEnglish().stream()
                .map(locale -> locale + ".toml")
                .collect(Collectors.toUnmodifiableSet());
        try (Stream<Path> paths = Files.list(Path.of("src/main/resources/languages"))) {
            Set<String> actual = paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
            assertEquals(expected, actual);
        }
        assertFalse(expected.contains(VolmitLocales.ENGLISH + ".toml"));
    }

    @Test
    void overlayFallbackAndDirectorTextRemainInMemoryAfterFilesDisappear() throws IOException {
        writeLocale("custom_ES", """
            "portal.deleted" = "<red>{portal} eliminado"
            "director.help.navigation.back" = "Atrás"
            "command.public_help" = ["Ayuda de portales", "Usa la varita"]

            [command.admin.deleted_portals]
            one = "{count} portal borrado"
            other = "{count} portales borrados"
            """);
        writeLocale("fr_FR", """
            "command.unknown" = "<gray>Commande inconnue. Utilisez <white>/wormholes."
            """);

        WormholesLocalization localization = new WormholesLocalization();
        LocalizationReloadResult result = localization.reload(tempDir, "custom_ES", "fr_FR");

        assertTrue(result.applied(), String.valueOf(result.failure()));
        assertEquals("Entrada eliminado", localization.plain(
                WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", "Entrada"))));
        assertEquals("Commande inconnue. Utilisez /wormholes.", localization.plain(WormholesMessages.COMMAND_UNKNOWN));
        assertEquals("Ayuda de portales", localization.legacyLines(WormholesMessages.COMMAND_PUBLIC_HELP).getFirst());
        assertEquals("2 portales borrados", localization.plain(
                WormholesMessages.COMMAND_DELETED_PORTALS,
                WormholesLocalization.args(MessageArgument.untrusted("count", Long.valueOf(2L)))));
        assertEquals("Atrás", localization.directorResolver().resolve(DirectorHelpMessages.BACK));
        String runtimeText = localization.directorResolver().resolve(
                DirectorRuntimeMessages.UNKNOWN_PARAMETER,
                MessageArgs.builder().untrusted("key", "<red>Bad</red>\u00A7cName").build()
        );
        assertTrue(runtimeText.contains("<red>Bad</red>"));
        assertFalse(runtimeText.contains("\u00A7"));

        Files.delete(tempDir.resolve("languages").resolve("custom_ES.toml"));
        Files.delete(tempDir.resolve("languages").resolve("fr_FR.toml"));

        assertEquals("Entrada eliminado", localization.plain(
                WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", "Entrada"))));
        assertEquals("Commande inconnue. Utilisez /wormholes.", localization.plain(WormholesMessages.COMMAND_UNKNOWN));
    }

    @Test
    void nonEnglishStartupCreatesEnglishAndPreservesOperatorEdits() throws Exception {
        WormholesLocalization localization = new WormholesLocalization();
        assertTrue(localization.reload(tempDir, "fr_FR", "").applied());
        Path english = tempDir.resolve("languages/en_US.toml");
        String generated = Files.readString(english);
        assertTrue(generated.startsWith("# Wormholes — en_US"));
        assertTrue(generated.contains("&c{portal} Deleted"));
        assertTrue(generated.contains("&b&lOpenState: &f&l{state}"));
        String content = "\"command.unknown\" = \"Edited feedback\"\n";
        Files.writeString(english, content);
        assertTrue(localization.reload(tempDir, "en_US", "").applied());
        assertEquals("Edited feedback", localization.plain(WormholesMessages.COMMAND_UNKNOWN));
        assertEquals(content, Files.readString(english));
    }

    @Test
    void selectedLanguageFallsThroughConfiguredLanguagesThenEditedEnglish() throws IOException {
        writeLocale("custom_ES", """
                "command.debug.enabled" = "Depuración activada."
                """);
        writeLocale("fr_FR", """
                "command.unknown" = "Commande inconnue."
                """);
        writeLocale("en_US", """
                "command.unknown" = "Edited unknown command."
                "command.debug.disabled" = "Edited debug disabled."
                """);
        WormholesLocalization localization = new WormholesLocalization();

        assertTrue(localization.reload(tempDir, "custom_ES", "fr_FR,../invalid").applied());
        assertEquals("Depuración activada.", localization.plain(WormholesMessages.COMMAND_DEBUG_ENABLED));
        assertEquals("Commande inconnue.", localization.plain(WormholesMessages.COMMAND_UNKNOWN));
        assertEquals("Edited debug disabled.", localization.plain(WormholesMessages.COMMAND_DEBUG_DISABLED));
        assertEquals("en_US", localization.snapshot().sourceLocale(WormholesMessages.COMMAND_DEBUG_DISABLED));
        assertEquals(WormholesMessages.PORTAL_DELETED.englishValue(),
                localization.snapshot().value(WormholesMessages.PORTAL_DELETED));

        writeLocale("en_US", """
                "command.debug.disabled" = "Updated English feedback."
                """);
        assertTrue(localization.reload(tempDir, "custom_ES", "fr_FR").applied());
        assertEquals("Updated English feedback.", localization.plain(WormholesMessages.COMMAND_DEBUG_DISABLED));
    }

    @Test
    void missingSelectedLanguageUsesEditedEnglishAndInvalidEnglishEntriesUseCatalogDefaults() throws IOException {
        writeLocale("en_US", """
                "command.unknown" = "Edited unknown command."
                "command.debug.enabled" = "   "
                "command.debug.disabled" = false
                """);
        WormholesLocalization localization = new WormholesLocalization();

        assertTrue(localization.reload(tempDir, "missing_custom", "").applied());
        assertEquals("Edited unknown command.", localization.plain(WormholesMessages.COMMAND_UNKNOWN));
        assertEquals("Debug logging enabled.", localization.plain(WormholesMessages.COMMAND_DEBUG_ENABLED));
        assertEquals("Debug logging disabled.", localization.plain(WormholesMessages.COMMAND_DEBUG_DISABLED));
    }

    @Test
    void invalidSelectedLocaleReturnsToEnglishAfterAnotherLanguageWasActive() throws IOException {
        writeLocale("en_US", "");
        WormholesLocalization localization = new WormholesLocalization();
        String[] invalidLocales = {null, "", " \t", "../outside", "fr/FR", "en_US", " EN-us "};
        for (String locale : invalidLocales) {
            assertTrue(localization.reload(tempDir, "fr_FR", "").applied());
            assertEquals("fr_FR", localization.snapshot().sourceLocale(WormholesMessages.COMMAND_UNKNOWN));

            assertTrue(localization.reload(tempDir, locale, "fr_FR").applied());
            assertEquals("en_US", localization.snapshot().sourceLocale(WormholesMessages.COMMAND_UNKNOWN));
            assertEquals("Unknown command, please use /wormholes for help.",
                    localization.plain(WormholesMessages.COMMAND_UNKNOWN));
        }
    }

    @Test
    void blankAndWrongShapeTranslationsFallBackWhileValidMessagesRemainTranslated() throws IOException {
        writeLocale("custom_ES", """
                "portal.deleted" = "{portal} eliminado"
                "command.unknown" = ""
                "command.debug.enabled" = "   "
                "command.debug.disabled" = false
                "command.public_help" = ["Ayuda", ""]

                [command.admin.deleted_portals]
                one = ""
                other = "{count} portales borrados"
                """);
        WormholesLocalization localization = new WormholesLocalization();

        assertTrue(localization.reload(tempDir, "custom_ES", "").applied());
        for (MessageKey key : List.of(WormholesMessages.COMMAND_UNKNOWN, WormholesMessages.COMMAND_DEBUG_ENABLED,
                WormholesMessages.COMMAND_DEBUG_DISABLED, WormholesMessages.COMMAND_PUBLIC_HELP,
                WormholesMessages.COMMAND_DELETED_PORTALS)) {
            assertEquals(key.englishValue(), localization.snapshot().value(key), key.id());
            assertEquals("en_US", localization.snapshot().sourceLocale(key), key.id());
        }
        assertEquals("Entrada eliminado", localization.plain(WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", "Entrada"))));
    }

    @Test
    void malformedEnglishFileFallsBackToCatalogWhileValidSelectedEntriesRemainTranslated() throws IOException {
        writeLocale("custom_ES", """
                "command.debug.enabled" = "Depuración activada."
                """);
        writeLocale("en_US", "[command\nunknown = \"Unclosed table\"\n");
        WormholesLocalization localization = new WormholesLocalization();

        assertTrue(localization.reload(tempDir, "custom_ES", "").applied());
        assertEquals("Depuración activada.", localization.plain(WormholesMessages.COMMAND_DEBUG_ENABLED));
        assertEquals("Unknown command, please use /wormholes for help.",
                localization.plain(WormholesMessages.COMMAND_UNKNOWN));
    }

    @Test
    void untrustedMiniMessageInputCannotInstallClickEvents() {
        WormholesLocalization localization = WormholesLocalization.english();
        String portalName = "<click:run_command:'/op attacker'>Owned</click>";
        Component rendered = localization.component(
                WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", portalName)));

        assertFalse(hasClickEvent(rendered));
        assertTrue(localization.plain(
                WormholesMessages.PORTAL_DELETED,
                WormholesLocalization.args(MessageArgument.untrusted("portal", portalName))).contains(portalName));
    }

    @Test
    void colorCodesPreserveBoldUntilResetAndKeepArgumentsLiteral() throws IOException {
        writeLocale("custom_colors", """
                "portal.deleted" = "&c&l{portal} &f&lWhite&c Plain&r End"
                """);
        WormholesLocalization localization = new WormholesLocalization();
        assertTrue(localization.reload(tempDir, "custom_colors", "").applied());
        MessageArgs arguments = WormholesLocalization.args(
                MessageArgument.untrusted("portal", "<red>Portal</red> &bName"));

        assertEquals("\u00A7c\u00A7l<red>Portal</red> &bName \u00A7f\u00A7lWhite\u00A7c Plain\u00A7r End",
                localization.legacy(WormholesMessages.PORTAL_DELETED, arguments));
        assertEquals("<red>Portal</red> &bName White Plain End",
                localization.plain(WormholesMessages.PORTAL_DELETED, arguments));
    }

    @Test
    void everyEnglishCatalogEntryRendersWithItsDeclaredArguments() {
        WormholesLocalization localization = WormholesLocalization.english();

        for (MessageKey key : WormholesMessages.catalog().keys()) {
            MessageArgs arguments = renderArguments(key);
            assertDoesNotThrow(() -> render(localization, key, arguments), key.id());
        }
        assertDoorTitleFormatting(localization, VolmitLocales.ENGLISH);
        assertTrue(localization.plain(WormholesMessages.NETWORK_COPY_CODE_HOVER).contains("/wh server import <code>"));
    }

    @Test
    void sharedLanguageEditorFormattingRendersWithoutLiteralColorCodes() {
        assertEquals("Back", WormholesLocalization.english().plain(BukkitLanguageMessages.EDITOR_BACK));
    }

    @Test
    void invalidPlaceholderUsesEnglishWhileValidEntriesRemainTranslated() throws IOException {
        writeLocale("es_ES", """
            "portal.deleted" = "<red>{portal} eliminado"
            """);
        WormholesLocalization localization = new WormholesLocalization();
        LocalizationReloadResult applied = localization.reload(tempDir, "es_ES", "");
        assertTrue(applied.applied(), String.valueOf(applied.failure()));
        LocalizationSnapshot lastGood = localization.snapshot();

        writeLocale("es_ES", """
            "portal.deleted" = "<red>{name} eliminado"
            "command.unknown" = "Comando desconocido."
            """);
        LocalizationReloadResult rejected = localization.reload(tempDir, "es_ES", "");

        assertTrue(rejected.applied());
        assertEquals(WormholesMessages.PORTAL_DELETED.englishValue(), localization.snapshot().value(WormholesMessages.PORTAL_DELETED));
        assertEquals("Comando desconocido.", localization.plain(WormholesMessages.COMMAND_UNKNOWN));

    }

    @Test
    void malformedTomlUsesEnglishAndUnknownKeysAreIgnored() throws IOException {
        writeLocale("es_ES", """
            [portal
            deleted = "<red>{portal} eliminado"
            """);
        WormholesLocalization localization = new WormholesLocalization();
        LocalizationSnapshot initial = localization.snapshot();

        LocalizationReloadResult malformedRejected = localization.reload(tempDir, "es_ES", "");
        assertTrue(malformedRejected.applied());
        assertEquals(WormholesMessages.PORTAL_DELETED.englishValue(), localization.snapshot().value(WormholesMessages.PORTAL_DELETED));

        writeLocale("es_ES", """
            "portal.unknown" = "Desconocido"
            """);
        LocalizationReloadResult keyRejected = localization.reload(tempDir, "es_ES", "");
        assertTrue(keyRejected.applied());
        assertNull(keyRejected.current().catalog().key("portal.unknown"));
    }

    private void writeLocale(String locale, String contents) throws IOException {
        Path languages = tempDir.resolve("languages");
        Files.createDirectories(languages);
        Files.writeString(languages.resolve(locale + ".toml"), contents, StandardCharsets.UTF_8);
    }

    private MessageArgs renderArguments(MessageKey key) {
        MessageArgs.Builder arguments = MessageArgs.builder();
        for (String placeholder : key.placeholders()) {
            Object value = key instanceof PluralKey pluralKey && placeholder.equals(pluralKey.selectorArgument())
                    ? Long.valueOf(2L)
                    : "sample";
            arguments.add(MessageArgument.untrusted(placeholder, value));
        }
        return arguments.build();
    }

    private void assertDoorTitleFormatting(WormholesLocalization localization, String locale) {
        List<String> lines = localization.legacyLines(WormholesMessages.DOOR_MENU_ACCESS_OPEN_STATE,
                WormholesLocalization.args(
                        MessageArgument.untrusted("state", "OPEN"),
                        MessageArgument.untrusted("next", "CLOSED")));
        assertTrue(lines.getFirst().startsWith("\u00A7b\u00A7l"), locale);
        assertTrue(lines.getFirst().endsWith("\u00A7f\u00A7lOPEN"), locale);
        assertTrue(lines.get(1).startsWith("\u00A77"), locale);
        assertFalse(lines.get(1).contains("\u00A7l"), locale);
        assertTrue(lines.get(2).contains("\u00A7fOPEN\u00A77"), locale);
    }

    private void render(WormholesLocalization localization, MessageKey key, MessageArgs arguments) {
        if (key instanceof TextKey textKey) {
            localization.component(textKey, arguments);
            return;
        }
        if (key instanceof LinesKey linesKey) {
            localization.components(linesKey, arguments);
            return;
        }
        localization.component((PluralKey) key, arguments);
    }

    private boolean hasClickEvent(Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasClickEvent(child)) {
                return true;
            }
        }
        return false;
    }
}
