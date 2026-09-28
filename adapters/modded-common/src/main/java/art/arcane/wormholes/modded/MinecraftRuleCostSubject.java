package art.arcane.wormholes.modded;

import art.arcane.wormholes.rules.ItemMatcher;
import art.arcane.wormholes.rules.KeyItemUses;
import art.arcane.wormholes.rules.RuleCostReservation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record MinecraftRuleCostSubject(ServerPlayer player, Currency currency)
    implements RuleCostReservation.Subject<ItemStack>, KeyItemUses.Host<ItemStack> {
    public MinecraftRuleCostSubject {
        Objects.requireNonNull(player);
    }

    public static ItemStack mintKey(UUID portalId, int uses) {
        ItemStack item = new ItemStack(Items.TRIPWIRE_HOOK);
        CompoundTag data = new CompoundTag();
        data.putString("wormholes:key", portalId.toString());
        data.putString("wormholes:key-portal", portalId.toString());
        data.putInt("wormholes:key-uses", Math.max(0, uses));
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return item;
    }

    public static UUID identity(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        String value = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            .getStringOr("wormholes:key", "");
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    @Override
    public int level() {
        return player.experienceLevel;
    }

    @Override
    public void setLevel(int level) {
        player.giveExperienceLevels(level - player.experienceLevel);
    }

    @Override
    public float experienceProgress() {
        return player.experienceProgress;
    }

    @Override
    public void giveExperience(int points) {
        player.giveExperiencePoints(points);
    }

    @Override
    public int food() {
        return player.getFoodData().getFoodLevel();
    }

    @Override
    public void setFood(int food) {
        player.getFoodData().setFoodLevel(food);
    }

    @Override
    public double health() {
        return player.getHealth();
    }

    @Override
    public double maximumHealth() {
        return player.getMaxHealth();
    }

    @Override
    public void setHealth(double health) {
        player.setHealth((float) health);
    }

    @Override
    public List<ItemStack> inventory() {
        List<ItemStack> items = new ArrayList<>(player.getInventory().getContainerSize());
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            items.add(player.getInventory().getItem(slot));
        }
        return items;
    }

    @Override
    public boolean matches(ItemStack stack, ItemMatcher matcher) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!matcher.material().isEmpty() && !BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()
            .toUpperCase(Locale.ROOT).equals(matcher.material())) {
            return false;
        }
        if (matcher.identity() != null && !matcher.identity().equals(identity(stack))) {
            return false;
        }
        return !matcher.exactMeta() || !stack.getComponentsPatch().isEmpty();
    }

    @Override
    public int count(ItemStack stack) {
        return stack.getCount();
    }

    @Override
    public void setCount(ItemStack stack, int count) {
        stack.setCount(count);
        player.getInventory().setChanged();
    }

    @Override
    public ItemStack copy(ItemStack stack, int count) {
        return stack.copyWithCount(count);
    }

    @Override
    public int damage(ItemStack stack) {
        return stack.getDamageValue();
    }

    @Override
    public int maximumDamage(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() : 0;
    }

    @Override
    public void setDamage(ItemStack stack, int damage) {
        stack.setDamageValue(damage);
        player.getInventory().setChanged();
    }

    @Override
    public boolean canAffordCurrency(BigDecimal amount) {
        return currency != null && currency.canAfford(player, amount);
    }

    @Override
    public RuleCostReservation.CurrencyCharge withdrawCurrency(BigDecimal amount) {
        return currency == null ? null : currency.withdraw(player, amount);
    }

    @Override
    public ItemStack findTicket(UUID id) {
        return find(id);
    }

    @Override
    public int remainingUses(ItemStack ticket) {
        return remaining(ticket);
    }

    @Override
    public boolean spendTicket(ItemStack ticket, int uses) {
        return KeyItemUses.spend(this, ticket, uses);
    }

    @Override
    public void refundTicket(UUID id, int uses) {
        KeyItemUses.refund(this, id, uses);
    }

    @Override
    public ItemStack find(UUID identity) {
        for (ItemStack item : inventory()) {
            if (identity.equals(identity(item))) {
                return item;
            }
        }
        return null;
    }

    @Override
    public int remaining(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
            .getIntOr("wormholes:key-uses", 0);
    }

    @Override
    public void setRemaining(ItemStack stack, int remaining) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> data.putInt("wormholes:key-uses", remaining));
        player.getInventory().setChanged();
    }

    @Override
    public void remove(ItemStack stack) {
        stack.setCount(0);
        player.getInventory().setChanged();
    }

    @Override
    public void give(UUID identity, int uses) {
        returnItems(List.of(mintKey(identity, uses)));
    }

    @Override
    public void returnItems(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            ItemStack remaining = stack.copy();
            player.getInventory().add(remaining);
            if (!remaining.isEmpty()) {
                player.drop(remaining, false);
            }
        }
        player.getInventory().setChanged();
    }

    public interface Currency {
        boolean canAfford(ServerPlayer player, BigDecimal amount);
        RuleCostReservation.CurrencyCharge withdraw(ServerPlayer player, BigDecimal amount);
    }
}
