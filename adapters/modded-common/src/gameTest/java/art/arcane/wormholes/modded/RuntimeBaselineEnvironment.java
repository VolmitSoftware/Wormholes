package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
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
        List<PlacedDoorEndpoint> endpoints = WormholesGameTests.RUNTIME.doors().state().endpoints();
        Set<UUID> doors = new HashSet<>(endpoints.size());
        for (PlacedDoorEndpoint endpoint : endpoints) {
            doors.add(endpoint.identity().itemId());
        }
        return new Baseline(ids, doors);
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
        for (PlacedDoorEndpoint endpoint : runtime.doors().state().endpoints()) {
            if (!baseline.doors().contains(endpoint.identity().itemId()) && endpoint.identity().kind() != DoorKind.RETURN) {
                removeDoor(runtime, endpoint);
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

    private static void removeDoor(WormholesModRuntime runtime, PlacedDoorEndpoint endpoint) {
        DoorPosition position = endpoint.position();
        ServerLevel level = runtime.server().getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(position.worldKey())));
        if (level == null) {
            return;
        }
        BlockPos lower = new BlockPos(position.x(), position.y(), position.z());
        boolean door = endpoint.identity().form() == DoorForm.DOOR;
        Block block = level.getBlockState(lower).getBlock();
        if (door ? !(block instanceof DoorBlock) : !(block instanceof TrapDoorBlock)) {
            return;
        }
        if (door) {
            level.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(lower, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    public record Baseline(Set<UUID> portals, Set<UUID> doors) {
    }
}
