package art.arcane.wormholes.render.clientview;

import java.util.function.Consumer;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.network.client.FxMessage;

public final class ClientViewEffects {
    private static final ThreadLocal<Arrival> ARRIVAL = new ThreadLocal<>();

    private ClientViewEffects() {
    }

    public static BukkitClientView active() {
        ProjectionManager manager = Wormholes.projectionManager;
        BukkitClientView view = manager == null ? null : manager.clientView();
        return view != null && view.active() ? view : null;
    }

    public static boolean seamless(Player player, UUID portal) {
        BukkitClientView view = active();
        return view != null && view.seamlessTravel(player.getUniqueId(), portal);
    }

    public static boolean arrivalSeamless(Player player) {
        Arrival arrival = ARRIVAL.get();
        return arrival != null && arrival.seamless() && arrival.traveler().equals(player.getUniqueId());
    }

    public static void arrival(Entity traveler, boolean seamless, Runnable dispatch) {
        Arrival previous = ARRIVAL.get();
        ARRIVAL.set(new Arrival(traveler.getUniqueId(), seamless));
        try {
            dispatch.run();
        } finally {
            if (previous == null) {
                ARRIVAL.remove();
            } else {
                ARRIVAL.set(previous);
            }
        }
    }

    public static void sound(Location point, String sound, SoundCategory category, float volume, float pitch, UUID excluded) {
        World world = point.getWorld();
        if (world == null) {
            return;
        }
        if (excluded == null) {
            world.playSound(point, sound, category, volume, pitch);
            return;
        }
        Location origin = point.clone();
        double radius = 16.0D * Math.max(1.0F, volume);
        for (Player receiver : Bukkit.getOnlinePlayers()) {
            if (!excluded.equals(receiver.getUniqueId())) {
                FoliaScheduler.runEntity(Wormholes.instance, receiver, () -> {
                    Location location = receiver.getLocation();
                    if (location.getWorld() == world && location.distanceSquared(origin) < radius * radius) {
                        receiver.playSound(origin, sound, category, volume, pitch);
                    }
                });
            }
        }
    }

    public static void spawn(World world, double x, double y, double z, Consumer<World> everyone, Consumer<Player> viewer,
                             FxMessage.FxEmitter clientEmitter) {
        BukkitClientView view = active();
        if (view == null || !view.particles(world, x, y, z, viewer, clientEmitter)) {
            everyone.accept(world);
        }
    }

    public static void burst(World world, Particle particle, double x, double y, double z, int count, double spreadX, double spreadY, double spreadZ,
                             double speed) {
        BukkitClientView view = active();
        if (view == null || !view.particles(world, x, y, z, player -> player.spawnParticle(particle, x, y, z, count, spreadX, spreadY, spreadZ, speed),
            ClientViewEmitters.burst(particle.getKey().toString(), x, y, z, count, spreadX, spreadY, speed))) {
            world.spawnParticle(particle, x, y, z, count, spreadX, spreadY, spreadZ, speed);
        }
    }

    public static void burst(World world, Particle particle, double x, double y, double z, int count, double spreadX,
                             double spreadY, double spreadZ, double speed, UUID excluded) {
        if (excluded == null) {
            burst(world, particle, x, y, z, count, spreadX, spreadY, spreadZ, speed);
            return;
        }
        FxMessage.FxEmitter emitter = ClientViewEmitters.burst(particle.getKey().toString(), x, y, z, count,
            spreadX, spreadY, speed);
        Location origin = new Location(world, x, y, z);
        for (Player receiver : Bukkit.getOnlinePlayers()) {
            if (!excluded.equals(receiver.getUniqueId())) {
                FoliaScheduler.runEntity(Wormholes.instance, receiver, () -> {
                    Location location = receiver.getLocation();
                    if (location.getWorld() != world || location.distanceSquared(origin) >= 32.0D * 32.0D) {
                        return;
                    }
                    BukkitClientView view = active();
                    if (view == null || !view.receiver(receiver)) {
                        receiver.spawnParticle(particle, x, y, z, count, spreadX, spreadY, spreadZ, speed);
                    } else {
                        view.oneShot(receiver, emitter);
                    }
                });
            }
        }
    }

    private record Arrival(UUID traveler, boolean seamless) {
    }
}
