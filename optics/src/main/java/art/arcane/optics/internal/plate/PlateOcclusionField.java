package art.arcane.optics.internal.plate;

import art.arcane.optics.view.BlockStates;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.BlockBox;

public final class PlateOcclusionField<B, M> {
    private static final byte UNKNOWN = 0;
    private static final byte OCCLUDING = 1;
    private static final byte OPEN = 2;
    private static final long MAX_CELLS = 16L * 1024L * 1024L;

    private final ContentView<B, M> view;
    private final BlockStates<B, M> blocks;
    private final BlockBox box;
    private final byte[] states;

    public PlateOcclusionField(ContentView<B, M> view, BlockStates<B, M> blocks, BlockBox box) {
        this.view = view;
        this.blocks = blocks;
        this.box = box.cells() > MAX_CELLS ? BlockBox.EMPTY : box;
        this.states = new byte[(int) this.box.cells()];
    }

    public int depth(int x, int y, int z, B self) {
        if (self == null || !blocks.isOccluding(blocks.material(self))) {
            return 0;
        }
        remember(x, y, z);
        if (!occluding(x + 1, y, z)
            || !occluding(x - 1, y, z)
            || !occluding(x, y + 1, z)
            || !occluding(x, y - 1, z)
            || !occluding(x, y, z + 1)
            || !occluding(x, y, z - 1)) {
            return 0;
        }
        if (!occluding(x + 2, y, z)
            || !occluding(x + 1, y + 1, z)
            || !occluding(x + 1, y - 1, z)
            || !occluding(x + 1, y, z + 1)
            || !occluding(x + 1, y, z - 1)
            || !occluding(x - 2, y, z)
            || !occluding(x - 1, y + 1, z)
            || !occluding(x - 1, y - 1, z)
            || !occluding(x - 1, y, z + 1)
            || !occluding(x - 1, y, z - 1)
            || !occluding(x, y + 2, z)
            || !occluding(x, y + 1, z + 1)
            || !occluding(x, y + 1, z - 1)
            || !occluding(x, y - 2, z)
            || !occluding(x, y - 1, z + 1)
            || !occluding(x, y - 1, z - 1)
            || !occluding(x, y, z + 2)
            || !occluding(x, y, z - 2)) {
            return 1;
        }
        return 2;
    }

    private void remember(int x, int y, int z) {
        int index = box.index(x, y, z);
        if (index >= 0) {
            states[index] = OCCLUDING;
        }
    }

    private boolean occluding(int x, int y, int z) {
        int index = box.index(x, y, z);
        if (index < 0) {
            return blocks.isOccluding(view.material(x, y, z));
        }
        byte state = states[index];
        if (state != UNKNOWN) {
            return state == OCCLUDING;
        }
        boolean occluding = blocks.isOccluding(view.material(x, y, z));
        states[index] = occluding ? OCCLUDING : OPEN;
        return occluding;
    }
}
