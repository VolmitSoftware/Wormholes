package art.arcane.wormholes.modded.menu;

import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.MenuLines;
import art.arcane.wormholes.menu.MenuText;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftMenuStringsTest {
    private final MinecraftMenuStrings strings = new MinecraftMenuStrings(LocalizationSnapshot.create(
        LocalizationCandidate.english(WormholesMessages.catalog(), PluralSelector.oneOther())));

    @Test
    public void textRendersTheViewerLocaleAsALegacyString() {
        assertEquals("Portal", MenuText.stripColors(strings.text(MenuMessages.TITLE_PORTAL)));
    }

    @Test
    public void linesSplitLabelDescriptionAndChestHints() {
        MenuLines lines = strings.lines(WormholesMessages.PORTAL_MENU_RENAME, MessageArgs.builder().untrusted("portal", "Gate").build(), 1);

        assertEquals("Rename Portal", MenuText.stripColors(lines.label()));
        assertTrue(lines.label().contains("§"));
        assertFalse(lines.label().contains("&"));
        assertEquals(List.of("Change the name shown on links and menus.", "Current: Gate"),
            lines.description().stream().map(MenuText::stripColors).toList());
        assertEquals(List.of("", "Click to type a new name."), lines.chestHints().stream().map(MenuText::stripColors).toList());
    }
}
