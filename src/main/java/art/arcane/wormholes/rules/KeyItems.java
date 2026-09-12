package art.arcane.wormholes.rules;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Portal keys and tickets: ordinary items carrying a PDC identity. A key is matched by
 * {@link Condition.KeyItem}; a ticket is spent by {@link Cost.Ticket}, which decrements its remaining uses and
 * consumes the item when they run out.
 */
public final class KeyItems {
    public static final NamespacedKey IDENTITY = new NamespacedKey("wormholes", "key");
    public static final NamespacedKey PORTAL = new NamespacedKey("wormholes", "key-portal");
    public static final NamespacedKey USES = new NamespacedKey("wormholes", "key-uses");

    private static final Material KEY_MATERIAL = Material.TRIPWIRE_HOOK;

    private KeyItems() {
    }

    /** A key for one portal. Uses below one mean the key never runs out. */
    public static ItemStack mint(UUID portalId, int uses) {
        ItemStack stack = new ItemStack(KEY_MATERIAL, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(IDENTITY, PersistentDataType.STRING, portalId.toString());
        data.set(PORTAL, PersistentDataType.STRING, portalId.toString());
        data.set(USES, PersistentDataType.INTEGER, Integer.valueOf(Math.max(0, uses)));
        stack.setItemMeta(meta);
        return stack;
    }

    /** True when the player carries any item stamped with {@code keyId}. */
    public static boolean holds(Player player, UUID keyId) {
        return find(player, keyId) != null;
    }

    /** The first carried item stamped with {@code keyId}, or null. */
    public static ItemStack find(Player player, UUID keyId) {
        if (player == null || keyId == null) {
            return null;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (keyId.equals(identity(stack))) {
                return stack;
            }
        }
        return null;
    }

    /** The identity stamped on a stack, or null when it carries none. */
    public static UUID identity(ItemStack stack) {
        String stored = read(stack, IDENTITY);
        if (stored == null) {
            return null;
        }
        try {
            return UUID.fromString(stored);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Remaining uses, or zero for an unlimited key. */
    public static int remainingUses(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer uses = meta.getPersistentDataContainer().get(USES, PersistentDataType.INTEGER);
        return uses == null ? 0 : uses.intValue();
    }

    /**
     * Takes {@code uses} from a limited-use item, destroying it when it is spent. Unlimited items are
     * untouched. Returns false when the item does not have that many uses left.
     */
    public static boolean spend(Player player, ItemStack stack, int uses) {
        int remaining = remainingUses(stack);
        if (remaining <= 0) {
            return true;
        }
        if (remaining < uses) {
            return false;
        }
        int left = remaining - uses;
        if (left <= 0) {
            player.getInventory().removeItem(stack);
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(USES, PersistentDataType.INTEGER, Integer.valueOf(left));
        stack.setItemMeta(meta);
        return true;
    }

    /** Puts back uses a rolled-back reservation had taken. */
    public static void refund(Player player, UUID identity, int uses) {
        ItemStack existing = find(player, identity);
        if (existing == null) {
            player.getInventory().addItem(mint(identity, uses));
            return;
        }
        int remaining = remainingUses(existing);
        if (remaining <= 0) {
            return;
        }
        ItemMeta meta = existing.getItemMeta();
        meta.getPersistentDataContainer().set(USES, PersistentDataType.INTEGER, Integer.valueOf(remaining + uses));
        existing.setItemMeta(meta);
    }

    private static String read(ItemStack stack, NamespacedKey key) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }
}
