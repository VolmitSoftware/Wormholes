package art.arcane.wormholes.transit;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.TransitBridge;
import art.arcane.wormholes.render.clientview.ClientViewEffects;

/** A sound matched to the destination dimension, played to the arriving player on their own thread. */
public final class ArrivalCue {
    public static final String OVERWORLD_SOUND = TraversalCues.OVERWORLD_SOUND;
    public static final String NETHER_SOUND = TraversalCues.NETHER_SOUND;
    public static final String END_SOUND = TraversalCues.END_SOUND;

    private ArrivalCue() {
    }

    public static void play(LocalPortal destination, Entity traveler, Location exit) {
        if (Wormholes.instance == null || !TransitSubsystem.config().cinematicsEnabled || !(traveler instanceof Player player)) {
            return;
        }
        if (ClientViewEffects.arrivalSeamless(player) || !TransitBridge.portalSoundEnabled(destination)) {
            return;
        }
        World world = exit == null ? null : exit.getWorld();
        TransitPortalExtension transit = TransitPortalExtension.of(destination.getId());
        String override = transit == null ? "" : transit.profile().arrivalSound();
        String sound = override.isBlank() ? soundFor(world == null ? World.Environment.NORMAL : world.getEnvironment()) : override;
        player.playSound(exit == null ? player.getLocation() : exit, sound, Settings.portalSoundVolume(0.6F), pitchFor(world));
    }

    static String soundFor(World.Environment environment) {
        return TraversalCues.arrivalSound(dimension(environment), "");
    }

    private static float pitchFor(World world) {
        if (world == null) {
            return 1.0F;
        }
        return TraversalCues.arrivalPitch(dimension(world.getEnvironment()));
    }

    private static String dimension(World.Environment environment) {
        return switch (environment) {
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
    }
}
