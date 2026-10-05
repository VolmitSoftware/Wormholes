package art.arcane.wormholes.util;

import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GChunkTest {
    @Test
    void loadedChunksAndCoordinatesAddressTheSameWorldSpecificEntry() {
        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(-17);
        when(chunk.getZ()).thenReturn(32);
        Map<GChunk, String> entries = new HashMap<>();
        entries.put(new GChunk(chunk), "rune");

        assertEquals("rune", entries.get(new GChunk(-17, 32, "minecraft:overworld")));
        assertNull(entries.get(new GChunk(-17, 32, "minecraft:the_nether")));
        assertNull(entries.get(new GChunk(-16, 32, "minecraft:overworld")));
    }
}
