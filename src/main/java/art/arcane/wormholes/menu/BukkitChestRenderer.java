package art.arcane.wormholes.menu;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIVoidDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.WindowResolution;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.service.WormholesHud;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class BukkitChestRenderer implements MenuRenderer {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final BukkitMenuIcons icons;
    private final MenuCaptures captures;
    private final Map<UUID, ChestView> views = new ConcurrentHashMap<>();
    private volatile MenuServiceApi service;

    public BukkitChestRenderer(JavaPlugin plugin, BukkitMenuIcons icons, MenuCaptures captures) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.icons = Objects.requireNonNull(icons, "icons");
        this.captures = Objects.requireNonNull(captures, "captures");
    }

    public void start(MenuServiceApi menus) {
        service = Objects.requireNonNull(menus, "menus");
    }

    @Override
    public void show(MenuViewer viewer, MenuBuild build, boolean opening) {
        Player player = BukkitMenuViewer.player(viewer);
        ChestView view = views.computeIfAbsent(viewer.id(), ignored -> new ChestView());
        view.build = build;
        if (view.prompt != null) {
            if (!opening) {
                return;
            }
            view.prompt = null;
        }
        paint(player, view);
    }

    @Override
    public void close(MenuViewer viewer) {
        ChestView view = views.remove(viewer.id());
        if (view != null && view.window != null) {
            view.window.close();
        }
    }

    @Override
    public void notice(MenuViewer viewer, MenuNotice notice) {
        Player player = BukkitMenuViewer.player(viewer);
        String text = notice.feedback().text();
        switch (notice.chestChannel()) {
            case HUD -> WormholesHud.notice(player, LEGACY.deserialize(text));
            case CHAT -> WormholesAudience.sendMessage(player, LEGACY.deserialize(text));
            case SUCCESS_EFFECT -> Wormholes.effectManager.playNotificationSuccess(text, player);
            case FAILURE_EFFECT -> Wormholes.effectManager.playNotificationFail(text, player);
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

    public void closeAll() {
        for (ChestView view : views.values()) {
            dismiss(view);
        }
        views.clear();
    }

    UIElement element(Player player, MenuElement element) {
        UIElement result = new UIElement(element.id());
        applyIcon(result, element.icon());
        result.setName(element.label());
        for (String line : element.description()) {
            result.addLore(line);
        }
        for (String line : element.common().chestHints()) {
            result.addLore(line);
        }
        Map<ChestClick, ChestTarget> clicks = ChestClicks.resolve(element);
        for (Map.Entry<ChestClick, ChestTarget> entry : clicks.entrySet()) {
            ChestTarget target = entry.getValue();
            String targetId = target.elementId() == null ? element.id() : target.elementId();
            switch (entry.getKey()) {
                case LEFT -> result.onLeftClick(clicked -> click(player, targetId, target));
                case RIGHT -> result.onRightClick(clicked -> click(player, targetId, target));
                case SHIFT_LEFT -> result.onShiftLeftClick(clicked -> click(player, targetId, target));
                case SHIFT_RIGHT -> result.onShiftRightClick(clicked -> click(player, targetId, target));
                case MIDDLE -> result.onMiddleClick(clicked -> click(player, targetId, target));
            }
        }
        return result;
    }

    private void applyIcon(UIElement element, MenuIcon icon) {
        if (!(icon instanceof MenuIcon.Item item)) {
            element.setMaterial(new MaterialBlock(Material.BARRIER));
            return;
        }
        element.setMaterial(new MaterialBlock(icons.material(item.itemId())));
        element.setCount(item.count());
        element.setEnchanted(item.glint());
        if (item.templateRef() != null) {
            icons.template(item.templateRef()).ifPresent(element::setBaseItemStack);
        }
    }

    private void paint(Player player, ChestView view) {
        MenuBuild build = view.build;
        ChestLayout layout = build.chest();
        String windowKey = build.windowKey();
        UIWindow window = view.window;
        boolean reuse = window != null && window.isVisible() && windowKey.equals(view.windowKey) && layout.title().equals(window.getTitle())
            && layout.rows() == window.getViewportHeight();
        if (!reuse) {
            UIWindow previous = window;
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
        UIWindow target = window;
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
            target.setVisible(true);
        }
    }

    private UIWindow window(Player player, ChestView view, ChestLayout layout) {
        UIWindow window = new UIWindow(plugin, player);
        window.setTitle(layout.title());
        window.setResolution(WindowResolution.W9_H6);
        window.setViewportHeight(layout.rows());
        decorate(window, layout.pane());
        window.onClosed(closed -> escaped(player, view, window));
        return window;
    }

    private static void decorate(UIWindow window, ChestPane pane) {
        Material material = pane(pane);
        window.setDecorator(material == null ? new UIVoidDecorator() : new UIPaneDecorator(material));
    }

    private void escaped(Player player, ChestView view, UIWindow window) {
        UUID viewerId = player.getUniqueId();
        if (!player.isOnline() || view.window != window || !views.remove(viewerId, view)) {
            return;
        }
        view.window = null;
        service.chestEscape(viewerId);
    }

    private void click(Player player, String elementId, ChestTarget target) {
        UUID viewerId = player.getUniqueId();
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
        service.chestAction(viewerId, new MenuChestAction(elementId, target.interaction(), null, target.preconfirmed(), null));
    }

    private void prompt(Player player, ChestView view, TextElement text) {
        UUID viewerId = player.getUniqueId();
        if (!service.chestPrecheck(viewerId, text.id(), MenuInteraction.SET_TEXT)) {
            return;
        }
        ChestPrompt prompt = new ChestPrompt(text.chestReopen(), text.chestCancelRoute());
        hide(view, prompt);
        captures.prompt(new BukkitMenuViewer(player), text.chestPrompt(),
            input -> submitted(player, view, prompt, new MenuChestAction(text.id(), MenuInteraction.SET_TEXT, MenuValue.text(input), false, null)),
            () -> cancelled(player, view, prompt));
    }

    private void capture(Player player, ChestView view, ItemCaptureElement capture) {
        UUID viewerId = player.getUniqueId();
        if (!service.chestPrecheck(viewerId, capture.id(), MenuInteraction.PRIMARY)) {
            return;
        }
        ChestPrompt prompt = new ChestPrompt(ChestReopen.ALWAYS, null);
        hide(view, prompt);
        captures.dropItem(new BukkitMenuViewer(player), capture.chestPrompt(),
            item -> submitted(player, view, prompt, new MenuChestAction(capture.id(), MenuInteraction.PRIMARY, null, false, item)),
            () -> cancelled(player, view, prompt));
    }

    private void hide(ChestView view, ChestPrompt prompt) {
        view.prompt = prompt;
        dismiss(view);
    }

    private static void dismiss(ChestView view) {
        UIWindow window = view.window;
        view.window = null;
        if (window != null) {
            window.close();
        }
    }

    private void submitted(Player player, ChestView view, ChestPrompt prompt, MenuChestAction action) {
        UUID viewerId = player.getUniqueId();
        if (!pending(viewerId, view, prompt)) {
            return;
        }
        if (prompt.reopen() != ChestReopen.ALWAYS) {
            service.chestAction(viewerId, action);
            end(viewerId, view, prompt);
            return;
        }
        view.prompt = null;
        service.chestAction(viewerId, action);
        reveal(player, view);
    }

    private void cancelled(Player player, ChestView view, ChestPrompt prompt) {
        UUID viewerId = player.getUniqueId();
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
            service.open(viewerId, prompt.cancelRoute());
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
        service.chestEnd(viewerId);
    }

    private void reveal(Player player, ChestView view) {
        if (views.get(player.getUniqueId()) != view || view.prompt != null) {
            return;
        }
        if (view.window == null || !view.window.isVisible()) {
            paint(player, view);
        }
    }

    private boolean pending(UUID viewerId, ChestView view, ChestPrompt prompt) {
        return views.get(viewerId) == view && view.prompt == prompt;
    }

    private static Material pane(ChestPane pane) {
        return switch (pane) {
            case GRAY -> Material.GRAY_STAINED_GLASS_PANE;
            case BLUE -> Material.BLUE_STAINED_GLASS_PANE;
            case BLACK -> Material.BLACK_STAINED_GLASS_PANE;
            case CYAN -> Material.CYAN_STAINED_GLASS_PANE;
            case BROWN -> Material.BROWN_STAINED_GLASS_PANE;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case WHITE -> Material.WHITE_STAINED_GLASS_PANE;
            case NONE -> null;
        };
    }

    private static final class ChestView {
        private UIWindow window;
        private String windowKey;
        private ChestPane pane;
        private MenuBuild build;
        private ChestPrompt prompt;
    }

    private record ChestPrompt(ChestReopen reopen, MenuRoute cancelRoute) {
    }
}
