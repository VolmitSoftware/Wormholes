package art.arcane.wormholes.network.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PlatePatchEncoder {
    private PlatePatchEncoder() {
    }

    public static ClientViewMessage.PlatePatch diff(int portalKey, int fromRevision, int toRevision, EncodedPlate previous, EncodedPlate next) {
        if (!previous.sections().equals(next.sections())) {
            throw new IllegalArgumentException("plates with different section boxes cannot be patched");
        }
        List<ClientViewMessage.PatchOp> ops = new ArrayList<ClientViewMessage.PatchOp>();
        int[] before = new int[ClientViewProtocol.BRICK_CELLS];
        int[] after = new int[ClientViewProtocol.BRICK_CELLS];
        for (int index = 0; index < next.brickCount(); index++) {
            if (Arrays.equals(previous.body(index), next.body(index))) {
                continue;
            }
            Brick target = next.brick(index);
            Brick source = previous.brick(index);
            if (target.isEmpty() && !target.hasLight()) {
                ops.add(new ClientViewMessage.ClearOp(index));
                continue;
            }
            if (target.isEmpty()) {
                ops.add(new ClientViewMessage.FullOp(target));
                continue;
            }
            if (!source.sameExtras(target) || source.isEmpty()) {
                ops.add(new ClientViewMessage.FullOp(target));
                continue;
            }
            BrickCodec.unpackInto(source, before);
            BrickCodec.unpackInto(target, after);
            int changed = 0;
            for (int cell = 0; cell < ClientViewProtocol.BRICK_CELLS; cell++) {
                if (before[cell] != after[cell]) {
                    changed++;
                    if (changed >= ClientViewProtocol.SPARSE_PATCH_MAX_CELLS) {
                        break;
                    }
                }
            }
            if (changed >= ClientViewProtocol.SPARSE_PATCH_MAX_CELLS) {
                ops.add(new ClientViewMessage.FullOp(target));
                continue;
            }
            int[] cells = new int[changed];
            int[] ids = new int[changed];
            int cursor = 0;
            for (int cell = 0; cell < ClientViewProtocol.BRICK_CELLS && cursor < changed; cell++) {
                if (before[cell] != after[cell]) {
                    cells[cursor] = cell;
                    ids[cursor] = after[cell];
                    cursor++;
                }
            }
            ops.add(new ClientViewMessage.SparseOp(index, cells, ids));
        }
        return new ClientViewMessage.PlatePatch(portalKey, fromRevision, toRevision, ops);
    }

    public static Brick[] apply(Brick[] previous, ClientViewMessage.PlatePatch patch) throws ClientViewProtocolException {
        Brick[] result = previous.clone();
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        for (ClientViewMessage.PatchOp op : patch.ops()) {
            int index = op.brickIndex();
            if (index < 0 || index >= result.length) {
                throw new ClientViewProtocolException("patch op for brick " + index + " outside the plate");
            }
            switch (op) {
                case ClientViewMessage.FullOp full -> result[index] = full.brick();
                case ClientViewMessage.ClearOp clear -> result[index] = Brick.empty(index);
                case ClientViewMessage.SparseOp sparse -> {
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
