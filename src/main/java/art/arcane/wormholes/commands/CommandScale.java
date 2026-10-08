package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.OpsMessages;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.transit.BukkitScaleAccess;
import art.arcane.wormholes.transit.ScaleResetRequest;
import art.arcane.wormholes.transit.TravellerScale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Director(name = "scale", descriptionKey = OpsMessages.SCALE_HELP, description = "Restore the default size of travellers scaled by portals")
public class CommandScale {
    private static final String PERMISSION = "wormholes.admin.scale";
    private static final TravellerScale<Entity> SCALE = new TravellerScale<>(BukkitScaleAccess.scaleAttribute());

    @Director(name = "reset", sync = true, descriptionKey = OpsMessages.SCALE_RESET_HELP,
            description = "Remove the portal scale from a player or from every entity nearby")
    public void reset(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "target", descriptionKey = OpsMessages.SCALE_RESET_TARGET_HELP,
                              description = "self, a player name, or all", defaultValue = "self") String target,
                      @Param(name = "radius", descriptionKey = OpsMessages.SCALE_RESET_RADIUS_HELP,
                              description = "Search radius in blocks when target=all (1-256)", defaultValue = "") String radius) {
        if (!sender.hasPermission(PERMISSION)) {
            send(sender, WormholesMessages.COMMAND_NO_PERMISSION, MessageArgs.empty());
            return;
        }
        ScaleResetRequest request = ScaleResetRequest.parse(target, radius);
        if (request.problem() == ScaleResetRequest.Problem.RADIUS_REQUIRED) {
            send(sender, TransitMessages.SCALE_RESET_RADIUS, WormholesLocalization.args(
                    MessageArgument.untrusted("value", Integer.valueOf(ScaleResetRequest.MAX_RADIUS))));
            return;
        }
        switch (request.target()) {
            case SELF -> {
                if (!(sender instanceof Player player)) {
                    send(sender, WormholesMessages.COMMAND_ONLY_PLAYERS, MessageArgs.empty());
                    return;
                }
                resetEntity(sender, player);
            }
            case PLAYER -> {
                Player player = Bukkit.getPlayerExact(request.player());
                if (player == null) {
                    send(sender, TransitMessages.SCALE_RESET_PLAYER, WormholesLocalization.args(MessageArgument.untrusted("name", request.player())));
                    return;
                }
                resetEntity(sender, player);
            }
            case ALL -> {
                if (!(sender instanceof Player player)) {
                    send(sender, WormholesMessages.COMMAND_ONLY_PLAYERS, MessageArgs.empty());
                    return;
                }
                resetNear(sender, player.getLocation(), request.radius());
            }
        }
    }

    private static void resetEntity(CommandSender sender, Entity entity) {
        if (!FoliaScheduler.runEntity(Wormholes.instance, entity, () -> reported(sender, SCALE.reset(entity) ? 1 : 0))) {
            Wormholes.w("Entity scheduler rejected the scale reset for " + entity.getUniqueId());
        }
    }

    private static void resetNear(CommandSender sender, Location center, int radius) {
        if (!FoliaScheduler.runRegion(Wormholes.instance, center, () -> reported(sender, resetAround(center, radius)))) {
            Wormholes.w("Region scheduler rejected the scale reset around " + center);
        }
    }

    private static int resetAround(Location center, int radius) {
        Collection<Entity> nearby = center.getWorld().getNearbyEntities(center, radius, radius, radius,
                entity -> entity.getLocation().distanceSquared(center) <= (double) radius * radius);
        List<Entity> owned = new ArrayList<>(nearby.size());
        int scheduled = 0;
        for (Entity entity : nearby) {
            if (FoliaScheduler.isOwnedByCurrentRegion(entity)) {
                owned.add(entity);
            } else if (FoliaScheduler.runEntity(Wormholes.instance, entity, () -> SCALE.reset(entity))) {
                scheduled++;
            }
        }
        return SCALE.resetAll(owned, new ArrayList<>(owned.size())) + scheduled;
    }

    private static void reported(CommandSender sender, int count) {
        send(sender, TransitMessages.SCALE_RESET, WormholesLocalization.args(MessageArgument.untrusted("count", Integer.valueOf(count))));
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key, arguments));
    }
}
