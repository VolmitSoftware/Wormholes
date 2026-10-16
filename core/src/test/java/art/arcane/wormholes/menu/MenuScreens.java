package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MenuScreens {
    private static final Set<String> STATE_WORDS = Set.of("on", "off", "true", "false");

    private MenuScreens() {
    }

    public static MenuElement element(MenuScreen screen, String id) {
        return screen.element(id).orElseThrow(() -> new AssertionError(screen.screenId() + " has no element " + id));
    }

    public static List<String> chestLore(MenuElement element) {
        List<String> lore = new ArrayList<>(element.description());
        lore.addAll(element.common().chestHints());
        return lore;
    }

    public static Map<String, String> mapOf(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return map;
    }

    public static Map<String, String> layout(MenuScreen screen) {
        Map<String, String> layout = new LinkedHashMap<>();
        for (MenuElement element : screen.elements()) {
            layout.put(element.id(), describe(element));
        }
        return layout;
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

    public static Map<String, String> clickMaps(MenuScreen screen) {
        Map<String, String> maps = new LinkedHashMap<>();
        for (MenuElement element : screen.elements()) {
            StringBuilder clicks = new StringBuilder("{");
            for (Map.Entry<ChestClick, ChestTarget> click : ChestClicks.resolve(element).entrySet()) {
                if (clicks.length() > 1) {
                    clicks.append(", ");
                }
                clicks.append(click.getKey()).append('=').append(code(click.getValue().interaction()));
            }
            maps.put(element.id(), clicks.append('}').toString());
        }
        return maps;
    }

    public static void assertBound(MenuBuild build) {
        for (MenuElement element : build.screen().elements()) {
            for (MenuInteraction interaction : element.interactions()) {
                assertTrue(build.bindings().find(element.id()).flatMap(binding -> binding.handler(interaction)).isPresent(),
                    element.id() + " " + interaction);
            }
        }
    }

    public static void assertSettingLabels(MenuScreen screen) {
        int checked = 0;
        for (MenuElement element : screen.elements()) {
            String value = switch (element) {
                case ToggleElement toggle -> "";
                case CycleElement cycle -> MenuText.stripColors(cycle.options().get(cycle.index())).strip();
                default -> null;
            };
            if (value == null) {
                continue;
            }
            checked++;
            String label = MenuText.stripColors(element.common().wireLabel());
            String where = screen.screenId() + " " + element.id() + " reads \"" + label + "\"";
            assertFalse(label.isBlank() || label.strip().endsWith(":"), where);
            assertFalse(value.length() > 1 && label.contains(value), where);
            for (String word : label.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                assertFalse(STATE_WORDS.contains(word), where);
            }
        }
        assertTrue(checked > 0, screen.screenId() + " has no toggle or cycle rows");
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

    private static String describe(MenuElement element) {
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
        return kind + " " + slot.position() + "," + slot.row() + " " + item.itemId() + (item.glint() ? "*" : "") + " " + sprite + " " + clicks
            + (element.common().nativeHidden() ? " hidden" : "");
    }
}
