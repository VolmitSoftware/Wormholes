package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MenuRow {
    private static final int TOOLTIP_WRAP = 170;

    private final Parts parts;
    private final MenuClippedText label;
    private final MenuClippedText secondary;
    private final List<AbstractWidget> widgets;
    private MenuLayout.Row geometry;
    private int tooltipWidth = -1;
    private List<FormattedCharSequence> hoverLines = List.of();

    public MenuRow(Parts parts) {
        this.parts = Objects.requireNonNull(parts, "parts");
        this.label = new MenuClippedText(parts.label());
        this.secondary = parts.secondary() == null ? null : new MenuClippedText(parts.secondary());
        List<AbstractWidget> ordered = new ArrayList<>(4 + parts.sides().size());
        addPresent(ordered, parts.decrement());
        addPresent(ordered, parts.main());
        addPresent(ordered, parts.increment());
        addPresent(ordered, parts.textField());
        addPresent(ordered, parts.apply());
        ordered.addAll(parts.sides());
        this.widgets = Collections.unmodifiableList(ordered);
    }

    public MenuElement element() {
        return parts.element();
    }

    public String id() {
        return parts.element().id();
    }

    public int height() {
        return parts.height();
    }

    public MenuLayout.Control control() {
        return parts.control();
    }

    public Component label() {
        return parts.label();
    }

    public List<AbstractWidget> widgets() {
        return widgets;
    }

    public Optional<AbstractWidget> main() {
        return Optional.ofNullable(parts.main());
    }

    public Optional<AbstractWidget> decrement() {
        return Optional.ofNullable(parts.decrement());
    }

    public Optional<AbstractWidget> increment() {
        return Optional.ofNullable(parts.increment());
    }

    public Optional<MenuSideButton> apply() {
        return Optional.ofNullable(parts.apply());
    }

    public Optional<MenuTextField> textField() {
        return Optional.ofNullable(parts.textField());
    }

    public Optional<MenuSideButton> side(MenuInteraction interaction) {
        List<MenuInteraction> order = parts.sideInteractions();
        int index = order.indexOf(interaction);
        return index < 0 ? Optional.empty() : Optional.of(parts.sides().get(index));
    }

    public Optional<AbstractWidget> widget(Part part, MenuInteraction interaction) {
        return switch (part) {
            case MAIN -> parts.element() instanceof TextElement ? textField().map(AbstractWidget.class::cast) : main();
            case DECREMENT -> decrement();
            case INCREMENT -> increment();
            case APPLY -> apply().map(AbstractWidget.class::cast);
            case SIDE -> side(interaction).map(AbstractWidget.class::cast);
        };
    }

    public Optional<Part> part(GuiEventListener listener) {
        if (listener == null) {
            return Optional.empty();
        }
        if (listener == parts.main() || listener == parts.textField()) {
            return Optional.of(Part.MAIN);
        }
        if (listener == parts.decrement()) {
            return Optional.of(Part.DECREMENT);
        }
        if (listener == parts.increment()) {
            return Optional.of(Part.INCREMENT);
        }
        if (listener == parts.apply()) {
            return Optional.of(Part.APPLY);
        }
        return parts.sides().contains(listener) ? Optional.of(Part.SIDE) : Optional.empty();
    }

    public Optional<MenuInteraction> sideInteraction(GuiEventListener listener) {
        int index = parts.sides().indexOf(listener);
        return index < 0 ? Optional.empty() : Optional.of(parts.sideInteractions().get(index));
    }

    public String valueText() {
        if (parts.textField() != null) {
            return parts.textField().getValue();
        }
        return switch (parts.control()) {
            case TOGGLE, CYCLE, CAPTURE, NUMBER -> parts.main().getMessage().getString();
            case ROW, NONE, TEXT -> secondary == null ? "" : secondary.text().getString();
        };
    }

    public MenuLayout.Row geometry() {
        return geometry;
    }

    public void layout(Font font, int x, int y, int width) {
        int valueWidth = parts.element() instanceof NumberElement number ? font.width(number.display()) : 0;
        geometry = MenuLayout.row(parts.control(), x, y, width, parts.height(), parts.sides().size(), font.width(parts.label()), valueWidth);
        switch (parts.control()) {
            case ROW -> place(parts.main(), parts.main() instanceof MenuEntryWidget ? geometry.entry() : geometry.control());
            case TOGGLE, CYCLE, CAPTURE -> place(parts.main(), geometry.control());
            case NUMBER -> {
                place(parts.decrement(), geometry.decrement());
                place(parts.main(), geometry.slider());
                place(parts.increment(), geometry.increment());
            }
            case TEXT -> {
                place(parts.textField(), geometry.edit());
                place(parts.apply(), geometry.apply());
            }
            case NONE -> {
            }
        }
        for (int index = 0; index < parts.sides().size(); index++) {
            place(parts.sides().get(index), geometry.side(index));
        }
        if (width != tooltipWidth) {
            tooltipWidth = width;
            refreshTooltips(font);
        }
    }

    public void extract(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, float partialTick) {
        if (geometry == null) {
            return;
        }
        boolean enabled = parts.element().enabled();
        if (parts.control() != MenuLayout.Control.ROW) {
            parts.icon().draw(graphics, font, geometry.iconX(), geometry.iconY(), enabled);
            int labelWidth = geometry.labelRight() - geometry.labelX();
            int labelColor = enabled ? MenuPalette.WHITE : MenuPalette.DISABLED;
            graphics.text(font, label.clip(font, labelWidth), geometry.labelX(), geometry.textY(), labelColor, true);
            if (secondary != null) {
                graphics.text(font, secondary.clip(font, labelWidth), geometry.labelX(), geometry.secondY(),
                    enabled ? MenuPalette.SECONDARY : MenuPalette.DISABLED, true);
            }
        }
        for (AbstractWidget widget : widgets) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
        if (parts.control() != MenuLayout.Control.ROW && !hoverLines.isEmpty() && geometry.label().contains(mouseX, mouseY)
            && graphics.containsPointInScissor(mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, hoverLines, mouseX, mouseY);
        }
    }

    private void refreshTooltips(Font font) {
        boolean truncated = switch (parts.main()) {
            case MenuRowButton button -> button.truncated();
            case MenuEntryWidget entry -> entry.truncated();
            case null, default -> label.truncated(font, geometry.labelRight() - geometry.labelX())
                || secondary != null && secondary.truncated(font, geometry.labelRight() - geometry.labelX());
        };
        Component text = tooltipText(truncated);
        AbstractWidget target = parts.textField() != null ? parts.textField() : parts.main();
        if (target != null) {
            target.setTooltip(text == null ? null : Tooltip.create(text));
        }
        hoverLines = text == null ? List.of() : font.split(text, TOOLTIP_WRAP);
    }

    private Component tooltipText(boolean truncated) {
        boolean described = !parts.description().getString().isEmpty();
        if (!truncated && !described) {
            return null;
        }
        if (!truncated) {
            return parts.description();
        }
        MutableComponent combined = Component.empty().append(parts.label());
        if (described) {
            combined.append(Component.literal("\n")).append(parts.description());
        }
        return combined;
    }

    private static void place(AbstractWidget widget, MenuLayout.Rect rect) {
        if (widget != null) {
            widget.setRectangle(rect.width(), rect.height(), rect.x(), rect.y());
        }
    }

    private static void addPresent(List<AbstractWidget> target, AbstractWidget widget) {
        if (widget != null) {
            target.add(widget);
        }
    }

    public enum Part {
        MAIN, DECREMENT, INCREMENT, APPLY, SIDE
    }

    public record Parts(
        MenuElement element,
        MenuLayout.Control control,
        int height,
        MenuIconRenderer icon,
        Component label,
        Component secondary,
        Component description,
        AbstractWidget main,
        AbstractWidget decrement,
        AbstractWidget increment,
        MenuSideButton apply,
        MenuTextField textField,
        List<MenuSideButton> sides,
        List<MenuInteraction> sideInteractions
    ) {
        public Parts {
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(description, "description");
            sides = List.copyOf(sides);
            sideInteractions = List.copyOf(sideInteractions);
            if (sides.size() != sideInteractions.size()) {
                throw new IllegalArgumentException("side buttons and interactions differ in size");
            }
        }
    }
}
