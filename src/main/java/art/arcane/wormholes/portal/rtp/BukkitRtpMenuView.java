package art.arcane.wormholes.portal.rtp;

import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.wormholes.Wormholes;
import org.bukkit.Material;

import java.util.Objects;

public final class BukkitRtpMenuView implements RtpPortalEditor.View {
    private final Window window;

    public BukkitRtpMenuView(Window window) {
        this.window = Objects.requireNonNull(window);
    }

    @Override
    public void configure(String title) {
        window.setTitle(title);
        window.setViewportHeight(6);
        window.setDecorator(new UIPaneDecorator(Material.BLACK_STAINED_GLASS_PANE));
    }

    @Override
    public void batch(Runnable operation) {
        window.batch(operation);
    }

    @Override
    public void clearElements() {
        window.clearElements();
    }

    @Override
    public void setElement(int position, int row, RtpPortalEditor.Entry entry) {
        UIElement element = new UIElement(entry.id());
        element.setMaterial(new MaterialBlock(Material.valueOf(entry.icon().name())));
        element.setEnchanted(entry.selected());
        Wormholes.text().apply(element, entry.key(), entry.arguments());
        for (String line : entry.lore()) {
            element.addLore(line);
        }
        element.onLeftClick(event -> entry.activate());
        window.setElement(position, row, element);
    }

    @Override
    public void updateInventory() {
        window.updateInventory();
    }

    @Override
    public void close() {
        window.close();
    }
}
