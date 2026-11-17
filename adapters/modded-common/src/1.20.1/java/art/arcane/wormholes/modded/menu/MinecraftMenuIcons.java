package art.arcane.wormholes.modded.menu;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
    private static final Map<String, Item> LEGACY_ITEMS = Map.of("minecraft:copper_torch", Items.TORCH);
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
        Item legacy = LEGACY_ITEMS.get(itemId);
        if (legacy != null) {
            return legacy;
        }
        ResourceLocation identifier = ResourceLocation.tryParse(itemId);
        Optional<Item> item = identifier == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(identifier);
        if (item.isPresent() && item.get() != Items.AIR) {
            return item.get();
        }
        warnings.accept("Menu item " + itemId + " is not known to this server; showing a barrier instead.");
        return Items.BARRIER;
    }
}
