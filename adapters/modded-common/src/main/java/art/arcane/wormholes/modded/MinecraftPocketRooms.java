package art.arcane.wormholes.modded;

import art.arcane.optics.plate.ChunkLease;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.PocketBlockPosition;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class MinecraftPocketRooms implements AutoCloseable {
    private static final int BLOCKS_PER_TICK = 2_048;
    private static final Identifier DIMENSION = Identifier.fromNamespaceAndPath("wormholes", "pockets");
    private static final UUID WORLD_ID = UUID.nameUUIDFromBytes(DIMENSION.toString().getBytes(StandardCharsets.UTF_8));

    private final WormholesModRuntime runtime;
    private final MinecraftServer server;
    private final Set<Prepared> prepared = new HashSet<>();
    private boolean closed;

    MinecraftPocketRooms(WormholesModRuntime runtime) {
        this.runtime = runtime;
        this.server = runtime.server();
    }

    CompletableFuture<Prepared> prepare(PocketSpace space) {
        return acquire(new PocketLayout(space), true, true);
    }

    CompletableFuture<Prepared> load(PocketSpace space) {
        return acquire(new PocketLayout(space), false, true);
    }

    CompletableFuture<Prepared> prepareAdditional(PocketLayout layout) {
        return acquire(layout, true, false);
    }

    private CompletableFuture<Prepared> acquire(PocketLayout layout, boolean provision, boolean returnDoor) {
        runtime.requireServerThread();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, DIMENSION));
        if (closed || level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("The wormholes:pockets dimension is not loaded"));
        }
        if (layout.minY() < level.getMinY() || layout.maxY() >= level.getMaxY()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Pocket room exceeds dimension height"));
        }
        Prepared room = new Prepared(level, layout, returnDoor);
        prepared.add(room);
        List<CompletableFuture<Boolean>> chunks = new ArrayList<>();
        try {
            for (int x = layout.minX() >> 4; x <= layout.maxX() >> 4; x++) {
                for (int z = layout.minZ() >> 4; z <= layout.maxZ() >> 4; z++) {
                    ChunkLease lease = runtime.leases().retain(level, WORLD_ID, x, z);
                    room.leases.add(lease);
                    chunks.add(lease.ready());
                }
            }
        } catch (RuntimeException exception) {
            room.fail(exception);
            return room.ready;
        }
        CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).whenCompleteAsync((ignored, error) -> {
            if (error != null) {
                room.fail(error);
                return;
            }
            for (CompletableFuture<Boolean> chunk : chunks) {
                if (!Boolean.TRUE.equals(chunk.join())) {
                    room.fail(new IllegalStateException("Pocket chunk could not be loaded"));
                    return;
                }
            }
            if (closed || room.released) {
                room.fail(new IllegalStateException("Pocket preparation was stopped"));
                return;
            }
            if (provision) {
                provision(room);
            } else {
                room.ready.complete(room);
            }
        }, server);
        return room.ready;
    }

    @Override
    public void close() {
        closed = true;
        for (Prepared room : List.copyOf(prepared)) {
            room.fail(new IllegalStateException("Pocket service stopped"));
        }
    }

    private void provision(Prepared room) {
        Block shell = material(room.layout.space().shell().shellMaterial(), Blocks.SMOOTH_STONE);
        if (!shell.defaultBlockState().isSolid()) {
            shell = Blocks.SMOOTH_STONE;
        }
        Block door = material(room.layout.space().shell().returnDoorMaterial(), Blocks.CRIMSON_DOOR);
        if (!(door instanceof DoorBlock)) {
            door = Blocks.CRIMSON_DOOR;
        }
        List<BlockPos> shellBlocks = new ArrayList<>();
        room.layout.forEachShellBlock((x, y, z) -> shellBlocks.add(new BlockPos(x, y, z)));
        write(room, shellBlocks, shell.defaultBlockState(), door.defaultBlockState(), 0);
    }

    private void write(Prepared room, List<BlockPos> shell, BlockState material, BlockState door, int offset) {
        if (closed || room.released) {
            room.fail(new IllegalStateException("Pocket preparation was stopped"));
            return;
        }
        try {
            int limit = Math.min(shell.size(), offset + BLOCKS_PER_TICK);
            BlockPos lower = block(room.layout.returnDoorLower());
            BlockPos upper = lower.above();
            for (int i = offset; i < limit; i++) {
                BlockPos at = shell.get(i);
                if ((!room.returnDoor || !at.equals(lower) && !at.equals(upper)) && !room.level.getBlockState(at).is(material.getBlock())) {
                    room.level.setBlock(at, material, Block.UPDATE_CLIENTS);
                }
            }
            if (limit < shell.size()) {
                runtime.schedule(() -> write(room, shell, material, door, limit), 1L);
                return;
            }
            if (room.returnDoor) {
                BlockState exit = door.setValue(DoorBlock.FACING, Direction.SOUTH)
                    .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT).setValue(DoorBlock.OPEN, false).setValue(DoorBlock.POWERED, false);
                room.level.setBlock(lower, exit.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.UPDATE_CLIENTS);
                room.level.setBlock(upper, exit.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
            }
            room.ready.complete(room);
        } catch (RuntimeException exception) {
            room.fail(exception);
        }
    }

    static PlacedDoorEndpoint endpoint(PocketLayout layout) {
        PocketBlockPosition point = layout.returnDoorLower();
        return new PlacedDoorEndpoint(new DoorPosition(WORLD_ID, DIMENSION.toString(), point.x(), point.y(), point.z()),
            layout.returnDoorIdentity());
    }

    private static Block material(String name, Block fallback) {
        Identifier key = Identifier.tryParse(name.toLowerCase(Locale.ROOT));
        return key == null ? fallback : BuiltInRegistries.BLOCK.getOptional(key).orElse(fallback);
    }

    private static BlockPos block(PocketBlockPosition point) {
        return new BlockPos(point.x(), point.y(), point.z());
    }

    final class Prepared implements AutoCloseable {
        private final ServerLevel level;
        private final PocketLayout layout;
        private final boolean returnDoor;
        private final List<ChunkLease> leases = new ArrayList<>();
        private final CompletableFuture<Prepared> ready = new CompletableFuture<>();
        private boolean released;

        private Prepared(ServerLevel level, PocketLayout layout, boolean returnDoor) {
            this.level = level;
            this.layout = layout;
            this.returnDoor = returnDoor;
        }

        ServerLevel level() {
            return level;
        }

        PocketLayout layout() {
            return layout;
        }

        PlacedDoorEndpoint endpoint() {
            return MinecraftPocketRooms.endpoint(layout);
        }

        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            prepared.remove(this);
            for (ChunkLease lease : leases) {
                lease.close();
            }
        }

        private void fail(Throwable error) {
            ready.completeExceptionally(error);
            close();
        }
    }
}
