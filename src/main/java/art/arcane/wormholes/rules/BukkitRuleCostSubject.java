package art.arcane.wormholes.rules;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.wormholes.Wormholes;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BukkitRuleCostSubject(Player player) implements RuleCostReservation.Subject<ItemStack> {
    public BukkitRuleCostSubject {
        Objects.requireNonNull(player);
    }

    @Override
    public int level() {
        return player.getLevel();
    }

    @Override
    public void setLevel(int level) {
        player.setLevel(level);
    }

    @Override
    public float experienceProgress() {
        return player.getExp();
    }

    @Override
    public void giveExperience(int points) {
        player.giveExp(points);
    }

    @Override
    public int food() {
        return player.getFoodLevel();
    }

    @Override
    public void setFood(int food) {
        player.setFoodLevel(food);
    }

    @Override
    public double health() {
        return player.getHealth();
    }

    @Override
    @SuppressWarnings("deprecation")
    public double maximumHealth() {
        return player.getMaxHealth();
    }

    @Override
    public void setHealth(double health) {
        player.setHealth(health);
    }

    @Override
    public List<ItemStack> inventory() {
        PlayerInventory inventory = player.getInventory();
        return inventory == null ? List.of() : Arrays.asList(inventory.getContents());
    }

    @Override
    public boolean matches(ItemStack stack, ItemMatcher matcher) {
        return BukkitRulesEnvironment.matches(stack, matcher);
    }

    @Override
    public int count(ItemStack stack) {
        return stack.getAmount();
    }

    @Override
    public void setCount(ItemStack stack, int count) {
        stack.setAmount(count);
    }

    @Override
    public ItemStack copy(ItemStack stack, int count) {
        ItemStack copy = stack.clone();
        copy.setAmount(count);
        return copy;
    }

    @Override
    public int damage(ItemStack stack) {
        return stack.getItemMeta() instanceof Damageable damageable ? damageable.getDamage() : 0;
    }

    @Override
    public int maximumDamage(ItemStack stack) {
        return stack.getItemMeta() instanceof Damageable ? stack.getType().getMaxDurability() : 0;
    }

    @Override
    public void setDamage(ItemStack stack, int damage) {
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(damage);
            stack.setItemMeta(meta);
        }
    }

    @Override
    public boolean canAffordCurrency(BigDecimal amount) {
        return Wormholes.vaultEconomy != null && Wormholes.vaultEconomy.canAfford(player, amount.doubleValue());
    }

    @Override
    public RuleCostReservation.CurrencyCharge withdrawCurrency(BigDecimal amount) {
        VaultEconomy economy = Wormholes.vaultEconomy;
        if (economy == null || !economy.isAvailable()) {
            return null;
        }
        VaultEconomy.ChargeResult result = economy.withdraw(player, amount.doubleValue(), "wormholes-rule-cost");
        return result.successful() ? new CurrencyCharge(result.charge()) : null;
    }

    @Override
    public ItemStack findTicket(UUID id) {
        return KeyItems.find(player, id);
    }

    @Override
    public int remainingUses(ItemStack ticket) {
        return KeyItems.remainingUses(ticket);
    }

    @Override
    public boolean spendTicket(ItemStack ticket, int uses) {
        return KeyItems.spend(player, ticket, uses);
    }

    @Override
    public void refundTicket(UUID id, int uses) {
        KeyItems.refund(player, id, uses);
    }

    @Override
    public void returnItems(List<ItemStack> stacks) {
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

    private record CurrencyCharge(VaultEconomy.Charge charge) implements RuleCostReservation.CurrencyCharge {
        @Override
        public void commit() {
            charge.commit();
        }

        @Override
        public void refund() {
            charge.refund();
        }
    }
}
