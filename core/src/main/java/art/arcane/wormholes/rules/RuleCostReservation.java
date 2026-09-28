package art.arcane.wormholes.rules;

import java.math.BigDecimal;
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
    public static <I> RuleCostReservation reserve(Subject<I> player, List<Cost> costs, ChargePool charges) {
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
    public static <I> boolean canAfford(Subject<I> player, List<Cost> costs, ChargePool charges) {
        return firstUnaffordable(player, costs, charges) == null;
    }

    /** The first cost the traveler cannot pay right now, null when they can pay all of them. */
    public static <I> Cost firstUnaffordable(Subject<I> player, List<Cost> costs, ChargePool charges) {
        for (Cost cost : costs) {
            if (!canAfford(player, cost, charges)) {
                return cost;
            }
        }
        return null;
    }

    private static <I> boolean canAfford(Subject<I> player, Cost cost, ChargePool charges) {
        return switch (cost) {
            case Cost.Xp value -> value.levels() ? player.level() >= value.amount() : experiencePoints(player) >= value.amount();
            case Cost.Hunger value -> player.food() >= value.points();
            case Cost.Health value -> player.health() - value.points() >= MINIMUM_HEALTH;
            case Cost.Charge value -> charges.canConsume(value.count(), System.currentTimeMillis());
            case Cost.Vault value -> player.canAffordCurrency(value.amount());
            case Cost.Item value -> countMatching(player, value.matcher()) >= value.quantity();
            case Cost.Durability value -> hasRepairableMatch(player, value);
            case Cost.Ticket value -> hasTicket(player, value);
        };
    }

    private static <I> int countMatching(Subject<I> player, ItemMatcher matcher) {
        List<I> inventory = player.inventory();
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (I stack : inventory) {
            if (player.matches(stack, matcher)) {
                total += player.count(stack);
            }
        }
        return total;
    }

    private static <I> boolean hasRepairableMatch(Subject<I> player, Cost.Durability cost) {
        List<I> inventory = player.inventory();
        if (inventory == null) {
            return false;
        }
        for (I stack : inventory) {
            if (!player.matches(stack, cost.matcher()) || player.maximumDamage(stack) <= 0) {
                continue;
            }
            int maximum = player.maximumDamage(stack);
            if (maximum > 0 && player.damage(stack) + cost.points() < maximum) {
                return true;
            }
        }
        return false;
    }

    private static <I> boolean hasTicket(Subject<I> player, Cost.Ticket cost) {
        I ticket = player.findTicket(cost.ticketId());
        if (ticket == null) {
            return false;
        }
        int remaining = player.remainingUses(ticket);
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

    private static <I> Entry take(Subject<I> player, Cost cost, ChargePool charges) {
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

    private static <I> Entry takeExperience(Subject<I> player, Cost.Xp cost) {
        if (cost.levels()) {
            if (player.level() < cost.amount()) {
                return null;
            }
            player.setLevel(player.level() - cost.amount());
            return new LevelEntry<>(player, cost.amount());
        }
        if (experiencePoints(player) < cost.amount()) {
            return null;
        }
        player.giveExperience(-cost.amount());
        return new PointsEntry<>(player, cost.amount());
    }

    private static <I> Entry takeHunger(Subject<I> player, Cost.Hunger cost) {
        if (player.food() < cost.points()) {
            return null;
        }
        player.setFood(player.food() - cost.points());
        return new HungerEntry<>(player, cost.points());
    }

    private static <I> Entry takeHealth(Subject<I> player, Cost.Health cost) {
        double remaining = player.health() - cost.points();
        if (remaining < MINIMUM_HEALTH) {
            return null;
        }
        player.setHealth(remaining);
        return new HealthEntry<>(player, cost.points());
    }

    private static <I> Entry takeVault(Subject<I> player, Cost.Vault cost) {
        CurrencyCharge charge = player.withdrawCurrency(cost.amount());
        return charge == null ? null : new VaultEntry(charge);
    }

    private static <I> Entry takeItems(Subject<I> player, Cost.Item cost) {
        List<I> inventory = player.inventory();
        if (inventory == null) {
            return null;
        }
        List<I> removed = new ArrayList<>();
        int outstanding = cost.quantity();
        for (I stack : inventory) {
            if (outstanding <= 0) {
                break;
            }
            if (!player.matches(stack, cost.matcher())) {
                continue;
            }
            int take = Math.min(outstanding, player.count(stack));
            I copy = player.copy(stack, take);
            removed.add(copy);
            player.setCount(stack, player.count(stack) - take);
            outstanding -= take;
        }
        if (outstanding > 0) {
            returnItems(player, removed);
            return null;
        }
        return new ItemEntry<>(player, removed);
    }

    private static <I> Entry takeDurability(Subject<I> player, Cost.Durability cost) {
        List<I> inventory = player.inventory();
        if (inventory == null) {
            return null;
        }
        for (I stack : inventory) {
            if (!player.matches(stack, cost.matcher())) {
                continue;
            }
            int maximum = player.maximumDamage(stack);
            if (maximum <= 0 || player.damage(stack) + cost.points() >= maximum) {
                continue;
            }
            player.setDamage(stack, player.damage(stack) + cost.points());
            return new DurabilityEntry<>(player, stack, cost.points());
        }
        return null;
    }

    private static <I> Entry takeTicket(Subject<I> player, Cost.Ticket cost) {
        I ticket = player.findTicket(cost.ticketId());
        if (ticket == null || !player.spendTicket(ticket, cost.uses())) {
            return null;
        }
        return new TicketEntry<>(player, cost.ticketId(), cost.uses());
    }

    /** Total experience points the traveler is carrying, from their level and progress bar. */
    static <I> int experiencePoints(Subject<I> player) {
        int level = player.level();
        int base = level <= 16 ? level * level + 6 * level
            : level <= 31 ? (int) (2.5D * level * level - 40.5D * level + 360.0D)
            : (int) (4.5D * level * level - 162.5D * level + 2220.0D);
        int toNext = level <= 15 ? 2 * level + 7 : level <= 30 ? 5 * level - 38 : 9 * level - 158;
        return base + Math.round(player.experienceProgress() * toNext);
    }

    /** One taken cost and how to put it back. */
    private interface Entry {
        default void commit() {
        }

        void refund();
    }

    private record LevelEntry<I>(Subject<I> player, int levels) implements Entry {
        @Override
        public void refund() {
            player.setLevel(player.level() + levels);
        }
    }

    private record PointsEntry<I>(Subject<I> player, int points) implements Entry {
        @Override
        public void refund() {
            player.giveExperience(points);
        }
    }

    private record HungerEntry<I>(Subject<I> player, int points) implements Entry {
        @Override
        public void refund() {
            player.setFood(Math.min(20, player.food() + points));
        }
    }

    private record HealthEntry<I>(Subject<I> player, double points) implements Entry {
        @Override
        public void refund() {
            player.setHealth(Math.min(player.health() + points, maximumHealth(player)));
        }
    }

    static <I> double maximumHealth(Subject<I> player) {
        double maximum = player.maximumHealth();
        return maximum > 0.0D ? maximum : player.health();
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

    private record VaultEntry(CurrencyCharge charge) implements Entry {
        @Override
        public void commit() {
            charge.commit();
        }

        @Override
        public void refund() {
            charge.refund();
        }
    }

    private record ItemEntry<I>(Subject<I> player, List<I> removed) implements Entry {
        @Override
        public void refund() {
            returnItems(player, removed);
        }
    }

    /** Gives items back, dropping whatever the inventory has no room for rather than deleting it. */
    static <I> void returnItems(Subject<I> player, List<I> stacks) {
        player.returnItems(stacks);
    }

    private record DurabilityEntry<I>(Subject<I> player, I stack, int points) implements Entry {
        @Override
        public void refund() {
            if (player.maximumDamage(stack) > 0) {
                player.setDamage(stack, Math.max(0, player.damage(stack) - points));
            }
        }
    }

    private record TicketEntry<I>(Subject<I> player, UUID ticketId, int uses) implements Entry {
        @Override
        public void refund() {
            player.refundTicket(ticketId, uses);
        }
    }

    public interface Subject<I> {
        int level();
        void setLevel(int level);
        float experienceProgress();
        void giveExperience(int points);
        int food();
        void setFood(int food);
        double health();
        double maximumHealth();
        void setHealth(double health);
        List<I> inventory();
        boolean matches(I stack, ItemMatcher matcher);
        int count(I stack);
        void setCount(I stack, int count);
        I copy(I stack, int count);
        int damage(I stack);
        int maximumDamage(I stack);
        void setDamage(I stack, int damage);
        boolean canAffordCurrency(BigDecimal amount);
        CurrencyCharge withdrawCurrency(BigDecimal amount);
        I findTicket(UUID id);
        int remainingUses(I ticket);
        boolean spendTicket(I ticket, int uses);
        void refundTicket(UUID id, int uses);
        void returnItems(List<I> stacks);
    }

    public interface CurrencyCharge {
        void commit();
        void refund();
    }
}
