package art.arcane.optics.frame;

public final class AxisPermutation {
    public static final AxisPermutation IDENTITY = new AxisPermutation(0);

    private final int index;

    private AxisPermutation(int index) {
        this.index = index;
    }

    public static AxisPermutation ofIndex(int index) {
        throw new UnsupportedOperationException();
    }

    public int index() {
        return index;
    }

    public boolean reflects() {
        throw new UnsupportedOperationException();
    }

    public boolean flipsWorldUp() {
        throw new UnsupportedOperationException();
    }

    public int quarterTurnsClockwise() {
        throw new UnsupportedOperationException();
    }

    public int rotation16(int rotation) {
        throw new UnsupportedOperationException();
    }

    public AxisPermutation compose(AxisPermutation inner) {
        throw new UnsupportedOperationException();
    }

    public AxisPermutation inverse() {
        throw new UnsupportedOperationException();
    }

    public void vectorInto(double x, double y, double z, double[] out3) {
        throw new UnsupportedOperationException();
    }

    public void cellInto(int x, int y, int z, int[] out3) {
        throw new UnsupportedOperationException();
    }
}
