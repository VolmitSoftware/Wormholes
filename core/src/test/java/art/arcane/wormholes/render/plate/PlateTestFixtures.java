package art.arcane.wormholes.render.plate;

public final class PlateTestFixtures {
    private PlateTestFixtures() {
    }

    public static <B> ViewPlate<B> empty(ViewPlateKey key, PlateBox box) {
        PlateGrid<B> grid = new PlateGrid.Writer<B>(box).finish();
        return new ViewPlate<B>(key, grid, 0L, 0L, null, Long.MIN_VALUE, 0, 0, -1, -1, ViewPlate.estimateBytes(grid));
    }
}
