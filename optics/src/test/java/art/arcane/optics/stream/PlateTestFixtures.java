package art.arcane.optics.stream;

import java.util.UUID;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.PlateGrid;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateKey;

public final class PlateTestFixtures {
    private PlateTestFixtures() {
    }

    public static <B> ViewPlate<B> tracked(UUID portal, BlockBox box, UUID world, long version) {
        PlateGrid<B> grid = new PlateGrid.Writer<B>(box).finish();
        return new ViewPlate<B>(new ViewPlateKey(portal, box, false, 0, 0), grid, 0, 0, world, version,
            box.minX() >> 4, box.minZ() >> 4, (box.minX() + box.sizeX() - 1) >> 4,
            (box.minZ() + box.sizeZ() - 1) >> 4, ViewPlate.estimateBytes(grid), null);
    }

    public static <B> ViewPlate<B> empty(ViewPlateKey key, BlockBox box) {
        PlateGrid<B> grid = new PlateGrid.Writer<B>(box).finish();
        return new ViewPlate<B>(key, grid, 0L, 0L, null, Long.MIN_VALUE, 0, 0, -1, -1, ViewPlate.estimateBytes(grid), null);
    }
}
