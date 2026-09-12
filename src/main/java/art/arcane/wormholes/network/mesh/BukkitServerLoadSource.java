package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.TraversalService;

import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Reads player counts from Bukkit and TPS / tick times from Paper's Server API through reflection so
 * the Spigot compatibility compile stays clean; on servers without those methods TPS reports 20 and
 * MSPT falls back to the average tick time or zero.
 */
public final class BukkitServerLoadSource implements ServerLoadSource {
    private static final Method GET_TPS = resolve("getTPS");
    private static final Method GET_TICK_TIMES = resolve("getTickTimes");
    private static final Method GET_AVERAGE_TICK_TIME = resolve("getAverageTickTime");
    private static final double NANOS_PER_MILLI = 1_000_000.0D;

    @Override
    public int online() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public int max() {
        return Math.max(0, Bukkit.getMaxPlayers());
    }

    @Override
    public int reserved() {
        TraversalService traversal = Wormholes.traversalService;
        return traversal == null ? 0 : traversal.activeInboundReservations(System.currentTimeMillis());
    }

    @Override
    public double tps() {
        Object tps = invoke(GET_TPS);
        if (tps instanceof double[] values && values.length > 0) {
            return Math.min(20.0D, values[0]);
        }
        return 20.0D;
    }

    @Override
    public double msptP95() {
        Object times = invoke(GET_TICK_TIMES);
        if (times instanceof long[] values && values.length > 0) {
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            int index = Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(sorted.length * 0.95D) - 1));
            return sorted[index] / NANOS_PER_MILLI;
        }
        Object average = invoke(GET_AVERAGE_TICK_TIME);
        return average instanceof Double value ? value.doubleValue() : 0.0D;
    }

    private static Method resolve(String name) {
        try {
            return Server.class.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invoke(Method method) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
