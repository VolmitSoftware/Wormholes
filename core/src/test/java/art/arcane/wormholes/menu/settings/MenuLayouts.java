package art.arcane.wormholes.menu.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.RecordingRenderer;

public final class MenuLayouts {
    private MenuLayouts() {
    }

    public static Map<String, String> layout(MenuScreen screen) {
        Map<String, String> layout = new LinkedHashMap<>();
        for (MenuElement element : screen.elements()) {
            MenuSlot slot = element.chestSlot();
            MenuIcon.Item item = (MenuIcon.Item) element.icon();
            MenuIcon nativeIcon = element.common().nativeIcon();
            String sprite = nativeIcon instanceof MenuIcon.Sprite named ? named.name() : "-";
            StringBuilder clicks = new StringBuilder("{");
            for (Map.Entry<ChestClick, ChestTarget> click : slot.clicks().entrySet()) {
                if (clicks.length() > 1) {
                    clicks.append(", ");
                }
                ChestTarget target = click.getValue();
                clicks.append(click.getKey()).append('=').append(target.interaction().name().charAt(0)).append(target.preconfirmed() ? "!" : "");
            }
            clicks.append('}');
            String kind = element.getClass().getSimpleName().replace("Element", "");
            layout.put(element.id(), kind + " " + slot.position() + "," + slot.row() + " " + item.itemId() + (item.glint() ? "*" : "") + " "
                + sprite + " " + clicks + (element.common().nativeHidden() ? " hidden" : ""));
        }
        return layout;
    }

    public static Map<String, String> sideActions(MenuScreen screen) {
        Map<String, String> sides = new LinkedHashMap<>();
        for (MenuElement element : screen.elements()) {
            List<MenuSideAction> actions = element.common().sideActions();
            if (actions.isEmpty()) {
                continue;
            }
            List<String> described = new ArrayList<>(actions.size());
            for (MenuSideAction action : actions) {
                described.add(action.interaction().name().charAt(0) + " " + action.label() + " " + ((MenuIcon.Sprite) action.icon()).name());
            }
            sides.put(element.id(), String.join(", ", described));
        }
        return sides;
    }

    public static Map<String, String> mapOf(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return map;
    }

    public static List<String> chestLore(MenuElement element) {
        List<String> lore = new ArrayList<>(element.description());
        lore.addAll(element.common().chestHints());
        return lore;
    }

    public static MenuElement element(MenuScreen screen, String id) {
        return screen.element(id).orElseThrow();
    }

    public static void assertBound(MenuBuild build) {
        for (MenuElement element : build.screen().elements()) {
            for (MenuInteraction interaction : element.interactions()) {
                assertTrue(build.bindings().find(element.id()).flatMap(binding -> binding.handler(interaction)).isPresent(),
                    element.id() + " " + interaction);
            }
        }
    }

    public static void assertClosedWithNotice(RecordingRenderer chest, String message) {
        List<String> events = chest.events;
        assertTrue(events.size() >= 2, message);
        assertEquals(List.of("close", "notice:HUD"), events.subList(events.size() - 2, events.size()), message);
    }

    public static List<String> boundIds(MenuScreen screen) {
        List<String> ids = new ArrayList<>();
        for (MenuElement element : screen.elements()) {
            if (!element.interactions().isEmpty()) {
                ids.add(element.id());
            }
        }
        return ids;
    }
}
