package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.CycleElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

public final class MenuPickerOverlay {
    private static final ResourceLocation CURRENT = MenuIconRenderer.sprite("small/confirm");

    private final CycleElement element;
    private final Component title;
    private final MenuClippedText clippedTitle;
    private final OptionList list;
    private final List<MenuActionButton> options;
    private final MenuActionButton cancel;
    private MenuLayout.Picker layout;

    public MenuPickerOverlay(Minecraft minecraft, CycleElement element, Component cancelLabel, IntConsumer select, Runnable onCancel) {
        this.element = Objects.requireNonNull(element, "element");
        this.title = MenuComponents.component(element.label());
        this.clippedTitle = new MenuClippedText(title);
        this.list = new OptionList(minecraft);
        Objects.requireNonNull(onCancel, "onCancel");
        this.cancel = new MenuActionButton(cancelLabel, cancelLabel, input -> onCancel.run());
        List<MenuActionButton> created = new ArrayList<>(element.options().size());
        for (int index = 0; index < element.options().size(); index++) {
            int chosen = index;
            Component option = MenuComponents.component(element.options().get(index));
            MenuActionButton button = new MenuActionButton(option, Component.empty().append(title).append(", ").append(option),
                input -> select.accept(chosen));
            created.add(button);
            list.add(new OptionEntry(button, index == element.index()));
        }
        this.options = List.copyOf(created);
    }

    public CycleElement element() {
        return element;
    }

    public ContainerObjectSelectionList<?> list() {
        return list;
    }

    public MenuActionButton cancelButton() {
        return cancel;
    }

    public Optional<AbstractWidget> option(int index) {
        return index < 0 || index >= options.size() ? Optional.empty() : Optional.of(options.get(index));
    }

    public void reveal(int index) {
        if (index >= 0 && index < list.children().size()) {
            list.reveal(list.children().get(index));
        }
    }

    public void layout(int screenWidth, int screenHeight) {
        layout = MenuLayout.picker(screenWidth, screenHeight, options.size());
        MenuLayout.Rect panel = layout.panel();
        list.rowWidth = layout.rowWidth();
        list.updateSize(panel.width(), panel.bottom(), panel.y(), panel.bottom());
        list.setLeftPos(panel.x());
        list.place();
        MenuDraw.place(cancel, layout.cancel());
    }

    public void extract(GuiGraphics graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY,
                        float partialTick) {
        if (layout == null) {
            return;
        }
        graphics.fill(0, 0, screenWidth, screenHeight, MenuPalette.DIM);
        int titleRoom = Math.max(0, screenWidth - 2 * MenuLayout.MARGIN);
        int titleWidth = Math.min(font.width(title), titleRoom);
        graphics.drawString(font, clippedTitle.clip(font, titleRoom), screenWidth / 2 - titleWidth / 2, MenuLayout.TITLE_Y, MenuPalette.WHITE, true);
        MenuLayout.Rect panel = layout.panel();
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), MenuPalette.PANEL);
        graphics.renderOutline(panel.x(), panel.y(), panel.width(), panel.height(), MenuPalette.PANEL_EDGE);
        list.render(graphics, mouseX, mouseY, partialTick);
        cancel.render(graphics, mouseX, mouseY, partialTick);
    }

    private static final class OptionList extends ContainerObjectSelectionList<OptionEntry> {
        private int rowWidth = MenuLayout.PICKER_W;

        private OptionList(Minecraft minecraft) {
            super(minecraft, 0, 0, 0, 0, MenuLayout.ROW_1);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            setRenderSelection(false);
        }

        private void add(OptionEntry entry) {
            addEntry(entry);
        }

        private void reveal(OptionEntry entry) {
            ensureVisible(entry);
            place();
        }

        private void place() {
            int left = x0 + width / 2 - rowWidth / 2;
            List<OptionEntry> entries = children();
            for (int index = 0; index < entries.size(); index++) {
                entries.get(index).place(left, getRowTop(index), rowWidth);
            }
        }

        @Override
        public void setScrollAmount(double scrollAmount) {
            super.setScrollAmount(scrollAmount);
            place();
        }

        @Override
        public int getRowWidth() {
            return rowWidth;
        }

        @Override
        protected int getScrollbarPosition() {
            return x1 - 6;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            place();
            super.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private static final class OptionEntry extends ContainerObjectSelectionList.Entry<OptionEntry> {
        private final MenuActionButton button;
        private final boolean current;

        private OptionEntry(MenuActionButton button, boolean current) {
            this.button = button;
            this.current = current;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(button);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTick) {
            button.render(graphics, mouseX, mouseY, partialTick);
            if (current) {
                MenuDraw.sprite(graphics, CURRENT, button.getX() + MenuLayout.GAP_S,
                    button.getY() + (button.getHeight() - MenuLayout.SMALL_ICON) / 2, MenuLayout.SMALL_ICON, MenuLayout.SMALL_ICON);
            }
        }

        private void place(int left, int top, int width) {
            button.setX(left);
            button.setY(top + (MenuLayout.ROW_1 - MenuLayout.BTN_H) / 2);
            button.setWidth(width);
        }
    }
}
