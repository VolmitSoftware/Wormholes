package art.arcane.wormholes.render.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

public final class ProjectionWorldViewMaterialTest {
    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "toString", "getAsString" -> String.valueOf(material);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> null;
            });
    }

    private static final class StubView implements ProjectionWorldView {
        private final Map<String, BlockData> blocks = new HashMap<String, BlockData>();

        private void put(int x, int y, int z, Material material) {
            blocks.put(x + ":" + y + ":" + z, blockData(material));
        }

        @Override
        public World getWorld() {
            return null;
        }

        @Override
        public int getMinHeight() {
            return -64;
        }

        @Override
        public int getMaxHeight() {
            return 320;
        }

        @Override
        public BlockData sampleBlockData(int x, int y, int z) {
            return blocks.get(x + ":" + y + ":" + z);
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return LIGHT_UNAVAILABLE;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }
    }

    @Test
    public void defaultMaterialSamplingMirrorsTheBlockDataSample() {
        StubView view = new StubView();
        view.put(1, 2, 3, Material.STONE);
        view.put(1, 3, 3, Material.CAVE_AIR);

        assertEquals(Material.STONE, view.sampleMaterial(1, 2, 3));
        assertEquals(Material.CAVE_AIR, view.sampleMaterial(1, 3, 3));
    }

    @Test
    public void defaultMaterialSamplingReportsMissingBlocksAsNull() {
        StubView view = new StubView();

        assertNull(view.sampleMaterial(9, 9, 9));
        assertNull(view.sampleBlockData(9, 9, 9));
    }
}
