package art.arcane.wormholes.menu;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum MenuInteraction {
    PRIMARY(0),
    SECONDARY(1),
    TERTIARY(2),
    INCREMENT(3),
    DECREMENT(4),
    BIG_INCREMENT(5),
    BIG_DECREMENT(6),
    SET_VALUE(7),
    SET_TEXT(8),
    SELECT(9);

    private final int wireId;

    MenuInteraction(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public int mask() {
        return 1 << wireId;
    }

    public static MenuInteraction byWireId(int wireId) {
        for (MenuInteraction interaction : values()) {
            if (interaction.wireId == wireId) {
                return interaction;
            }
        }
        return null;
    }

    public static int allMask() {
        int mask = 0;
        for (MenuInteraction interaction : values()) {
            mask |= interaction.mask();
        }
        return mask;
    }

    public static int mask(Set<MenuInteraction> interactions) {
        int mask = 0;
        for (MenuInteraction interaction : interactions) {
            mask |= interaction.mask();
        }
        return mask;
    }

    public static Set<MenuInteraction> fromMask(int mask) {
        if ((mask & ~allMask()) != 0) {
            throw new IllegalArgumentException("unknown interaction bits " + Integer.toBinaryString(mask & ~allMask()));
        }
        EnumSet<MenuInteraction> interactions = EnumSet.noneOf(MenuInteraction.class);
        for (MenuInteraction interaction : values()) {
            if ((mask & interaction.mask()) != 0) {
                interactions.add(interaction);
            }
        }
        return Collections.unmodifiableSet(interactions);
    }
}
