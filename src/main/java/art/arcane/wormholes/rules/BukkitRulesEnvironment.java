package art.arcane.wormholes.rules;

import art.arcane.wormholes.nexus.Dialer;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.UUID;

/**
 * The live server behind the rule conditions. PlaceholderAPI is probed once by class presence, never by
 * version, so a server without it simply expands every expression to an empty string.
 */
public final class BukkitRulesEnvironment implements RulesEnvironment {
    private static final boolean PLACEHOLDER_API_PRESENT = probePlaceholderApi();

    @Override
    public long worldTimeTicks(LocalPortal portal) {
        World world = portal.getWorld();
        return world == null ? 0L : world.getTime();
    }

    @Override
    public Condition.WeatherKind weather(LocalPortal portal) {
        World world = portal.getWorld();
        if (world == null) {
            return Condition.WeatherKind.CLEAR;
        }
        if (world.isThundering()) {
            return Condition.WeatherKind.THUNDER;
        }
        return world.hasStorm() ? Condition.WeatherKind.RAIN : Condition.WeatherKind.CLEAR;
    }

    @Override
    public int moonPhase(LocalPortal portal) {
        World world = portal.getWorld();
        return world == null ? 0 : (int) ((world.getFullTime() / 24000L) % 8L);
    }

    @Override
    public boolean redstonePowered(LocalPortal portal, int dx, int dy, int dz) {
        Location origin = portal.getStructure().getCenter();
        if (origin == null || origin.getWorld() == null) {
            return false;
        }
        Block block = origin.getWorld().getBlockAt(origin.getBlockX() + dx, origin.getBlockY() + dy, origin.getBlockZ() + dz);
        return block.isBlockPowered() || block.isBlockIndirectlyPowered();
    }

    @Override
    public boolean hasPermission(Entity traveler, String node) {
        return traveler != null && !node.isEmpty() && traveler.hasPermission(node);
    }

    @Override
    public boolean hasAdvancement(Player player, String key) {
        NamespacedKey parsed = NamespacedKey.fromString(key);
        if (parsed == null) {
            return false;
        }
        Advancement advancement = Bukkit.getAdvancement(parsed);
        return advancement != null && player.getAdvancementProgress(advancement).isDone();
    }

    @Override
    public String placeholder(Player player, String expression) {
        if (!PLACEHOLDER_API_PRESENT || expression.isEmpty()) {
            return "";
        }
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, expression);
    }

    @Override
    public boolean holdsItem(Player player, ItemMatcher matcher, Condition.Hand hand) {
        return switch (hand) {
            case MAIN -> matches(player.getInventory().getItemInMainHand(), matcher);
            case OFF -> matches(player.getInventory().getItemInOffHand(), matcher);
            case EITHER -> matches(player.getInventory().getItemInMainHand(), matcher)
                || matches(player.getInventory().getItemInOffHand(), matcher);
        };
    }

    @Override
    public int countItems(Player player, ItemMatcher matcher) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (matches(stack, matcher)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @Override
    public boolean holdsKey(Player player, UUID keyId) {
        return KeyItems.holds(player, keyId);
    }

    @Override
    public String entityTypeKey(Entity traveler) {
        return traveler.getType().getKey().toString().toLowerCase(Locale.ROOT);
    }

    @Override
    public String dialedAddress(LocalPortal portal) {
        return Dialer.dialedAddress(portal);
    }

    /** True when the stack satisfies the matcher's material, PDC identity and exact-meta requirements. */
    static boolean matches(ItemStack stack, ItemMatcher matcher) {
        if (stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        if (!matcher.material().isEmpty() && !stack.getType().name().equals(matcher.material())) {
            return false;
        }
        if (matcher.identity() != null && !matcher.identity().equals(KeyItems.identity(stack))) {
            return false;
        }
        return !matcher.exactMeta() || stack.hasItemMeta();
    }

    private static boolean probePlaceholderApi() {
        try {
            Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }
}
