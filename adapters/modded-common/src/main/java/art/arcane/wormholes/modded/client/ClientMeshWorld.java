package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.render.client.ClientViewBlockTransform;
import art.arcane.wormholes.network.client.SectionBiomes;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
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

public final class ClientMeshWorld implements BlockAndTintGetter {
    private final Long2ObjectOpenHashMap<ClientMeshSections.Section> sections = new Long2ObjectOpenHashMap<>(27);
    private final Long2ObjectOpenHashMap<Biomes> biomes = new Long2ObjectOpenHashMap<>(27);
    private final Map<ColorResolver, Long2IntOpenHashMap> colors = new IdentityHashMap<>();
    private final ClientViewEnvironment.Transform transform;
    private final ClientViewBlockTransform cells;
    private final CardinalLighting lighting;
    private final int blendRadius;
    private final int minY;
    private final int height;
    private final ClientViewEnvironment.Dimension dimension;
    private final DestinationWorld destination = new DestinationWorld();

    public ClientMeshWorld(Snapshot snapshot) {
        ClientViewEnvironment environment = Objects.requireNonNull(snapshot.environment());
        int sectionX = SectionPos.x(snapshot.center());
        int sectionY = SectionPos.y(snapshot.center());
        int sectionZ = SectionPos.z(snapshot.center());
        minY = snapshot.view().bounds().minY();
        height = snapshot.view().bounds().sizeY();
        transform = environment.transform();
        cells = new ClientViewBlockTransform(transform);
        dimension = environment.dimension();
        blendRadius = snapshot.blendRadius();
        CardinalLighting source = environment.dimension().cardinalLighting() == ClientViewEnvironment.CardinalLighting.NETHER
            ? CardinalLighting.NETHER : CardinalLighting.DEFAULT;
        lighting = new CardinalLighting(shade(source, Direction.DOWN), shade(source, Direction.UP), shade(source, Direction.NORTH),
            shade(source, Direction.SOUTH), shade(source, Direction.WEST), shade(source, Direction.EAST));
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    long key = SectionPos.asLong(sectionX + dx, sectionY + dy, sectionZ + dz);
                    ClientMeshSections.Section section = snapshot.view().section(key);
                    if (section == null) {
                        continue;
                    }
                    sections.put(key, section);
                    SectionBiomes sourceBiomes = section.biomes();
                    Biome[] palette = new Biome[sourceBiomes.palette().size()];
                    for (int index = 0; index < palette.length; index++) {
                        Identifier id = Identifier.parse(sourceBiomes.palette().get(index));
                        palette[index] = snapshot.biomes().getOptional(id).orElseThrow(() -> new IllegalArgumentException("Unknown destination biome " + id));
                    }
                    biomes.put(key, new Biomes(palette, sourceBiomes.indices()));
                }
            }
        }
    }

    public ClientViewEnvironment.Transform transform() {
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
        GeometryVector destination = transform.destinationPoint(position.getX() + 0.5D, position.getY() + 0.5D, position.getZ() + 0.5D);
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int z = -blendRadius; z <= blendRadius; z++) {
            for (int x = -blendRadius; x <= blendRadius; x++) {
                Biome biome = biome(position.getX() + x * transform.xAxis().x() + z * transform.zAxis().x(),
                    position.getY() + x * transform.xAxis().y() + z * transform.zAxis().y(),
                    position.getZ() + x * transform.xAxis().z() + z * transform.zAxis().z());
                int color = resolver.getColor(biome == null ? center : biome, Math.floor(destination.x()) + x, Math.floor(destination.z()) + z);
                red += color >> 16 & 255;
                green += color >> 8 & 255;
                blue += color & 255;
            }
        }
        int samples = (blendRadius * 2 + 1) * (blendRadius * 2 + 1);
        return ARGB.color(red / samples, green / samples, blue / samples);
    }

    private Biome biome(int x, int y, int z) {
        Biomes section = biomes.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        if (section == null || section.palette().length == 0) {
            return null;
        }
        int cell = ((y & 15) >> 2) << 4 | ((z & 15) >> 2) << 2 | ((x & 15) >> 2);
        return section.palette()[section.indices().length == 0 ? 0 : Byte.toUnsignedInt(section.indices()[cell])];
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
            return dimension.cardinalLighting() == ClientViewEnvironment.CardinalLighting.NETHER
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

    public record Snapshot(ClientMeshSections.View view, long center, Registry<Biome> biomes, ClientViewEnvironment environment, int blendRadius) {
        public Snapshot {
            if (blendRadius < 0 || blendRadius > 7) {
                throw new IllegalArgumentException("Invalid biome blend radius");
            }
        }
    }

    private record Biomes(Biome[] palette, byte[] indices) {
    }
}
