package art.arcane.wormholes.render.view;

import art.arcane.optics.stream.BiomeVisual;
import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.terrain.NativeGenerationRegistry;
import io.papermc.paper.InternalAPIBridge;
import org.bukkit.ChunkSnapshot;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class BukkitBiomeVisualsTest {
    @Test
    void customRegistryVisualsAreCapturedOnceAndRemainAvailableAfterSourceUnload() throws ClassNotFoundException {
        Class.forName("org.bukkit.Registry");
        try (MockedStatic<InternalAPIBridge> bridge = mockStatic(InternalAPIBridge.class)) {
            bridge.when(InternalAPIBridge::get).thenReturn(mock(InternalAPIBridge.class));
            Class.forName("org.bukkit.block.Biome");
        }
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        Biome biome = mock(Biome.class);
        when(biome.getKey()).thenReturn(new NamespacedKey("iris", "biomes/test"));
        when(snapshot.getBiome(anyInt(), anyInt(), anyInt())).thenReturn(biome);
        NativeGenerationRegistry registry = mock(NativeGenerationRegistry.class);
        when(registry.generatedDefinition("minecraft:worldgen/biome", "iris:biomes/test"))
            .thenReturn(NativeGenerationRegistry.Definition.exactJson("""
                {"temperature":0.7,"downfall":0.8,"has_precipitation":true,
                "effects":{"water_color":1193046,"grass_color":11259375,"grass_color_modifier":"swamp"}}
                """));
        AtomicInteger reads = new AtomicInteger();
        Map<String, BiomeVisual> captured;
        try (MockedStatic<NativeAdapters> adapters = mockStatic(NativeAdapters.class)) {
            adapters.when(() -> NativeAdapters.require(NativeGenerationRegistry.class)).thenReturn(registry);
            captured = BukkitBiomeVisuals.capture(snapshot, -64, 319, key -> {
                reads.incrementAndGet();
                return BukkitBiomeVisuals.capture(key);
            });
        }
        when(snapshot.getBiome(anyInt(), anyInt(), anyInt())).thenThrow(new IllegalStateException("unloaded"));
        assertEquals(1, reads.get());
        assertEquals(0x123456, captured.get("iris:biomes/test").waterColor());
        assertEquals(0xABCDEF, captured.get("iris:biomes/test").grassColor());
        assertEquals(BiomeVisual.GrassModifier.SWAMP, captured.get("iris:biomes/test").grassModifier());
    }
}
