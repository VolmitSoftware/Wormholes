package art.arcane.wormholes.transit;

import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.TransitBridge;
import art.arcane.wormholes.portal.Traversive;

/** Particle burst and sound at the commit point, played on the source region thread when a crossing departs. */
public final class ThresholdCue {
    public static final Particle DEFAULT_PARTICLE = Particle.REVERSE_PORTAL;
    public static final String SOUND = "minecraft:block.respawn_anchor.set_spawn";
    private static final int PARTICLE_COUNT = 24;
    private static final double PARTICLE_SPREAD = 0.35D;
    private static final double PARTICLE_SPEED = 0.05D;

    private ThresholdCue() {
    }

    public static void play(TraversalAttempt attempt) {
        if (Wormholes.instance == null || !TransitSubsystem.config().cinematicsEnabled) {
            return;
        }
        Traversive traversive = attempt.traversive();
        LocalPortal portal = attempt.portal();
        World world = portal.getStructure() == null ? null : portal.getStructure().getWorld();
        if (traversive == null || world == null) {
            return;
        }
        TransitPortalExtension transit = portal.extension(TransitPortalExtension.class);
        Particle particle = particle(transit == null ? "" : transit.profile().thresholdEffect());
        Location point = traversive.getInPoint().toLocation(world);
        world.spawnParticle(particle, point, PARTICLE_COUNT, PARTICLE_SPREAD, PARTICLE_SPREAD, PARTICLE_SPREAD, PARTICLE_SPEED);
        if (TransitBridge.portalSoundEnabled(portal)) {
            world.playSound(point, SOUND, Settings.portalSoundVolume(0.6F), 1.3F);
        }
    }

    static Particle particle(String effect) {
        if (effect == null || effect.isBlank()) {
            return DEFAULT_PARTICLE;
        }
        String name = effect.trim();
        int namespace = name.indexOf(':');
        if (namespace >= 0) {
            name = name.substring(namespace + 1);
        }
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return DEFAULT_PARTICLE;
        }
    }
}
