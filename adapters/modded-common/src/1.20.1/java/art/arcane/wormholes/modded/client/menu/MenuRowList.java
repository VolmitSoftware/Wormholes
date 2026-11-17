package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class MenuRowList extends ContainerObjectSelectionList<MenuRowList.Entry> {
    private static final int SCROLLBAR_GAP = 6;

    private final Font font;
    private int rowWidth = MenuLayout.ROW_W_SETTINGS;
    private boolean mouseInside;

    public MenuRowList(Minecraft minecraft, int width, int height, int y, int rowHeight) {
        super(minecraft, width, minecraft.getWindow().getGuiScaledHeight(), y, y + height, rowHeight);
        this.font = minecraft.font;
        setRenderSelection(false);
    }

    public static int rowHeight(List<MenuRow> rows) {
        int height = MenuLayout.ROW_1;
        for (MenuRow row : rows) {
            height = Math.max(height, row.height());
        }
        return height;
    }

    public void rows(List<MenuRow> rows, MenuLayout.Grid grid) {
        clearEntries();
        rowWidth = grid.rowWidth();
        for (int index = 0; index < rows.size(); index += grid.columns()) {
            List<MenuRow> cells = List.copyOf(rows.subList(index, Math.min(rows.size(), index + grid.columns())));
            addEntry(new Entry(this, cells, grid, font));
        }
        setScrollAmount(getScrollAmount());
    }

    public void band(int height) {
    }

    public Optional<Entry> entry(MenuRow row) {
        for (Entry entry : children()) {
            if (entry.cells.contains(row)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public MenuLayout.Rect viewport() {
        return new MenuLayout.Rect(x0, y0, x1 - x0, y1 - y0);
    }

    public double scrollAmount() {
        return getScrollAmount();
    }

    public void updateSizeAndPosition(int width, int height, int x, int y) {
        updateSize(width, minecraft.getWindow().getGuiScaledHeight(), y, y + height);
        setLeftPos(x);
        setScrollAmount(getScrollAmount());
    }

    @Override
    public void setScrollAmount(double scrollAmount) {
        super.setScrollAmount(scrollAmount);
        layoutEntries();
    }

    @Override
    public int getRowWidth() {
        return rowWidth;
    }

    @Override
    protected int getScrollbarPosition() {
        return rowLeft() + rowWidth + SCROLLBAR_GAP;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        mouseInside = isMouseOver(mouseX, mouseY);
        layoutEntries();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int rowLeft() {
        return x0 + width / 2 - rowWidth / 2;
    }

    private void layoutEntries() {
        List<Entry> entries = children();
        for (int index = 0; index < entries.size(); index++) {
            entries.get(index).layoutCells(rowLeft(), getRowTop(index), itemHeight);
        }
    }

    public static final class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        private final MenuRowList list;
        private final List<MenuRow> cells;
        private final MenuLayout.Grid grid;
        private final Font font;
        private final List<AbstractWidget> widgets;

        private Entry(MenuRowList list, List<MenuRow> cells, MenuLayout.Grid grid, Font font) {
            this.list = list;
            this.cells = cells;
            this.grid = grid;
            this.font = font;
            List<AbstractWidget> ordered = new ArrayList<>();
            for (MenuRow cell : cells) {
                ordered.addAll(cell.widgets());
            }
            this.widgets = Collections.unmodifiableList(ordered);
        }

        public List<MenuRow> cells() {
            return cells;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTick) {
            int x = list.mouseInside ? mouseX : -1;
            int y = list.mouseInside ? mouseY : -1;
            for (MenuRow cell : cells) {
                cell.extract(graphics, font, x, y, partialTick);
            }
        }

        private void layoutCells(int left, int top, int rowHeight) {
            int cellWidth = grid.columns() == 1 ? grid.rowWidth() : grid.columnWidth();
            for (int index = 0; index < cells.size(); index++) {
                MenuRow cell = cells.get(index);
                int y = top + Math.max(0, rowHeight - cell.height()) / 2;
                cell.layout(font, left + index * (grid.columnWidth() + MenuLayout.COLUMN_GAP), y, cellWidth);
            }
        }
    }
}
