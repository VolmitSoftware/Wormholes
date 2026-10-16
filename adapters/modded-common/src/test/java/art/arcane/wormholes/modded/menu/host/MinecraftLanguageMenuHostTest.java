package art.arcane.wormholes.modded.menu.host;

import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.wormholes.menu.language.LanguageMenuView;
import art.arcane.wormholes.modded.MinecraftLocalization;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftLanguageMenuHostTest extends MinecraftTestBase {
    private static final TextKey NAME = TextKey.of("portal.name", "Portal {name}");

    private MinecraftLocalization localization;
    private PluginLanguageService languages;
    private PluginLanguageEditor editor;
    private MinecraftLanguageMenuHost host;

    @Before
    public void setUp() {
        localization = mock(MinecraftLocalization.class);
        languages = mock(PluginLanguageService.class);
        editor = mock(PluginLanguageEditor.class);
        when(localization.languages()).thenReturn(languages);
        when(localization.editor()).thenReturn(editor);
        host = new MinecraftLanguageMenuHost(localization);
    }

    @Test
    public void localesAndTheDefaultComeFromTheLanguageService() {
        when(languages.availableLocales()).thenReturn(List.of("en_US", "de_DE"));
        when(languages.defaultLocale()).thenReturn("de_DE");

        assertEquals(List.of("en_US", "de_DE"), host.locales());
        assertEquals("de_DE", host.defaultLocale());
    }

    @Test
    public void loadsAndSavesKeepTheCachedDocumentCurrent() throws Exception {
        when(editor.load("de-de")).thenReturn(CompletableFuture.completedFuture(document("de_DE", "Tor {name}")));
        LanguageMenuView loaded = host.load("de-de").get();
        assertSame(loaded, host.document("de_DE").orElseThrow());

        PluginLanguageEditor.Edit edit = new PluginLanguageEditor.Edit("de_DE", NAME.id(), new TextValue("Tor {name}"), new TextValue("Pforte {name}"));
        when(editor.save(edit)).thenReturn(CompletableFuture.completedFuture(document("de_DE", "Pforte {name}")));
        host.apply(edit).get();

        assertEquals(new TextValue("Pforte {name}"), host.document("de_DE").orElseThrow().message(NAME.id()).orElseThrow().value());
    }

    @Test
    public void aConflictKeepsTheEditorIoException() {
        PluginLanguageEditor.Edit edit = new PluginLanguageEditor.Edit("de_DE", NAME.id(), new TextValue("Old"), new TextValue("New"));
        when(editor.save(edit)).thenReturn(CompletableFuture.failedFuture(new IOException("Language message changed")));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> host.apply(edit).get());

        assertTrue(failure.getCause() instanceof IOException);
    }

    @Test
    public void exitShowsTheServerLanguageMenu() {
        ServerPlayer player = mock(ServerPlayer.class);

        host.exit(new MinecraftMenuViewer(mock(WormholesModRuntime.class), player));

        verify(localization).showServerLanguages(player);
    }

    private static PluginLanguageEditor.Document document(String locale, String name) {
        LocaleOverlay overlay = LocaleOverlay.builder("test", locale).put(NAME.id(), new TextValue(name)).build();
        return new PluginLanguageEditor.Document(locale, LocalizationSnapshot.create(new LocalizationCandidate(MessageCatalog.of("en_US", NAME),
            List.of(overlay), PluralSelector.oneOther())));
    }
}
