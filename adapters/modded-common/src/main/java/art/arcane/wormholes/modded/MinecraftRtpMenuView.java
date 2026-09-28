package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.rtp.RtpPortalEditor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Objects;

public final class MinecraftRtpMenuView implements RtpPortalEditor.View {
    private final MinecraftWindow window;

    public MinecraftRtpMenuView(MinecraftWindow window) {
        this.window = Objects.requireNonNull(window);
    }

    @Override
    public void configure(String title) {
        window.setTitle(title);
        window.setViewportHeight(6);
        window.setDecorator(Items.STAINED_GLASS_PANE.black());
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
        MinecraftElement element = new MinecraftElement(entry.id());
        element.setMaterial(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(entry.icon().name().toLowerCase(Locale.ROOT))));
        element.setEnchanted(entry.selected());
        MinecraftLegacyText.apply(window.getViewer(), element, entry.key(), entry.arguments());
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
