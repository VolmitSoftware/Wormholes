package art.arcane.wormholes.ops.importers;

import art.arcane.wormholes.util.Direction;

/** One portal an importer wants built: where it sits, which way it faces, and what it linked to. */
public record ImportedPortal(String name, String worldName, int x, int y, int z, Direction facing,
                             int width, int height, String destination) {
}
