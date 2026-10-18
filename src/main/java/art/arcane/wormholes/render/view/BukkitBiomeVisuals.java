package art.arcane.wormholes.render.view;

import art.arcane.optics.stream.BiomeVisual;
import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.terrain.NativeGenerationRegistry;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.BiomeVisuals;
import com.google.gson.JsonParser;
import org.bukkit.ChunkSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class BukkitBiomeVisuals {
    private BukkitBiomeVisuals() {
    }

    public static Map<String, BiomeVisual> capture(ChunkSnapshot snapshot, int minimum, int maximum, Function<String, BiomeVisual> resolver) {
        Map<String, BiomeVisual> captured = new HashMap<>();
        for (int y = minimum & ~3; y <= maximum; y += 4) {
            for (int z = 0; z < 16; z += 4) {
                for (int x = 0; x < 16; x += 4) {
                    String key = WormholesPlatform.keyString(snapshot.getBiome(x, y, z).getKey());
                    captured.computeIfAbsent(key, resolver);
                }
            }
        }
        return Map.copyOf(captured);
    }

    public static BiomeVisual capture(String key) {
        NativeGenerationRegistry.Definition definition = NativeAdapters.require(NativeGenerationRegistry.class)
            .generatedDefinition("minecraft:worldgen/biome", key);
        if (definition.encoding() != NativeGenerationRegistry.Encoding.JSON) {
            throw new IllegalStateException("Destination biome has no visual definition: " + key);
        }
        return BiomeVisuals.read(JsonParser.parseString(definition.value()).getAsJsonObject());
    }
}
