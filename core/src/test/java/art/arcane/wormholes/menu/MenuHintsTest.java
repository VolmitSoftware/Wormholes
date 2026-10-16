package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.access.AccessMenuController;
import art.arcane.wormholes.menu.atlas.AtlasMenuController;
import art.arcane.wormholes.menu.cosmetics.CosmeticsMenuController;
import art.arcane.wormholes.menu.cost.CostMenuController;
import art.arcane.wormholes.menu.destination.DestinationMenuController;
import art.arcane.wormholes.menu.door.DoorAccessMenuController;
import art.arcane.wormholes.menu.extensions.ExtensionsMenuController;
import art.arcane.wormholes.menu.fidelity.FidelityMenuController;
import art.arcane.wormholes.menu.nexus.DialMenuController;
import art.arcane.wormholes.menu.nexus.NetworkMenuController;
import art.arcane.wormholes.menu.portal.PortalMenuController;
import art.arcane.wormholes.menu.rtp.RtpMenuController;
import art.arcane.wormholes.menu.rules.RulesMenuController;
import art.arcane.wormholes.menu.settings.SettingsMenuController;
import art.arcane.wormholes.menu.transit.TransitMenuController;

final class MenuHintsTest {
    private static final List<String> CLICK_WORDS = List.of("click", "left", "right", "shift");

    @Test
    void declaredHintCountsMatchTheEnglishClickInstructions() {
        for (Map<LinesKey, Integer> hints : List.of(PortalMenuController.CHEST_HINTS, SettingsMenuController.CHEST_HINTS,
            CosmeticsMenuController.CHEST_HINTS, CostMenuController.CHEST_HINTS, DestinationMenuController.CHEST_HINTS,
            ExtensionsMenuController.CHEST_HINTS, NetworkMenuController.CHEST_HINTS, DialMenuController.CHEST_HINTS,
            AtlasMenuController.CHEST_HINTS, AccessMenuController.CHEST_HINTS, DoorAccessMenuController.CHEST_HINTS,
            RulesMenuController.CHEST_HINTS, RtpMenuController.CHEST_HINTS, TransitMenuController.CHEST_HINTS,
            FidelityMenuController.CHEST_HINTS)) {
            for (Map.Entry<LinesKey, Integer> entry : hints.entrySet()) {
                List<String> english = entry.getKey().english();
                int declared = entry.getValue();
                int trailing = trailingHintLines(english);
                assertEquals(trailing, declared, entry.getKey().id());
            }
        }
    }

    @Test
    void splitMovesHintsAndTheirSeparatorOutOfTheDescription() {
        MenuLines lines = new FakeStrings().lines(WormholesMessages.PORTAL_MENU_RENAME, MessageArgs.builder()
            .untrusted("portal", "Alpha").build(), 1);

        assertEquals("§a§lRename Portal§r", lines.label());
        assertEquals(List.of("§7Change the name shown on links and menus.", "§7Current: §fAlpha"), lines.description());
        assertEquals(List.of("", "§8Click to type a new name."), lines.chestHints());
        assertEquals(List.of("§7Change the name shown on links and menus.", "§7Current: §fAlpha", "", "§8Click to type a new name."),
            lines.chestLore());
    }

    @Test
    void translationsWithADifferentLineCountKeepEveryLineInTheDescription() {
        MenuLines lines = MenuLines.split(List.of("Umbenennen", "Neuen Namen eingeben."), WormholesMessages.PORTAL_MENU_RENAME, 1);

        assertEquals("Umbenennen", lines.label());
        assertEquals(List.of("Neuen Namen eingeben."), lines.description());
        assertTrue(lines.chestHints().isEmpty());
    }

    private static int trailingHintLines(List<String> english) {
        int last = english.size() - 1;
        int note = last > 0 && !english.get(last).isEmpty() && !clickLine(english.get(last)) ? 1 : 0;
        int clicks = 0;
        for (int index = last - note; index > 0 && clickLine(english.get(index)); index--) {
            clicks++;
        }
        return clicks == 0 ? 0 : clicks + note;
    }

    private static boolean clickLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String word : CLICK_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
