package art.arcane.wormholes.modded;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MinecraftItemExchangeGameTest {
    private MinecraftItemExchangeGameTest() { }

    public static void run(GameTestHelper helper) {
        Path directory = helper.getLevel().getServer().getServerDirectory().resolve("config/wormholes/item-exchange");
        Path input = directory.resolve("inbound.txt");
        if (!Files.isRegularFile(input)) {
            return;
        }
        try {
            ItemStack item = MinecraftItemEncoding.decode(Files.readString(input).trim(), helper.getLevel().registryAccess());
            helper.assertTrue(item.is(Items.DIAMOND_PICKAXE) && item.getCount() == 1, "Bukkit item type or count changed");
            helper.assertTrue(item.getHoverName().getString().equals("Wormholes exchange"), "Bukkit item name changed");
            helper.assertTrue(item.get(DataComponents.LORE).lines().size() == 2, "Bukkit item lore changed");
            helper.assertTrue(item.getEnchantments().getLevel(helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING)) == 3, "Bukkit item enchantment changed");
            Files.writeString(directory.resolve("outbound.txt"), MinecraftItemEncoding.encode(item, helper.getLevel().registryAccess()));
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_ITEM_EXCHANGE_PASS bukkit_to_native exact_components");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
