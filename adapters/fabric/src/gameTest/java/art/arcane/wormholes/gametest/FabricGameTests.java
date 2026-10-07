package art.arcane.wormholes.gametest;

import art.arcane.wormholes.fabric.WormholesFabric;
import art.arcane.wormholes.modded.RuntimeBaselineEnvironment;
import art.arcane.wormholes.modded.WormholesGameTests;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;

public final class FabricGameTests implements ModInitializer {
    private MinecraftServer attachedServer;

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.TEST_ENVIRONMENT_DEFINITION_TYPE, RuntimeBaselineEnvironment.ID, RuntimeBaselineEnvironment.CODEC);
        ServerTickEvents.END_SERVER_TICK.register(this::attach);
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
