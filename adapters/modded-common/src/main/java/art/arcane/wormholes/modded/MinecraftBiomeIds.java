package art.arcane.wormholes.modded;

import java.util.function.ToIntFunction;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

final class MinecraftBiomeIds implements ToIntFunction<String> {
    private final ServerLevel level;
    private Registry<Biome> registry;

    MinecraftBiomeIds(ServerLevel level) {
        this.level = level;
    }

    @Override
    public int applyAsInt(String key) {
        Identifier id = key == null ? null : Identifier.tryParse(key);
        if (id == null) {
            return -1;
        }
        Registry<Biome> biomes = registry();
        Biome biome = biomes.getOptional(id).orElse(null);
        return biome == null ? -1 : biomes.getId(biome);
    }

    private Registry<Biome> registry() {
        Registry<Biome> resolved = registry;
        if (resolved == null) {
            resolved = level.registryAccess().lookupOrThrow(Registries.BIOME);
            registry = resolved;
        }
        return resolved;
    }
}
