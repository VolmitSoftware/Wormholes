package art.arcane.wormholes.modded;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class MinecraftWindow {
    private static final int WIDTH = 9;
    private static final int MAX_WIDTH_OFFSET = 4;
    private static final int MAX_HEIGHT = 6;
    private static final Map<UUID, MinecraftWindow> ACTIVE_WINDOWS = new ConcurrentHashMap<>();

    private final WormholesModRuntime runtime;
    private final ServerPlayer viewer;
    private final Map<Integer, MinecraftElement> elements = new HashMap<>();
    private MinecraftElement decorator;
    private Consumer<MinecraftWindow> closed;
    private String title = "";
    private boolean visible;
    private int viewportPosition;
    private int viewportHeight = 3;
    private int highestRow;
    private MinecraftWindowMenu menu;
    private int clickCheck;
    private boolean batching;
    private boolean doubleClicked;

    public MinecraftWindow(WormholesModRuntime runtime, ServerPlayer viewer) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
    }

    public static void closeAll() {
        for (MinecraftWindow window : List.copyOf(ACTIVE_WINDOWS.values())) {
            window.close();
        }
    }

    public static MinecraftWindow active(ServerPlayer viewer) {
        return ACTIVE_WINDOWS.get(viewer.getUUID());
    }

    public ServerPlayer getViewer() {
        return viewer;
    }

    public String getTitle() {
        return title;
    }

    public MinecraftWindow setTitle(String title) {
        this.title = title == null ? "" : title;
        if (visible) {
            reopen();
        }
        return this;
    }

    public int getViewportHeight() {
        return viewportHeight;
    }

    public MinecraftWindow setViewportHeight(int height) {
        viewportHeight = clip(height, 1, MAX_HEIGHT);
        if (visible) {
            reopen();
        }
        return this;
    }

    public MinecraftWindow setDecorator(Item pane) {
        decorator = pane == null ? null : new MinecraftElement("c").setName(" ").setMaterial(pane);
        return this;
    }

    public MinecraftWindow onClosed(Consumer<MinecraftWindow> callback) {
        closed = callback;
        return this;
    }

    public MinecraftWindow setElement(int position, int row, MinecraftElement element) {
        if (row > highestRow) {
            highestRow = row;
        }
        elements.put(getRealPosition(position, row), element);
        if (!batching) {
            updateInventory();
        }
        return this;
    }

    public MinecraftElement getElement(int position, int row) {
        return elements.get(getRealPosition(position, row));
    }

    public boolean hasElement(int position, int row) {
        return getElement(position, row) != null;
    }

    public MinecraftWindow clearElements() {
        highestRow = 0;
        elements.clear();
        if (!batching) {
            updateInventory();
        }
        return this;
    }

    public MinecraftWindow batch(Runnable mutations) {
        if (mutations == null) {
            return this;
        }
        boolean wasBatching = batching;
        batching = true;
        try {
            mutations.run();
        } finally {
            batching = wasBatching;
        }
        if (!batching) {
            updateInventory();
        }
        return this;
    }

    public boolean isVisible() {
        return visible;
    }

    public MinecraftWindow open() {
        return setVisible(true);
    }

    public MinecraftWindow close() {
        return setVisible(false);
    }

    public MinecraftWindow reopen() {
        return close().open();
    }

    public MinecraftWindow setVisible(boolean visible) {
        if (this.visible == visible) {
            return this;
        }
        if (!visible) {
            deactivate(true);
            return this;
        }
        MinecraftWindow active = ACTIVE_WINDOWS.get(viewer.getUUID());
        if (active != null && active != this) {
            active.deactivate(false);
        }
        if (viewer.containerMenu instanceof MinecraftWindowMenu current && current.reusableFor(viewportHeight, title)) {
            current.bind(this);
            menu = current;
            this.visible = true;
            ACTIVE_WINDOWS.put(viewer.getUUID(), this);
            updateInventory();
            return this;
        }
        this.visible = true;
        ACTIVE_WINDOWS.put(viewer.getUUID(), this);
        int rows = viewportHeight;
        String menuTitle = title;
        OptionalInt opened = viewer.openMenu(new SimpleMenuProvider((id, inventory, player) -> {
            MinecraftWindowMenu created = new MinecraftWindowMenu(id, inventory, viewer, this, rows, menuTitle);
            menu = created;
            updateInventory();
            return created;
        }, MinecraftLegacyText.component(title)));
        if (opened.isEmpty()) {
            deactivate(false);
        }
        return this;
    }

    public MinecraftWindow scroll(int direction) {
        viewportPosition = clip(viewportPosition + direction, 0, getMaxViewportPosition());
        updateInventory();
        return this;
    }

    public int getMaxViewportPosition() {
        return Math.max(0, highestRow + 1 - viewportHeight);
    }

    public MinecraftWindow updateInventory() {
        if (!visible || menu == null) {
            return this;
        }
        for (int slot = 0; slot < menu.size(); slot++) {
            ItemStack next = computeItemStack(slot);
            if (!ItemStack.matches(menu.item(slot), next)) {
                menu.setItem(slot, next);
            }
        }
        menu.broadcastChanges();
        return this;
    }

    public MinecraftWindow callClosed() {
        if (closed != null) {
            closed.accept(this);
        }
        return this;
    }

    void handleClick(MinecraftWindowMenu source, int slot, int button, ContainerInput input) {
        if (!visible || menu != source || slot < 0 || slot >= source.size()) {
            return;
        }
        MinecraftElement element = getElement(getLayoutPosition(slot), getLayoutRow(slot));
        if (input == ContainerInput.PICKUP_ALL) {
            doubleClicked = true;
        } else if (input == ContainerInput.PICKUP && button == 0) {
            leftClick(element);
        } else if (input == ContainerInput.PICKUP && button == 1) {
            if (element != null) {
                element.call(Click.RIGHT);
            } else {
                scroll(-1);
            }
        } else if (input == ContainerInput.CLONE && button == 2) {
            call(element, Click.MIDDLE);
        } else if (input == ContainerInput.QUICK_MOVE && button == 0) {
            call(element, Click.SHIFT_LEFT);
        } else if (input == ContainerInput.QUICK_MOVE && button == 1) {
            call(element, Click.SHIFT_RIGHT);
        }
    }

    void handleRemoved(MinecraftWindowMenu source) {
        if (!visible || menu != source) {
            return;
        }
        deactivate(false);
        if (viewer.hasDisconnected() || viewer.isDeadOrDying()) {
            callClosed();
            return;
        }
        if (!runtime.schedule(this::callClosed, 1L)) {
            callClosed();
        }
    }

    private void leftClick(MinecraftElement element) {
        clickCheck++;
        if (clickCheck == 1) {
            runtime.schedule(() -> {
                if (clickCheck == 1) {
                    clickCheck = 0;
                    call(element, Click.LEFT);
                }
            }, 1L);
        } else if (clickCheck == 2) {
            runtime.schedule(() -> {
                if (doubleClicked) {
                    doubleClicked = false;
                } else {
                    scroll(1);
                }
                clickCheck = 0;
            }, 1L);
        }
    }

    private static void call(MinecraftElement element, Click click) {
        if (element != null) {
            element.call(click);
        }
    }

    private ItemStack computeItemStack(int slot) {
        int row = getLayoutRow(slot);
        int position = getLayoutPosition(slot);
        MinecraftElement element = hasElement(position, row) ? getElement(position, row) : decorator;
        return element == null ? ItemStack.EMPTY : element.computeItemStack();
    }

    private void deactivate(boolean closeInventory) {
        MinecraftWindowMenu current = menu;
        visible = false;
        menu = null;
        ACTIVE_WINDOWS.remove(viewer.getUUID(), this);
        if (closeInventory && current != null && viewer.containerMenu == current) {
            viewer.closeContainer();
        }
    }

    private int getLayoutRow(int slot) {
        return getRow(getRealPosition(getPosition(slot), getRow(slot) + viewportPosition));
    }

    private int getLayoutPosition(int slot) {
        return getPosition(slot);
    }

    private static int getRealPosition(int position, int row) {
        return row * WIDTH + MAX_WIDTH_OFFSET + clip(position, -MAX_WIDTH_OFFSET, MAX_WIDTH_OFFSET);
    }

    private static int getRow(int realPosition) {
        return realPosition / WIDTH;
    }

    private static int getPosition(int realPosition) {
        return realPosition % WIDTH - MAX_WIDTH_OFFSET;
    }

    private static int clip(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    enum Click {
        LEFT,
        RIGHT,
        MIDDLE,
        SHIFT_LEFT,
        SHIFT_RIGHT
    }
}
