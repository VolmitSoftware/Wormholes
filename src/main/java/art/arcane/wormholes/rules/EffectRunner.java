package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.RulesMessages;
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
        Player player = traveler instanceof Player value ? value : null;
        for (Effect effect : effects) {
            apply(destination, traveler, player, arrival, effect);
        }
    }

    private static void apply(LocalPortal destination, Entity traveler, Player player, Location arrival, Effect effect) {
        switch (effect) {
            case Effect.Velocity value -> traveler.setVelocity(traveler.getVelocity().multiply(value.multiplier()));
            case Effect.Potion value -> potion(player, value);
            case Effect.Command value -> command(player, value);
            case Effect.Message value -> message(player, value);
            case Effect.Sound value -> sound(arrival, value);
            case Effect.StampCooldown value -> PortalCooldowns.stamp(traveler.getUniqueId(), destination.getId(),
                value.group(), value.millis(), System.currentTimeMillis());
        }
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

    private static void command(Player player, Effect.Command effect) {
        if (player == null || effect.line().isEmpty()) {
            return;
        }
        String line = effect.line().replace("{player}", player.getName());
        if (!effect.asConsole()) {
            player.performCommand(line);
            return;
        }
        if (!RulesLimits.config().commandEffectsConsoleAllowed || !authorMayRunConsoleCommands(effect.authorId())) {
            Wormholes.w("rules console command refused: author " + effect.authorId() + " is no longer an administrator");
            return;
        }
        dispatchAsConsole(line);
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

    private static void message(Player player, Effect.Message effect) {
        if (player == null || effect.message().isEmpty()) {
            return;
        }
        TextKey key = catalogKey(effect.message());
        if (key != null) {
            WormholesAudience.sendMessage(player, Wormholes.text().component(player, key, MessageArgs.empty()));
            return;
        }
        WormholesAudience.sendMessage(player, LegacyComponentSerializer.legacyAmpersand().deserialize(effect.message()));
    }

    /** The rules-lane message this literal names, or null when it is ordinary text. */
    private static TextKey catalogKey(String message) {
        for (MessageKey key : RulesMessages.keys()) {
            if (key instanceof TextKey text && text.id().equals(message) && text.placeholders().isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private static void sound(Location arrival, Effect.Sound effect) {
        World world = arrival == null ? null : arrival.getWorld();
        if (world == null || effect.key().isEmpty()) {
            return;
        }
        world.playSound(arrival, effect.key(), effect.volume(), effect.pitch());
    }
}
