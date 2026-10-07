package art.arcane.wormholes.modded;

import com.mojang.serialization.MapCodec;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record RuntimeBaselineEnvironment() implements TestEnvironmentDefinition<RuntimeBaselineEnvironment.Baseline> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("wormholes", "runtime_baseline");
    public static final MapCodec<RuntimeBaselineEnvironment> CODEC = MapCodec.unit(RuntimeBaselineEnvironment::new);

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
        WormholesModRuntime runtime = WormholesGameTests.RUNTIME;
        if (!runtime.running()) {
            return;
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

    public record Baseline(Set<UUID> portals) {
    }
}
