package art.arcane.wormholes.modded.menu;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public final class MinecraftMenuIcons {
    private final Consumer<String> warnings;
    private final Function<String, Optional<ItemStack>> templates;
    private final Map<String, Item> items = new HashMap<>();

    public MinecraftMenuIcons(Consumer<String> warnings, Function<String, Optional<ItemStack>> templates) {
        this.warnings = Objects.requireNonNull(warnings, "warnings");
        this.templates = Objects.requireNonNull(templates, "templates");
    }

    public Item item(String itemId) {
        return items.computeIfAbsent(itemId, this::resolve);
    }

    public Optional<ItemStack> template(String templateRef) {
        return templates.apply(templateRef);
    }

    private Item resolve(String itemId) {
        Identifier identifier = Identifier.tryParse(itemId);
        Optional<Item> item = identifier == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(identifier);
        if (item.isPresent() && item.get() != Items.AIR) {
            return item.get();
        }
        warnings.accept("Menu item " + itemId + " is not known to this server; showing a barrier instead.");
        return Items.BARRIER;
    }
}
