package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MenuLayouts {
    private MenuLayouts() {
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
        String kind = element.getClass().getSimpleName().replace("Element", "");
        return kind + " " + slot.position() + "," + slot.row() + " " + item.itemId() + (item.glint() ? "*" : "") + " " + sprite + " "
            + clicks(slot.clicks()) + (element.common().nativeHidden() ? " hidden" : "");
    }

    public static String clicks(Map<ChestClick, ChestTarget> clicks) {
        StringBuilder text = new StringBuilder("{");
        for (Map.Entry<ChestClick, ChestTarget> click : clicks.entrySet()) {
            if (text.length() > 1) {
                text.append(", ");
            }
            ChestTarget target = click.getValue();
            text.append(click.getKey()).append('=').append(code(target.interaction())).append(target.preconfirmed() ? "!" : "");
        }
        return text.append('}').toString();
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

    public static boolean bound(MenuBuild build) {
        for (MenuElement element : build.screen().elements()) {
            for (MenuInteraction interaction : element.interactions()) {
                if (build.bindings().find(element.id()).flatMap(binding -> binding.handler(interaction)).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
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
