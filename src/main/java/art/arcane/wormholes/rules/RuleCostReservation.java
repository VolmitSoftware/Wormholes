package art.arcane.wormholes.rules;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.wormholes.Wormholes;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Takes a matched rule's costs before the built-in portal travel cost runs. Reserving is all-or-nothing: the
 * first cost the traveler cannot pay rolls back everything already taken and names itself, so a refusal can
 * say what was missing. The traversal commits on departure and refunds on any rejection.
 *
 * <p>Charges are the one cost that leaves the pool alone until commit: the gate has already refused the
 * crossing when the pool cannot cover it, and commit runs on the same tick as that check.</p>
 */
public final class RuleCostReservation {
    private static final double MINIMUM_HEALTH = 1.0D;

    private final List<Entry> entries;
    private final Cost failedCost;
    private boolean settled;

    private RuleCostReservation(List<Entry> entries, Cost failedCost) {
        this.entries = entries;
        this.failedCost = failedCost;
    }

    /** Takes every cost, or nothing at all. */
    public static RuleCostReservation reserve(Player player, List<Cost> costs, ChargePool charges) {
        List<Entry> taken = new ArrayList<>(costs.size());
        for (Cost cost : costs) {
            Entry entry = take(player, cost, charges);
            if (entry == null) {
                for (int i = taken.size() - 1; i >= 0; i--) {
                    taken.get(i).refund();
                }
                return new RuleCostReservation(List.of(), cost);
            }
            taken.add(entry);
        }
        return new RuleCostReservation(taken, null);
    }

    /** Whether the traveler could pay every cost right now, without taking anything. */
    public static boolean canAfford(Player player, List<Cost> costs, ChargePool charges) {
        return firstUnaffordable(player, costs, charges) == null;
    }

    /** The first cost the traveler cannot pay right now, null when they can pay all of them. */
    public static Cost firstUnaffordable(Player player, List<Cost> costs, ChargePool charges) {
        for (Cost cost : costs) {
            if (!canAfford(player, cost, charges)) {
                return cost;
            }
        }
        return null;
    }

    private static boolean canAfford(Player player, Cost cost, ChargePool charges) {
        return switch (cost) {
            case Cost.Xp value -> value.levels() ? player.getLevel() >= value.amount() : experiencePoints(player) >= value.amount();
            case Cost.Hunger value -> player.getFoodLevel() >= value.points();
            case Cost.Health value -> player.getHealth() - value.points() >= MINIMUM_HEALTH;
            case Cost.Charge value -> charges.canConsume(value.count(), System.currentTimeMillis());
            case Cost.Vault value -> Wormholes.vaultEconomy != null && Wormholes.vaultEconomy.canAfford(player, value.amount().doubleValue());
            case Cost.Item value -> countMatching(player, value.matcher()) >= value.quantity();
            case Cost.Durability value -> hasRepairableMatch(player, value);
            case Cost.Ticket value -> hasTicket(player, value);
        };
    }

    private static int countMatching(Player player, ItemMatcher matcher) {
        PlayerInventory inventory = player.getInventory();
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (BukkitRulesEnvironment.matches(stack, matcher)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static boolean hasRepairableMatch(Player player, Cost.Durability cost) {
        PlayerInventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        for (ItemStack stack : inventory.getContents()) {
            if (!BukkitRulesEnvironment.matches(stack, cost.matcher()) || !(stack.getItemMeta() instanceof Damageable damageable)) {
                continue;
            }
            int maximum = stack.getType().getMaxDurability();
            if (maximum > 0 && damageable.getDamage() + cost.points() < maximum) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTicket(Player player, Cost.Ticket cost) {
        ItemStack ticket = KeyItems.find(player, cost.ticketId());
        if (ticket == null) {
            return false;
        }
        int remaining = KeyItems.remainingUses(ticket);
        return remaining <= 0 || remaining >= cost.uses();
    }

    public boolean successful() {
        return failedCost == null;
    }

    /** The cost that could not be paid, null when the reservation succeeded. */
    public Cost failedCost() {
        return failedCost;
    }

    public void commit() {
        if (settled) {
            return;
        }
        settled = true;
        for (Entry entry : entries) {
            entry.commit();
        }
    }

    public void refund() {
        if (settled) {
            return;
        }
        settled = true;
        for (int i = entries.size() - 1; i >= 0; i--) {
            entries.get(i).refund();
        }
    }

    private static Entry take(Player player, Cost cost, ChargePool charges) {
        return switch (cost) {
            case Cost.Xp value -> takeExperience(player, value);
            case Cost.Hunger value -> takeHunger(player, value);
            case Cost.Health value -> takeHealth(player, value);
            case Cost.Charge value -> charges.canConsume(value.count(), System.currentTimeMillis())
                ? new ChargeEntry(charges, value.count()) : null;
            case Cost.Vault value -> takeVault(player, value);
            case Cost.Item value -> takeItems(player, value);
            case Cost.Durability value -> takeDurability(player, value);
            case Cost.Ticket value -> takeTicket(player, value);
        };
    }

    private static Entry takeExperience(Player player, Cost.Xp cost) {
        if (cost.levels()) {
            if (player.getLevel() < cost.amount()) {
                return null;
            }
            player.setLevel(player.getLevel() - cost.amount());
            return new LevelEntry(player, cost.amount());
        }
        if (experiencePoints(player) < cost.amount()) {
            return null;
        }
        player.giveExp(-cost.amount());
        return new PointsEntry(player, cost.amount());
    }

    private static Entry takeHunger(Player player, Cost.Hunger cost) {
        if (player.getFoodLevel() < cost.points()) {
            return null;
        }
        player.setFoodLevel(player.getFoodLevel() - cost.points());
        return new HungerEntry(player, cost.points());
    }

    private static Entry takeHealth(Player player, Cost.Health cost) {
        double remaining = player.getHealth() - cost.points();
        if (remaining < MINIMUM_HEALTH) {
            return null;
        }
        player.setHealth(remaining);
        return new HealthEntry(player, cost.points());
    }

    private static Entry takeVault(Player player, Cost.Vault cost) {
        VaultEconomy economy = Wormholes.vaultEconomy;
        if (economy == null || !economy.isAvailable()) {
            return null;
        }
        VaultEconomy.ChargeResult result = economy.withdraw(player, cost.amount().doubleValue(), "wormholes-rule-cost");
        return result.successful() ? new VaultEntry(result.charge()) : null;
    }

    private static Entry takeItems(Player player, Cost.Item cost) {
        PlayerInventory inventory = player.getInventory();
        if (inventory == null) {
            return null;
        }
        List<ItemStack> removed = new ArrayList<>();
        int outstanding = cost.quantity();
        for (ItemStack stack : inventory.getContents()) {
            if (outstanding <= 0) {
                break;
            }
            if (!BukkitRulesEnvironment.matches(stack, cost.matcher())) {
                continue;
            }
            int take = Math.min(outstanding, stack.getAmount());
            ItemStack copy = stack.clone();
            copy.setAmount(take);
            removed.add(copy);
            stack.setAmount(stack.getAmount() - take);
            outstanding -= take;
        }
        if (outstanding > 0) {
            returnItems(player, removed);
            return null;
        }
        return new ItemEntry(player, removed);
    }

    private static Entry takeDurability(Player player, Cost.Durability cost) {
        PlayerInventory inventory = player.getInventory();
        if (inventory == null) {
            return null;
        }
        for (ItemStack stack : inventory.getContents()) {
            if (!BukkitRulesEnvironment.matches(stack, cost.matcher())) {
                continue;
            }
            ItemMeta meta = stack.getItemMeta();
            if (!(meta instanceof Damageable damageable)) {
                continue;
            }
            int maximum = stack.getType().getMaxDurability();
            if (maximum <= 0 || damageable.getDamage() + cost.points() >= maximum) {
                continue;
            }
            damageable.setDamage(damageable.getDamage() + cost.points());
            stack.setItemMeta(meta);
            return new DurabilityEntry(stack, cost.points());
        }
        return null;
    }

    private static Entry takeTicket(Player player, Cost.Ticket cost) {
        ItemStack ticket = KeyItems.find(player, cost.ticketId());
        if (ticket == null || !KeyItems.spend(player, ticket, cost.uses())) {
            return null;
        }
        return new TicketEntry(player, cost.ticketId(), cost.uses());
    }

    /** Total experience points the traveler is carrying, from their level and progress bar. */
    static int experiencePoints(Player player) {
        int level = player.getLevel();
        int base = level <= 16 ? level * level + 6 * level
            : level <= 31 ? (int) (2.5D * level * level - 40.5D * level + 360.0D)
            : (int) (4.5D * level * level - 162.5D * level + 2220.0D);
        int toNext = level <= 15 ? 2 * level + 7 : level <= 30 ? 5 * level - 38 : 9 * level - 158;
        return base + Math.round(player.getExp() * toNext);
    }

    /** One taken cost and how to put it back. */
    private interface Entry {
        default void commit() {
        }

        void refund();
    }

    private record LevelEntry(Player player, int levels) implements Entry {
        @Override
        public void refund() {
            player.setLevel(player.getLevel() + levels);
        }
    }

    private record PointsEntry(Player player, int points) implements Entry {
        @Override
        public void refund() {
            player.giveExp(points);
        }
    }

    private record HungerEntry(Player player, int points) implements Entry {
        @Override
        public void refund() {
            player.setFoodLevel(Math.min(20, player.getFoodLevel() + points));
        }
    }

    private record HealthEntry(Player player, double points) implements Entry {
        @Override
        public void refund() {
            player.setHealth(Math.min(player.getHealth() + points, maximumHealth(player)));
        }
    }

    /**
     * The traveler's own maximum, not the vanilla twenty: a refund must never take health away from a
     * player whose maximum is scaled. {@code getMaxHealth} reads the same max-health attribute and,
     * unlike the attribute constant, needs no registry, so it is the one that works everywhere.
     */
    @SuppressWarnings("deprecation")
    static double maximumHealth(Player player) {
        double maximum = player.getMaxHealth();
        return maximum > 0.0D ? maximum : player.getHealth();
    }

    private record ChargeEntry(ChargePool charges, int count) implements Entry {
        @Override
        public void commit() {
            charges.consume(count, System.currentTimeMillis());
        }

        @Override
        public void refund() {
        }
    }

    private record VaultEntry(VaultEconomy.Charge charge) implements Entry {
        @Override
        public void commit() {
            charge.commit();
        }

        @Override
        public void refund() {
            charge.refund();
        }
    }

    private record ItemEntry(Player player, List<ItemStack> removed) implements Entry {
        @Override
        public void refund() {
            returnItems(player, removed);
        }
    }

    /** Gives items back, dropping whatever the inventory has no room for rather than deleting it. */
    static void returnItems(Player player, List<ItemStack> stacks) {
        PlayerInventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            for (ItemStack leftover : inventory.addItem(stack).values()) {
                World world = player.getWorld();
                if (world != null) {
                    world.dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }
    }

    private record DurabilityEntry(ItemStack stack, int points) implements Entry {
        @Override
        public void refund() {
            ItemMeta meta = stack.getItemMeta();
            if (meta instanceof Damageable damageable) {
                damageable.setDamage(Math.max(0, damageable.getDamage() - points));
                stack.setItemMeta(meta);
            }
        }
    }

    private record TicketEntry(Player player, UUID ticketId, int uses) implements Entry {
        @Override
        public void refund() {
            KeyItems.refund(player, ticketId, uses);
        }
    }
}
