package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MenuTestViews {
    private static final List<String> CLICK_WORDS = List.of("click", "left", "right", "shift");

    private MenuTestViews() {
    }

    public static Map<String, String> layout(MenuScreen screen) {
        Map<String, String> layout = new LinkedHashMap<>();
        for (MenuElement element : screen.elements()) {
            layout.put(element.id(), describe(element));
        }
        return layout;
    }

    public static String describe(MenuElement element) {
        MenuSlot slot = element.chestSlot();
        MenuIcon.Item item = (MenuIcon.Item) element.icon();
        MenuIcon nativeIcon = element.common().nativeIcon();
        String sprite = nativeIcon instanceof MenuIcon.Sprite named ? named.name() : "-";
        String position = slot == null ? "-" : slot.position() + "," + slot.row();
        StringBuilder clicks = new StringBuilder("{");
        if (slot != null) {
            for (Map.Entry<ChestClick, ChestTarget> click : slot.clicks().entrySet()) {
                if (clicks.length() > 1) {
                    clicks.append(", ");
                }
                ChestTarget target = click.getValue();
                clicks.append(click.getKey()).append('=');
                if (target.elementId() != null) {
                    clicks.append(target.elementId()).append('.');
                }
                clicks.append(code(target.interaction())).append(target.preconfirmed() ? "!" : "");
            }
        }
        clicks.append('}');
        String kind = element.getClass().getSimpleName().replace("Element", "");
        return kind + " " + position + " " + item.itemId() + (item.glint() ? "*" : "") + (item.count() > 1 ? "x" + item.count() : "") + " "
            + sprite + " " + clicks + (element.common().nativeHidden() ? " hidden" : "") + (element.enabled() ? "" : " disabled");
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
        return screen.element(id).orElseThrow(() -> new AssertionError("no element " + id + " on " + screen.screenId()));
    }

    public static void assertBound(MenuBuild build) {
        for (MenuElement element : build.screen().elements()) {
            for (MenuInteraction interaction : element.interactions()) {
                assertTrue(build.bindings().find(element.id()).flatMap(binding -> binding.handler(interaction)).isPresent(),
                    element.id() + " " + interaction);
            }
        }
    }

    public static int trailingClickLines(List<String> english) {
        int count = 0;
        for (int index = english.size() - 1; index > 0; index--) {
            String line = english.get(index).toLowerCase(Locale.ROOT);
            boolean click = false;
            for (String word : CLICK_WORDS) {
                click |= line.contains(word);
            }
            if (!click) {
                break;
            }
            count++;
        }
        return count;
    }

    private static String code(MenuInteraction interaction) {
        return switch (interaction) {
            case PRIMARY -> "P";
            case SECONDARY -> "S";
            case TERTIARY -> "T";
            case INCREMENT -> "+";
            case DECREMENT -> "-";
            case BIG_INCREMENT -> "++";
            case BIG_DECREMENT -> "--";
            case SET_VALUE -> "V";
            case SET_TEXT -> "X";
            case SELECT -> "Sel";
        };
    }
}
