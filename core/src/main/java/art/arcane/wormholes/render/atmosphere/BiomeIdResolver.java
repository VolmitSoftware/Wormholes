package art.arcane.wormholes.render.atmosphere;

/** Resolves a namespaced biome key to the client registry id, or -1 when the biome is unknown. */
@FunctionalInterface
public interface BiomeIdResolver {
    int id(String biomeKey);
}
