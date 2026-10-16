package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.CycleElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

public final class MenuPickerOverlay {
    private static final Identifier CURRENT = MenuIconRenderer.sprite("small/confirm");

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

    public AbstractWidget list() {
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
        list.updateSizeAndPosition(panel.width(), panel.height(), panel.x(), panel.y());
        MenuLayout.Rect button = layout.cancel();
        cancel.setRectangle(button.width(), button.height(), button.x(), button.y());
    }

    public void extract(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY,
                        float partialTick) {
        if (layout == null) {
            return;
        }
        graphics.fill(0, 0, screenWidth, screenHeight, MenuPalette.DIM);
        int titleRoom = Math.max(0, screenWidth - 2 * MenuLayout.MARGIN);
        int titleWidth = Math.min(font.width(title), titleRoom);
        graphics.text(font, clippedTitle.clip(font, titleRoom), screenWidth / 2 - titleWidth / 2, MenuLayout.TITLE_Y, MenuPalette.WHITE, true);
        MenuLayout.Rect panel = layout.panel();
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), MenuPalette.PANEL);
        graphics.outline(panel.x(), panel.y(), panel.width(), panel.height(), MenuPalette.PANEL_EDGE);
        list.extractRenderState(graphics, mouseX, mouseY, partialTick);
        cancel.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private static final class OptionList extends ContainerObjectSelectionList<OptionEntry> {
        private int rowWidth = MenuLayout.PICKER_W;

        private OptionList(Minecraft minecraft) {
            super(minecraft, 0, 0, 0, MenuLayout.ROW_1);
        }

        private void add(OptionEntry entry) {
            addEntry(entry);
        }

        private void reveal(OptionEntry entry) {
            scrollToEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return rowWidth;
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
        public void setX(int x) {
            super.setX(x);
            place();
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            place();
        }

        @Override
        public void setWidth(int width) {
            super.setWidth(width);
            place();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            button.extractRenderState(graphics, mouseX, mouseY, partialTick);
            if (current) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CURRENT, button.getX() + MenuLayout.GAP_S,
                    button.getY() + (button.getHeight() - MenuLayout.SMALL_ICON) / 2, MenuLayout.SMALL_ICON, MenuLayout.SMALL_ICON);
            }
        }

        private void place() {
            button.setRectangle(getWidth(), MenuLayout.BTN_H, getX(), getY() + (MenuLayout.ROW_1 - MenuLayout.BTN_H) / 2);
        }
    }
}
