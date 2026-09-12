package art.arcane.wormholes.transit;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.TransitBridge;
import art.arcane.wormholes.util.AxisAlignedBB;

/**
 * The 4 Hz attendance loop behind the approach cue. Only open, attended, non-mirror portals are
 * considered; for each, every online player of that world inside {@code cinematics-approach-range}
 * of the aperture box gets a rate-limited sound on their own thread.
 */
public final class Cinematics {
    static final long PERIOD_TICKS = 5L;
    private static final long STALE_CLOCK_MILLIS = 30_000L;

    private final ApproachCue approach = new ApproachCue();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Wormholes plugin;

    public void start(Wormholes plugin) {
        this.plugin = plugin;
        if (running.compareAndSet(false, true)) {
            schedule();
        }
    }

    public void stop() {
        running.set(false);
        plugin = null;
    }

    private void schedule() {
        Wormholes active = plugin;
        if (!running.get() || active == null || !FoliaScheduler.runGlobal(active, this::tick, PERIOD_TICKS)) {
            running.set(false);
        }
    }

    private void tick() {
        if (!running.get()) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException failure) {
            Wormholes.w("[cinematics] sweep failed: " + failure);
        }
        schedule();
    }

    private void sweep() {
        TransitConfig config = TransitSubsystem.config();
        PortalManager manager = Wormholes.portalManager;
        Wormholes active = plugin;
        if (!config.cinematicsEnabled || manager == null || active == null || config.cinematicsApproachRange <= 0.0D) {
            return;
        }
        long now = System.currentTimeMillis();
        approach.prune(now, STALE_CLOCK_MILLIS);
        List<ILocalPortal> portals = manager.getLocalPortals();
        for (ILocalPortal candidate : portals) {
            if (!(candidate instanceof LocalPortal portal) || !portal.isOpen() || portal.isMirrorMode() || !portal.isAmbientAttended()) {
                continue;
            }
            AxisAlignedBB area = portal.getStructure() == null ? null : portal.getStructure().getArea();
            World world = portal.getStructure() == null ? null : portal.getStructure().getWorld();
            if (area == null || world == null || !TransitBridge.portalSoundEnabled(portal)) {
                continue;
            }
            TransitPortalExtension transit = portal.extension(TransitPortalExtension.class);
            String override = transit == null ? "" : transit.profile().approachSound();
            String sound = override.isBlank() ? ApproachCue.DEFAULT_SOUND : override;
            UUID worldId = world.getUID();
            for (Player player : Bukkit.getOnlinePlayers()) {
                Location location = player.getLocation();
                if (location.getWorld() == null || !location.getWorld().getUID().equals(worldId)) {
                    continue;
                }
                double distance = distanceTo(area, location.getX(), location.getY(), location.getZ());
                ApproachCue.Sound cue = approach.plan(player.getUniqueId(), portal.getId(), distance, config.cinematicsApproachRange, now);
                if (cue == null) {
                    continue;
                }
                float volume = Settings.portalSoundVolume(cue.volume());
                FoliaScheduler.runEntity(active, player, () -> player.playSound(player.getLocation(), sound, volume, cue.pitch()));
            }
        }
    }

    /** Euclidean distance from a point to the box; zero inside. */
    static double distanceTo(AxisAlignedBB box, double x, double y, double z) {
        double dx = Math.max(Math.max(box.getXa() - x, 0.0D), x - box.getXb());
        double dy = Math.max(Math.max(box.getYa() - y, 0.0D), y - box.getYb());
        double dz = Math.max(Math.max(box.getZa() - z, 0.0D), z - box.getZb());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
