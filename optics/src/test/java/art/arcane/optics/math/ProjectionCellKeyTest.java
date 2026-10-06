package art.arcane.optics.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


public final class ProjectionCellKeyTest {
    @ParameterizedTest
    @CsvSource({"0,64,0", "-301,-64,2048", "120000,319,-120000"})
    public void blockKeyRoundTripPreservesSignedCoordinates(int x, int y, int z) {
        long key = CellKeys.pack(x, y, z);
        assertEquals(x, CellKeys.unpackX(key));
        assertEquals(y, CellKeys.unpackY(key));
        assertEquals(z, CellKeys.unpackZ(key));
    }

    @Test
    public void packedCoordinatesKeepTheirSignedBitWidthsAcrossIntegerOverflow() {
        long key = CellKeys.pack(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        assertEquals(-1, CellKeys.unpackX(key));
        assertEquals(0, CellKeys.unpackY(key));
        assertEquals(0, CellKeys.unpackZ(key));
        assertEquals(CellKeys.pack(-1, 0, 0), key);
        assertEquals(-2048, CellKeys.unpackY(CellKeys.pack(0, 2048, 0)));
        assertEquals(2047, CellKeys.unpackY(CellKeys.pack(0, -2049, 0)));
    }

}
