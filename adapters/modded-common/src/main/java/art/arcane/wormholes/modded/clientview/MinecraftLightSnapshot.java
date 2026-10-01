package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.render.client.session.ClientViewPlateLight;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.render.view.ProjectionContentView;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;

import java.util.Objects;

final class MinecraftLightSnapshot implements ClientViewPlateLight.Sampler {
    private static final DataLayer OPEN_SKY = new DataLayer(15);
    private static final DataLayer DARK = new DataLayer(0);

    private final int minSectionX;
    private final int minSectionY;
    private final int minSectionZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final DataLayer[] block;
    private final DataLayer[] sky;
    private final boolean[] skyFromAbove;
    private final boolean[] loaded;

    private MinecraftLightSnapshot(int minSectionX, int minSectionY, int minSectionZ, int sizeX, int sizeY, int sizeZ) {
        this.minSectionX = minSectionX;
        this.minSectionY = minSectionY;
        this.minSectionZ = minSectionZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        int sections = sizeX * sizeY * sizeZ;
        this.block = new DataLayer[sections];
        this.sky = new DataLayer[sections];
        this.skyFromAbove = new boolean[sections];
        this.loaded = new boolean[sizeX * sizeZ];
    }

    static MinecraftLightSnapshot capture(ServerLevel level, PlateBox box) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(box, "box");
        if (box.cells() == 0L) {
            return new MinecraftLightSnapshot(0, 0, 0, 0, 0, 0);
        }
        int minSectionY = Math.max(level.getMinSectionY(), box.minY() >> 4);
        int maxSectionY = Math.min(level.getMaxSectionY(), (box.minY() + box.sizeY() - 1) >> 4);
        int minSectionX = box.minX() >> 4;
        int minSectionZ = box.minZ() >> 4;
        int sizeX = ((box.minX() + box.sizeX() - 1) >> 4) - minSectionX + 1;
        int sizeZ = ((box.minZ() + box.sizeZ() - 1) >> 4) - minSectionZ + 1;
        int sizeY = Math.max(0, maxSectionY - minSectionY + 1);
        MinecraftLightSnapshot snapshot = new MinecraftLightSnapshot(minSectionX, minSectionY, minSectionZ, sizeX, sizeY, sizeZ);
        LayerLightEventListener blockLight = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY);
        boolean hasSky = level.dimensionType().hasSkyLight();
        int worldTop = level.getMaxSectionY();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                int chunkX = minSectionX + dx;
                int chunkZ = minSectionZ + dz;
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    continue;
                }
                snapshot.loaded[dx * sizeZ + dz] = true;
                DataLayer above = null;
                boolean aboveResolved = false;
                for (int dy = sizeY - 1; dy >= 0; dy--) {
                    int sectionY = minSectionY + dy;
                    int index = snapshot.index(dx, dy, dz);
                    SectionPos position = SectionPos.of(chunkX, sectionY, chunkZ);
                    DataLayer blockLayer = blockLight.getDataLayerData(position);
                    snapshot.block[index] = blockLayer == null ? DARK : blockLayer.copy();
                    if (!hasSky) {
                        snapshot.sky[index] = DARK;
                        continue;
                    }
                    DataLayer skyLayer = skyLight.getDataLayerData(position);
                    if (skyLayer != null) {
                        snapshot.sky[index] = skyLayer.copy();
                        above = snapshot.sky[index];
                        aboveResolved = true;
                        continue;
                    }
                    if (!aboveResolved) {
                        above = resolveAbove(skyLight, chunkX, sectionY + 1, chunkZ, worldTop);
                        aboveResolved = true;
                    }
                    snapshot.sky[index] = above;
                    snapshot.skyFromAbove[index] = above != OPEN_SKY;
                }
            }
        }
        return snapshot;
    }

    @Override
    public int light(int x, int y, int z) {
        int dx = (x >> 4) - minSectionX;
        int dy = (y >> 4) - minSectionY;
        int dz = (z >> 4) - minSectionZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ || !loaded[dx * sizeZ + dz]) {
            return ClientViewPlateLight.UNAVAILABLE;
        }
        int index = index(dx, dy, dz);
        DataLayer skyLayer = sky[index];
        int skyValue = skyFromAbove[index] ? skyLayer.get(x & 15, 0, z & 15) : skyLayer.get(x & 15, y & 15, z & 15);
        return ProjectionContentView.packLight(skyValue, block[index].get(x & 15, y & 15, z & 15));
    }

    private int index(int dx, int dy, int dz) {
        return (dx * sizeY + dy) * sizeZ + dz;
    }

    private static DataLayer resolveAbove(LayerLightEventListener skyLight, int chunkX, int fromSectionY, int chunkZ, int worldTop) {
        for (int sectionY = fromSectionY; sectionY <= worldTop; sectionY++) {
            DataLayer layer = skyLight.getDataLayerData(SectionPos.of(chunkX, sectionY, chunkZ));
            if (layer != null) {
                return layer.copy();
            }
        }
        return OPEN_SKY;
    }
}
