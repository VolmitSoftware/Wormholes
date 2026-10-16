package art.arcane.wormholes.menu;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesAudience;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class BukkitMenuCaptures implements MenuCaptures, Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Map<UUID, DropCapture> drops = new ConcurrentHashMap<>();

    @Override
    public void prompt(MenuViewer viewer, List<String> promptLines, Consumer<String> submitted, Runnable cancelled) {
        Player player = BukkitMenuViewer.player(viewer);
        send(player, promptLines);
        Wormholes.awaitChatInput(player, input -> {
            if (input == null || isCancelInput(player, input)) {
                cancelled.run();
                return;
            }
            submitted.accept(input);
        });
    }

    @Override
    public void dropItem(MenuViewer viewer, List<String> promptLines, Consumer<CapturedItem> captured, Runnable cancelled) {
        Player player = BukkitMenuViewer.player(viewer);
        DropCapture previous = drops.put(player.getUniqueId(), new DropCapture(captured, cancelled));
        if (previous != null) {
            previous.cancelled().run();
        }
        send(player, promptLines);
    }

    @Override
    public Optional<CapturedItem> heldItem(MenuViewer viewer) {
        ItemStack held = BukkitMenuViewer.player(viewer).getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return Optional.empty();
        }
        return Optional.of(new BukkitCapturedItem(held));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void on(PlayerDropItemEvent event) {
        DropCapture capture = drops.remove(event.getPlayer().getUniqueId());
        if (capture == null) {
            return;
        }
        event.setCancelled(true);
        capture.captured().accept(new BukkitCapturedItem(event.getItemDrop().getItemStack()));
    }

    @EventHandler
    public void on(PlayerQuitEvent event) {
        drops.remove(event.getPlayer().getUniqueId());
    }

    private static boolean isCancelInput(Player player, String input) {
        String cancelWord = LanguageAudience.call(player.getUniqueId(), () -> Wormholes.text().plain(WormholesMessages.PORTAL_INPUT_CANCEL));
        return input.equalsIgnoreCase(cancelWord);
    }

    private static void send(Player player, List<String> lines) {
        for (String line : lines) {
            WormholesAudience.sendMessage(player, LEGACY.deserialize(line));
        }
    }

    private record DropCapture(Consumer<CapturedItem> captured, Runnable cancelled) {
    }
}
