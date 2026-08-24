package art.arcane.wormholes.service;

import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class WormholesAudience {
    private WormholesAudience() {
    }

    public static void sendActionBar(Player player, Component component) {
        if (player != null && component != null) {
            ComponentMessenger.sendActionBar(player, ComponentText.component(component));
        }
    }

    public static void showTitle(Player player, Title title) {
        if (player == null || title == null || title.times() == null) {
            return;
        }
        Title.Times times = title.times();
        ComponentMessenger.showTitle(
                player,
                ComponentText.component(title.title()),
                ComponentText.component(title.subtitle()),
                times.fadeIn(),
                times.stay(),
                times.fadeOut());
    }

    public static void sendMessage(CommandSender sender, Component component) {
        if (sender == null || component == null) {
            return;
        }
        try {
            ComponentMessenger.send(sender, ComponentText.component(component));
        } catch (Throwable ex) {
            WormholesTelemetry.countFailure("AUDIENCE_MESSAGE_DELIVERY_FAILED");
            logger().log(Level.WARNING, "audience: message delivery failed for " + sender.getClass().getName(), ex);
            throw ex;
        }
    }

    private static Logger logger() {
        Wormholes plugin = Wormholes.instance;
        return plugin == null ? Logger.getLogger("Wormholes") : plugin.getLogger();
    }

}
