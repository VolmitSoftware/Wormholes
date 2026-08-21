package art.arcane.wormholes.door;

import art.arcane.wormholes.platform.WormholesPlatform;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reshapes an existing pocket room in place on the owning region thread.
 *
 * <p>The room is anchored at its minimum corner, so growing only ever adds space
 * beyond the old maximum walls and ceiling and nothing already built moves.
 * Shrinking destroys whatever falls outside the new walls, which is why callers
 * are expected to run {@link #assess} first and make the operator confirm.</p>
 */
public final class PocketResizeService {
    private final Plugin plugin;
    private final PocketStructureService structures;

    public PocketResizeService(Plugin plugin, PocketStructureService structures) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    /** What reshaping {@code space} into {@code target} would destroy or displace. */
    public Impact assess(World world, PocketSpace space, PocketShell target) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(target, "target");
        PocketLayout previous = structures.layout(space);
        PocketLayout updated = structures.layout(space.withShell(target));
        Material previousShell = structures.shellMaterial(space);

        long[] counts = {0L, 0L};
        forEachDisplacedBlock(previous, updated, (x, y, z) -> {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (type.isAir() || type == previousShell) {
                return;
            }
            counts[0]++;
            if (hasStoredItems(block)) {
                counts[1]++;
            }
        });

        long entities = 0L;
        long players = 0L;
        for (Entity entity : displacedEntities(world, previous, updated)) {
            entities++;
            if (entity instanceof Player) {
                players++;
            }
        }
        return new Impact(counts[0], counts[1], entities, players);
    }

    /**
     * Rebuilds the room at its new size and materials.
     *
     * <p>Container contents from destroyed blocks are dropped at the entry rather
     * than deleted, and anything left standing outside the new walls is moved to
     * the entry.</p>
     *
     * @return the space as it should now be persisted
     */
    public PocketSpace apply(World world, PocketSpace space, PocketShell target) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(target, "target");
        PocketSpace reshaped = space.withShell(target);
        PocketLayout previous = structures.layout(space);
        PocketLayout updated = structures.layout(reshaped);
        PocketStructureService.requireWorldHeight(world, updated);
        requireOwnershipOfBoth(world, previous, updated);

        Material previousShell = structures.shellMaterial(space);
        Material shell = structures.shellMaterial(reshaped);
        Material door = structures.returnDoorMaterial(reshaped);

        clearReturnDoor(world, previous);
        List<Entity> displaced = displacedEntities(world, previous, updated);
        List<ItemStack> salvaged = new ArrayList<>();
        forEachDisplacedBlock(previous, updated, (x, y, z) -> {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isAir()) {
                return;
            }
            salvage(block, salvaged);
            block.setType(Material.AIR, false);
        });

        PocketStructureService.initializeShell(world, updated, shell);
        carveEnclosedShell(world, previous, updated, previousShell);
        PocketStructureService.repairReturnDoor(world, updated, shell, door);

        Location entry = structures.entryLocation(world, reshaped);
        for (ItemStack stack : salvaged) {
            world.dropItemNaturally(entry, stack);
        }
        for (Entity entity : displaced) {
            WormholesPlatform.teleport(plugin, entity, entry, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        return reshaped;
    }

    /**
     * Blocks the reshape destroys: everything left outside the new room, plus the
     * interior blocks the new walls are laid through.
     */
    private static void forEachDisplacedBlock(
        PocketLayout previous,
        PocketLayout updated,
        PocketLayout.BlockVisitor visitor
    ) {
        for (int x = previous.minX(); x <= previous.maxX(); x++) {
            for (int y = previous.minY(); y <= previous.maxY(); y++) {
                for (int z = previous.minZ(); z <= previous.maxZ(); z++) {
                    boolean outside = !updated.contains(x, y, z);
                    if (!outside && !(updated.isShellBlock(x, y, z) && previous.isInteriorBlock(x, y, z))) {
                        continue;
                    }
                    visitor.visit(x, y, z);
                }
            }
        }
    }

    /** Old walls and ceiling that a larger room now encloses are cleared back to open space. */
    private static void carveEnclosedShell(
        World world,
        PocketLayout previous,
        PocketLayout updated,
        Material previousShell
    ) {
        if (updated.size() <= previous.size()) {
            return;
        }
        previous.forEachShellBlock((x, y, z) -> {
            if (!updated.isInteriorBlock(x, y, z)) {
                return;
            }
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() == previousShell) {
                block.setType(Material.AIR, false);
            }
        });
    }

    private static void clearReturnDoor(World world, PocketLayout previous) {
        PocketBlockPosition lower = previous.returnDoorLower();
        PocketBlockPosition upper = previous.returnDoorUpper();
        PocketStructureService.clearDoorBlock(world.getBlockAt(upper.x(), upper.y(), upper.z()));
        PocketStructureService.clearDoorBlock(world.getBlockAt(lower.x(), lower.y(), lower.z()));
    }

    private static List<Entity> displacedEntities(World world, PocketLayout previous, PocketLayout updated) {
        BoundingBox searched = BoundingBox.of(
            new Vector(previous.minX(), previous.minY(), previous.minZ()),
            new Vector(previous.maxX() + 1.0D, previous.maxY() + 1.0D, previous.maxZ() + 1.0D)
        );
        List<Entity> displaced = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(searched)) {
            Location at = entity.getLocation();
            if (updated.isInteriorBlock(at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
                continue;
            }
            displaced.add(entity);
        }
        return displaced;
    }

    private static boolean hasStoredItems(Block block) {
        BlockState state = WormholesPlatform.blockState(block, false);
        if (!(state instanceof InventoryHolder holder)) {
            return false;
        }
        for (ItemStack stack : holder.getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private static void salvage(Block block, List<ItemStack> salvaged) {
        BlockState state = WormholesPlatform.blockState(block, false);
        if (!(state instanceof InventoryHolder holder)) {
            return;
        }
        Inventory inventory = holder.getInventory();
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                salvaged.add(stack.clone());
            }
        }
        inventory.clear();
    }

    private static void requireOwnershipOfBoth(World world, PocketLayout previous, PocketLayout updated) {
        int minChunkX = Math.min(previous.minX(), updated.minX()) >> 4;
        int minChunkZ = Math.min(previous.minZ(), updated.minZ()) >> 4;
        int maxChunkX = Math.max(previous.maxX(), updated.maxX()) >> 4;
        int maxChunkZ = Math.max(previous.maxZ(), updated.maxZ()) >> 4;
        if (!WormholesPlatform.isOwnedByCurrentRegion(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
            throw new IllegalStateException("pocket reshaping must run on the owning region thread");
        }
    }

    /** Everything a reshape would destroy or move, counted before anything is touched. */
    public record Impact(long blocks, long containers, long entities, long players) {
        public Impact {
            if (blocks < 0 || containers < 0 || entities < 0 || players < 0) {
                throw new IllegalArgumentException("impact counts cannot be negative");
            }
        }

        public static Impact none() {
            return new Impact(0L, 0L, 0L, 0L);
        }

        /** True when the reshape takes nothing away and moves nobody. */
        public boolean isHarmless() {
            return blocks == 0L && containers == 0L && entities == 0L;
        }
    }
}
