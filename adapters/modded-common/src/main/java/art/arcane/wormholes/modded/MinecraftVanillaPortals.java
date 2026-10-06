package art.arcane.wormholes.modded;

import art.arcane.optics.plate.ChunkLease;
import art.arcane.wormholes.config.toml.DimensionalConfig;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.vanilla.DimensionalRouting;
import art.arcane.wormholes.portal.vanilla.NetherSitePlan;
import art.arcane.wormholes.portal.vanilla.NetherSiteSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinecraftVanillaPortals implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private final Map<Site, Shape> pending = new LinkedHashMap<>();
    private final Set<ChunkLease> held = new HashSet<>();
    private long generation;
    private int ticks;

    public MinecraftVanillaPortals(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void afterUse(ServerPlayer player, BlockPos clicked) {
        if (!enabled()) {
            return;
        }
        ServerLevel level = player.level();
        for (BlockPos position : BlockPos.betweenClosed(clicked.offset(-3, -1, -3), clicked.offset(3, 1, 3))) {
            BlockState state = level.getBlockState(position);
            if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL)) {
                replace(player, level, position.immutable());
            }
        }
    }

    public boolean suppresses(ServerLevel level, BlockPos position) {
        if (!enabled()) {
            return false;
        }
        MinecraftPortal portal = runtime.portals().at(level, position);
        if (portal != null && portal.isManaged() && portal.getDimensionalKind() != DimensionalPortalKind.END_EXIT) {
            return true;
        }
        for (Map.Entry<Site, Shape> entry : pending.entrySet()) {
            if (entry.getKey().level() == level && entry.getValue().cells().contains(position)) {
                return true;
            }
        }
        return false;
    }

    public CompletableFuture<Boolean> replace(ServerPlayer player, ServerLevel source, BlockPos anchor) {
        runtime.requireServerThread();
        if (!enabled() || !runtime.access().permission(player, PortalType.PORTAL.permission())) {
            return CompletableFuture.completedFuture(false);
        }
        Shape shape = shape(source, anchor);
        if (shape == null || !unoccupied(source, shape.cells()) || !runtime.access().canConstruct(player)
            || !runtime.access().canPlace(placement(player, source, shape.cells()))) {
            return CompletableFuture.completedFuture(false);
        }
        ServerLevel target = target(source, shape.end());
        if (target == null) {
            return CompletableFuture.completedFuture(false);
        }
        Site site = new Site(source, shape.minimum());
        if (pending.putIfAbsent(site, shape) != null) {
            return CompletableFuture.completedFuture(false);
        }
        DimensionalConfig config = runtime.configuration().settings().getDimensional();
        String sourceKey = source.dimension().identifier().toString();
        String targetKey = target.dimension().identifier().toString();
        int[] mapped = shape.end() ? new int[] {12, 9} : DimensionalRouting.map(anchor.getX(), anchor.getZ(),
            DimensionalRouting.scaleOf(sourceKey, config.scales), DimensionalRouting.scaleOf(targetKey, config.scales));
        BlockPos center = target.getWorldBorder().clampToBounds(mapped[0], anchor.getY(), mapped[1]);
        List<ChunkLease> leases = new ArrayList<>();
        try {
            retain(source, shape.minimum().offset(-1, -1, -1), shape.maximum().offset(1, 1, 1), leases);
            retain(target, center.offset(-24, 0, -24), center.offset(24, 0, 24), leases);
        } catch (RuntimeException failure) {
            release(site, leases);
            return CompletableFuture.failedFuture(failure);
        }
        long expected = generation;
        return CompletableFuture.allOf(leases.stream().map(ChunkLease::ready).toArray(CompletableFuture<?>[]::new))
            .thenApplyAsync(ignored -> {
                if (leases.stream().anyMatch(lease -> !Boolean.TRUE.equals(lease.ready().getNow(false)))
                    || generation != expected || !enabled() || player.level() != source || !unchanged(source, shape)
                    || !unoccupied(source, shape.cells()) || !runtime.access().canPlace(placement(player, source, shape.cells()))) {
                    return false;
                }
                return pair(player, source, target, shape, center);
            }, runtime.server()).whenCompleteAsync((result, failure) -> {
                release(site, leases);
                if (failure != null) {
                    LOGGER.error("Could not replace vanilla portal at {} in {}", anchor, source.dimension().identifier(), failure);
                }
            }, runtime.server());
    }

    public void tick() {
        if (!enabled() || ++ticks % 40 != 0) {
            return;
        }
        discoverEndExit();
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            DimensionalPortalKind kind = portal.getDimensionalKind();
            if (kind == DimensionalPortalKind.END_EXIT) {
                ServerLevel level = runtime.portals().resolveLevel(portal);
                if (level != null && !exitActive(level, geometry(portal).cells())) {
                    runtime.portals().remove(portal.getId());
                }
                continue;
            }
            if (!kind.isNetherPortal() && kind != DimensionalPortalKind.END_SOURCE) {
                continue;
            }
            ServerLevel level = runtime.portals().resolveLevel(portal);
            if (level == null) {
                continue;
            }
            Shape shape = geometry(portal);
            for (BlockPos frame : frame(shape)) {
                if (!level.hasChunk(frame.getX() >> 4, frame.getZ() >> 4)) {
                    continue;
                }
                BlockState state = level.getBlockState(frame);
                boolean intact = shape.end() ? state.is(Blocks.END_PORTAL_FRAME) && state.getValue(EndPortalFrameBlock.HAS_EYE)
                    : state.is(Blocks.OBSIDIAN);
                if (!intact) {
                    runtime.portals().remove(portal.getId());
                    break;
                }
            }
        }
    }

    @Override
    public void close() {
        generation++;
        pending.clear();
        for (ChunkLease lease : List.copyOf(held)) {
            lease.close();
        }
        held.clear();
        ticks = 0;
    }

    private boolean pair(ServerPlayer player, ServerLevel source, ServerLevel target, Shape shape, BlockPos center) {
        MinecraftPortal existing = reusable(target, center, shape.end());
        Shape physical = existing == null && !shape.end() ? physical(target, center) : null;
        Build build = existing == null ? physical == null ? build(target, shape, center) : clear(physical) : null;
        List<BlockPos> destinationCells = existing == null ? build == null ? List.of() : build.cells() : geometry(existing).cells();
        if (destinationCells.isEmpty() || !runtime.access().canPlace(placement(player, target, build == null ? destinationCells : List.copyOf(build.changes().keySet())))) {
            return false;
        }
        Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
        MinecraftPortal origin = null;
        MinecraftPortal destination = existing;
        try {
            if (build != null) {
                for (Map.Entry<BlockPos, BlockState> change : build.changes().entrySet()) {
                    originals.put(change.getKey(), target.getBlockState(change.getKey()));
                    target.setBlock(change.getKey(), change.getValue(), Block.UPDATE_CLIENTS);
                }
                destination = runtime.portals().create(null, target, destinationCells, PortalType.PORTAL, shape.normal());
                destination.setDimensionalKind(shape.end() ? DimensionalPortalKind.END_ARRIVAL : DimensionalPortalKind.NETHER);
            }
            origin = runtime.portals().create(null, source, shape.cells(), PortalType.PORTAL, shape.normal());
            origin.setDimensionalKind(shape.end() ? DimensionalPortalKind.END_SOURCE : DimensionalPortalKind.NETHER);
            origin.link(destination);
            if (!shape.end()) {
                destination.link(origin);
            }
            origin.setCounterpartId(destination.getId());
            destination.setCounterpartId(origin.getId());
            if (shape.end()) {
                origin.setIncomingTraversalsEnabled(false);
                destination.unlink();
                destination.setOutgoingTraversalsEnabled(false);
                destination.setIncomingTraversalsEnabled(true);
                destination.setProjectionMode(ProjectionMode.OFF);
            }
            runtime.portals().save(origin);
            runtime.portals().save(destination);
            for (BlockPos cell : shape.cells()) {
                source.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            return true;
        } catch (RuntimeException failure) {
            if (origin != null) {
                origin.setCounterpartId(null);
                runtime.portals().remove(origin.getId());
            }
            if (destination != null && destination != existing) {
                destination.setCounterpartId(null);
                runtime.portals().remove(destination.getId());
            }
            if (existing != null) {
                existing.unlink();
                existing.setCounterpartId(null);
                runtime.portals().save(existing);
            }
            for (Map.Entry<BlockPos, BlockState> original : originals.entrySet()) {
                target.setBlock(original.getKey(), original.getValue(), Block.UPDATE_CLIENTS);
            }
            throw failure;
        }
    }

    private Shape physical(ServerLevel target, BlockPos center) {
        for (PoiRecord candidate : target.getPoiManager().getInSquare(type -> type.is(PoiTypes.NETHER_PORTAL), center, 24, PoiManager.Occupancy.ANY).toList()) {
            Shape shape = shape(target, candidate.getPos());
            if (shape != null && unoccupied(target, shape.cells())) {
                return shape;
            }
        }
        return null;
    }

    private static Build clear(Shape shape) {
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        for (BlockPos cell : shape.cells()) {
            changes.put(cell, Blocks.AIR.defaultBlockState());
        }
        return new Build(shape.cells(), changes);
    }

    private Build build(ServerLevel target, Shape source, BlockPos center) {
        for (int pass = 0; pass < (source.end() ? 1 : 2); pass++) {
            for (int offset : new int[] {0, 8, -8, 16, -16}) {
                int x = center.getX() + offset;
                int z = center.getZ();
                int highest = source.end() ? Math.min(180, target.getMaxY() - 4) : Math.min(NetherSiteSearch.maximumBaseY(target.getMaxY(), source.height(), target.dimension() == Level.NETHER),
                    target.dimension() == Level.NETHER ? 120 : target.getMaxY());
                int y = Math.max(target.getMinY() + 5, Math.min(highest, center.getY()));
                if (source.end()) {
                    y = target.getMinY() + 50;
                    for (int scan = highest; scan > target.getMinY(); scan--) {
                        if (!target.getBlockState(new BlockPos(x, scan, z)).isAir()) {
                            y = scan;
                            break;
                        }
                    }
                    y = Math.min(target.getMaxY() - 4, y + 10);
                }
                if (!source.end() && pass == 0) {
                    OptionalInt grounded = NetherSiteSearch.findBaseY(new NetherSiteSearch.Options(x, z, source.alongX(), source.width(),
                        Math.clamp(source.height(), 2, 21), y, target.getMinY() + 5, highest),
                        (blockX, blockY, blockZ) -> siteCell(target, blockX, blockY, blockZ));
                    if (grounded.isEmpty()) {
                        continue;
                    }
                    y = grounded.getAsInt();
                }
                Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
                List<BlockPos> cells = new ArrayList<>();
                if (source.end()) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos cell = new BlockPos(x + dx, y, z + dz);
                            cells.add(cell);
                            changes.put(cell, Blocks.AIR.defaultBlockState());
                        }
                    }
                } else {
                    List<NetherSitePlan.Mutation> planned = NetherSitePlan.plan(new NetherSitePlan.Options(x, y, z,
                        source.alongX(), source.width(), source.height()));
                    for (NetherSitePlan.Mutation mutation : planned) {
                        BlockPos cell = new BlockPos(mutation.x(), mutation.y(), mutation.z());
                        if (!mutation.preserveObsidian() || !target.getBlockState(cell).is(Blocks.OBSIDIAN)) {
                            BlockState state = switch (mutation.material()) {
                                case AIR -> Blocks.AIR.defaultBlockState();
                                case OBSIDIAN -> Blocks.OBSIDIAN.defaultBlockState();
                                case NETHERRACK -> Blocks.NETHERRACK.defaultBlockState();
                            };
                            changes.put(cell, state);
                        }
                        if (mutation.interior()) {
                            cells.add(cell);
                        }
                    }
                }
                boolean blocked = false;
                for (BlockPos cell : changes.keySet()) {
                    if (!target.getWorldBorder().isWithinBounds(cell) || !target.hasChunk(cell.getX() >> 4, cell.getZ() >> 4)
                        || runtime.portals().at(target, cell) != null || target.getBlockEntity(cell) != null
                        || target.getBlockState(cell).is(Blocks.BEDROCK) || target.getBlockState(cell).is(Blocks.NETHER_PORTAL)
                        || target.getBlockState(cell).is(Blocks.END_PORTAL_FRAME)) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked) {
                    return new Build(List.copyOf(cells), changes);
                }
            }
        }
        return null;
    }

    private static NetherSiteSearch.Cell siteCell(ServerLevel level, int x, int y, int z) {
        BlockPos position = new BlockPos(x, y, z);
        if (!level.hasChunk(x >> 4, z >> 4) || !level.getWorldBorder().isWithinBounds(position)) {
            return NetherSiteSearch.Cell.BLOCKED;
        }
        BlockState state = level.getBlockState(position);
        if (state.is(Blocks.LAVA) || state.is(Blocks.WATER) || !state.getFluidState().isEmpty()
            || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
            || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.BEDROCK)) {
            return NetherSiteSearch.Cell.BLOCKED;
        }
        if (state.isCollisionShapeFullBlock(level, position)) {
            return NetherSiteSearch.Cell.FLOOR;
        }
        return state.getCollisionShape(level, position).isEmpty() ? NetherSiteSearch.Cell.CLEAR : NetherSiteSearch.Cell.BLOCKED;
    }

    private void discoverEndExit() {
        ServerLevel level = runtime.server().getLevel(Level.END);
        if (level == null || DimensionalRouting.isDisabled(level.dimension().identifier().toString(), runtime.configuration().settings().getDimensional().groups)
            || !level.hasChunk(-1, -1) || !level.hasChunk(-1, 0)
            || !level.hasChunk(0, -1) || !level.hasChunk(0, 0)) {
            return;
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 1, 0);
        for (int y = Math.max(level.getMinY(), surface - 8); y <= Math.min(level.getMaxY() - 1, surface + 8); y++) {
            BlockPos anchor = new BlockPos(1, y, 0);
            if (!level.getBlockState(anchor).is(Blocks.END_PORTAL) || runtime.portals().at(level, anchor) != null) {
                continue;
            }
            Shape shape = shape(level, anchor);
            if (shape == null || !unoccupied(level, shape.cells())) {
                continue;
            }
            MinecraftPortal exit = runtime.portals().create(null, level, shape.cells(), PortalType.PORTAL, new Vec3(0, 1, 0));
            exit.setDimensionalKind(DimensionalPortalKind.END_EXIT);
            exit.setOutgoingTraversalsEnabled(false);
            exit.setIncomingTraversalsEnabled(false);
            exit.setProjectionMode(ProjectionMode.ON);
            runtime.portals().save(exit);
        }
    }

    private static boolean exitActive(ServerLevel level, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            if (level.hasChunk(cell.getX() >> 4, cell.getZ() >> 4) && !level.getBlockState(cell).is(Blocks.END_PORTAL)) {
                return false;
            }
        }
        return true;
    }

    private MinecraftPortal reusable(ServerLevel target, BlockPos center, boolean end) {
        MinecraftPortal nearest = null;
        double nearestDistance = 128D * 128D;
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (!portal.getWorldKey().equals(target.dimension().identifier().toString()) || portal.getDestinationId() != null
                || portal.getCounterpartId() != null || (end ? portal.getDimensionalKind() != DimensionalPortalKind.END_ARRIVAL
                : !portal.getDimensionalKind().isNetherPortal())) {
                continue;
            }
            double dx = portal.getOrigin().getX() - center.getX();
            double dz = portal.getOrigin().getZ() - center.getZ();
            double distance = dx * dx + dz * dz;
            if (distance <= nearestDistance) {
                nearest = portal;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private ServerLevel target(ServerLevel source, boolean end) {
        if (source.dimension() == Level.END) {
            return null;
        }
        ServerLevel target = runtime.server().getLevel(end ? Level.END : source.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER);
        DimensionalConfig config = runtime.configuration().settings().getDimensional();
        String sourceKey = source.dimension().identifier().toString();
        if (target == null || DimensionalRouting.isDisabled(sourceKey, config.groups)
            || !DimensionalRouting.canPair(sourceKey, target.dimension().identifier().toString(), config.groups)) {
            return null;
        }
        return target;
    }

    private void retain(ServerLevel level, BlockPos minimum, BlockPos maximum, List<ChunkLease> leases) {
        UUID world = UUID.nameUUIDFromBytes(level.dimension().identifier().toString().getBytes(StandardCharsets.UTF_8));
        for (int x = minimum.getX() >> 4; x <= maximum.getX() >> 4; x++) {
            for (int z = minimum.getZ() >> 4; z <= maximum.getZ() >> 4; z++) {
                ChunkLease lease = runtime.leases().retain(level, world, x, z);
                leases.add(lease);
                held.add(lease);
            }
        }
    }

    private void release(Site site, List<ChunkLease> leases) {
        pending.remove(site);
        for (ChunkLease lease : leases) {
            if (held.remove(lease)) {
                lease.close();
            }
        }
    }

    private boolean enabled() {
        return runtime.running() && runtime.configuration().settings().getMain().replaceNetherAndEndPortals;
    }

    private boolean unoccupied(ServerLevel level, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            if (runtime.portals().at(level, cell) != null) {
                return false;
            }
        }
        return true;
    }

    private static boolean unchanged(ServerLevel level, Shape shape) {
        for (BlockPos cell : shape.cells()) {
            if (!level.getBlockState(cell).is(shape.end() ? Blocks.END_PORTAL : Blocks.NETHER_PORTAL)) {
                return false;
            }
        }
        return true;
    }

    private static MinecraftAccessService.Placement placement(ServerPlayer player, ServerLevel level, List<BlockPos> cells) {
        return new MinecraftAccessService.Placement(player, level, cells, MinecraftAccessService.PlacementKind.VANILLA);
    }

    private static Shape shape(ServerLevel level, BlockPos anchor) {
        BlockState original = level.getBlockState(anchor);
        boolean end = original.is(Blocks.END_PORTAL);
        if (!end && !original.is(Blocks.NETHER_PORTAL)) {
            return null;
        }
        boolean alongX = !end && original.getValue(NetherPortalBlock.AXIS) == Direction.Axis.X;
        Direction[] directions = end ? new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}
            : alongX ? new Direction[] {Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST}
            : new Direction[] {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};
        Set<BlockPos> cells = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(anchor);
        while (!queue.isEmpty()) {
            BlockPos position = queue.removeFirst();
            if (!level.hasChunk(position.getX() >> 4, position.getZ() >> 4) || cells.contains(position)
                || !level.getBlockState(position).equals(original)) {
                continue;
            }
            cells.add(position);
            if (cells.size() > 441) {
                return null;
            }
            for (Direction direction : directions) {
                queue.add(position.relative(direction));
            }
        }
        Shape shape = bounds(List.copyOf(cells), end, alongX);
        if (end && level.dimension() == Level.END) {
            return shape.minimum().getY() == shape.maximum().getY() && shape.cells().size() <= 24
                && shape.minimum().getX() >= -3 && shape.maximum().getX() <= 3
                && shape.minimum().getZ() >= -3 && shape.maximum().getZ() <= 3 ? shape : null;
        }
        return end ? shape.cells().size() == 9 && shape.width() == 3 && shape.maximum().getZ() - shape.minimum().getZ() == 2 ? shape : null
            : shape.width() >= 2 && shape.width() <= 21 && shape.height() >= 3 && shape.height() <= 21
                && shape.cells().size() == shape.width() * shape.height() ? shape : null;
    }

    private static Shape geometry(MinecraftPortal portal) {
        List<BlockPos> cells = new ArrayList<>();
        for (art.arcane.optics.math.Vec3 position : portal.getGeometry().getBlockPositions()) {
            cells.add(new BlockPos((int) position.getX(), (int) position.getY(), (int) position.getZ()));
        }
        return bounds(cells, portal.getDimensionalKind().isManagedEndPortal(),
            Math.abs(portal.getFrame().getNormal().z()) > 0.5D);
    }

    private static Shape bounds(List<BlockPos> cells, boolean end, boolean alongX) {
        BlockPos minimum = cells.getFirst();
        BlockPos maximum = minimum;
        for (BlockPos cell : cells) {
            minimum = new BlockPos(Math.min(minimum.getX(), cell.getX()), Math.min(minimum.getY(), cell.getY()), Math.min(minimum.getZ(), cell.getZ()));
            maximum = new BlockPos(Math.max(maximum.getX(), cell.getX()), Math.max(maximum.getY(), cell.getY()), Math.max(maximum.getZ(), cell.getZ()));
        }
        return new Shape(cells, minimum, maximum, end, alongX);
    }

    private static List<BlockPos> frame(Shape shape) {
        List<BlockPos> frame = new ArrayList<>();
        for (BlockPos cell : shape.cells()) {
            Direction[] directions = shape.end() ? new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}
                : shape.alongX() ? new Direction[] {Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST}
                : new Direction[] {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};
            for (Direction direction : directions) {
                BlockPos adjacent = cell.relative(direction);
                if (!shape.cells().contains(adjacent)) {
                    frame.add(adjacent);
                }
            }
        }
        return frame;
    }

    private record Site(ServerLevel level, BlockPos minimum) {
    }

    private record Build(List<BlockPos> cells, Map<BlockPos, BlockState> changes) {
    }

    private record Shape(List<BlockPos> cells, BlockPos minimum, BlockPos maximum, boolean end, boolean alongX) {
        private int width() {
            return end || alongX ? maximum.getX() - minimum.getX() + 1 : maximum.getZ() - minimum.getZ() + 1;
        }

        private int height() {
            return maximum.getY() - minimum.getY() + 1;
        }

        private Vec3 normal() {
            return end ? new Vec3(0, 1, 0) : alongX ? new Vec3(0, 0, -1) : new Vec3(1, 0, 0);
        }
    }
}
