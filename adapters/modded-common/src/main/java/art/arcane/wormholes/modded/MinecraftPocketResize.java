package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.PocketBlockPosition;
import art.arcane.wormholes.door.PocketEntryCoordinates;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketResizeGeometry;
import art.arcane.wormholes.door.PocketResizeImpact;
import art.arcane.wormholes.door.PocketShell;
import art.arcane.wormholes.door.PocketSpace;
import art.arcane.optics.math.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class MinecraftPocketResize {
    private static final int BLOCKS_PER_TICK = 2048;
    private final WormholesModRuntime runtime;
    private final MinecraftDoorService doors;
    private final MinecraftPocketRooms rooms;

    MinecraftPocketResize(WormholesModRuntime runtime, MinecraftDoorService doors, MinecraftPocketRooms rooms) {
        this.runtime = runtime;
        this.doors = doors;
        this.rooms = rooms;
    }

    static void validate(PocketShell shell) {
        Block material = material(shell.shellMaterial());
        if (!material.defaultBlockState().isSolid() || material instanceof FallingBlock) {
            throw new IllegalArgumentException("Pocket shell must be a solid, non-falling block");
        }
        Block door = material(shell.returnDoorMaterial());
        if (!(door instanceof DoorBlock block) || !block.type().canOpenByHand()) {
            throw new IllegalArgumentException("Pocket exit must be a hand-operable door");
        }
    }

    CompletableFuture<PocketResizeImpact> assess(ServerLevel level, PocketSpace space, PocketShell target) {
        validate(target);
        PocketLayout previous = new PocketLayout(space);
        PocketLayout updated = new PocketLayout(space.withShell(target));
        Block previousShell = material(space.shell().shellMaterial());
        long[] counts = new long[2];
        List<BlockPos> displaced = displaced(previous, updated);
        return visit(displaced, position -> {
            Block block = level.getBlockState(position).getBlock();
            if (block == previousShell || level.getBlockState(position).isAir()) {
                return;
            }
            counts[0]++;
            if (storedItems(level, position)) {
                counts[1]++;
            }
        }, 0).thenApply(ignored -> {
            List<Entity> entities = displacedEntities(level, previous, updated);
            long players = 0;
            for (Entity entity : entities) {
                if (entity instanceof ServerPlayer) {
                    players++;
                }
            }
            return new PocketResizeImpact(counts[0], counts[1], entities.size(), players);
        });
    }

    CompletableFuture<Void> apply(ServerLevel level, PocketSpace space, PocketShell target) {
        validate(target);
        PocketLayout previous = new PocketLayout(space);
        PocketLayout updated = new PocketLayout(space.withShell(target));
        Block previousShell = material(space.shell().shellMaterial());
        List<BlockPos> displaced = displaced(previous, updated);
        List<Entity> moved = displacedEntities(level, previous, updated);
        return visit(displaced, position -> {
            Block block = level.getBlockState(position).getBlock();
            if (block != previousShell && !level.getBlockState(position).isAir() && storedItems(level, position)) {
                throw new IllegalStateException("Pocket resize cannot destroy a non-empty container at " + position);
            }
        }, 0).thenCompose(ignored -> {
            relocate(level, updated, moved);
            clearDoor(level, previous.returnDoorLower());
            clearDoor(level, previous.returnDoorUpper());
            return visit(displaced, position -> level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS), 0);
        }).thenCompose(ignored -> {
            List<BlockPos> enclosed = new ArrayList<>();
            if (updated.size() > previous.size()) {
                previous.forEachShellBlock((x, y, z) -> {
                    if (updated.isInteriorBlock(x, y, z)) {
                        enclosed.add(new BlockPos(x, y, z));
                    }
                });
            }
            return visit(enclosed, position -> {
                if (level.getBlockState(position).is(previousShell)) {
                    level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }, 0);
        }).thenCompose(ignored -> rooms.prepare(space.withShell(target))).thenAccept(MinecraftPocketRooms.Prepared::close);
    }

    private void relocate(ServerLevel level, PocketLayout target, List<Entity> entities) {
        PocketEntryCoordinates entry = target.entry();
        if (!entities.isEmpty()) {
            level.setBlock(BlockPos.containing(entry.x(), entry.y() - 1, entry.z()),
                material(target.space().shell().shellMaterial()).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (Entity entity : entities) {
            if (!doors.teleport(entity, level, new Vec3d(entry.x(), entry.y(), entry.z()), entity.getYRot(), entity.getXRot(), new Vec3d(0, 0, 0))) {
                throw new IllegalStateException("Could not relocate pocket entity " + entity.getUUID());
            }
        }
    }

    private CompletableFuture<Void> visit(List<BlockPos> blocks, Consumer<BlockPos> visitor, int offset) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            int limit = Math.min(blocks.size(), offset + BLOCKS_PER_TICK);
            for (int index = offset; index < limit; index++) {
                visitor.accept(blocks.get(index));
            }
            if (limit == blocks.size()) {
                result.complete(null);
            } else if (!runtime.schedule(() -> visit(blocks, visitor, limit).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(failure);
                }
            }), 1L)) {
                result.completeExceptionally(new IllegalStateException("Pocket resize scheduler stopped"));
            }
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private static List<BlockPos> displaced(PocketLayout previous, PocketLayout updated) {
        List<BlockPos> displaced = new ArrayList<>();
        PocketResizeGeometry.forEachDisplacedBlock(previous, updated, (x, y, z) -> displaced.add(new BlockPos(x, y, z)));
        return displaced;
    }

    private static List<Entity> displacedEntities(ServerLevel level, PocketLayout previous, PocketLayout updated) {
        return level.getEntities((Entity) null, new AABB(previous.minX(), previous.minY(), previous.minZ(),
            previous.maxX() + 1.0D, previous.maxY() + 1.0D, previous.maxZ() + 1.0D),
            entity -> !updated.isInteriorBlock(entity.blockPosition().getX(), entity.blockPosition().getY(), entity.blockPosition().getZ()));
    }

    private static boolean storedItems(ServerLevel level, BlockPos position) {
        BlockEntity blockEntity = level.getBlockEntity(position);
        return blockEntity instanceof Container container && !container.isEmpty();
    }

    private static void clearDoor(ServerLevel level, PocketBlockPosition point) {
        BlockPos position = new BlockPos(point.x(), point.y(), point.z());
        if (level.getBlockState(position).getBlock() instanceof DoorBlock) {
            level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static Block material(String name) {
        Identifier key = Identifier.tryParse(name.toLowerCase(Locale.ROOT));
        return key == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getOptional(key).orElse(Blocks.AIR);
    }
}
