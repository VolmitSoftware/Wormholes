package art.arcane.wormholes.ops.importers;

import art.arcane.optics.math.Face;

/** One portal an importer wants built: where it sits, which way it faces, and what it linked to. */
public record ImportedPortal(String name, String worldName, int x, int y, int z, Face facing,
                             int width, int height, String destination) {
}
