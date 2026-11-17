package art.arcane.wormholes.gametest;

import art.arcane.wormholes.fabric.WormholesFabric;
import art.arcane.wormholes.modded.WormholesGameTests;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.util.concurrent.locks.LockSupport;

public final class FabricGameTestAttach implements ModInitializer {
    private static final long TICK_NANOS = 50_000_000L;
    private MinecraftServer attachedServer;
    private long nextTick;

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(this::attach);
        ServerTickEvents.END_SERVER_TICK.register(this::pace);
    }

    // The 1.20.1 GameTestServer runs ticks back to back. Hold it to 20 ticks per second so the runtime's
    // wall-clock cooldowns, warmups and timeouts line up with tick counts as they do on a real server.
    private void pace(MinecraftServer server) {
        long now = System.nanoTime();
        if (nextTick != 0L && now < nextTick) {
            LockSupport.parkNanos(nextTick - now);
            now = nextTick;
        }
        nextTick = now + TICK_NANOS;
    }

    private void attach(MinecraftServer server) {
        if (attachedServer == server) {
            return;
        }
        for (ModInitializer initializer : FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class)) {
            if (initializer instanceof WormholesFabric) {
                try {
                    Field field = WormholesFabric.class.getDeclaredField("runtime");
                    field.setAccessible(true);
                    WormholesGameTests.attach(server, (WormholesModRuntime) field.get(initializer));
                    attachedServer = server;
                    return;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not attach GameTests to the production Fabric runtime", failure);
                }
            }
        }
        throw new IllegalStateException("The Wormholes production Fabric initializer is not loaded");
    }
}
