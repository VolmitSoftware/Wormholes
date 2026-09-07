package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public final class ProjectionCellKeyOwnershipTest {
    @Test
    public void portalSkinFluidClaimsAreKeyedByTheSharedCellLayout() {
        int[][] cells = new int[][] {
            {0, 0, 0},
            {1, 64, 2},
            {-1, -64, -2},
            {30_000_000, 319, -30_000_000},
            {-30_000_000, -2032, 30_000_000}
        };
        List<Vector> positions = new ArrayList<Vector>();
        for (int[] cell : cells) {
            positions.add(new Vector(cell[0], cell[1], cell[2]));
        }

        Long2ObjectOpenHashMap<ProjectedBlockClaim> claims = PortalSkinRenderer.fluidClaims(positions, null);

        assertEquals(cells.length, claims.size(), "every skinned cell must produce exactly one distinct claim key");
        for (int[] cell : cells) {
            long key = ProjectionCellKey.pack(cell[0], cell[1], cell[2]);
            assertTrue(claims.containsKey(key),
                "the skin renderer must key its claims with the same layout the arbiter decodes with");
            assertEquals(cell[0], ProjectionCellKey.unpackX(key), "x round trip");
            assertEquals(cell[1], ProjectionCellKey.unpackY(key), "y round trip");
            assertEquals(cell[2], ProjectionCellKey.unpackZ(key), "z round trip");
        }
    }
}
