package art.arcane.wormholes.render.clientview;

import java.util.function.Consumer;

import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;

public final class ClientViewEffects {
    private ClientViewEffects() {
    }

    public static BukkitClientView active() {
        ProjectionManager manager = Wormholes.projectionManager;
        BukkitClientView view = manager == null ? null : manager.clientView();
        return view != null && view.active() ? view : null;
    }

    public static void spawn(World world, double x, double y, double z, Consumer<World> everyone, Consumer<Player> viewer,
                             ClientViewMessage.FxEmitter clientEmitter) {
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
}
