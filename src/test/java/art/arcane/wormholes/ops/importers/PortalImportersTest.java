package art.arcane.wormholes.ops.importers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortalImportersTest {
    @Test
    void everyAdvertisedIdResolvesAndUnknownIdsDoNot() {
        for (String id : PortalImporters.ids()) {
            PortalImporter importer = PortalImporters.byId(id, 0, 0);
            assertEquals(id, importer.id());
        }
        assertNull(PortalImporters.byId("stargate-legacy", 0, 0));
        assertNull(PortalImporters.byId(null, 0, 0));
    }

    @Test
    void theFrameOptionIsWidthCommaHeightOrNothing() {
        assertArrayEquals(new int[]{0, 0}, PortalImporters.parseFrame(""));
        assertArrayEquals(new int[]{2, 3}, PortalImporters.parseFrame("2,3"));
        assertThrows(IllegalArgumentException.class, () -> PortalImporters.parseFrame("2"));
        assertThrows(IllegalArgumentException.class, () -> PortalImporters.parseFrame("0,3"));
        assertThrows(IllegalArgumentException.class, () -> PortalImporters.parseFrame("wide,tall"));
    }
}
