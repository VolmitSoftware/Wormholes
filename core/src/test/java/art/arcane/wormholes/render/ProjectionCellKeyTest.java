package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


public final class ProjectionCellKeyTest {
    @ParameterizedTest
    @CsvSource({"0,64,0", "-301,-64,2048", "120000,319,-120000"})
    public void blockKeyRoundTripPreservesSignedCoordinates(int x, int y, int z) {
        long key = ProjectionCellKey.pack(x, y, z);
        assertEquals(x, ProjectionCellKey.unpackX(key));
        assertEquals(y, ProjectionCellKey.unpackY(key));
        assertEquals(z, ProjectionCellKey.unpackZ(key));
    }

    @Test
    public void packedCoordinatesKeepTheirSignedBitWidthsAcrossIntegerOverflow() {
        long key = ProjectionCellKey.pack(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertEquals(-1, ProjectionCellKey.unpackX(key));
        assertEquals(0, ProjectionCellKey.unpackY(key));
        assertEquals(0, ProjectionCellKey.unpackZ(key));
        assertEquals(ProjectionCellKey.pack(-1, 0, 0), key);
        assertEquals(-2048, ProjectionCellKey.unpackY(ProjectionCellKey.pack(0, 2048, 0)));
        assertEquals(2047, ProjectionCellKey.unpackY(ProjectionCellKey.pack(0, -2049, 0)));
    }

}
