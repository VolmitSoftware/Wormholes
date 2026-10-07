package art.arcane.optics.internal.stream;

import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PlatePatchEncoder {
    private PlatePatchEncoder() {
    }

    public static ViewStreamMessage.PlatePatch diff(int portalKey, int fromRevision, int toRevision, EncodedPlate previous, EncodedPlate next) {
        if (!previous.sections().equals(next.sections())) {
            throw new IllegalArgumentException("plates with different section boxes cannot be patched");
        }
        List<ViewStreamMessage.PatchOp> ops = new ArrayList<ViewStreamMessage.PatchOp>();
        int[] before = new int[ViewStreamLimits.BRICK_CELLS];
        int[] after = new int[ViewStreamLimits.BRICK_CELLS];
        for (int index = 0; index < next.brickCount(); index++) {
            if (Arrays.equals(previous.body(index), next.body(index))) {
                continue;
            }
            Brick target = next.brick(index);
            Brick source = previous.brick(index);
            if (target.isEmpty() && !target.hasLight()) {
                ops.add(new ViewStreamMessage.ClearOp(index));
                continue;
            }
            if (target.isEmpty()) {
                ops.add(new ViewStreamMessage.FullOp(target));
                continue;
            }
            if (!source.sameExtras(target) || source.isEmpty()) {
                ops.add(new ViewStreamMessage.FullOp(target));
                continue;
            }
            BrickCodec.unpackInto(source, before);
            BrickCodec.unpackInto(target, after);
            int changed = 0;
            int sparseBytes = 2;
            int fullBytes = 2 + next.body(index).length;
            for (int cell = 0; cell < ViewStreamLimits.BRICK_CELLS; cell++) {
                if (before[cell] != after[cell]) {
                    changed++;
                    sparseBytes += 2 + ViewStreamWriter.varintSize(after[cell]);
                    if (changed >= ViewStreamLimits.SPARSE_PATCH_MAX_CELLS || sparseBytes >= fullBytes) {
                        break;
                    }
                }
            }
            if (changed >= ViewStreamLimits.SPARSE_PATCH_MAX_CELLS || sparseBytes >= fullBytes) {
                ops.add(new ViewStreamMessage.FullOp(target));
                continue;
            }
            int[] cells = new int[changed];
            int[] ids = new int[changed];
            int cursor = 0;
            for (int cell = 0; cell < ViewStreamLimits.BRICK_CELLS && cursor < changed; cell++) {
                if (before[cell] != after[cell]) {
                    cells[cursor] = cell;
                    ids[cursor] = after[cell];
                    cursor++;
                }
            }
            ops.add(new ViewStreamMessage.SparseOp(index, cells, ids));
        }
        return new ViewStreamMessage.PlatePatch(portalKey, fromRevision, toRevision, ops);
    }

    public static Brick[] apply(Brick[] previous, ViewStreamMessage.PlatePatch patch) throws ViewStreamProtocolException {
        Brick[] result = previous.clone();
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        for (ViewStreamMessage.PatchOp op : patch.ops()) {
            int index = op.brickIndex();
            if (index < 0 || index >= result.length) {
                throw new ViewStreamProtocolException("patch op for brick " + index + " outside the plate");
            }
            switch (op) {
                case ViewStreamMessage.FullOp full -> result[index] = full.brick();
                case ViewStreamMessage.ClearOp clear -> result[index] = Brick.empty(index);
                case ViewStreamMessage.SparseOp sparse -> {
                    Brick base = result[index];
                    BrickCodec.unpackInto(base, cells);
                    int[] indices = sparse.cellIndices();
                    int[] ids = sparse.paletteIds();
                    for (int i = 0; i < indices.length; i++) {
                        cells[indices[i]] = ids[i];
                    }
                    Brick repacked = BrickCodec.pack(index, cells);
                    if (base.hasLight()) {
                        repacked = repacked.withLight(base.blockLight(), base.skyLight());
                    }
                    if (base.hasBlockEntities() && !repacked.isEmpty()) {
                        repacked = repacked.withBlockEntities(base.blockEntities());
                    }
                    result[index] = repacked;
                }
            }
        }
        return result;
    }
}
