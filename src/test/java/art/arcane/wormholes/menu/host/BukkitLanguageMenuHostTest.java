package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.language.LanguageMenuView;

class BukkitLanguageMenuHostTest {
    private static final TextKey NAME = TextKey.of("portal.name", "Portal {name}");

    private final List<Player> exits = new ArrayList<>();
    private final Logger logger = mock(Logger.class);
    private PluginLanguageService languages;
    private PluginLanguageEditor editor;
    private BukkitLanguageMenuHost host;

    @BeforeEach
    void setUp() {
        languages = mock(PluginLanguageService.class);
        editor = mock(PluginLanguageEditor.class);
        host = new BukkitLanguageMenuHost(new BukkitLanguageMenuHost.Options(languages, editor, exits::add, logger));
    }

    @Test
    void localesAndTheDefaultComeFromTheLanguageService() {
        when(languages.availableLocales()).thenReturn(List.of("en_US", "fr_FR"));
        when(languages.defaultLocale()).thenReturn("fr_FR");

        assertEquals(List.of("en_US", "fr_FR"), host.locales());
        assertEquals("fr_FR", host.defaultLocale());
    }

    @Test
    void aLoadedDocumentIsCachedUnderItsCanonicalLocale() throws Exception {
        when(editor.load("fr-fr")).thenReturn(CompletableFuture.completedFuture(document("fr_FR", "Porte {name}")));

        assertTrue(host.document("fr_FR").isEmpty());
        LanguageMenuView loaded = host.load("fr-fr").get();

        assertEquals("fr_FR", loaded.locale());
        assertSame(loaded, host.document("fr_FR").orElseThrow());
    }

    @Test
    void anAppliedEditCompletesOnceTheCacheShowsTheSavedValue() throws Exception {
        when(editor.load("fr_FR")).thenReturn(CompletableFuture.completedFuture(document("fr_FR", "Porte {name}")));
        host.load("fr_FR").get();
        PluginLanguageEditor.Edit edit = new PluginLanguageEditor.Edit("fr_FR", NAME.id(), new TextValue("Porte {name}"),
            new TextValue("Portail {name}"));
        when(editor.save(edit)).thenReturn(CompletableFuture.completedFuture(document("fr_FR", "Portail {name}")));

        host.apply(edit).get();

        assertEquals(new TextValue("Portail {name}"), host.document("fr_FR").orElseThrow().message(NAME.id()).orElseThrow().value());
    }

    @Test
    void aConflictingSaveFailsWithTheEditorIoException() {
        PluginLanguageEditor.Edit edit = new PluginLanguageEditor.Edit("fr_FR", NAME.id(), new TextValue("Old"), new TextValue("New"));
        when(editor.save(edit)).thenReturn(CompletableFuture.failedFuture(new IOException("Language message changed")));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> host.apply(edit).get());

        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(host.document("fr_FR").isEmpty());
    }

    @Test
    void exitHandsThePlayerBackToTheServerLanguageMenu() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        host.exit(new BukkitMenuViewer(player));

        assertEquals(List.of(player), exits);
    }

    @Test
    void editorFailuresReachThePluginLoggerWithTheirCause() {
        IOException cause = new IOException("disk full");

        host.failed("Unable to save Wormholes language message portal.name in fr_FR", cause);

        verify(logger).log(Level.SEVERE, "Unable to save Wormholes language message portal.name in fr_FR", cause);
    }

    private static PluginLanguageEditor.Document document(String locale, String name) {
        LocaleOverlay overlay = LocaleOverlay.builder("test", locale).put(NAME.id(), new TextValue(name)).build();
        LocalizationSnapshot snapshot = LocalizationSnapshot.create(new LocalizationCandidate(MessageCatalog.of("en_US", NAME),
            List.of(overlay), PluralSelector.oneOther()));
        return new PluginLanguageEditor.Document(locale, snapshot);
    }
}
