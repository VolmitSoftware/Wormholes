package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MenuElementCommon(
    String id,
    String label,
    List<String> description,
    List<String> chestHints,
    MenuIcon icon,
    MenuIcon nativeIcon,
    boolean enabled,
    MenuStyle style,
    Set<MenuInteraction> interactions,
    List<MenuSideAction> sideActions,
    MenuSlot chestSlot,
    boolean nativeHidden,
    List<String> nativeDescription,
    String nativeLabel
) {
    private static final MenuIcon DEFAULT_ICON = MenuIcon.item("minecraft:paper");

    public MenuElementCommon {
        id = MenuText.id(id, "element id");
        label = MenuText.text(label, "label");
        description = MenuText.lines(description, MenuLimits.MAX_DESCRIPTION_LINES, "description");
        chestHints = MenuText.lines(chestHints, MenuLimits.MAX_CHEST_HINT_LINES, "chest hints");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(style, "style");
        EnumSet<MenuInteraction> interactionCopy = EnumSet.noneOf(MenuInteraction.class);
        interactionCopy.addAll(Objects.requireNonNull(interactions, "interactions"));
        interactions = Collections.unmodifiableSet(interactionCopy);
        sideActions = copySideActions(sideActions, interactions);
        if (chestSlot == null && nativeHidden) {
            throw new IllegalArgumentException("element " + id + " is hidden natively and has no chest slot");
        }
        nativeDescription = nativeDescription == null ? null
            : MenuText.lines(nativeDescription, MenuLimits.MAX_DESCRIPTION_LINES, "native description");
        nativeLabel = nativeLabel == null ? null : MenuText.text(nativeLabel, "native label");
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public Builder toBuilder() {
        return new Builder(id)
            .label(label)
            .description(description)
            .chestHints(chestHints)
            .icon(icon)
            .nativeIcon(nativeIcon)
            .enabled(enabled)
            .style(style)
            .interactions(interactions)
            .sideActions(sideActions)
            .chestSlot(chestSlot)
            .nativeHidden(nativeHidden)
            .nativeDescription(nativeDescription)
            .nativeLabel(nativeLabel);
    }

    public boolean nativeVisible() {
        return !nativeHidden;
    }

    public String wireLabel() {
        return nativeLabel == null ? label : nativeLabel;
    }

    public List<String> wireDescription() {
        return nativeDescription == null ? description : nativeDescription;
    }

    public MenuIcon wireIcon() {
        MenuIcon chosen = nativeIcon == null ? icon : nativeIcon;
        return chosen instanceof MenuIcon.Item item ? item.withoutTemplate() : chosen;
    }

    void requireInteractions(Set<MenuInteraction> allowed, String kind) {
        for (MenuInteraction interaction : interactions) {
            if (!allowed.contains(interaction)) {
                throw new IllegalArgumentException(kind + " element " + id + " cannot declare " + interaction);
            }
        }
    }

    private static List<MenuSideAction> copySideActions(List<MenuSideAction> sideActions, Set<MenuInteraction> interactions) {
        List<MenuSideAction> required = Objects.requireNonNull(sideActions, "sideActions");
        if (required.size() > MenuLimits.MAX_SIDE_ACTIONS) {
            throw new IllegalArgumentException(required.size() + " side actions exceed " + MenuLimits.MAX_SIDE_ACTIONS);
        }
        EnumSet<MenuInteraction> seen = EnumSet.noneOf(MenuInteraction.class);
        List<MenuSideAction> copy = new ArrayList<>(required.size());
        for (MenuSideAction action : required) {
            MenuSideAction present = Objects.requireNonNull(action, "side action");
            if (!interactions.contains(present.interaction())) {
                throw new IllegalArgumentException("side action " + present.interaction() + " is not a declared interaction");
            }
            if (!seen.add(present.interaction())) {
                throw new IllegalArgumentException("duplicate side action " + present.interaction());
            }
            copy.add(present);
        }
        return Collections.unmodifiableList(copy);
    }

    public static final class Builder {
        private final String id;
        private String label = "";
        private List<String> description = List.of();
        private List<String> chestHints = List.of();
        private MenuIcon icon = DEFAULT_ICON;
        private MenuIcon nativeIcon;
        private boolean enabled = true;
        private MenuStyle style = MenuStyle.NORMAL;
        private Set<MenuInteraction> interactions = Set.of();
        private List<MenuSideAction> sideActions = List.of();
        private MenuSlot chestSlot;
        private boolean nativeHidden;
        private List<String> nativeDescription;
        private String nativeLabel;

        private Builder(String id) {
            this.id = id;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder description(List<String> description) {
            this.description = description;
            return this;
        }

        public Builder chestHints(List<String> chestHints) {
            this.chestHints = chestHints;
            return this;
        }

        public Builder lines(MenuLines lines) {
            this.label = lines.label();
            this.description = lines.description();
            this.chestHints = lines.chestHints();
            return this;
        }

        public Builder icon(MenuIcon icon) {
            this.icon = icon;
            return this;
        }

        public Builder nativeIcon(MenuIcon nativeIcon) {
            this.nativeIcon = nativeIcon;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder style(MenuStyle style) {
            this.style = style;
            return this;
        }

        public Builder interactions(Set<MenuInteraction> interactions) {
            this.interactions = interactions;
            return this;
        }

        public Builder sideActions(List<MenuSideAction> sideActions) {
            this.sideActions = sideActions;
            return this;
        }

        public Builder chestSlot(MenuSlot chestSlot) {
            this.chestSlot = chestSlot;
            return this;
        }

        public Builder nativeHidden(boolean nativeHidden) {
            this.nativeHidden = nativeHidden;
            return this;
        }

        public Builder nativeDescription(List<String> nativeDescription) {
            this.nativeDescription = nativeDescription;
            return this;
        }

        public Builder nativeLabel(String nativeLabel) {
            this.nativeLabel = nativeLabel;
            return this;
        }

        public MenuElementCommon build() {
            return new MenuElementCommon(id, label, description, chestHints, icon, nativeIcon, enabled, style, interactions,
                sideActions, chestSlot, nativeHidden, nativeDescription, nativeLabel);
        }
    }
}
