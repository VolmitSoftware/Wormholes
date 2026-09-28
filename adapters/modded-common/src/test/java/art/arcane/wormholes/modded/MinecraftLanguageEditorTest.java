package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.format.ColorFormatter;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftLanguageEditorTest {
    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Test
    public void inputDecodesNewlinesAndLiteralBackslashesWithoutChangingOtherEscapes() {
        assertEquals("first\nsecond", MinecraftLanguageEditor.decodeInput("first\\nsecond"));
        assertEquals("path\\name\\tvalue", MinecraftLanguageEditor.decodeInput("path\\\\name\\tvalue"));
        assertEquals("trailing\\", MinecraftLanguageEditor.decodeInput("trailing\\"));
    }

    @Test
    public void editsPreserveEveryMessageShape() {
        MessageValue lines = MinecraftLanguageEditor.replacement(new LinesValue(List.of("first", "old", "last")), "1", "");
        assertEquals(new LinesValue(List.of("first", "", "last")), lines);
        assertEquals("first\n\nlast", MinecraftLanguageEditor.rawValue(lines, null));
        PluralValue plural = new PluralValue(Map.of("one", "One {name}", "other", "{count} {name}"));
        MessageValue changed = MinecraftLanguageEditor.replacement(plural, "one", "Single {name}");
        assertEquals(new PluralValue(Map.of("one", "Single {name}", "other", "{count} {name}")), changed);
        assertEquals("{count} {name}", MinecraftLanguageEditor.rawValue(plural, "few"));
        assertEquals(new TextValue(""), MinecraftLanguageEditor.replacement(new TextValue("old"), null, ""));
        assertEquals("{count} {name}", MinecraftLanguageEditor.variableNames(PluralKey.of("test.plural", "count", Map.of("other", "Hello {name}"))));
        assertEquals("", MinecraftLanguageEditor.variableNames(TextKey.of("test.text", "Hello")));
    }

    @Test
    public void formattedPreviewsWrapKeepColorsAndTruncate() {
        List<String> lines = MinecraftLanguageEditor.preview("<light_purple>1234567890abcdef</light_purple>\n<green>next</green>", 10, 6, "(empty)");
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).startsWith("§d"));
        assertEquals("next", ColorFormatter.stripColor(lines.get(2)));
        List<String> truncated = MinecraftLanguageEditor.preview("x".repeat(200), 10, 3, "(empty)");
        assertEquals(3, truncated.size());
        assertTrue(truncated.get(2).endsWith("§8..."));
        assertEquals(List.of("(empty)"), MinecraftLanguageEditor.preview("", 10, 3, "(empty)"));
        assertEquals("§c§l", MinecraftLanguageEditor.lastColors("§aOld §cRed §lBold"));
        assertEquals("§x§1§2§3§4§5§6§o", MinecraftLanguageEditor.lastColors("§x§1§2§3§4§5§6Hex §oItalic"));
        assertEquals("§r", MinecraftLanguageEditor.lastColors("§aGreen§r"));
        assertEquals("", MinecraftLanguageEditor.lastColors("plain §"));
    }

    @Test
    public void catalogsGroupAndSearchLikeTheBukkitEditor() {
        MessageCatalog catalog = MessageCatalog.of("en_US",
            TextKey.of("gui.prompt.cancel", "Cancel"),
            TextKey.of("portal.created", "Portal created"),
            TextKey.of("command.create", "Create a portal"),
            TextKey.of("runtime.prefix", "Prefix"),
            TextKey.of("custom", "Custom"));
        PluginLanguageEditor.Document document = new PluginLanguageEditor.Document("en_US",
            LocalizationSnapshot.create(LocalizationCandidate.english(catalog, PluralSelector.oneOther())));
        assertEquals(List.of("command", "custom", "gui", "portal", "runtime"), MinecraftLanguageEditor.groups(document));
        assertEquals("director", MinecraftLanguageEditor.group("director.help.page"));
        assertEquals("GUI", MinecraftLanguageEditor.groupName("gui"));
        assertEquals("Hot reload", MinecraftLanguageEditor.groupName("hot_reload"));
        assertEquals(Items.OBSIDIAN, MinecraftLanguageEditor.groupMaterial("portal"));
        assertEquals(Items.PAPER, MinecraftLanguageEditor.groupMaterial("custom"));
        assertEquals(List.of("command.create", "portal.created"),
            MinecraftLanguageEditor.matchingKeys(document, null, "portal").stream().map(MessageKey::id).toList());
        assertEquals(List.of("portal.created"),
            MinecraftLanguageEditor.matchingKeys(document, "portal", "created").stream().map(MessageKey::id).toList());
    }

    @Test
    public void tilesAndSlotsMatchTheBukkitLayout() {
        assertEquals("§a✔§r §ffr_FR§r §8—§r §7French (France)",
            MinecraftLanguageEditor.localeTitle("fr_FR", "French (France)", true).legacy());
        assertEquals("§8•§r §fde_DE§r §8—§r §7German (Germany)",
            MinecraftLanguageEditor.localeTitle("de_DE", "German (Germany)", false).legacy());
        assertTrue(MinecraftLanguageEditor.categoryTitle("Command").legacy().contains("§dCommand"));
        assertEquals(List.of(20, 21, 22, 23, 24), MinecraftLanguageEditor.categorySlots(5));
        assertEquals(List.of(11, 12, 13, 14, 20, 21, 22, 23), MinecraftLanguageEditor.categorySlots(8));
        assertEquals(16, MinecraftLanguageEditor.categorySlots(40).size());
        assertEquals(Set.of(45, 48, 49, 50, 53), MinecraftLanguageEditor.navigationSlots());
    }

    @Test
    public void localizedTextUsesThePlayersSnapshotAndEscapesUntrustedArguments() {
        TextKey title = BukkitLanguageMessages.EDITOR_TITLE;
        TextKey languages = BukkitLanguageMessages.EDITOR_LANGUAGES;
        LocalizationSnapshot snapshot = LocalizationSnapshot.create(new LocalizationCandidate(
            MessageCatalog.of("en_US", title, languages),
            List.of(LocaleOverlay.builder("fr_FR").text(languages.id(), "<gold>Langues</gold>").build()),
            PluralSelector.oneOther()));
        PluginLanguageService service = mock(PluginLanguageService.class);
        ServerPlayer player = mock(ServerPlayer.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUUID()).thenReturn(playerId);
        when(service.snapshot(playerId)).thenReturn(snapshot);
        String section = MinecraftLanguageEditor.localized(service, player, languages, MessageArgs.empty()).plain();
        assertTrue(MinecraftLanguageEditor.localized(service, player, languages, MessageArgs.empty()).legacy().contains("§6Langues"));
        assertEquals("Test<green>&c[ff0000] › Langues", MinecraftLanguageEditor.localized(service, player, title,
            MessageArgs.builder().untrusted("plugin", "Test<green>&c[ff0000]§c").untrusted("section", section).build()).plain());
    }
}
