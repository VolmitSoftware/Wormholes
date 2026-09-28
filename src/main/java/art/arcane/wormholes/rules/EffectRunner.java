package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesAudience;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.UUID;

/**
 * Runs a matched rule's arrival effects. Command effects are the privilege surface: a console command runs
 * only when the config allows it and the author still holds {@code wormholes.admin}; anything else runs as the
 * traveler, with their own permissions.
 */
final class EffectRunner {
    private static final String AUTHOR_PERMISSION = "wormholes.admin";

    private EffectRunner() {
    }

    static void run(LocalPortal destination, Entity traveler, Location arrival, List<Effect> effects) {
        RuleEffects.run(new Host(destination, traveler, arrival), effects);
    }

    private static void potion(Player player, Effect.Potion effect) {
        if (player == null) {
            return;
        }
        PotionEffectType type = PotionEffectType.getByName(effect.type());
        if (type == null) {
            return;
        }
        if (effect.clear()) {
            player.removePotionEffect(type);
            return;
        }
        player.addPotionEffect(new PotionEffect(type, effect.ticks(), effect.amplifier()));
    }

    /**
     * Arrival effects run on the traveler's own region thread, which is where a player command belongs
     * and is not where a console command does: the console owns no region, so its dispatch goes global.
     */
    private static void dispatchAsConsole(String line) {
        Runnable dispatch = () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        if (Wormholes.instance == null || !FoliaScheduler.runGlobal(Wormholes.instance, dispatch)) {
            dispatch.run();
        }
    }

    private static boolean authorMayRunConsoleCommands(UUID authorId) {
        if (authorId == null) {
            return false;
        }
        Player author = Bukkit.getPlayer(authorId);
        if (author != null) {
            return author.hasPermission(AUTHOR_PERMISSION);
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(authorId);
        return offline.isOp();
    }

    private static void sound(Location arrival, Effect.Sound effect) {
        World world = arrival == null ? null : arrival.getWorld();
        if (world == null || effect.key().isEmpty()) {
            return;
        }
        world.playSound(arrival, effect.key(), effect.volume(), effect.pitch());
    }
    private record Host(LocalPortal destination, Entity traveler, Location arrival) implements RuleEffects.Host {
        @Override
        public UUID travelerId() { return traveler.getUniqueId(); }
        @Override
        public UUID destinationId() { return destination.getId(); }
        @Override
        public long nowMillis() { return System.currentTimeMillis(); }
        @Override
        public boolean isPlayer() { return traveler instanceof Player; }
        @Override
        public String playerName() { return traveler.getName(); }
        @Override
        public void scaleVelocity(double multiplier) { traveler.setVelocity(traveler.getVelocity().multiply(multiplier)); }
        @Override
        public void potion(Effect.Potion effect) { EffectRunner.potion((Player) traveler, effect); }
        @Override
        public void message(String literal, TextKey key) {
            Player player = (Player) traveler;
            WormholesAudience.sendMessage(player, key == null
                ? LegacyComponentSerializer.legacyAmpersand().deserialize(literal)
                : Wormholes.text().component(player, key, MessageArgs.empty()));
        }
        @Override
        public void sound(Effect.Sound effect) { EffectRunner.sound(arrival, effect); }
        @Override
        public boolean consoleCommandsEnabled() { return RulesLimits.config().commandEffectsConsoleAllowed; }
        @Override
        public boolean authorMayRunConsoleCommands(UUID authorId) { return EffectRunner.authorMayRunConsoleCommands(authorId); }
        @Override
        public void consoleCommandRefused(UUID authorId) {
            Wormholes.w("rules console command refused: author " + authorId + " is no longer an administrator");
        }
        @Override
        public void command(String line, boolean console) {
            if (console) {
                dispatchAsConsole(line);
            } else {
                ((Player) traveler).performCommand(line);
            }
        }
    }
}
