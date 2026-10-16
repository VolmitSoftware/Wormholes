package art.arcane.wormholes.modded.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class MenuRowList extends ContainerObjectSelectionList<MenuRowList.Entry> {
    private static final Identifier MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/menu_list_background.png");
    private static final Identifier INWORLD_MENU_LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
    private static final int SEPARATOR_H = 2;
    private static final int TILE = 32;

    private final Font font;
    private int rowWidth = MenuLayout.ROW_W_SETTINGS;
    private int band;

    public MenuRowList(Minecraft minecraft, int width, int height, int y) {
        super(minecraft, width, height, y, MenuLayout.ROW_1);
        this.font = minecraft.font;
    }

    public void rows(List<MenuRow> rows, MenuLayout.Grid grid) {
        clearEntries();
        rowWidth = grid.rowWidth();
        for (int index = 0; index < rows.size(); index += grid.columns()) {
            List<MenuRow> cells = List.copyOf(rows.subList(index, Math.min(rows.size(), index + grid.columns())));
            int height = 0;
            for (MenuRow cell : cells) {
                height = Math.max(height, cell.height());
            }
            addEntry(new Entry(cells, grid, font), height);
        }
        refreshScrollAmount();
    }

    public void band(int height) {
        band = Math.max(0, height);
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
        return new MenuLayout.Rect(getX(), getY(), getWidth(), getHeight());
    }

    @Override
    public void setScrollAmount(double scrollAmount) {
        super.setScrollAmount(scrollAmount);
        for (Entry entry : children()) {
            entry.layoutCells();
        }
    }

    @Override
    public int getRowWidth() {
        return rowWidth;
    }

    @Override
    protected void extractListBackground(GuiGraphicsExtractor graphics) {
        super.extractListBackground(graphics);
        if (band == 0) {
            return;
        }
        Identifier background = minecraft.level == null ? MENU_LIST_BACKGROUND : INWORLD_MENU_LIST_BACKGROUND;
        graphics.blit(RenderPipelines.GUI_TEXTURED, background, getX(), getY() - band, (float) getRight(),
            (float) (getBottom() + (int) scrollAmount() - band), getWidth(), band, TILE, TILE);
    }

    @Override
    protected void extractListSeparators(GuiGraphicsExtractor graphics) {
        Identifier header = minecraft.level == null ? Screen.HEADER_SEPARATOR : Screen.INWORLD_HEADER_SEPARATOR;
        Identifier footer = minecraft.level == null ? Screen.FOOTER_SEPARATOR : Screen.INWORLD_FOOTER_SEPARATOR;
        graphics.blit(RenderPipelines.GUI_TEXTURED, header, getX(), getY() - band - SEPARATOR_H, 0.0F, 0.0F, getWidth(), SEPARATOR_H, TILE,
            SEPARATOR_H);
        graphics.blit(RenderPipelines.GUI_TEXTURED, footer, getX(), getBottom(), 0.0F, 0.0F, getWidth(), SEPARATOR_H, TILE, SEPARATOR_H);
    }

    public static final class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        private final List<MenuRow> cells;
        private final MenuLayout.Grid grid;
        private final Font font;
        private final List<AbstractWidget> widgets;

        private Entry(List<MenuRow> cells, MenuLayout.Grid grid, Font font) {
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
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            for (MenuRow cell : cells) {
                cell.extract(graphics, font, mouseX, mouseY, partialTick);
            }
        }

        private void layoutCells() {
            int cellWidth = grid.columns() == 1 ? getWidth() : grid.columnWidth();
            for (int index = 0; index < cells.size(); index++) {
                cells.get(index).layout(font, getX() + index * (grid.columnWidth() + MenuLayout.COLUMN_GAP), getY(), cellWidth);
            }
        }
    }
}
