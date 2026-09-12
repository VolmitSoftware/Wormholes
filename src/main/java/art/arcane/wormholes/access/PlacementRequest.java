package art.arcane.wormholes.access;

import org.bukkit.World;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One placement question: may {@code playerId} do {@code kind} at these block cells. Cells are
 * {@code {x, y, z}} triples in {@code world}.
 */
public record PlacementRequest(UUID playerId, World world, List<int[]> cells, PlacementKind kind) {
    public PlacementRequest {
        world = Objects.requireNonNull(world, "world");
        kind = Objects.requireNonNull(kind, "kind");
        cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
    }

    public PlacementRequest withCells(List<int[]> replacement) {
        return new PlacementRequest(playerId, world, replacement, kind);
    }
}
