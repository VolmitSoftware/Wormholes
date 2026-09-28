package art.arcane.wormholes;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.item.ItemStackAccess;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;

public final class ItemExchangePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        getServer().getScheduler().runTask(this, this::exchange);
    }

    private void exchange() {
        try {
            ItemStack expected = new ItemStack(Material.DIAMOND_PICKAXE);
            ItemMeta metadata = expected.getItemMeta();
            metadata.setDisplayName("Wormholes exchange");
            metadata.setLore(List.of("Across platforms", "Exact components"));
            metadata.addEnchant(Enchantment.UNBREAKING, 3, true);
            metadata.getPersistentDataContainer().set(new NamespacedKey("wormholes", "exchange"), PersistentDataType.STRING, "preserved");
            expected.setItemMeta(metadata);
            ItemStackAccess codec = NativeAdapters.require(ItemStackAccess.class);
            Path directory = getDataFolder().toPath();
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("outbound.txt"), Base64.getEncoder().encodeToString(codec.encode(expected)));
            if (!expected.isSimilar(codec.decode(codec.encode(expected)))) {
                throw new IllegalStateException("Bukkit item roundtrip changed components");
            }
            Path returned = directory.resolve("inbound.txt");
            if (Files.isRegularFile(returned)) {
                ItemStack actual = codec.decode(Base64.getDecoder().decode(Files.readString(returned).trim()));
                if (!expected.isSimilar(actual) || actual.getAmount() != expected.getAmount()) {
                    throw new IllegalStateException("Native item exchange changed components");
                }
                getLogger().info("WORMHOLES_ITEM_EXCHANGE_PASS native_to_bukkit exact_components");
            }
            getLogger().info("WORMHOLES_ITEM_EXCHANGE_PASS bukkit_encode roundtrip");
        } catch (IOException | RuntimeException failure) {
            getLogger().log(Level.SEVERE, "Wormholes item exchange failed", failure);
        }
    }
}
