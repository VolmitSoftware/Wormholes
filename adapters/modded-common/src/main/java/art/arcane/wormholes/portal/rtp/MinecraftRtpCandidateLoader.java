package art.arcane.wormholes.portal.rtp;

import art.arcane.optics.plate.ChunkLease;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class MinecraftRtpCandidateLoader implements RtpService.CandidateLoader, AutoCloseable {
    private static final Set<String> TREE_PARTS = Set.of("minecraft:bamboo", "minecraft:mangrove_roots", "minecraft:muddy_mangrove_roots",
        "minecraft:hanging_roots", "minecraft:mushroom_stem", "minecraft:brown_mushroom_block", "minecraft:red_mushroom_block",
        "minecraft:chorus_plant", "minecraft:chorus_flower", "minecraft:azalea", "minecraft:flowering_azalea", "minecraft:shroomlight",
        "minecraft:bee_nest", "minecraft:creaking_heart");
    private final WormholesModRuntime runtime;
    private final MinecraftServer server;
    private final RtpSafetyValidator validator = new RtpSafetyValidator();
    private final Map<RtpService.SearchRequest, Pending> searches = new HashMap<>();
    private final Set<Retention> retained = new HashSet<>();
    private boolean closed;

    public MinecraftRtpCandidateLoader(WormholesModRuntime runtime) {
        this.runtime = runtime;
        server = runtime.server();
    }

    @Override
    public CompletionStage<RtpService.LoadedCandidate> load(RtpService.SearchRequest request) {
        return begin(request, RtpValidationRequest.EntityEnvelope.baseline(), false);
    }

    public CompletionStage<RtpService.LoadedCandidate> exact(RtpService.SearchRequest request, RtpValidationRequest.EntityEnvelope envelope) {
        return begin(request, envelope, true);
    }

    @Override
    public void cancel(RtpService.SearchRequest request) {
        server.execute(() -> {
            Pending pending = searches.remove(request);
            if (pending != null) {
                pending.retention().close();
                pending.result().completeExceptionally(new IllegalStateException("RTP candidate load cancelled"));
            }
        });
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        for (Pending pending : searches.values()) {
            pending.result().completeExceptionally(new IllegalStateException("RTP candidate loader closed"));
        }
        searches.clear();
        for (Retention retention : List.copyOf(retained)) {
            retention.release();
        }
    }

    public ServerLevel level(String key) {
        runtime.requireServerThread();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(key)) {
                return level;
            }
        }
        return null;
    }

    private CompletableFuture<RtpService.LoadedCandidate> begin(RtpService.SearchRequest request,
                                                               RtpValidationRequest.EntityEnvelope envelope, boolean exact) {
        CompletableFuture<RtpService.LoadedCandidate> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                if (closed) {
                    throw new IllegalStateException("RTP candidate loader closed");
                }
                if (!exact && searches.containsKey(request)) {
                    throw new IllegalStateException("RTP candidate load already active");
                }
                ServerLevel level = level(request.destination().worldKey());
                if (level == null) {
                    throw new IllegalStateException("RTP target world is unavailable");
                }
                Retention retention = retain(level, request.destination(), envelope);
                Pending pending = new Pending(result, retention);
                if (!exact) {
                    searches.put(request, pending);
                }
                CompletableFuture.allOf(retention.leases.stream().map(ChunkLease::ready).toArray(CompletableFuture<?>[]::new))
                    .whenComplete((ignored, error) -> server.execute(() -> complete(request, envelope, exact, level, pending, error)));
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private void complete(RtpService.SearchRequest request, RtpValidationRequest.EntityEnvelope envelope, boolean exact,
                          ServerLevel level, Pending pending, Throwable failure) {
        if (!exact && !searches.remove(request, pending)) {
            pending.retention().release();
            return;
        }
        try {
            if (closed || pending.retention().released) {
                throw new IllegalStateException("RTP candidate load cancelled");
            }
            if (failure != null) {
                throw new IllegalStateException("RTP terrain preparation failed", failure);
            }
            for (ChunkLease lease : pending.retention().leases) {
                if (!Boolean.TRUE.equals(lease.ready().getNow(false))) {
                    throw new IllegalStateException("RTP terrain could not be retained");
                }
            }
            RtpValidationRequest snapshot = exact ? capture(level, request.destination(), envelope, request.settings())
                : search(level, request, envelope);
            RtpService.LoadedCandidate loaded = new RtpService.LoadedCandidate(snapshot, pending.retention());
            if (!pending.result().complete(loaded)) {
                pending.retention().release();
            }
        } catch (RuntimeException error) {
            pending.retention().release();
            pending.result().completeExceptionally(error);
        }
    }

    private RtpValidationRequest search(ServerLevel level, RtpService.SearchRequest request, RtpValidationRequest.EntityEnvelope envelope) {
        boolean surface = request.settings().getVerticalMode() == RtpVerticalMode.SURFACE;
        int hint = surface ? surfaceFeet(level, request.destination(), request.settings().getSafetyMode()) : request.settings().getPreferredY();
        if (request.enforceTargetBiome() && request.settings().getTargetBiomeKey() != null) {
            BlockPos sample = new BlockPos(request.destination().blockX(), Math.clamp(hint, level.getMinY(), level.getMaxY() - 1),
                request.destination().blockZ());
            String biome = level.getBiome(sample).unwrapKey().orElseThrow().identifier().toString();
            if (!RtpBiomeMatcher.matches(request.settings().getTargetBiomeKey(), List.of(biome))) {
                throw new IllegalStateException("RTP candidate is outside the target biome");
            }
        }
        RtpSampler sampler = new RtpSampler(0, 0, 0);
        for (int feetY : sampler.feetYProbeOrder(request.settings(), hint)) {
            RtpDestination original = request.destination();
            RtpDestination candidate = new RtpDestination(original.worldKey(), original.blockX(), feetY, original.blockZ(),
                original.generation(), original.attempt());
            RtpValidationRequest snapshot = capture(level, candidate, envelope, request.settings());
            if (validator.validate(snapshot).join().safe()) {
                return snapshot;
            }
        }
        throw new IllegalStateException("RTP candidate has no safe landing position");
    }

    private int surfaceFeet(ServerLevel level, RtpDestination destination, RtpSafetyMode safety) {
        if (!level.dimension().equals(Level.NETHER)) {
            return level.getHeight(safety == RtpSafetyMode.SAFE ? Heightmap.Types.MOTION_BLOCKING_NO_LEAVES : Heightmap.Types.MOTION_BLOCKING,
                destination.blockX(), destination.blockZ());
        }
        int top = Math.min(level.getMaxY(), level.dimensionType().logicalHeight()) - RtpSafetyValidator.NETHER_ROOF_BAND_DEPTH - 2;
        Integer feet = RtpSampler.descendingSurfaceFeetY(top, level.getMinY() + 1,
            y -> support(level, new BlockPos(destination.blockX(), y, destination.blockZ())),
            y -> open(level, new BlockPos(destination.blockX(), y, destination.blockZ())));
        if (feet == null) {
            throw new IllegalStateException("RTP nether column has no sheltered surface");
        }
        return feet;
    }

    private static boolean support(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return !state.getCollisionShape(level, position).isEmpty() && state.getFluidState().isEmpty();
    }

    private static boolean open(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return state.getCollisionShape(level, position).isEmpty() && state.getFluidState().isEmpty();
    }

    private Retention retain(ServerLevel level, RtpDestination destination, RtpValidationRequest.EntityEnvelope envelope) {
        RtpProbeBounds bounds = RtpProbeBounds.of(destination, envelope);
        int minX = bounds.minimumX() >> 4;
        int maxX = bounds.maximumX() >> 4;
        int minZ = bounds.minimumZ() >> 4;
        int maxZ = bounds.maximumZ() >> 4;
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > 4L || envelope.height() > 8D) {
            throw new IllegalArgumentException("RTP entity envelope is too large");
        }
        List<ChunkLease> leases = new ArrayList<>(4);
        try {
            UUID world = UUID.nameUUIDFromBytes(destination.worldKey().getBytes(StandardCharsets.UTF_8));
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    leases.add(runtime.leases().retain(level, world, x, z));
                }
            }
        } catch (RuntimeException error) {
            leases.forEach(ChunkLease::close);
            throw error;
        }
        Retention retention = new Retention(leases);
        retained.add(retention);
        return retention;
    }

    private RtpValidationRequest capture(ServerLevel level, RtpDestination destination,
                                         RtpValidationRequest.EntityEnvelope envelope, RtpSettings settings) {
        RtpProbeBounds bounds = RtpProbeBounds.of(destination, envelope);
        List<RtpValidationRequest.RegionSnapshot> regions = new ArrayList<>(4);
        for (int chunkX = bounds.minimumX() >> 4; chunkX <= bounds.maximumX() >> 4; chunkX++) {
            for (int chunkZ = bounds.minimumZ() >> 4; chunkZ <= bounds.maximumZ() >> 4; chunkZ++) {
                List<RtpValidationRequest.BlockSnapshot> blocks = new ArrayList<>();
                for (int x = Math.max(bounds.minimumX(), chunkX << 4); x <= Math.min(bounds.maximumX(), (chunkX << 4) + 15); x++) {
                    for (int z = Math.max(bounds.minimumZ(), chunkZ << 4); z <= Math.min(bounds.maximumZ(), (chunkZ << 4) + 15); z++) {
                        for (int y = Math.max(bounds.minimumY(), level.getMinY()); y <= Math.min(bounds.maximumY(), level.getMaxY() - 1); y++) {
                            blocks.add(block(level, new BlockPos(x, y, z)));
                        }
                    }
                }
                regions.add(new RtpValidationRequest.RegionSnapshot(chunkX + ":" + chunkZ, chunkX, chunkZ, blocks));
            }
        }
        RtpValidationRequest.Dimension dimension = level.dimension().equals(Level.NETHER) ? RtpValidationRequest.Dimension.NETHER
            : level.dimension().equals(Level.END) ? RtpValidationRequest.Dimension.END : RtpValidationRequest.Dimension.OVERWORLD;
        return RtpValidationRequest.builder(destination).worldBounds(level.getMinY(), level.getMaxY())
            .worldBorder(new RtpValidationRequest.WorldBorder(level.getWorldBorder().getMinX(), level.getWorldBorder().getMinZ(),
                level.getWorldBorder().getMaxX(), level.getWorldBorder().getMaxZ()))
            .dimension(dimension).netherLogicalCeiling(dimension == RtpValidationRequest.Dimension.NETHER
                ? Math.min(level.getMaxY(), level.dimensionType().logicalHeight()) : level.getMaxY())
            .entityEnvelope(envelope).surfaceMode(settings.getVerticalMode() == RtpVerticalMode.SURFACE).safetyMode(settings.getSafetyMode())
            .regionSnapshots(regions).build();
    }

    private static RtpValidationRequest.BlockSnapshot block(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        List<AABB> shape = state.getCollisionShape(level, position).toAabbs();
        List<RtpValidationRequest.CollisionBox> collision = new ArrayList<>(shape.size());
        for (AABB box : shape) {
            if (box.maxX > box.minX && box.maxY > box.minY && box.maxZ > box.minZ) {
                collision.add(new RtpValidationRequest.CollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
            }
        }
        String material = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        boolean tree = state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.getBlock() instanceof SaplingBlock
            || state.is(BlockTags.WART_BLOCKS) || state.is(BlockTags.BAMBOO_BLOCKS) || TREE_PARTS.contains(material);
        return RtpValidationRequest.BlockSnapshot.of(position.getX(), position.getY(), position.getZ(), material,
            !state.getFluidState().isEmpty(), state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT), tree, collision);
    }

    private record Pending(CompletableFuture<RtpService.LoadedCandidate> result, Retention retention) {
    }

    private final class Retention implements RtpService.Retention {
        private final List<ChunkLease> leases;
        private boolean released;

        private Retention(List<ChunkLease> leases) {
            this.leases = leases;
        }

        @Override
        public void close() {
            server.execute(this::release);
        }

        private void release() {
            if (released) {
                return;
            }
            released = true;
            retained.remove(this);
            leases.forEach(ChunkLease::close);
        }
    }
}
