package art.arcane.wormholes.modded;

import com.mojang.serialization.MapCodec;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record RuntimeBaselineEnvironment() implements TestEnvironmentDefinition<RuntimeBaselineEnvironment.Baseline> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("wormholes", "runtime_baseline");
    public static final MapCodec<RuntimeBaselineEnvironment> CODEC = MapCodec.unit(RuntimeBaselineEnvironment::new);
    private static final List<Runnable> TEARDOWN_CLEANUPS = new ArrayList<>();

    public static void cleanupOnTeardown(Runnable cleanup) {
        synchronized (TEARDOWN_CLEANUPS) {
            TEARDOWN_CLEANUPS.add(cleanup);
        }
    }

    @Override
    public Baseline setup(ServerLevel level) {
        List<MinecraftPortal> portals = WormholesGameTests.RUNTIME.portals().snapshot();
        Set<UUID> ids = new HashSet<>(portals.size());
        for (MinecraftPortal portal : portals) {
            ids.add(portal.getId());
        }
        return new Baseline(ids);
    }

    @Override
    public void teardown(ServerLevel level, Baseline baseline) {
        List<Runnable> cleanups = drainCleanups();
        WormholesModRuntime runtime = WormholesGameTests.RUNTIME;
        if (!runtime.running()) {
            return;
        }
        for (Runnable cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (RuntimeException error) {
                LoggerFactory.getLogger("WormholesGameTest").error("Game test cleanup failed during environment teardown", error);
            }
        }
        MinecraftGameTestPlayer.closeConnected();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (!baseline.portals().contains(portal.getId())) {
                runtime.portals().remove(portal.getId());
            }
        }
    }

    @Override
    public MapCodec<RuntimeBaselineEnvironment> codec() {
        return CODEC;
    }

    private static List<Runnable> drainCleanups() {
        synchronized (TEARDOWN_CLEANUPS) {
            List<Runnable> drained = List.copyOf(TEARDOWN_CLEANUPS);
            TEARDOWN_CLEANUPS.clear();
            return drained;
        }
    }

    public record Baseline(Set<UUID> portals) {
    }
}
