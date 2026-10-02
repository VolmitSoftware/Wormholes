package art.arcane.wormholes.render.plate;

import java.util.UUID;

public final class PlateTestFixtures {
    private PlateTestFixtures() {
    }

    public static <B> ViewPlate<B> tracked(UUID portal, PlateBox box, UUID world, long version) {
        PlateGrid<B> grid = new PlateGrid.Writer<B>(box).finish();
        return new ViewPlate<B>(new ViewPlateKey(portal, box, false, 0, 0), grid, 0, 0, world, version,
            box.minX() >> 4, box.minZ() >> 4, (box.minX() + box.sizeX() - 1) >> 4,
            (box.minZ() + box.sizeZ() - 1) >> 4, ViewPlate.estimateBytes(grid), null);
    }

    public static <B> ViewPlate<B> empty(ViewPlateKey key, PlateBox box) {
        PlateGrid<B> grid = new PlateGrid.Writer<B>(box).finish();
        return new ViewPlate<B>(key, grid, 0L, 0L, null, Long.MIN_VALUE, 0, 0, -1, -1, ViewPlate.estimateBytes(grid), null);
    }
}
