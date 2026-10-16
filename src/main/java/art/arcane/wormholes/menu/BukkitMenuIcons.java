package art.arcane.wormholes.menu;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class BukkitMenuIcons {
    private final Logger logger;
    private final Function<String, Optional<ItemStack>> templates;
    private final Predicate<Material> items;
    private final Map<String, Material> materials = new ConcurrentHashMap<>();

    public BukkitMenuIcons(Logger logger, Function<String, Optional<ItemStack>> templates, Predicate<Material> items) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.items = Objects.requireNonNull(items, "items");
    }

    public Material material(String itemId) {
        return materials.computeIfAbsent(itemId, this::resolve);
    }

    public Optional<ItemStack> template(String templateRef) {
        return templates.apply(templateRef);
    }

    private Material resolve(String itemId) {
        Material material = Material.matchMaterial(itemId);
        if (material != null && items.test(material)) {
            return material;
        }
        logger.warning("Menu item " + itemId + " is not known to this server; showing a barrier instead.");
        return Material.BARRIER;
    }
}
