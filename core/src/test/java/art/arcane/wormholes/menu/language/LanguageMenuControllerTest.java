package art.arcane.wormholes.menu.language;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.FakeMenuViewer;
import art.arcane.wormholes.menu.FakeStrings;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLayouts;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

final class LanguageMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final String ANY_PRIMARY = "{LEFT=P, RIGHT=P, SHIFT_LEFT=P, SHIFT_RIGHT=P, MIDDLE=P}";
    private static final String ANY_TEXT = "{LEFT=X, RIGHT=X, SHIFT_LEFT=X, SHIFT_RIGHT=X, MIDDLE=X}";
    private static final String CONFLICT = "Language message changed while the editor was open; reopen it and try again";
    private static final String THEME_HEX = "§x§8§c§7§a§4§5";
    private static final String PROMPT_PREFIX = ComponentText.markup("<#8c7a45>⇀ </#8c7a45>").legacy() + "§r";

    private FakeMenuHost host;
    private FakeMenuViewer viewer;
    private FakeLanguageMenuHost languages;
    private MenuService service;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        viewer = host.viewer(VIEWER, "Editor");
        viewer.permissions.add("volmit.language.admin");
        languages = new FakeLanguageMenuHost();
        languages.locales.addAll(List.of("de_DE", "en_US", "fr_FR"));
        service = new MenuService(host, new MenuRegistry(List.of(new LanguageMenuController(languages))), () -> 1_000L);
    }

    @Test
    void localesMatchTodaysEditorWindowWithTheDefaultLocaleMarked() {
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("Wormholes › Languages", 6, ChestPane.BLACK, null), build.chest());
        assertEquals(LanguageRoutes.LOCALES, build.screen().screenId());
        assertEquals("Language", build.screen().title());
        assertEquals("Languages", build.screen().context());
        assertEquals(new MenuPaging(0, 1), build.screen().paging());
        assertEquals(MenuLayouts.mapOf(
            "language-locale-de_DE", "ListEntry -4,0 minecraft:book - " + ANY_PRIMARY,
            "language-locale-en_US", "ListEntry -3,0 minecraft:writable_book - " + ANY_PRIMARY,
            "language-locale-fr_FR", "ListEntry -2,0 minecraft:book - " + ANY_PRIMARY,
            "language-back", "Button -4,5 minecraft:arrow - " + ANY_PRIMARY + " hidden",
            "language-close", "Button 4,5 minecraft:barrier - " + ANY_PRIMARY + " hidden"), MenuLayouts.layout(build.screen()));
        ListEntryElement english = (ListEntryElement) element(build.screen(), "language-locale-en_US");
        ListEntryElement german = (ListEntryElement) element(build.screen(), "language-locale-de_DE");
        assertTrue(english.selected());
        assertFalse(german.selected());
        assertEquals("§a✔§r §fen_US§r §8—§r §7English (United States)", english.label());
        assertEquals("§8•§r §fde_DE§r §8—§r §7German (Germany)", german.label());
        assertTrue(english.description().isEmpty());
        assertEquals(List.of("§8Click to open this language.§r"), english.common().chestHints());
        assertEquals("§eBack§r", element(build.screen(), "language-back").label());
        assertEquals("§cClose§r", element(build.screen(), "language-close").label());
        assertTrue(MenuLayouts.bound(build));
    }

    @Test
    void openingALocaleLoadsItsDocumentThenShowsTheCentredCategories() {
        open();

        chest("language-locale-en_US");
        assertEquals(List.of("load:en_US"), languages.calls);
        assertEquals(LanguageRoutes.LOCALES, host.chest.screen().screenId());
        languages.loading.complete(document());

        MenuBuild build = host.chest.last();
        assertEquals(LanguageRoutes.GROUPS, build.screen().screenId());
        assertEquals(new ChestLayout("Wormholes › en_US", 6, ChestPane.BLACK, null), build.chest());
        assertEquals("en_US", build.screen().context());
        assertTrue(build.screen().canGoBack());
        assertEquals(MenuLayouts.mapOf(
            "language-group-command", "ListEntry -1,2 minecraft:command_block - " + ANY_PRIMARY,
            "language-group-gui", "ListEntry 0,2 minecraft:chest - " + ANY_PRIMARY,
            "language-group-portal", "ListEntry 1,2 minecraft:obsidian - " + ANY_PRIMARY,
            "language-back", "Button -4,5 minecraft:arrow - " + ANY_PRIMARY + " hidden",
            "language-search", "Text 0,5 minecraft:compass - " + ANY_TEXT,
            "language-close", "Button 4,5 minecraft:barrier - " + ANY_PRIMARY + " hidden"), MenuLayouts.layout(build.screen()));
        MenuElement portal = element(build.screen(), "language-group-portal");
        assertEquals("Portal", MenuText.stripColors(portal.label()));
        assertTrue(portal.label().startsWith("§d"));
        assertEquals(List.of("§73 messages§r"), portal.description());
        assertEquals(List.of("§8Click to open this category.§r"), portal.common().chestHints());
        assertEquals("GUI", MenuText.stripColors(element(build.screen(), "language-group-gui").label()));
        TextElement search = (TextElement) element(build.screen(), "language-search");
        assertEquals("Search messages", MenuText.stripColors(search.label()));
        assertEquals(List.of("Click to search all messages."), stripped(search.common().chestHints()));
        assertEquals(new FakeStrings().text(MenuMessages.PLACEHOLDER_SEARCH), search.placeholder());
        assertEquals("", search.value());
        assertEquals(List.of("⇀ Search message keys or text. Type cancel to return."), stripped(search.chestPrompt()));
        assertEquals(ChestReopen.ALWAYS, search.chestReopen());
        assertTrue(MenuLayouts.bound(build));
    }

    @Test
    void loadFailuresReturnToTheFirstLocalesPageWithTodaysMessage() {
        open();
        chest("language-locale-fr_FR");

        languages.loading.completeExceptionally(new IOException("missing file"));

        assertEquals(LanguageRoutes.LOCALES, host.chest.screen().screenId());
        assertEquals(themed("Unable to edit: missing file"), host.chest.lastNotice());
        assertEquals(NoticeChannel.CHAT, lastNotice().chestChannel());
        assertEquals(MenuFeedbackStyle.ERROR, lastNotice().feedback().style());
    }

    @Test
    void categoriesPageSixteenAtATimeInTodaysCentredRows() {
        List<LanguageMenuView.Message> messages = new ArrayList<>(20);
        for (int index = 0; index < 20; index++) {
            messages.add(text(String.format("g%02d.key", index), "Value"));
        }
        openGroups(new LanguageMenuView("en_US", messages));
        MenuScreen first = host.chest.screen();

        assertEquals(new MenuPaging(0, 2), first.paging());
        assertEquals("ListEntry -3,1 minecraft:paper - " + ANY_PRIMARY, MenuLayouts.describe(element(first, "language-group-g00")));
        assertEquals("ListEntry 2,1 minecraft:paper - " + ANY_PRIMARY, MenuLayouts.describe(element(first, "language-group-g05")));
        assertEquals("ListEntry -2,2 minecraft:paper - " + ANY_PRIMARY, MenuLayouts.describe(element(first, "language-group-g06")));
        assertEquals("ListEntry 2,3 minecraft:paper - " + ANY_PRIMARY, MenuLayouts.describe(element(first, "language-group-g15")));
        assertEquals("Button 1,5 minecraft:arrow - " + ANY_PRIMARY + " hidden", MenuLayouts.describe(element(first, "language-next")));
        assertEquals("§eNext page§r", element(first, "language-next").label());

        chest("language-next");
        MenuScreen second = host.chest.screen();
        assertEquals(new MenuPaging(1, 2), second.paging());
        assertEquals("ListEntry -2,2 minecraft:paper - " + ANY_PRIMARY, MenuLayouts.describe(element(second, "language-group-g16")));
        assertEquals("ListEntry 1,2 minecraft:paper - " + ANY_PRIMARY, MenuLayouts.describe(element(second, "language-group-g19")));
        assertEquals("Button -1,5 minecraft:arrow - " + ANY_PRIMARY + " hidden", MenuLayouts.describe(element(second, "language-previous")));
        assertEquals("§ePrevious page§r", element(second, "language-previous").label());
        assertTrue(second.element("language-next").isEmpty());
    }

    @Test
    void groupKeysShowTodaysMessageItems() {
        openGroups(document());

        chest("language-group-portal");
        MenuBuild build = host.chest.last();

        assertEquals(LanguageRoutes.KEYS, build.screen().screenId());
        assertEquals(new ChestLayout("Wormholes › en_US / Portal", 6, ChestPane.BLACK, null), build.chest());
        assertEquals(MenuLayouts.mapOf(
            "language-key-portal.count", "ListEntry -4,0 minecraft:paper - " + ANY_PRIMARY,
            "language-key-portal.lines", "ListEntry -3,0 minecraft:paper - " + ANY_PRIMARY,
            "language-key-portal.name", "Text -2,0 minecraft:paper - " + ANY_TEXT,
            "language-back", "Button -4,5 minecraft:arrow - " + ANY_PRIMARY + " hidden",
            "language-close", "Button 4,5 minecraft:barrier - " + ANY_PRIMARY + " hidden"), MenuLayouts.layout(build.screen()));
        MenuElement count = element(build.screen(), "language-key-portal.count");
        assertEquals("portal.count", MenuText.stripColors(count.label()));
        assertTrue(count.label().startsWith("§f"));
        assertEquals(List.of("Current Value:", "one: {count} portal", "other: {count} portals", "", "{count}"), stripped(count.description()));
        assertEquals(List.of("Click to edit a plural form."), stripped(count.common().chestHints()));
        assertEquals(List.of("Click to edit individual lines."), stripped(element(build.screen(), "language-key-portal.lines").common().chestHints()));
        TextElement name = (TextElement) element(build.screen(), "language-key-portal.name");
        assertEquals(List.of("Current Value:", "Portal"), stripped(name.description()));
        assertEquals(List.of("Click to edit in chat."), stripped(name.common().chestHints()));
        assertEquals("&6Portal", name.value());
        assertEquals(MenuLimits.MAX_TEXT_INPUT_LENGTH, name.maxLength());
        assertEquals(List.of("⇀ Type a new value for portal.name in chat.", "⇀ Current Value: <reset><gold>Portal", "⇀ Variables: None",
            "⇀ Use \\n for new lines, \\\\ for a backslash, or type cancel to return."), stripped(name.chestPrompt()));
        assertEquals(ChestReopen.ALWAYS, name.chestReopen());
        assertTrue(MenuLayouts.bound(build));
    }

    @Test
    void textEditsSaveWithTheValueCapturedAtBuildTime() {
        openGroups(document());
        chest("language-group-portal");
        languages.documents.put("en_US", new LanguageMenuView("en_US", List.of(text("portal.name", "&cChanged elsewhere"))));

        chestText("language-key-portal.name", "&aGate\\nTwo");

        assertEquals(List.of(new PluginLanguageEditor.Edit("en_US", "portal.name", new TextValue("&6Portal"), new TextValue("&aGate\nTwo"))),
            languages.edits);
        languages.documents.put("en_US", document());
        languages.applying.complete(null);

        assertEquals("⇀ Saved en_US. Language selections are unchanged.\n⇀ portal.name: “<reset><gold>Portal” changed to “<reset><green>Gate\nTwo”.",
            MenuText.stripColors(host.chest.lastNotice()));
        assertEquals(PROMPT_PREFIX + "Saved en_US. Language selections are unchanged.\n" + PROMPT_PREFIX
            + "portal.name: “<reset><gold>Portal” changed to “<reset><green>Gate\nTwo”.", host.chest.lastNotice());
        assertEquals(NoticeChannel.CHAT, lastNotice().chestChannel());
        assertEquals(MenuFeedbackStyle.SUCCESS, lastNotice().feedback().style());
        assertEquals(LanguageRoutes.KEYS, host.chest.screen().screenId());
        assertEquals("&aGate\\nTwo", ((TextElement) element(host.chest.screen(), "language-key-portal.name")).value());
    }

    @Test
    void conflictsFailuresAndInvalidValuesUseTodaysEditorMessages() {
        openGroups(document());
        chest("language-group-portal");

        chestText("language-key-portal.name", "&aGate");
        languages.applying.completeExceptionally(new IOException(CONFLICT));
        assertEquals(themed("Unable to edit: " + CONFLICT), host.chest.lastNotice());
        assertEquals(MenuFeedbackStyle.ERROR, lastNotice().feedback().style());
        assertEquals("&6Portal", ((TextElement) element(host.chest.screen(), "language-key-portal.name")).value());

        languages.applying = new CompletableFuture<>();
        chestText("language-key-portal.name", "&aGate");
        languages.applying.completeExceptionally(new IOException("disk full"));
        assertEquals("Unable to edit: disk full", MenuText.stripColors(host.chest.lastNotice()));

        languages.applying = new CompletableFuture<>();
        chestText("language-key-portal.name", "&aGate");
        languages.applying.completeExceptionally(new IllegalStateException());
        assertEquals("Unable to edit: IllegalStateException", MenuText.stripColors(host.chest.lastNotice()));

        chestText("language-key-portal.name", "broken {");
        assertEquals(3, languages.edits.size());
        assertEquals(themed("Unable to save: Unclosed placeholder in template: broken {"), host.chest.lastNotice());
        assertEquals(LanguageRoutes.KEYS, host.chest.screen().screenId());
    }

    @Test
    void linesAndPluralFormsEditOnePartAtATime() {
        openGroups(document());
        chest("language-group-portal");

        chest("language-key-portal.lines");
        MenuBuild lines = host.chest.last();
        assertEquals(LanguageRoutes.FORMS, lines.screen().screenId());
        assertEquals(new ChestLayout("Wormholes › en_US / Portal", 6, ChestPane.BLACK, null), lines.chest());
        assertEquals(MenuLayouts.mapOf(
            "language-form-0", "Text -4,0 minecraft:paper - " + ANY_TEXT,
            "language-form-1", "Text -3,0 minecraft:paper - " + ANY_TEXT,
            "language-back", "Button -4,5 minecraft:arrow - " + ANY_PRIMARY + " hidden",
            "language-close", "Button 4,5 minecraft:barrier - " + ANY_PRIMARY + " hidden"), MenuLayouts.layout(lines.screen()));
        TextElement second = (TextElement) element(lines.screen(), "language-form-1");
        assertEquals("Line 2", MenuText.stripColors(second.label()));
        assertEquals(List.of("Current Value:", "Second {name}", "", "{name}"), stripped(second.description()));
        assertEquals(List.of("Click to edit in chat."), stripped(second.common().chestHints()));
        assertEquals("Second {name}", second.value());
        assertEquals("⇀ Type a new value for portal.lines [Line 2] in chat.", MenuText.stripColors(second.chestPrompt().getFirst()));
        assertEquals("⇀ Variables: {name}", MenuText.stripColors(second.chestPrompt().get(2)));

        chestText("language-form-1", "Second {name}!");
        assertEquals(new PluginLanguageEditor.Edit("en_US", "portal.lines", new LinesValue(List.of("First", "Second {name}")),
            new LinesValue(List.of("First", "Second {name}!"))), languages.edits.getLast());
        languages.applying.complete(null);
        assertEquals(LanguageRoutes.FORMS, host.chest.screen().screenId());
        assertEquals("Second {name}!", ((TextElement) element(host.chest.screen(), "language-form-1")).value());

        chest("language-back");
        chest("language-key-portal.count");
        MenuScreen plural = host.chest.screen();
        assertEquals(List.of("language-form-one", "language-form-other", "language-back", "language-close"),
            plural.elements().stream().map(MenuElement::id).toList());
        assertEquals("other", MenuText.stripColors(element(plural, "language-form-other").label()));
        languages.applying = new CompletableFuture<>();
        chestText("language-form-other", "{count} gates");
        assertEquals(new PluginLanguageEditor.Edit("en_US", "portal.count", plural("{count} portal", "{count} portals"),
            plural("{count} portal", "{count} gates")), languages.edits.getLast());
    }

    @Test
    void searchMatchesKeysAndValuesAndClearsBackToTheCategories() {
        openGroups(document());

        chestText("language-search", "  PORTAL  ");
        MenuBuild results = host.chest.last();
        assertEquals(LanguageRoutes.KEYS, results.screen().screenId());
        assertEquals(new ChestLayout("Wormholes › en_US / Search", 6, ChestPane.BLACK, null), results.chest());
        assertEquals(List.of("language-clear-search", "language-key-gui.title", "language-key-portal.count", "language-key-portal.lines",
            "language-key-portal.name", "language-back", "language-close"), results.screen().elements().stream().map(MenuElement::id).toList());
        MenuElement clear = element(results.screen(), "language-clear-search");
        assertEquals("Button 0,5 minecraft:paper - " + ANY_PRIMARY, MenuLayouts.describe(clear));
        assertEquals("Clear search", MenuText.stripColors(clear.label()));

        chest("language-key-portal.lines");
        assertEquals(new ChestLayout("Wormholes › en_US / Search", 6, ChestPane.BLACK, null), host.chest.last().chest());
        assertTrue(host.chest.screen().element("language-clear-search").isEmpty());
        chest("language-back");
        assertEquals(LanguageRoutes.KEYS, host.chest.screen().screenId());

        chest("language-clear-search");
        assertEquals(LanguageRoutes.GROUPS, host.chest.screen().screenId());
        chestText("language-search", "   ");
        assertEquals(LanguageRoutes.GROUPS, host.chest.screen().screenId());
    }

    @Test
    void backWalksTheEditorAndResetsPagesLikeToday() {
        List<LanguageMenuView.Message> messages = new ArrayList<>(70);
        for (int index = 0; index < 50; index++) {
            messages.add(text(String.format("portal.k%02d", index), "Value " + index));
        }
        messages.add(new LanguageMenuView.Message("portal.z", new LinesValue(List.of("Only")), List.of()));
        for (int index = 0; index < 20; index++) {
            messages.add(text(String.format("g%02d.key", index), "Value"));
        }
        openGroups(new LanguageMenuView("en_US", messages));
        chest("language-next");
        chest("language-group-portal");
        chest("language-next");
        chest("language-key-portal.z");

        chest("language-back");
        assertEquals(LanguageRoutes.KEYS, host.chest.screen().screenId());
        assertEquals(new MenuPaging(1, 2), host.chest.screen().paging());
        chest("language-back");
        assertEquals(LanguageRoutes.GROUPS, host.chest.screen().screenId());
        assertEquals(new MenuPaging(0, 2), host.chest.screen().paging());
        chest("language-next");
        chest("language-group-portal");
        assertEquals(new MenuPaging(0, 2), host.chest.screen().paging());
        chest("language-back");
        chest("language-back");
        assertEquals(LanguageRoutes.LOCALES, host.chest.screen().screenId());

        chest("language-back");
        assertEquals("close", host.chest.lastEvent());
        assertEquals(List.of("load:en_US", "exit:Editor"), languages.calls);
        open();
        chest("language-close");
        assertEquals("close", host.chest.lastEvent());
        assertEquals(2, languages.calls.size());
    }

    @Test
    void onlyLanguageAdministratorsMayOpenOrUseTheEditor() {
        viewer.permissions.clear();
        open();
        assertTrue(host.chest.builds.isEmpty());
        assertEquals(themed("You do not have permission to edit Wormholes languages."), host.chest.lastNotice());
        assertEquals(NoticeChannel.CHAT, lastNotice().chestChannel());

        viewer.permissions.add("wormholes.admin");
        open();
        assertEquals(LanguageRoutes.LOCALES, host.chest.screen().screenId());
        viewer.permissions.clear();
        chest("language-locale-en_US");
        assertTrue(languages.calls.isEmpty());
        assertEquals(List.of("close", "notice:CHAT"), host.chest.events.subList(host.chest.events.size() - 2, host.chest.events.size()));
    }

    @Test
    void categoryRoutesNeedALoadedDocument() {
        service.open(VIEWER, LanguageRoutes.groups("en_US"));
        assertTrue(host.chest.builds.isEmpty());

        languages.documents.put("en_US", document());
        service.open(VIEWER, LanguageRoutes.groups("en_US"));
        assertEquals(LanguageRoutes.GROUPS, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());
    }

    @Test
    void nativeViewersGetTheSameScreensWithoutChestChrome() throws MenuProtocolException {
        hello();
        open();
        MenuServerMessage.Open opened = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertTrue(opened.screen().element("language-back").isEmpty());
        assertTrue(opened.screen().element("language-close").isEmpty());

        nativeAction("language-locale-en_US", MenuInteraction.PRIMARY, null);
        languages.loading.complete(document());
        assertEquals(LanguageRoutes.GROUPS, nativeScreen().screenId());
        assertEquals("language-search", nativeScreen().elements().getFirst().id());
        assertEquals("Search messages", nativeScreen().elements().getFirst().label());

        nativeAction("language-search", MenuInteraction.SET_TEXT, MenuValue.text("name"));
        assertEquals(List.of("language-clear-search", "language-key-portal.lines", "language-key-portal.name"), nativeScreen().elements().stream().map(MenuElement::id).toList());
        assertEquals("Clear search", nativeScreen().elements().getFirst().label());
        nativeAction("language-key-portal.name", MenuInteraction.SET_TEXT, MenuValue.text("&bPortal"));
        languages.applying.complete(null);

        assertEquals(new TextValue("&bPortal"), languages.edits.getLast().value());
        MenuServerMessage.Feedback feedback = assertInstanceOf(MenuServerMessage.Feedback.class, host.lastMessage());
        assertTrue(MenuText.stripColors(feedback.feedback().text()).startsWith("⇀ Saved en_US."));
    }

    @Test
    void documentsKeepCatalogOrderAndGroupByTheFirstSegment() {
        LanguageMenuView view = new LanguageMenuView("en_US", List.of(text("zeta.b", "One"), text("Alpha.a", "Two"), text("zeta.a", "Gate"),
            text("single", "Three")));

        assertEquals(List.of("Alpha", "single", "zeta"), view.groups());
        assertEquals(2, view.count("zeta"));
        assertEquals(List.of("zeta.a", "zeta.b"), view.matching("zeta", "").stream().map(LanguageMenuView.Message::key).toList());
        assertEquals(List.of("zeta.a"), view.matching(null, "gate").stream().map(LanguageMenuView.Message::key).toList());
        assertEquals("Three", ((TextValue) view.message("single").orElseThrow().value()).template());
    }

    @Test
    void documentsMirrorTheEditorSnapshot() {
        TextKey name = TextKey.of("portal.name", "Portal {name}");
        LinesKey lines = LinesKey.of("portal.lines", "One", "Two");
        LocaleOverlay overlay = LocaleOverlay.builder("test", "de_DE").put("portal.name", new TextValue("Tor {name}")).build();
        LocalizationSnapshot snapshot = LocalizationSnapshot.create(new LocalizationCandidate(MessageCatalog.of("en_US", name, lines),
            List.of(overlay), PluralSelector.oneOther()));

        LanguageMenuView view = LanguageMenuView.of(new PluginLanguageEditor.Document("de_DE", snapshot));

        assertEquals("de_DE", view.locale());
        assertEquals(List.of(new LanguageMenuView.Message("portal.name", new TextValue("Tor {name}"), List.of("name")),
            new LanguageMenuView.Message("portal.lines", new LinesValue(List.of("One", "Two")), List.of())), view.messages());
    }

    @Test
    void everyLoadAnnouncesItselfInChatInTheThemeColour() {
        open();

        chest("language-locale-fr_FR");
        assertEquals(List.of("load:fr_FR"), languages.calls);
        assertEquals(themed("Loading fr_FR messages..."), host.chest.lastNotice());
        assertTrue(host.chest.lastNotice().startsWith(THEME_HEX));
        assertEquals(NoticeChannel.CHAT, lastNotice().chestChannel());
        assertEquals(LanguageRoutes.LOCALES, host.chest.screen().screenId());

        languages.loading.completeExceptionally(new IOException("missing file"));
        languages.loading = new CompletableFuture<>();
        chest("language-locale-de_DE");
        assertEquals(List.of("load:fr_FR", "load:de_DE"), languages.calls);
        assertEquals(themed("Loading de_DE messages..."), host.chest.lastNotice());
        assertEquals(List.of(themed("Loading fr_FR messages..."), themed("Unable to edit: missing file"), themed("Loading de_DE messages...")),
            host.chest.notices.stream().map(notice -> notice.feedback().text()).toList());
    }

    @Test
    void chatNoticesCarryTheEditorThemeColour() {
        openGroups(document());
        chest("language-group-portal");

        chestText("language-key-portal.name", "broken {");
        assertTrue(host.chest.lastNotice().startsWith(THEME_HEX));
        chestText("language-key-portal.name", "&aGate");
        languages.applying.completeExceptionally(new IOException("disk full"));
        assertEquals(themed("Unable to edit: disk full"), host.chest.lastNotice());
        assertTrue(host.chest.lastNotice().startsWith(THEME_HEX));

        viewer.permissions.clear();
        open();
        assertTrue(host.chest.lastNotice().startsWith(THEME_HEX));
    }

    @Test
    void completionsBuildTheirNoticesOnlyOnceTheSessionTakesTheResult() {
        open();
        chest("language-locale-fr_FR");
        host.deferTasks = true;
        int lookups = viewer.stringLookups;

        languages.loading.completeExceptionally(new IOException("missing file"));
        assertEquals(lookups, viewer.stringLookups);
        assertEquals(1, host.deferred.size());
        host.runDeferred();
        host.deferTasks = false;
        assertTrue(viewer.stringLookups > lookups);
        assertEquals(themed("Unable to edit: missing file"), host.chest.lastNotice());

        languages.loading = new CompletableFuture<>();
        chest("language-locale-en_US");
        languages.loading.complete(document());
        chest("language-group-portal");
        chestText("language-key-portal.name", "&aGate");
        host.deferTasks = true;
        lookups = viewer.stringLookups;
        languages.applying.complete(null);
        assertEquals(lookups, viewer.stringLookups);
        host.runDeferred();
        host.deferTasks = false;
        assertTrue(MenuText.stripColors(host.chest.lastNotice()).startsWith("⇀ Saved en_US."));
    }

    @Test
    void loadAndSaveFailuresAreLoggedWithTheirCauseOnceWhenTheSessionTakesThem() {
        IOException missing = new IOException("missing file");
        open();
        chest("language-locale-fr_FR");
        languages.loading.completeExceptionally(missing);
        assertEquals(themed("Unable to edit: missing file"), host.chest.lastNotice());

        IOException full = new IOException("disk full");
        languages.loading = new CompletableFuture<>();
        openGroups(document());
        chest("language-group-portal");
        chestText("language-key-portal.name", "&aGate");
        languages.applying.completeExceptionally(full);
        assertEquals(themed("Unable to edit: disk full"), host.chest.lastNotice());

        assertEquals(List.of(missing, full), languages.failures);
    }

    @Test
    void loadAndSaveFailuresAreLoggedWhenNoSessionTakesThem() {
        IOException ended = new IOException("ended");
        open();
        chest("language-locale-fr_FR");
        service.quit(VIEWER);
        languages.loading.completeExceptionally(ended);

        IOException offline = new IOException("offline");
        languages.loading = new CompletableFuture<>();
        open();
        chest("language-locale-de_DE");
        FakeMenuViewer gone = host.viewers.remove(VIEWER);
        languages.loading.completeExceptionally(offline);
        host.viewers.put(VIEWER, gone);

        IOException superseded = new IOException("superseded");
        languages.loading = new CompletableFuture<>();
        openGroups(document());
        chest("language-group-portal");
        chestText("language-key-portal.name", "&aGate");
        open();
        int notices = host.chest.notices.size();
        languages.applying.completeExceptionally(superseded);

        assertEquals(notices, host.chest.notices.size());
        assertEquals(LanguageRoutes.LOCALES, host.chest.screen().screenId());
        assertEquals(List.of(ended, offline, superseded), languages.failures);
    }

    @Test
    void revokedEditorsCannotEditSearchOrExitAtActionTime() {
        openGroups(document());
        chest("language-group-portal");
        viewer.permissions.clear();

        chestText("language-key-portal.name", "&aGate");
        assertTrue(languages.edits.isEmpty());
        assertEquals(List.of("close", "notice:CHAT"), lastEvents(2));

        viewer.permissions.add("wormholes.admin");
        service.open(VIEWER, LanguageRoutes.groups("en_US"));
        viewer.permissions.clear();
        int builds = host.chest.builds.size();
        chestText("language-search", "portal");
        assertEquals(builds, host.chest.builds.size());
        assertEquals(List.of("close", "notice:CHAT"), lastEvents(2));

        viewer.permissions.add("wormholes.admin");
        open();
        viewer.permissions.clear();
        chest("language-back");
        assertEquals(List.of("close", "notice:CHAT"), lastEvents(2));
        assertEquals(List.of("load:en_US"), languages.calls);
    }

    @Test
    void clicksWhileALoadOrSaveIsOutstandingAreIgnored() {
        open();
        chest("language-locale-en_US");
        chest("language-locale-fr_FR");
        chest("language-close");
        assertEquals(List.of("load:en_US"), languages.calls);
        assertFalse(host.chest.events.contains("close"));

        languages.loading.complete(document());
        assertEquals(LanguageRoutes.GROUPS, host.chest.screen().screenId());
        chest("language-group-portal");
        chestText("language-key-portal.name", "&aGate");
        chestText("language-key-portal.name", "&bGate");
        chest("language-back");
        chest("language-close");
        assertEquals(1, languages.edits.size());
        assertEquals(LanguageRoutes.KEYS, host.chest.screen().screenId());
        assertFalse(host.chest.events.contains("close"));

        languages.applying.complete(null);
        assertEquals(LanguageRoutes.KEYS, host.chest.screen().screenId());
        assertEquals("&aGate", ((TextElement) element(host.chest.screen(), "language-key-portal.name")).value());
    }

    @Test
    void valuesLongerThanTheEditBoxAreShownButNotEditable() {
        String longValue = "x".repeat(MenuLimits.MAX_TEXT_INPUT_LENGTH + 1);
        openGroups(new LanguageMenuView("en_US", List.of(text("portal.long", longValue), text("portal.name", "&6Portal"))));
        chest("language-group-portal");
        MenuScreen screen = host.chest.screen();

        TextElement tooLong = (TextElement) element(screen, "language-key-portal.long");
        assertFalse(tooLong.enabled());
        assertEquals("Editor input must be at most 256 characters.", MenuText.stripColors(tooLong.description().getLast()));
        assertTrue(element(screen, "language-key-portal.name").enabled());

        chestText("language-key-portal.long", "short");
        assertTrue(languages.edits.isEmpty());
        chestText("language-key-portal.name", "&aGate");
        assertEquals(1, languages.edits.size());
    }

    private List<String> lastEvents(int count) {
        return host.chest.events.subList(host.chest.events.size() - count, host.chest.events.size());
    }

    private void openGroups(LanguageMenuView document) {
        open();
        chest("language-locale-" + document.locale());
        languages.loading.complete(document);
        languages.calls.clear();
        languages.calls.add("load:" + document.locale());
    }

    private static LanguageMenuView document() {
        return new LanguageMenuView("en_US", List.of(
            new LanguageMenuView.Message("command.reload", new TextValue("Reloaded {count} files."), List.of("count")),
            text("gui.title", "Portal menu"),
            new LanguageMenuView.Message("portal.count", plural("{count} portal", "{count} portals"), List.of("count")),
            new LanguageMenuView.Message("portal.lines", new LinesValue(List.of("First", "Second {name}")), List.of("name")),
            text("portal.name", "&6Portal")));
    }

    private static LanguageMenuView.Message text(String key, String value) {
        return new LanguageMenuView.Message(key, new TextValue(value), List.of());
    }

    private static PluralValue plural(String one, String other) {
        Map<String, String> forms = new LinkedHashMap<>();
        forms.put("one", one);
        forms.put("other", other);
        return new PluralValue(forms);
    }

    private static String themed(String plain) {
        return ComponentText.section(plain).colorIfAbsent("#8c7a45").legacy();
    }

    private static List<String> stripped(List<String> lines) {
        return lines.stream().map(MenuText::stripColors).toList();
    }

    private MenuNotice lastNotice() {
        return host.chest.notices.get(host.chest.notices.size() - 1);
    }

    private void open() {
        service.open(VIEWER, LanguageRoutes.locales());
    }

    private void hello() {
        receive(new MenuClientMessage.Hello(1));
    }

    private void chest(String elementId) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, MenuInteraction.PRIMARY, null, false, null));
    }

    private void chestText(String elementId, String text) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, MenuInteraction.SET_TEXT, MenuValue.text(text), false, null));
    }

    private void nativeAction(String elementId, MenuInteraction interaction, MenuValue value) throws MenuProtocolException {
        receive(new MenuClientMessage.Action(new MenuAction(nativeScreen().revision(), elementId, interaction, value)));
    }

    private void receive(MenuClientMessage message) {
        try {
            service.receive(VIEWER, MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MenuScreen nativeScreen() throws MenuProtocolException {
        List<MenuServerMessage> messages = host.serverMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            MenuServerMessage message = messages.get(index);
            if (message instanceof MenuServerMessage.Open opened) {
                return opened.screen();
            }
            if (message instanceof MenuServerMessage.Update update) {
                return update.screen();
            }
        }
        throw new IllegalStateException("no native screen was sent");
    }

    private static MenuElement element(MenuScreen screen, String id) {
        return screen.element(id).orElseThrow();
    }
}
