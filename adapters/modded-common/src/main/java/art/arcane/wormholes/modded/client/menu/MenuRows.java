package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.ItemCaptureElement;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.LookCaptureElement;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class MenuRows {
    private static final Duration TOOLTIP_DELAY = Duration.ofMillis(500L);
    private static final int PICKER_THRESHOLD = 6;
    private static final MenuIcon APPLY_ICON = MenuIcon.sprite("small/confirm");

    private MenuRows() {
    }

    public static MenuRow create(MenuElement element, MenuRowContext context) {
        return switch (element) {
            case ButtonElement button -> button(button, context);
            case ToggleElement toggle -> toggle(toggle, context);
            case CycleElement cycle -> cycle(cycle, context);
            case NumberElement number -> number(number, context);
            case TextElement text -> text(text, context);
            case ListEntryElement entry -> entry(entry, context);
            case LabelElement label -> labelRow(label);
            case ItemCaptureElement capture -> capture(capture, context, context.chrome().useHeldItem());
            case LookCaptureElement capture -> capture(capture, context, context.chrome().aim());
        };
    }

    public static MenuInteraction press(MenuElement element) {
        return element.accepts(MenuInteraction.PRIMARY) && !sideAction(element, MenuInteraction.PRIMARY) ? MenuInteraction.PRIMARY : null;
    }

    private static MenuRow button(ButtonElement element, MenuRowContext context) {
        Component label = label(element);
        MenuInteraction press = press(element);
        MenuRowButton main = new MenuRowButton(label, label, icon(element), input -> send(context, element, press, null, MenuRow.Part.MAIN));
        return row(element, MenuLayout.Control.ROW, MenuLayout.ROW_1, label, null, activate(main, element, press != null), null, null,
            null, null, context);
    }

    private static MenuRow toggle(ToggleElement element, MenuRowContext context) {
        Component label = label(element);
        MenuInteraction press = press(element);
        MenuToggleButton main = new MenuToggleButton(label, element.value(), input -> send(context, element, press, null, MenuRow.Part.MAIN));
        return row(element, MenuLayout.Control.TOGGLE, MenuLayout.ROW_1, label, null, activate(main, element, press != null), null, null,
            null, null, context);
    }

    private static MenuRow cycle(CycleElement element, MenuRowContext context) {
        Component label = label(element);
        Component option = MenuComponents.component(element.options().get(element.index()));
        MenuInteraction press = press(element);
        boolean secondary = element.accepts(MenuInteraction.SECONDARY) && !sideAction(element, MenuInteraction.SECONDARY);
        boolean picker = element.options().size() > PICKER_THRESHOLD && element.accepts(MenuInteraction.SELECT);
        MenuActionButton main = new MenuActionButton(option, narration(label, option), input -> {
            if (input.hasShiftDown() && secondary) {
                send(context, element, MenuInteraction.SECONDARY, null, MenuRow.Part.MAIN);
            } else if (picker) {
                context.picker().accept(element);
            } else {
                send(context, element, press, null, MenuRow.Part.MAIN);
            }
        });
        return row(element, MenuLayout.Control.CYCLE, MenuLayout.ROW_1, label, null, activate(main, element, press != null || picker),
            null, null, null, null, context);
    }

    private static MenuRow number(NumberElement element, MenuRowContext context) {
        Component label = label(element);
        MenuSlider slider = new MenuSlider(element, label,
            value -> send(context, element, MenuInteraction.SET_VALUE, MenuValue.number(value), MenuRow.Part.MAIN));
        MenuActionButton decrement = step(element, context, label, "-", MenuInteraction.DECREMENT, MenuInteraction.BIG_DECREMENT,
            MenuRow.Part.DECREMENT);
        MenuActionButton increment = step(element, context, label, "+", MenuInteraction.INCREMENT, MenuInteraction.BIG_INCREMENT,
            MenuRow.Part.INCREMENT);
        return row(element, MenuLayout.Control.NUMBER, MenuLayout.ROW_1, label, null,
            activate(slider, element, element.accepts(MenuInteraction.SET_VALUE)), decrement, increment, null, null, context);
    }

    private static MenuRow text(TextElement element, MenuRowContext context) {
        Component label = label(element);
        boolean editable = element.enabled() && element.accepts(MenuInteraction.SET_TEXT);
        MenuTextField field = new MenuTextField(context.font(), label,
            value -> send(context, element, MenuInteraction.SET_TEXT, MenuValue.text(value), MenuRow.Part.APPLY));
        field.setMaxLength(element.maxLength());
        field.setValue(element.value());
        field.moveCursorToStart(false);
        field.setHint(Component.literal(MenuComponents.plain(element.placeholder())));
        field.setEditable(editable);
        field.active = editable;
        field.setTooltipDelay(TOOLTIP_DELAY);
        MenuSideButton apply = new MenuSideButton(MenuComponents.component(context.chrome().apply()), APPLY_ICON,
            input -> send(context, element, MenuInteraction.SET_TEXT, MenuValue.text(field.getValue()), MenuRow.Part.APPLY));
        apply.active = editable;
        return row(element, MenuLayout.Control.TEXT, MenuLayout.ROW_1, label, null, null, null, null, apply, field, context);
    }

    private static MenuRow entry(ListEntryElement element, MenuRowContext context) {
        Component label = label(element);
        Component secondary = element.description().isEmpty() ? null : MenuComponents.component(element.description().getFirst());
        MenuInteraction press = press(element);
        MenuEntryWidget main = new MenuEntryWidget(label, secondary, element.selected(), element.enabled(), icon(element),
            secondary == null ? label : narration(label, secondary), () -> send(context, element, press, null, MenuRow.Part.MAIN));
        return row(element, MenuLayout.Control.ROW, MenuLayout.rowHeight(secondary != null), label, secondary,
            activate(main, element, press != null), null, null, null, null, context);
    }

    private static MenuRow labelRow(LabelElement element) {
        Component label = label(element);
        Component secondary = element.description().isEmpty() ? null : MenuComponents.component(element.description().getFirst());
        return new MenuRow(new MenuRow.Parts(element, MenuLayout.Control.NONE, MenuLayout.rowHeight(secondary != null), icon(element),
            label, secondary, MenuComponents.lines(element.description()), null, null, null, null, null, List.of(), List.of()));
    }

    private static MenuRow capture(MenuElement element, MenuRowContext context, String text) {
        Component label = label(element);
        Component message = MenuComponents.component(text);
        MenuInteraction press = press(element);
        MenuActionButton main = new MenuActionButton(message, narration(label, message),
            input -> send(context, element, press, null, MenuRow.Part.MAIN));
        return row(element, MenuLayout.Control.CAPTURE, MenuLayout.ROW_1, label, null, activate(main, element, press != null), null,
            null, null, null, context);
    }

    private static MenuRow row(MenuElement element, MenuLayout.Control control, int height, Component label, Component secondary,
                               AbstractWidget main, AbstractWidget decrement, AbstractWidget increment, MenuSideButton apply,
                               MenuTextField field, MenuRowContext context) {
        List<MenuSideAction> actions = element.common().sideActions();
        List<MenuSideButton> sides = new ArrayList<>(actions.size());
        List<MenuInteraction> interactions = new ArrayList<>(actions.size());
        for (MenuSideAction action : actions) {
            MenuSideButton side = new MenuSideButton(MenuComponents.component(action.label()), action.icon(),
                input -> send(context, element, action.interaction(), null, MenuRow.Part.SIDE));
            side.active = element.enabled();
            sides.add(side);
            interactions.add(action.interaction());
        }
        return new MenuRow(new MenuRow.Parts(element, control, height, icon(element), label, secondary,
            MenuComponents.lines(element.description()), main, decrement, increment, apply, field, sides, interactions));
    }

    private static MenuActionButton step(NumberElement element, MenuRowContext context, Component label, String symbol,
                                         MenuInteraction single, MenuInteraction big, MenuRow.Part part) {
        boolean bigAllowed = element.accepts(big);
        MenuActionButton button = new MenuActionButton(Component.literal(symbol), narration(label, Component.literal(symbol)),
            input -> send(context, element, stepInteraction(input, single, big, bigAllowed, element.accepts(single)), null, part));
        button.active = element.enabled() && (element.accepts(single) || bigAllowed);
        return button;
    }

    private static MenuInteraction stepInteraction(InputWithModifiers input, MenuInteraction single, MenuInteraction big, boolean bigAllowed,
                                                   boolean singleAllowed) {
        if (bigAllowed && (input.hasShiftDown() || !singleAllowed)) {
            return big;
        }
        return single;
    }

    private static void send(MenuRowContext context, MenuElement element, MenuInteraction interaction, MenuValue value, MenuRow.Part part) {
        if (interaction == null || !element.enabled()) {
            return;
        }
        context.actions().accept(new MenuRowAction(element.id(), interaction, value, part));
    }

    private static <T extends AbstractWidget> T activate(T widget, MenuElement element, boolean interactive) {
        widget.active = element.enabled() && interactive;
        widget.setTooltipDelay(TOOLTIP_DELAY);
        return widget;
    }

    private static boolean sideAction(MenuElement element, MenuInteraction interaction) {
        for (MenuSideAction action : element.common().sideActions()) {
            if (action.interaction() == interaction) {
                return true;
            }
        }
        return false;
    }

    private static Component label(MenuElement element) {
        return MenuComponents.component(element.label());
    }

    private static MenuIconRenderer icon(MenuElement element) {
        return MenuIconRenderer.of(element.common().wireIcon());
    }

    private static Component narration(Component label, Component value) {
        return Component.empty().append(label).append(", ").append(value);
    }
}
