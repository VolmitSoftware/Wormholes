package art.arcane.wormholes.render.plate;

import java.util.ArrayList;
import java.util.Arrays;

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.render.ProjectorFrameTransform;
import art.arcane.wormholes.render.view.ProjectionContentView;

public final class PlateEnvironment implements BrickLightSource {
    private final byte[] block;
    private final byte[] sky;
    private final SectionBiomes biomes;
    private final long bytes;

    private PlateEnvironment(SectionBiomes biomes, Light light) {
        this.biomes = biomes;
        block = compact(light.block());
        sky = compact(light.sky());
        long memory = 128L + block.length + sky.length + biomes.indices().length;
        for (String key : biomes.palette()) {
            memory += 48 + key.length() * 2L;
        }
        bytes = memory;
    }

    public static PlateEnvironment capture(PlateBox box, ProjectorFrameTransform transform, ProjectionContentView<?, ?> view) {
        if (box.sizeX() != 16 || box.sizeY() != 16 || box.sizeZ() != 16) {
            return null;
        }
        double[] remote = new double[3];
        ArrayList<String> palette = new ArrayList<String>();
        byte[] indices = new byte[64];
        for (int cell = 0; cell < 64; cell++) {
            transform.apply(box.minX() + (cell & 3) * 4 + 2.0D, box.minY() + (cell >> 4) * 4 + 2.0D,
                box.minZ() + (cell >> 2 & 3) * 4 + 2.0D, remote);
            String biome = view.sampleBiome((int) Math.floor(remote[0]), (int) Math.floor(remote[1]), (int) Math.floor(remote[2]));
            if (biome == null) {
                return null;
            }
            int index = palette.indexOf(biome);
            if (index < 0) {
                index = palette.size();
                palette.add(biome);
            }
            indices[cell] = (byte) index;
        }
        byte[] block = new byte[2048];
        byte[] sky = new byte[2048];
        for (int cell = 0; cell < 4096; cell++) {
            transform.apply(box.minX() + (cell & 15) + 0.5D, box.minY() + (cell >> 8) + 0.5D,
                box.minZ() + (cell >> 4 & 15) + 0.5D, remote);
            int light = view.getLight((int) Math.floor(remote[0]), (int) Math.floor(remote[1]), (int) Math.floor(remote[2]));
            if (light == ProjectionContentView.LIGHT_UNAVAILABLE) {
                return null;
            }
            BrickLightSource.setNibble(block, cell, ProjectionContentView.unpackBlockLight(light));
            BrickLightSource.setNibble(sky, cell, ProjectionContentView.unpackSkyLight(light));
        }
        return new PlateEnvironment(new SectionBiomes(palette, palette.size() == 1 ? new byte[0] : indices), new Light(block, sky));
    }

    public SectionBiomes biomes() {
        return biomes;
    }

    public long bytes() {
        return bytes;
    }

    @Override
    public boolean fill(int sectionX, int sectionY, int sectionZ, byte[] blockNibbles, byte[] skyNibbles) {
        expand(block, blockNibbles);
        expand(sky, skyNibbles);
        return true;
    }
    private static byte[] compact(byte[] values) {
        for (int i = 1; i < values.length; i++) {
            if (values[i] != values[0]) {
                return values;
            }
        }
        return new byte[] {values[0]};
    }

    private static void expand(byte[] values, byte[] target) {
        if (values.length == 1) {
            Arrays.fill(target, values[0]);
        } else {
            System.arraycopy(values, 0, target, 0, values.length);
        }
    }

    private record Light(byte[] block, byte[] sky) {
    }

}
