package art.arcane.wormholes.door;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Unlocks door recipes in the vanilla recipe book.
 *
 * <p>A plugin recipe reaches the client locked: vanilla recipes unlock through
 * advancement triggers that a Bukkit recipe has none of, so without this a
 * player never sees a dimensional door in the recipe browser, and with the
 * {@code doLimitedCrafting} gamerule on cannot craft one at all.</p>
 */
public final class DoorRecipeBook {
    private DoorRecipeBook() {
    }

    /**
     * Decides what one player's book should hold.
     *
     * @param registered every recipe key currently on the server
     * @param permitted whether this player may craft door products at all
     */
    public static Plan plan(Collection<NamespacedKey> registered, boolean permitted) {
        if (registered == null || registered.isEmpty()) {
            return Plan.empty();
        }
        List<NamespacedKey> keys = new ArrayList<>(registered.size());
        for (NamespacedKey key : registered) {
            if (key != null) {
                keys.add(key);
            }
        }
        return permitted ? new Plan(keys, List.of()) : new Plan(List.of(), keys);
    }

    /**
     * Applies a plan, skipping keys the server no longer knows.
     *
     * @param known reports whether a key still resolves to a live recipe
     */
    public static void synchronize(Player player, Plan plan, Predicate<NamespacedKey> known) {
        Objects.requireNonNull(known, "known");
        if (player == null || plan == null || !player.isOnline()) {
            return;
        }
        List<NamespacedKey> undiscover = live(plan.undiscover(), known);
        if (!undiscover.isEmpty()) {
            player.undiscoverRecipes(undiscover);
        }
        List<NamespacedKey> discover = live(plan.discover(), known);
        if (!discover.isEmpty()) {
            player.discoverRecipes(discover);
        }
    }

    private static List<NamespacedKey> live(List<NamespacedKey> keys, Predicate<NamespacedKey> known) {
        List<NamespacedKey> filtered = new ArrayList<>(keys.size());
        for (NamespacedKey key : keys) {
            if (known.test(key)) {
                filtered.add(key);
            }
        }
        return filtered;
    }

    public record Plan(List<NamespacedKey> discover, List<NamespacedKey> undiscover) {
        public Plan {
            discover = List.copyOf(Objects.requireNonNull(discover, "discover"));
            undiscover = List.copyOf(Objects.requireNonNull(undiscover, "undiscover"));
        }

        public static Plan empty() {
            return new Plan(List.of(), List.of());
        }

        public boolean isEmpty() {
            return discover.isEmpty() && undiscover.isEmpty();
        }
    }
}
