package art.arcane.wormholes.modded.client;

import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.modded.client.render.PortalScene;
import art.arcane.optics.client.ClientViewBlockTransform;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.stream.SectionBiomes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.ObjLongConsumer;

public final class ClientMeshWorld implements BlockAndTintGetter {
    private final Long2ObjectOpenHashMap<ClientMeshSections.Section> sections = new Long2ObjectOpenHashMap<>(27);
    private final Biomes biomes;
    private final int biomeX;
    private final int biomeY;
    private final int biomeZ;
    private final Map<ColorResolver, Long2IntOpenHashMap> colors = new IdentityHashMap<>();
    private final ProjectionEnvironment.Transform transform;
    private final ClientViewBlockTransform cells;
    private final CardinalLighting lighting;
    private final int blendRadius;
    private final int minY;
    private final int height;
    private final ProjectionEnvironment.Dimension dimension;
    private final PortalScene.MeshIdentity meshIdentity;
    private final DestinationWorld destination = new DestinationWorld();

    public ClientMeshWorld(Snapshot snapshot) {
        ProjectionEnvironment environment = Objects.requireNonNull(snapshot.environment());
        int sectionX = SectionPos.x(snapshot.center());
        int sectionY = SectionPos.y(snapshot.center());
        int sectionZ = SectionPos.z(snapshot.center());
        biomeX = sectionX << 4;
        biomeY = sectionY << 4;
        biomeZ = sectionZ << 4;
        minY = snapshot.view().bounds().minY();
        height = snapshot.view().bounds().sizeY();
        transform = environment.transform();
        cells = new ClientViewBlockTransform(transform);
        dimension = environment.dimension();
        blendRadius = snapshot.blendRadius();
        CardinalLighting source = environment.dimension().cardinalLighting() == ProjectionEnvironment.CardinalLighting.NETHER
            ? CardinalLighting.NETHER : CardinalLighting.DEFAULT;
        lighting = new CardinalLighting(shade(source, Direction.DOWN), shade(source, Direction.UP), shade(source, Direction.NORTH),
            shade(source, Direction.SOUTH), shade(source, Direction.WEST), shade(source, Direction.EAST));
        ClientMeshSections.Section[] inputs = inputs(snapshot.view(), snapshot.center());
        meshIdentity = identity(snapshot, inputs);
        int input = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    ClientMeshSections.Section section = inputs[input++];
                    if (section != null) {
                        sections.put(SectionPos.asLong(sectionX + dx, sectionY + dy, sectionZ + dz), section);
                    }
                }
            }
        }
        ClientMeshSections.Section center = Objects.requireNonNull(sections.get(snapshot.center()), "Missing mesh section");
        SectionBiomes sourceBiomes = center.biomes();
        Biome[] palette = new Biome[sourceBiomes.palette().size()];
        Registry<Biome> registry = snapshot.registry().lookupOrThrow(Registries.BIOME);
        for (int index = 0; index < palette.length; index++) {
            Identifier id = Identifier.parse(sourceBiomes.palette().get(index));
            palette[index] = registry.getOptional(id).orElseThrow(() -> new IllegalArgumentException("Unknown destination biome " + id));
        }
        biomes = new Biomes(palette, sourceBiomes.indices());
    }

    public static PortalScene.MeshIdentity meshContext(Snapshot snapshot) {
        return identity(snapshot, null);
    }

    public static PortalScene.MeshIdentity meshIdentity(Snapshot snapshot) {
        return identity(snapshot, inputs(snapshot.view(), snapshot.center()));
    }

    public PortalScene.MeshIdentity meshIdentity() {
        return meshIdentity;
    }

    static boolean matchesMeshIdentity(ClientMeshSections.View view, long center, RegistryAccess registry,
                                       PortalScene.MeshIdentity context, PortalScene.MeshIdentity retained) {
        if (!(retained instanceof MeshIdentity proof) || proof.inputs == null || proof.registry != registry
            || !proof.sameContext(context)) {
            return false;
        }
        int centerX = SectionPos.x(center);
        int centerY = SectionPos.y(center);
        int centerZ = SectionPos.z(center);
        int input = 0;
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    if (proof.inputs[input++] != view.section(SectionPos.asLong(centerX + x, centerY + y, centerZ + z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static PortalScene.MeshIdentity identity(Snapshot snapshot, ClientMeshSections.Section[] inputs) {
        ClientMeshSections.Identity identity = snapshot.view().identity();
        return identity == null || !identity.matchesEnvironment(snapshot.environment()) ? null : new MeshIdentity(snapshot, inputs);
    }

    private static ClientMeshSections.Section[] inputs(ClientMeshSections.View view, long center) {
        ClientMeshSections.Section[] inputs = new ClientMeshSections.Section[27];
        int centerX = SectionPos.x(center);
        int centerY = SectionPos.y(center);
        int centerZ = SectionPos.z(center);
        int index = 0;
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    inputs[index++] = view.section(SectionPos.asLong(centerX + x, centerY + y, centerZ + z));
                }
            }
        }
        return inputs;
    }

    public ProjectionEnvironment.Transform transform() {
        return transform;
    }

    public BlockAndTintGetter destination() {
        return destination;
    }

    public void destinationBlock(int x, int y, int z, BlockPos.MutableBlockPos output) {
        output.set(cells.destinationX(x, y, z), cells.destinationY(x, y, z), cells.destinationZ(x, y, z));
    }

    @Override
    public BlockState getBlockState(BlockPos position) {
        ClientMeshSections.Section section = sections.get(SectionPos.asLong(position));
        return section == null ? Blocks.AIR.defaultBlockState() : section.state(cell(position));
    }

    @Override
    public FluidState getFluidState(BlockPos position) {
        return getBlockState(position).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos position) {
        return null;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return lighting;
    }

    @Override
    public int getBlockTint(BlockPos position, ColorResolver resolver) {
        Long2IntOpenHashMap cache = colors.computeIfAbsent(resolver, ignored -> {
            Long2IntOpenHashMap result = new Long2IntOpenHashMap();
            result.defaultReturnValue(-1);
            return result;
        });
        long key = position.asLong();
        int cached = cache.get(key);
        if (cached != -1) {
            return cached;
        }
        int color = tint(position, resolver);
        cache.put(key, color);
        return color;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos position) {
        ClientMeshSections.Section section = sections.get(SectionPos.asLong(position));
        return section == null ? 0 : Math.max(0, section.light(layer == LightLayer.SKY, cell(position)));
    }

    @Override
    public int getRawBrightness(BlockPos position, int skyDarken) {
        return Math.max(getBrightness(LightLayer.BLOCK, position), getBrightness(LightLayer.SKY, position) - skyDarken);
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    private int tint(BlockPos position, ColorResolver resolver) {
        Biome center = biome(position.getX(), position.getY(), position.getZ());
        if (center == null) {
            throw new IllegalStateException("Destination biome missing at " + position);
        }
        Vec3 destination = transform.destinationPoint(position.getX() + 0.5D, position.getY() + 0.5D, position.getZ() + 0.5D);
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int z = -blendRadius; z <= blendRadius; z++) {
            for (int x = -blendRadius; x <= blendRadius; x++) {
                Biome biome = biome(position.getX() + x * transform.xAxis().x() + z * transform.zAxis().x(),
                    position.getY() + x * transform.xAxis().y() + z * transform.zAxis().y(),
                    position.getZ() + x * transform.xAxis().z() + z * transform.zAxis().z());
                if (biome == null) {
                    throw new IllegalStateException("Destination biome blend exceeds captured halo at " + position);
                }
                int color = resolver.getColor(biome, Math.floor(destination.x()) + x, Math.floor(destination.z()) + z);
                red += color >> 16 & 255;
                green += color >> 8 & 255;
                blue += color & 255;
            }
        }
        int samples = (blendRadius * 2 + 1) * (blendRadius * 2 + 1);
        return ARGB.color(red / samples, green / samples, blue / samples);
    }

    private Biome biome(int x, int y, int z) {
        int cell = SectionBiomes.cell(x - biomeX, y - biomeY, z - biomeZ);
        if (cell < 0 || biomes.palette().length == 0) {
            return null;
        }
        return biomes.palette()[biomes.indices().length == 0 ? 0
            : Byte.toUnsignedInt(biomes.indices()[cell * 2]) | Byte.toUnsignedInt(biomes.indices()[cell * 2 + 1]) << 8];
    }

    private float shade(CardinalLighting source, Direction direction) {
        int x = direction.getStepX() * transform.xAxis().x() + direction.getStepY() * transform.xAxis().y()
            + direction.getStepZ() * transform.xAxis().z();
        int y = direction.getStepX() * transform.yAxis().x() + direction.getStepY() * transform.yAxis().y()
            + direction.getStepZ() * transform.yAxis().z();
        int z = direction.getStepX() * transform.zAxis().x() + direction.getStepY() * transform.zAxis().y()
            + direction.getStepZ() * transform.zAxis().z();
        return source.byFace(Direction.getNearest(x, y, z, Direction.UP));
    }

    private static int cell(BlockPos position) {
        return ((position.getY() & 15) << 8) | ((position.getZ() & 15) << 4) | (position.getX() & 15);
    }

    private final class DestinationWorld implements BlockAndTintGetter {
        private final BlockPos.MutableBlockPos display = new BlockPos.MutableBlockPos();

        private BlockPos display(BlockPos position) {
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            return display.set(cells.displayX(x, y, z), cells.displayY(x, y, z), cells.displayZ(x, y, z));
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            return ClientMeshWorld.this.getBlockState(display(position));
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            return getBlockState(position).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            return null;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return dimension.cardinalLighting() == ProjectionEnvironment.CardinalLighting.NETHER
                ? CardinalLighting.NETHER : CardinalLighting.DEFAULT;
        }

        @Override
        public int getBlockTint(BlockPos position, ColorResolver resolver) {
            return ClientMeshWorld.this.getBlockTint(display(position), resolver);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos position) {
            return ClientMeshWorld.this.getBrightness(layer, display(position));
        }

        @Override
        public int getRawBrightness(BlockPos position, int skyDarken) {
            return Math.max(getBrightness(LightLayer.BLOCK, position), getBrightness(LightLayer.SKY, position) - skyDarken);
        }

        @Override
        public int getHeight() {
            return dimension.height();
        }

        @Override
        public int getMinY() {
            return dimension.minY();
        }
    }

    public record Snapshot(ClientMeshSections.View view, long center, RegistryAccess registry, ProjectionEnvironment environment, int blendRadius) {
        public Snapshot {
            if (blendRadius < 0 || blendRadius > 7) {
                throw new IllegalArgumentException("Invalid biome blend radius");
            }
        }
    }

    private static final class MeshIdentity implements PortalScene.MeshIdentity {
        private final ClientMeshSections.Identity identity;
        private final RegistryAccess registry;
        private final ProjectionEnvironment.Dimension dimension;
        private final PlateBox bounds;
        private final int blendRadius;
        private final ClientMeshSections.Section[] inputs;
        private final int contextHash;

        private MeshIdentity(Snapshot snapshot, ClientMeshSections.Section[] inputs) {
            identity = snapshot.view().identity();
            registry = snapshot.registry();
            dimension = snapshot.environment().dimension();
            bounds = snapshot.view().bounds();
            blendRadius = snapshot.blendRadius();
            this.inputs = inputs;
            int hash = identity.hashCode();
            hash = 31 * hash + System.identityHashCode(registry);
            hash = 31 * hash + dimension.hashCode();
            hash = 31 * hash + bounds.hashCode();
            contextHash = 31 * hash + blendRadius;
        }

        @Override
        public int contextHash() {
            return contextHash;
        }

        @Override
        public boolean sameContext(PortalScene.MeshIdentity value) {
            return value instanceof MeshIdentity other && contextHash == other.contextHash && registry == other.registry
                && blendRadius == other.blendRadius && identity.equals(other.identity) && dimension.equals(other.dimension)
                && bounds.equals(other.bounds);
        }

        @Override
        public boolean same(PortalScene.MeshIdentity value) {
            if (!(value instanceof MeshIdentity other) || inputs == null || other.inputs == null || !sameContext(other)) {
                return false;
            }
            for (int index = 0; index < inputs.length; index++) {
                if (inputs[index] != other.inputs[index]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void references(ObjLongConsumer<Object> consumer) {
            if (inputs == null) {
                throw new IllegalStateException("Projection mesh context has no retained inputs");
            }
            consumer.accept(this, 112L + 16 + inputs.length * 8L);
            for (ClientMeshSections.Section input : inputs) {
                if (input != null) {
                    consumer.accept(input, input.bytes());
                }
            }
        }
    }

    private record Biomes(Biome[] palette, byte[] indices) {
    }
}
