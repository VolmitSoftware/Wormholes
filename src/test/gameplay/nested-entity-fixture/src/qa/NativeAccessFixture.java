package qa;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.block.BlockEntityAccess;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketAccess;
import art.arcane.volmlib.nativelib.chunk.ChunkSendRateAccessor;
import art.arcane.volmlib.nativelib.chunk.ChunkSendRateLimit;
import art.arcane.volmlib.nativelib.entity.EntityVisibilityAccess;
import art.arcane.volmlib.nativelib.map.MapPixelsAccess;
import art.arcane.volmlib.nativelib.map.NativeMapSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;

public final class NativeAccessFixture {
    private NativeAccessFixture() {
    }

    public static void verify(Player player, Plugin plugin) {
        Block block = player.getLocation().add(2, 0, 0).getBlock();
        BlockState original = block.getState();
        Item item = null;
        try {
            BlockEntityAccess blocks = NativeAdapters.require(BlockEntityAccess.class);
            block.setType(Material.CHEST, false);
            Chest chest = (Chest) block.getState();
            chest.setLock("native-access-key");
            chest.getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
            chest.update(true, false);
            require(blocks.snapshotNbt(chest).length > 20, "Block entity NBT was empty");
            require(!blocks.matchesLock(chest, new ItemStack(Material.STICK)), "Unmatched key opened lock");
            ItemStack key = new ItemStack(Material.STICK);
            ItemMeta meta = key.getItemMeta();
            meta.setDisplayName("native-access-key");
            key.setItemMeta(meta);
            require(blocks.matchesLock(chest, key), "Named key failed native lock");

            MapView view = Bukkit.createMap(player.getWorld());
            NativeMapSnapshot map = NativeAdapters.require(MapPixelsAccess.class).capture(view).orElseThrow();
            require(map.sourceMapId() == view.getId() && map.pixels().length == 16384, "Map snapshot lost data");
            byte before = map.pixels()[0];
            byte[] changed = map.pixels();
            changed[0] = (byte) (before + 1);
            require(map.pixels()[0] == before, "Map snapshot leaked its pixel array");

            EntityVisibilityAccess visibility = NativeAdapters.require(EntityVisibilityAccess.class);
            item = player.getWorld().dropItem(player.getLocation().add(0, 2, 0), new ItemStack(Material.PAPER));
            require(visibility.isVisible(player, item.getUniqueId(), true, null), "Visible entity was hidden");
            player.hideEntity(plugin, item);
            require(!visibility.isVisible(player, item.getUniqueId(), true, null), "Hidden entity was visible");
            require(visibility.isVisible(player, item.getUniqueId(), true, plugin), "Excluded owner still hid entity");
            player.showEntity(plugin, item);
            require(visibility.isVisible(player, item.getUniqueId(), true, null), "Shown entity remained hidden");

            ChunkPacketAccess chunks = NativeAdapters.require(ChunkPacketAccess.class);
            require(chunks.supported(), "Chunk packet provider was unavailable");
            require(chunks.sendChunk(player, player.getWorld(), player.getLocation().getBlockX() >> 4,
                    player.getLocation().getBlockZ() >> 4), "Loaded chunk was not sent");
            ChunkSendRateAccessor rates = NativeAdapters.require(ChunkSendRateAccessor.class);
            require(rates.available(), "Native chunk rate configuration was unavailable");
            require(rates.read(ChunkSendRateLimit.SEND).orElseThrow() >= 1000.0D,
                    "Chunk send rate tuner did not apply its target");
            require(rates.read(ChunkSendRateLimit.LOAD).orElseThrow() >= 1000.0D,
                    "Chunk load rate tuner did not apply its target");
            player.sendMessage("NATIVE blocks=true locks=true maps=true visibility=true chunks=true");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Native access runtime assertion failed", exception);
        } finally {
            if (item != null) {
                player.showEntity(plugin, item);
                item.remove();
            }
            original.update(true, false);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
