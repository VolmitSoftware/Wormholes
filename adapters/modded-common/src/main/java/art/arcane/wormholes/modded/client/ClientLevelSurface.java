package art.arcane.wormholes.modded.client;

import art.arcane.optics.fidelity.BlockEntitySample;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.storage.TagValueInput;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public final class ClientLevelSurface implements ClientViewSurface {
    public static final int WRITE_FLAGS = Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final long BLOCK_ENTITY_NBT_QUOTA = BlockEntitySample.MAX_NBT_BYTES * 16L;

    private final ClientLevel level;
    private final LevelLightEngine lightEngine;
    private final boolean bulk;
    private final Long2ObjectOpenHashMap<TouchedSection> touched;
    private final LongOpenHashSet dirtySections;
    private final BlockPos.MutableBlockPos cursor;
    private long blockEntityFailures;

    public ClientLevelSurface(ClientLevel level, boolean bulk) {
        this.level = Objects.requireNonNull(level, "level");
        this.lightEngine = level.getLightEngine();
        this.bulk = bulk;
        this.touched = bulk ? new Long2ObjectOpenHashMap<>(64) : null;
        this.dirtySections = bulk ? new LongOpenHashSet(128) : null;
        this.cursor = new BlockPos.MutableBlockPos();
    }

    public ClientLevel level() {
        return level;
    }

    public boolean bulk() {
        return bulk;
    }

    @Override
    public boolean chunkLoaded(int chunkX, int chunkZ) {
        return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
    }

    @Override
    public BlockState state(int x, int y, int z) {
        if (level.isOutsideBuildHeight(y)) {
            return null;
        }
        LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) {
            return null;
        }
        return chunk.getBlockState(cursor.set(x, y, z));
    }

    @Override
    public void write(int x, int y, int z, BlockState state) {
        if (level.isOutsideBuildHeight(y)) {
            return;
        }
        ClientLightGate.enter(level, lightEngine);
        try {
            if (bulk) {
                writeBulk(x, y, z, state);
            } else {
                level.setServerVerifiedBlockState(new BlockPos(x, y, z), state, WRITE_FLAGS);
            }
        } finally {
            ClientLightGate.exit();
        }
    }

    @Override
    public void blockEntity(int x, int y, int z, BlockEntitySample sample) {
        BlockEntity entity = level.getBlockEntity(new BlockPos(x, y, z));
        if (entity != null) {
            load(entity, sample);
        }
    }

    public void rebuildBlockEntity(LevelChunk chunk, int x, int y, int z, BlockEntitySample sample) {
        BlockPos position = new BlockPos(x, y, z);
        chunk.removeBlockEntity(position);
        BlockEntity entity = chunk.getBlockEntity(position, LevelChunk.EntityCreationType.IMMEDIATE);
        if (entity != null && sample != null) {
            load(entity, sample);
        }
    }

    @Override
    public void attachLight(ClientLightPatches patches) {
        ClientLightPatches.bind(lightEngine.getLayerListener(LightLayer.BLOCK), lightEngine.getLayerListener(LightLayer.SKY), level::getSkyDarken, patches);
    }

    @Override
    public void detachLight(ClientLightPatches patches) {
        ClientLightPatches.unbind(patches);
    }

    @Override
    public void lightChanged(int sectionX, int sectionY, int sectionZ, int boundaryMask) {
        int minX = (boundaryMask & BORDER_WEST) != 0 ? -1 : 0;
        int maxX = (boundaryMask & BORDER_EAST) != 0 ? 1 : 0;
        int minY = (boundaryMask & BORDER_DOWN) != 0 ? -1 : 0;
        int maxY = (boundaryMask & BORDER_UP) != 0 ? 1 : 0;
        int minZ = (boundaryMask & BORDER_NORTH) != 0 ? -1 : 0;
        int maxZ = (boundaryMask & BORDER_SOUTH) != 0 ? 1 : 0;
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = minY; dy <= maxY; dy++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    Minecraft.getInstance().levelExtractor.setSectionDirty(sectionX + dx, sectionY + dy, sectionZ + dz);
                }
            }
        }
    }

    @Override
    public int skyDarken() {
        return level.getSkyDarken();
    }

    @Override
    public void flush() {
        if (!bulk || touched.isEmpty()) {
            return;
        }
        ObjectIterator<Long2ObjectMap.Entry<TouchedSection>> iterator = touched.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            TouchedSection section = iterator.next().getValue();
            boolean empty = section.section.hasOnlyAir();
            if (empty != section.wasEmpty) {
                lightEngine.updateSectionStatus(SectionPos.of(section.x, section.y, section.z), empty);
                level.getChunkSource().onSectionEmptinessChanged(section.x, section.y, section.z, empty);
            }
            dilate(section);
        }
        touched.clear();
        for (long key : dirtySections) {
            Minecraft.getInstance().levelExtractor.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
        }
        dirtySections.clear();
    }

    public long blockEntityFailures() {
        return blockEntityFailures;
    }

    private void load(BlockEntity entity, BlockEntitySample sample) {
        Identifier typeKey = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
        if (typeKey == null || !typeKey.toString().equals(sample.typeKey())) {
            return;
        }
        try {
            CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(sample.nbt())), NbtAccounter.create(BLOCK_ENTITY_NBT_QUOTA));
            entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        } catch (IOException | RuntimeException failure) {
            blockEntityFailures++;
        }
    }

    private void writeBulk(int x, int y, int z, BlockState state) {
        LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }
        int sectionIndex = chunk.getSectionIndex(y);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
            return;
        }
        LevelChunkSection section = chunk.getSection(sectionIndex);
        int sectionY = SectionPos.blockToSectionCoord(y);
        long key = SectionPos.asLong(x >> 4, sectionY, z >> 4);
        TouchedSection tracked = touched.get(key);
        if (tracked == null) {
            tracked = new TouchedSection(x >> 4, sectionY, z >> 4, section, section.hasOnlyAir());
            touched.put(key, tracked);
        }
        int localX = x & 15;
        int localY = y & 15;
        int localZ = z & 15;
        BlockState previous = writeSection(chunk, section, x, y, z, state);
        if (previous == null || previous == state) {
            return;
        }
        tracked.borders |= (localX == 0 ? BORDER_WEST : 0) | (localX == 15 ? BORDER_EAST : 0)
            | (localY == 0 ? BORDER_DOWN : 0) | (localY == 15 ? BORDER_UP : 0)
            | (localZ == 0 ? BORDER_NORTH : 0) | (localZ == 15 ? BORDER_SOUTH : 0);
    }

    private BlockState writeSection(LevelChunk chunk, LevelChunkSection section, int x, int y, int z, BlockState state) {
        if (state.hasBlockEntity()) {
            return chunk.setBlockState(new BlockPos(x, y, z), state, WRITE_FLAGS);
        }
        int localX = x & 15;
        int localY = y & 15;
        int localZ = z & 15;
        BlockState previous = section.setBlockState(localX, localY, localZ, state, false);
        if (previous == state) {
            return previous;
        }
        for (Map.Entry<Heightmap.Types, Heightmap> heightmap : chunk.getHeightmaps()) {
            heightmap.getValue().update(localX, y, localZ, state);
        }
        if (previous.hasBlockEntity()) {
            chunk.removeBlockEntity(new BlockPos(x, y, z));
        }
        return previous;
    }

    private void dilate(TouchedSection section) {
        int minX = (section.borders & BORDER_WEST) != 0 ? -1 : 0;
        int maxX = (section.borders & BORDER_EAST) != 0 ? 1 : 0;
        int minY = (section.borders & BORDER_DOWN) != 0 ? -1 : 0;
        int maxY = (section.borders & BORDER_UP) != 0 ? 1 : 0;
        int minZ = (section.borders & BORDER_NORTH) != 0 ? -1 : 0;
        int maxZ = (section.borders & BORDER_SOUTH) != 0 ? 1 : 0;
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = minY; dy <= maxY; dy++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    dirtySections.add(SectionPos.asLong(section.x + dx, section.y + dy, section.z + dz));
                }
            }
        }
    }

    private static final class TouchedSection {
        private final int x;
        private final int y;
        private final int z;
        private final LevelChunkSection section;
        private final boolean wasEmpty;
        private int borders;

        private TouchedSection(int x, int y, int z, LevelChunkSection section, boolean wasEmpty) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.section = section;
            this.wasEmpty = wasEmpty;
        }
    }
}
