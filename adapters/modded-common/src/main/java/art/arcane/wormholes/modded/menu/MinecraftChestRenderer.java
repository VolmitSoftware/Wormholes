package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestClicks;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.ItemCaptureElement;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuRenderer;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuServiceApi;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.modded.MinecraftDyedItems;
import art.arcane.wormholes.modded.MinecraftElement;
import art.arcane.wormholes.modded.MinecraftLegacyText;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.modded.MinecraftWindow;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftChestRenderer implements MenuRenderer, AutoCloseable {
    private final WormholesModRuntime runtime;
    private final MinecraftMenuIcons icons;
    private final MinecraftMenuCaptures captures;
    private final Map<UUID, ChestView> views = new HashMap<>();

    public MinecraftChestRenderer(WormholesModRuntime runtime, MinecraftMenuIcons icons, MinecraftMenuCaptures captures) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.icons = Objects.requireNonNull(icons, "icons");
        this.captures = Objects.requireNonNull(captures, "captures");
    }

    @Override
    public void show(MenuViewer viewer, MenuBuild build, boolean opening) {
        runtime.requireServerThread();
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        ChestView view = views.computeIfAbsent(viewer.id(), ignored -> new ChestView());
        view.build = build;
        if (view.prompt != null) {
            if (!opening) {
                return;
            }
            view.prompt = null;
            captures.cancel(player);
        }
        if (!opening && view.window != null && !view.window.isVisible()) {
            return;
        }
        paint(player, view);
    }

    @Override
    public void close(MenuViewer viewer) {
        runtime.requireServerThread();
        ChestView view = views.remove(viewer.id());
        if (view == null) {
            return;
        }
        if (view.prompt != null) {
            captures.cancel(MinecraftMenuViewer.player(viewer));
        }
        dismiss(view);
    }

    @Override
    public void close() {
        views.clear();
    }

    @Override
    public void notice(MenuViewer viewer, MenuNotice notice) {
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        String text = notice.feedback().text();
        switch (notice.chestChannel()) {
            case HUD -> MinecraftMenuText.notice(player, MinecraftLegacyText.component(text));
            case CHAT -> player.sendSystemMessage(MinecraftLegacyText.component(text));
            case SUCCESS_EFFECT -> MinecraftMenuText.notifySuccess(player, text);
            case FAILURE_EFFECT -> MinecraftMenuText.notifyFailure(player, text);
            case NONE -> {
            }
        }
    }

    public void disconnected(UUID viewerId) {
        ChestView view = views.remove(viewerId);
        if (view != null) {
            dismiss(view);
        }
    }

    MinecraftElement element(ServerPlayer player, MenuElement element) {
        MinecraftElement result = new MinecraftElement(element.id());
        applyIcon(result, element.icon());
        result.setName(element.label());
        for (String line : element.description()) {
            result.addLore(line);
        }
        for (String line : element.common().chestHints()) {
            result.addLore(line);
        }
        for (Map.Entry<ChestClick, ChestTarget> entry : ChestClicks.resolve(element).entrySet()) {
            ChestTarget target = entry.getValue();
            String targetId = target.elementId() == null ? element.id() : target.elementId();
            Consumer<MinecraftElement> handler = clicked -> click(player, targetId, target);
            switch (entry.getKey()) {
                case LEFT -> result.onLeftClick(handler);
                case RIGHT -> result.onRightClick(handler);
                case SHIFT_LEFT -> result.onShiftLeftClick(handler);
                case SHIFT_RIGHT -> result.onShiftRightClick(handler);
                case MIDDLE -> result.onMiddleClick(handler);
            }
        }
        return result;
    }

    private void applyIcon(MinecraftElement element, MenuIcon icon) {
        if (!(icon instanceof MenuIcon.Item item)) {
            element.setMaterial(Items.BARRIER);
            return;
        }
        element.setMaterial(icons.item(item.itemId()));
        element.setCount(item.count());
        element.setEnchanted(item.glint());
        if (item.templateRef() != null) {
            icons.template(item.templateRef()).ifPresent(element::setBaseItemStack);
        }
    }

    private void paint(ServerPlayer player, ChestView view) {
        MenuBuild build = view.build;
        ChestLayout layout = build.chest();
        String windowKey = build.windowKey();
        MinecraftWindow window = view.window;
        boolean reuse = window != null && window.isVisible() && windowKey.equals(view.windowKey) && layout.title().equals(window.getTitle())
            && layout.rows() == window.getViewportHeight();
        if (!reuse) {
            MinecraftWindow previous = window;
            window = window(player, view, layout);
            view.window = window;
            view.windowKey = windowKey;
            if (previous != null && previous.isVisible()) {
                previous.close();
            }
        } else if (view.pane != layout.pane()) {
            decorate(window, layout.pane());
        }
        view.pane = layout.pane();
        MinecraftWindow target = window;
        target.batch(() -> {
            target.clearElements();
            for (MenuElement element : build.screen().elements()) {
                MenuSlot slot = element.chestSlot();
                if (slot != null) {
                    target.setElement(slot.position(), slot.row(), element(player, element));
                }
            }
        });
        if (!reuse) {
            target.open();
        }
    }

    private MinecraftWindow window(ServerPlayer player, ChestView view, ChestLayout layout) {
        MinecraftWindow window = new MinecraftWindow(runtime, player);
        window.setTitle(layout.title());
        window.setViewportHeight(layout.rows());
        decorate(window, layout.pane());
        window.onClosed(closed -> escaped(player, view, closed));
        return window;
    }

    private static void decorate(MinecraftWindow window, ChestPane pane) {
        DyeColor color = pane(pane);
        window.setDecorator(color == null ? null : MinecraftDyedItems.pane(color));
    }

    private void escaped(ServerPlayer player, ChestView view, MinecraftWindow window) {
        UUID viewerId = player.getUUID();
        if (player.hasDisconnected() || view.window != window || !views.remove(viewerId, view)) {
            return;
        }
        view.window = null;
        menus().chestEscape(viewerId);
    }

    private void click(ServerPlayer player, String elementId, ChestTarget target) {
        UUID viewerId = player.getUUID();
        ChestView view = views.get(viewerId);
        if (view == null || view.prompt != null) {
            return;
        }
        MenuElement element = view.build.screen().element(elementId).orElse(null);
        if (target.interaction() == MenuInteraction.SET_TEXT && element instanceof TextElement text) {
            prompt(player, view, text);
            return;
        }
        if (target.interaction() == MenuInteraction.PRIMARY && element instanceof ItemCaptureElement capture) {
            capture(player, view, capture);
            return;
        }
        menus().chestAction(viewerId, new MenuChestAction(elementId, target.interaction(), null, target.preconfirmed(), null));
    }

    private void prompt(ServerPlayer player, ChestView view, TextElement text) {
        if (!menus().chestPrecheck(player.getUUID(), text.id(), MenuInteraction.SET_TEXT)) {
            return;
        }
        ChestPrompt prompt = new ChestPrompt(text.chestReopen(), text.chestCancelRoute());
        hide(view, prompt);
        captures.prompt(new MinecraftMenuViewer(runtime, player), text.chestPrompt(),
            input -> submitted(player, view, prompt, new MenuChestAction(text.id(), MenuInteraction.SET_TEXT, MenuValue.text(input), false, null)),
            () -> cancelled(player, view, prompt));
    }

    private void capture(ServerPlayer player, ChestView view, ItemCaptureElement capture) {
        if (!menus().chestPrecheck(player.getUUID(), capture.id(), MenuInteraction.PRIMARY)) {
            return;
        }
        ChestPrompt prompt = new ChestPrompt(ChestReopen.ALWAYS, null);
        hide(view, prompt);
        captures.dropItem(new MinecraftMenuViewer(runtime, player), capture.chestPrompt(),
            item -> submitted(player, view, prompt, new MenuChestAction(capture.id(), MenuInteraction.PRIMARY, null, false, item)),
            () -> submitted(player, view, prompt, new MenuChestAction(capture.id(), MenuInteraction.PRIMARY, null, false, null)));
    }

    private void hide(ChestView view, ChestPrompt prompt) {
        view.prompt = prompt;
        dismiss(view);
    }

    private static void dismiss(ChestView view) {
        MinecraftWindow window = view.window;
        view.window = null;
        if (window != null) {
            window.close();
        }
    }

    private void submitted(ServerPlayer player, ChestView view, ChestPrompt prompt, MenuChestAction action) {
        UUID viewerId = player.getUUID();
        if (!pending(viewerId, view, prompt)) {
            return;
        }
        if (prompt.reopen() != ChestReopen.ALWAYS) {
            menus().chestAction(viewerId, action);
            end(viewerId, view, prompt);
            return;
        }
        view.prompt = null;
        menus().chestAction(viewerId, action);
        reveal(player, view);
    }

    private void cancelled(ServerPlayer player, ChestView view, ChestPrompt prompt) {
        UUID viewerId = player.getUUID();
        if (!pending(viewerId, view, prompt)) {
            return;
        }
        if (prompt.reopen() == ChestReopen.NEVER) {
            end(viewerId, view, prompt);
            return;
        }
        view.prompt = null;
        if (prompt.cancelRoute() != null) {
            views.remove(viewerId, view);
            menus().open(viewerId, prompt.cancelRoute());
            return;
        }
        reveal(player, view);
    }

    private void end(UUID viewerId, ChestView view, ChestPrompt prompt) {
        if (!pending(viewerId, view, prompt)) {
            return;
        }
        view.prompt = null;
        views.remove(viewerId, view);
        menus().chestEnd(viewerId);
    }

    private void reveal(ServerPlayer player, ChestView view) {
        if (views.get(player.getUUID()) != view || view.prompt != null) {
            return;
        }
        if (view.window == null || !view.window.isVisible()) {
            paint(player, view);
        }
    }

    private boolean pending(UUID viewerId, ChestView view, ChestPrompt prompt) {
        return views.get(viewerId) == view && view.prompt == prompt;
    }

    private MenuServiceApi menus() {
        return runtime.menus();
    }

    private static DyeColor pane(ChestPane pane) {
        return switch (pane) {
            case GRAY -> DyeColor.GRAY;
            case BLUE -> DyeColor.BLUE;
            case BLACK -> DyeColor.BLACK;
            case CYAN -> DyeColor.CYAN;
            case BROWN -> DyeColor.BROWN;
            case LIGHT_BLUE -> DyeColor.LIGHT_BLUE;
            case WHITE -> DyeColor.WHITE;
            case NONE -> null;
        };
    }

    private static final class ChestView {
        private MinecraftWindow window;
        private String windowKey;
        private ChestPane pane;
        private MenuBuild build;
        private ChestPrompt prompt;
    }

    private record ChestPrompt(ChestReopen reopen, MenuRoute cancelRoute) {
    }
}
