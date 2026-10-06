package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.PlayerSpawnFinder;
import art.arcane.wormholes.modded.mixin.ServerPlayerRespawnInvoker;
import art.arcane.wormholes.modded.mixin.EndRespawnPositionAccessor;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class MinecraftEndReturnPreview {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private final Map<UUID, MinecraftPortal> destinations = new HashMap<>();
    private SpawnState state;
    private TeleportTransition transition;
    private int checkedTick = Integer.MIN_VALUE;
    private boolean preparing;

    MinecraftEndReturnPreview(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    MinecraftPortal destination(ServerPlayer observer, MinecraftPortal exit) {
        if (observer == null) {
            return null;
        }
        int tick = runtime.server().getTickCount();
        if (checkedTick == Integer.MIN_VALUE || tick - checkedTick >= 20) {
            checkedTick = tick;
            refresh(observer);
        }
        if (transition == null) {
            return null;
        }
        Vec3 point = transition.position();
        MinecraftPortal previous = destinations.get(exit.getId());
        String world = transition.newLevel().dimension().identifier().toString();
        if (previous != null && previous.getWorldKey().equals(world)
            && previous.getOrigin().equals(new Vec3d(point.x, point.y, point.z))) {
            return previous;
        }
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(point.x - 2, point.x + 2, point.y, point.y + 0.999,
            point.z - 2, point.z + 2));
        UUID id = UUID.nameUUIDFromBytes((exit.getId() + ":" + observer.getUUID()).getBytes(StandardCharsets.UTF_8));
        MinecraftPortal destination = new MinecraftPortal(new MinecraftPortal.Definition(
            new Portal.State(id, new Vec3d(point.x, point.y, point.z), "End return", Frame.canonical(Face.U), true),
            geometry, world, Map.of("owner", id.toString(), "type", PortalType.PORTAL.name(), "projectionMode", "OFF",
                "outgoingTraversalsEnabled", false, "incomingTraversalsEnabled", true)));
        destinations.put(exit.getId(), destination);
        return destination;
    }

    private void refresh(ServerPlayer observer) {
        ServerPlayer.RespawnConfig configuration = observer.getRespawnConfig();
        ServerLevel fallback = runtime.server().findRespawnDimension();
        if (fallback == null) {
            transition = null;
            return;
        }
        LevelData.RespawnData globalSpawn = fallback.getRespawnData();
        ServerLevel configuredLevel = configuration == null ? null : runtime.server().getLevel(configuration.respawnData().dimension());
        ServerLevel level = configuredLevel == null ? fallback : configuredLevel;
        BlockPos position = configuredLevel == null ? globalSpawn.pos() : configuration.respawnData().pos();
        boolean unchangedPolicy = state != null && Objects.equals(state.configuration(), configuration)
            && Objects.equals(state.globalSpawn(), globalSpawn);
        if (configuredLevel == null && unchangedPolicy) {
            return;
        }
        if (!loaded(level, position)) {
            if (!unchangedPolicy) {
                transition = null;
                state = null;
            }
            prepare(level, position);
            return;
        }
        long blockStamp = 1;
        if (configuredLevel != null) {
            for (BlockPos cell : BlockPos.betweenClosed(position.offset(-2, -1, -2), position.offset(2, 2, 2))) {
                blockStamp = blockStamp * 31 + level.getBlockState(cell).hashCode();
            }
        }
        SpawnState next = new SpawnState(configuration, globalSpawn, blockStamp);
        if (Objects.equals(state, next)) {
            return;
        }
        state = next;
        transition = null;
        Optional<?> respawn = configuredLevel == null ? Optional.empty()
            : ServerPlayerRespawnInvoker.wormholes$findRespawn(level, configuration, false);
        if (respawn.isPresent()) {
            Vec3 point = ((EndRespawnPositionAccessor) respawn.get()).wormholes$position();
            transition = new TeleportTransition(level, point, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING);
            return;
        }
        PlayerSpawnFinder.findSpawn(fallback, globalSpawn.pos()).whenCompleteAsync((point, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not resolve End return spawn for {}", observer.getUUID(), failure);
                if (Objects.equals(state, next)) {
                    state = null;
                }
            } else if (Objects.equals(state, next) && runtime.running()) {
                transition = new TeleportTransition(fallback, point, Vec3.ZERO, globalSpawn.yaw(), globalSpawn.pitch(), TeleportTransition.DO_NOTHING);
            }
        }, runtime.server());
    }

    private void prepare(ServerLevel level, BlockPos position) {
        if (preparing) {
            return;
        }
        preparing = true;
        List<ChunkLease> leases = new ArrayList<>(4);
        UUID world = UUID.nameUUIDFromBytes(level.dimension().identifier().toString().getBytes(StandardCharsets.UTF_8));
        try {
            for (int x = (position.getX() - 2) >> 4; x <= (position.getX() + 2) >> 4; x++) {
                for (int z = (position.getZ() - 2) >> 4; z <= (position.getZ() + 2) >> 4; z++) {
                    leases.add(runtime.leases().retain(level, world, x, z));
                }
            }
            CompletableFuture.allOf(leases.stream().map(ChunkLease::ready).toArray(CompletableFuture<?>[]::new))
                .whenCompleteAsync((ignored, failure) -> {
                    try {
                        if (failure != null) {
                            LOGGER.error("Could not prepare End return preview in {} at {}", level.dimension().identifier(), position, failure);
                        }
                    } finally {
                        for (ChunkLease lease : leases) {
                            lease.close();
                        }
                        preparing = false;
                        checkedTick = Integer.MIN_VALUE;
                    }
                }, runtime.server());
        } catch (RuntimeException failure) {
            for (ChunkLease lease : leases) {
                lease.close();
            }
            preparing = false;
            throw failure;
        }
    }

    private static boolean loaded(ServerLevel level, BlockPos position) {
        for (int x = (position.getX() - 2) >> 4; x <= (position.getX() + 2) >> 4; x++) {
            for (int z = (position.getZ() - 2) >> 4; z <= (position.getZ() + 2) >> 4; z++) {
                if (!level.hasChunk(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private record SpawnState(ServerPlayer.RespawnConfig configuration, LevelData.RespawnData globalSpawn, long blockStamp) {
    }
}
