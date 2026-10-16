package art.arcane.wormholes.menu;

import java.util.regex.Pattern;

public sealed interface MenuIcon permits MenuIcon.Item, MenuIcon.Sprite {
    static Item item(String itemId) {
        return new Item(itemId, 1, false, null);
    }

    static Item item(String itemId, int count, boolean glint) {
        return new Item(itemId, count, glint, null);
    }

    static Item template(String itemId, int count, boolean glint, String templateRef) {
        return new Item(itemId, count, glint, templateRef);
    }

    static Sprite sprite(String name) {
        return new Sprite(name);
    }

    record Item(String itemId, int count, boolean glint, String templateRef) implements MenuIcon {
        private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

        public Item {
            itemId = MenuText.text(itemId, "item id");
            if (!ITEM_ID.matcher(itemId).matches()) {
                throw new IllegalArgumentException("item id '" + itemId + "' is not a namespaced id");
            }
            if (count < 1 || count > MenuLimits.MAX_ITEM_COUNT) {
                throw new IllegalArgumentException("item count " + count + " is outside 1.." + MenuLimits.MAX_ITEM_COUNT);
            }
            if (templateRef != null) {
                MenuText.id(templateRef, "template ref");
            }
        }

        public Item withoutTemplate() {
            return templateRef == null ? this : new Item(itemId, count, glint, null);
        }
    }

    record Sprite(String name) implements MenuIcon {
        private static final Pattern SPRITE_NAME = Pattern.compile("[a-z0-9_.-]+(/[a-z0-9_.-]+)*");

        public Sprite {
            name = MenuText.text(name, "sprite name");
            if (!SPRITE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("sprite name '" + name + "' is not an icon path");
            }
        }
    }
}
