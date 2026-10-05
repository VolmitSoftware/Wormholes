package art.arcane.wormholes.modded.client.render;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class PortalTerrainMaterialsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void independentEquivalentSnapshotsReuseValueKeysAndRejectEveryChangedMaterialInput() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        Map<BlockState, Integer> ids = Map.of(stone, 31000);
        PortalTerrainMaterials.Lighting lighting = PortalTerrainMaterials.Lighting.VANILLA;
        PortalTerrainMaterials original = new PortalTerrainMaterials(true, ids, 7, lighting);
        PortalTerrainMaterials equivalent = new PortalTerrainMaterials(true, new HashMap<>(ids), 7,
            new PortalTerrainMaterials.Lighting(1, false, false));
        Map<PortalTerrainMaterials, String> cache = new HashMap<>();
        cache.put(original, "mesh");
        assertEquals(original, equivalent);
        assertEquals(equivalent, original);
        assertEquals(original.hashCode(), equivalent.hashCode());
        assertEquals("mesh", cache.get(equivalent));
        for (PortalTerrainMaterials changed : new PortalTerrainMaterials[] {
            new PortalTerrainMaterials(false, ids, 7, lighting),
            new PortalTerrainMaterials(true, ids, 8, lighting),
            new PortalTerrainMaterials(true, Map.of(stone, 31001), 7, lighting),
            new PortalTerrainMaterials(true, ids, 7, new PortalTerrainMaterials.Lighting(0.5F, false, false)),
            new PortalTerrainMaterials(true, ids, 7, new PortalTerrainMaterials.Lighting(1, true, false)),
            new PortalTerrainMaterials(true, ids, 7, new PortalTerrainMaterials.Lighting(1, false, true))}) {
            assertNotEquals(original, changed);
            assertNull(cache.get(changed));
        }
        assertFalse(original.equals(null));
        assertFalse(original.equals(ids));
    }

    @Test
    public void blockIdHashCollisionsKeepDistinctSnapshotsAndCachedMeshesSeparate() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        PortalTerrainMaterials first = new PortalTerrainMaterials(true, Map.of(stone, stone.hashCode(), dirt, dirt.hashCode()),
            7, PortalTerrainMaterials.Lighting.VANILLA);
        PortalTerrainMaterials second = new PortalTerrainMaterials(true, Map.of(stone, stone.hashCode() ^ 1, dirt, dirt.hashCode() ^ -1),
            7, PortalTerrainMaterials.Lighting.VANILLA);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, second);
        Map<PortalTerrainMaterials, String> cache = new HashMap<>();
        cache.put(first, "first");
        cache.put(second, "second");
        assertEquals("first", cache.get(first));
        assertEquals("second", cache.get(second));
    }

    @Test
    public void sourceMutationsCannotChangeTheSnapshotOrItsCachedHash() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        Map<BlockState, Integer> source = new HashMap<>();
        source.put(stone, 31000);
        PortalTerrainMaterials materials = new PortalTerrainMaterials(true, source, 7, PortalTerrainMaterials.Lighting.VANILLA);
        int hash = materials.hashCode();
        source.put(stone, 7);
        source.put(dirt, 8);
        assertEquals(31000, materials.blockId(stone));
        assertEquals(-1, materials.blockId(dirt));
        assertEquals(hash, materials.hashCode());
        assertThrows(UnsupportedOperationException.class, () -> materials.blockIds().put(stone, 9));
    }
}
