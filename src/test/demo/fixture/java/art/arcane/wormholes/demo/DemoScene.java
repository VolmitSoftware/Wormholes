package art.arcane.wormholes.demo;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.type.Leaves;

final class DemoScene {
    private final World world;

    DemoScene(World world) {
        this.world = world;
    }

    void build(boolean markers) {
        base(0, Material.GRASS_BLOCK);
        base(200, Material.SANDSTONE);
        garden();
        courtyard();
        frame(0, Material.DEEPSLATE_TILES);
        frame(200, Material.CUT_SANDSTONE);
        if (markers) {
            markers(0, Material.GLASS);
            markers(200, Material.GLASS);
        } else {
            backing(Material.GLASS);
        }
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(4000L);
        world.setSpawnLocation(0, 70, 7);
    }

    void backing(Material material) {
        for (int x = -1; x <= 1; x++) {
            for (int y = 70; y <= 72; y++) {
                block(x, y, -1, material);
            }
        }
    }

    void markers(int offset, Material material) {
        block(-1, 70, offset, material);
        block(1, 72, offset, material);
    }

    boolean hasFrame(int offset) {
        for (int x = -2; x <= 2; x++) {
            if (world.getBlockAt(x, 69, offset).isEmpty() || world.getBlockAt(x, 73, offset).isEmpty()) {
                return false;
            }
        }
        for (int y = 70; y <= 72; y++) {
            if (world.getBlockAt(-2, y, offset).isEmpty() || world.getBlockAt(2, y, offset).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void base(int offset, Material top) {
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                for (int y = 70; y <= 95; y++) {
                    block(x, y, offset + z, Material.AIR);
                }
                for (int y = 66; y <= 68; y++) {
                    block(x, y, offset + z, Material.STONE);
                }
                block(x, 69, offset + z, top);
            }
        }
    }

    private void garden() {
        path(0, Material.STONE_BRICKS, Material.ANDESITE);
        for (int z = -20; z <= 20; z++) {
            if (z % 5 == 0) {
                flowerBed(-5, z);
                flowerBed(5, z);
            }
        }
        tree(-12, -12);
        tree(12, -16);
        tree(-17, 12);
        tree(16, 17);
        tree(-24, -23);
        tree(24, 25);
        tree(23, -25);
        pond(10, -8);
        bench(-9, 9);
        bench(8, 12);
        for (int x = -22; x <= 22; x++) {
            for (int z = -22; z <= 22; z++) {
                if (Math.abs(x) > 8 && (x * 31 + z * 17) % 43 == 0 && world.getBlockAt(x, 70, z).isEmpty()) {
                    block(x, 70, z, (x + z) % 2 == 0 ? Material.OXEYE_DAISY : Material.SHORT_GRASS);
                }
            }
        }
    }

    private void courtyard() {
        path(200, Material.SMOOTH_SANDSTONE, Material.TERRACOTTA);
        for (int coordinate = -20; coordinate <= 20; coordinate++) {
            block(coordinate, 70, 180, Material.CUT_SANDSTONE);
            block(coordinate, 70, 220, Material.CUT_SANDSTONE);
            block(-20, 70, 200 + coordinate, Material.CUT_SANDSTONE);
            block(20, 70, 200 + coordinate, Material.CUT_SANDSTONE);
        }
        pavilion(-11, 184);
        pavilion(11, 216);
        desertPlant(-12, 211);
        desertPlant(12, 190);
        desertPlant(-16, 183);
        desertPlant(15, 219);
        fountain(9, 201);
        for (int x = -28; x <= 28; x++) {
            for (int z = -28; z <= 28; z++) {
                if (Math.abs(x) > 21 || Math.abs(z) > 21) {
                    block(x, 69, 200 + z, Material.SAND);
                }
            }
        }
    }

    private void path(int offset, Material main, Material edge) {
        for (int z = -28; z <= 28; z++) {
            for (int x = -3; x <= 3; x++) {
                block(x, 69, offset + z, Math.abs(x) == 3 ? edge : main);
            }
        }
        for (int x = -17; x <= 17; x++) {
            for (int z = 7; z <= 11; z++) {
                block(x, 69, offset + z, main);
            }
        }
    }

    private void frame(int offset, Material material) {
        for (int x = -2; x <= 2; x++) {
            block(x, 69, offset, material);
            block(x, 73, offset, material);
        }
        for (int y = 70; y <= 72; y++) {
            block(-2, y, offset, material);
            block(2, y, offset, material);
        }
        for (int side : new int[]{-4, 4}) {
            block(side, 70, offset, material);
            block(side, 71, offset, Material.LANTERN);
        }
    }

    private void flowerBed(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            block(x + dx, 70, z, dx == 0 ? Material.PINK_TULIP : Material.AZALEA);
        }
    }

    private void tree(int x, int z) {
        for (int y = 70; y <= 75; y++) {
            block(x, y, z, Material.OAK_LOG);
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = 73; y <= 77; y++) {
                    if (Math.abs(dx) + Math.abs(dz) + Math.abs(y - 75) <= 5 && (dx != 0 || dz != 0 || y > 75)) {
                        leaf(x + dx, y, z + dz);
                    }
                }
            }
        }
    }

    private void pond(int x, int z) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx * dx + dz * dz <= 10) {
                    block(x + dx, 68, z + dz, Material.CLAY);
                    block(x + dx, 69, z + dz, Material.WATER);
                }
            }
        }
        block(x, 70, z, Material.LILY_PAD);
    }

    private void bench(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            block(x + dx, 70, z, Material.SPRUCE_SLAB);
            block(x + dx, 70, z + 1, Material.SPRUCE_TRAPDOOR);
        }
    }

    private void pavilion(int x, int z) {
        for (int dx : new int[]{-3, 3}) {
            for (int dz : new int[]{-3, 3}) {
                for (int y = 70; y <= 74; y++) {
                    block(x + dx, y, z + dz, Material.SMOOTH_SANDSTONE);
                }
            }
        }
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                block(x + dx, 75, z + dz, Material.CUT_SANDSTONE_SLAB);
            }
        }
        block(x, 70, z, Material.CHISELED_SANDSTONE);
        block(x, 71, z, Material.LANTERN);
    }

    private void desertPlant(int x, int z) {
        block(x, 69, z, Material.SAND);
        for (int y = 70; y <= 72; y++) {
            block(x, y, z, Material.CACTUS);
        }
        block(x + 3, 69, z + 1, Material.SAND);
        block(x + 3, 70, z + 1, Material.DEAD_BUSH);
    }

    private void fountain(int x, int z) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean rim = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                block(x + dx, 70, z + dz, rim ? Material.SMOOTH_SANDSTONE : Material.WATER);
                if (!rim) {
                    block(x + dx, 69, z + dz, Material.PRISMARINE);
                }
            }
        }
        block(x, 70, z, Material.CUT_SANDSTONE);
        block(x, 71, z, Material.CUT_SANDSTONE);
        block(x, 72, z, Material.WATER);
    }

    private void leaf(int x, int y, int z) {
        Leaves leaves = (Leaves) Material.OAK_LEAVES.createBlockData();
        leaves.setPersistent(true);
        world.getBlockAt(x, y, z).setBlockData(leaves, false);
    }

    private void block(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }
}
