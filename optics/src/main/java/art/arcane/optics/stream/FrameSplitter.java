package art.arcane.optics.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

import art.arcane.optics.entity.EntitySnapshot;

public final class FrameSplitter {
    private final int maxFrameBytes;
    private final boolean deflate;
    private final ViewStreamCodec codec;

    public FrameSplitter(int maxFrameBytes, boolean deflate, ViewStreamCodec codec) {
        this.maxFrameBytes = ViewStreamLimits.clampMaxFrameBytes(maxFrameBytes);
        this.deflate = deflate;
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    public boolean deflate() {
        return deflate;
    }

    public List<byte[]> split(List<ViewStreamMessage> group, IntSupplier sequence) throws ViewStreamProtocolException {
        return split(group, sequence, true);
    }

    public List<byte[]> split(List<ViewStreamMessage> group, IntSupplier sequence, boolean closeGroup) throws ViewStreamProtocolException {
        List<ViewStreamMessage> pieces = new ArrayList<ViewStreamMessage>(group.size());
        int bodyBudget = maxFrameBytes - ViewStreamLimits.S2C_HEADER_BYTES;
        for (ViewStreamMessage message : group) {
            pieces.addAll(pieces(message, bodyBudget));
        }
        List<byte[]> frames = new ArrayList<byte[]>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            int flags = closeGroup && i == pieces.size() - 1 ? ViewStreamLimits.FLAG_LAST : 0;
            byte[] frame = codec.encodeS2C(pieces.get(i), sequence.getAsInt(), flags, deflate);
            if (frame.length > maxFrameBytes) {
                throw new ViewStreamProtocolException(codec.name(pieces.get(i)) + " frame of " + frame.length + " bytes exceeds " + maxFrameBytes);
            }
            frames.add(frame);
        }
        return frames;
    }

    public List<ViewStreamMessage> pieces(ViewStreamMessage message, int bodyBudget) throws ViewStreamProtocolException {
        return switch (message) {
            case ViewStreamMessage.PlateBricks bricks -> splitBricks(bricks, bodyBudget);
            case ViewStreamMessage.Palette palette -> splitPalette(palette, bodyBudget);
            case ViewStreamMessage.PlatePatch patch -> splitPatch(patch, bodyBudget);
            case ViewStreamMessage.EntityFrame frame -> splitEntityFrame(frame, bodyBudget);
            default -> {
                int size = codec.encodeBody(message).length;
                if (size > bodyBudget) {
                    throw new ViewStreamProtocolException(codec.name(message) + " body of " + size + " bytes cannot be split to fit " + bodyBudget);
                }
                yield List.of(message);
            }
        };
    }

    private static List<ViewStreamMessage> splitBricks(ViewStreamMessage.PlateBricks message, int bodyBudget) throws ViewStreamProtocolException {
        int header = ViewStreamWriter.varintSize(message.portalKey()) + 4 + 2;
        List<ViewStreamMessage> out = new ArrayList<ViewStreamMessage>();
        List<Brick> current = new ArrayList<Brick>();
        int used = header;
        for (Brick brick : message.bricks()) {
            int size = 2 + BrickCodec.encodedSize(brick);
            if (header + size > bodyBudget) {
                throw new ViewStreamProtocolException("brick " + brick.brickIndex() + " of " + size + " bytes cannot fit a frame of " + bodyBudget);
            }
            if (used + size > bodyBudget && !current.isEmpty()) {
                out.add(new ViewStreamMessage.PlateBricks(message.portalKey(), message.plateRevision(), current));
                current = new ArrayList<Brick>();
                used = header;
            }
            current.add(brick);
            used += size;
        }
        if (!current.isEmpty() || out.isEmpty()) {
            out.add(new ViewStreamMessage.PlateBricks(message.portalKey(), message.plateRevision(), current));
        }
        return out;
    }

    private static List<ViewStreamMessage> splitEntityFrame(ViewStreamMessage.EntityFrame message, int bodyBudget)
        throws ViewStreamProtocolException {
        if (ViewStreamCodec.projectionBody(message).length <= bodyBudget) {
            return List.of(message);
        }
        int header = ViewStreamWriter.varintSize(message.portalKey()) + 4 + 1;
        int unchanged = 2;
        int presence = message.presence() ? 2 + message.presentIds().size() * 16 : unchanged;
        if (header + presence > bodyBudget) {
            throw new ViewStreamProtocolException("entity presence list of " + message.presentIds().size() + " ids cannot fit a frame of " + bodyBudget);
        }
        List<ViewStreamMessage> out = new ArrayList<ViewStreamMessage>();
        List<EntitySnapshot> current = new ArrayList<EntitySnapshot>();
        int used = header;
        for (EntitySnapshot visual : message.entities()) {
            int length = ViewStreamCodec.entityBytes(visual).length;
            int size = ViewStreamWriter.varintSize(length) + length;
            if (header + size + unchanged > bodyBudget) {
                throw new ViewStreamProtocolException("entity " + visual.id() + " of " + size + " bytes cannot fit a frame of " + bodyBudget);
            }
            if (used + size + unchanged > bodyBudget && !current.isEmpty()) {
                out.add(new ViewStreamMessage.EntityFrame(message.portalKey(), message.entitySeq(), current, List.of(), false));
                current = new ArrayList<EntitySnapshot>();
                used = header;
            }
            current.add(visual);
            used += size;
        }
        if (used + presence > bodyBudget) {
            out.add(new ViewStreamMessage.EntityFrame(message.portalKey(), message.entitySeq(), current, List.of(), false));
            current = List.of();
        }
        out.add(new ViewStreamMessage.EntityFrame(message.portalKey(), message.entitySeq(), current, message.presentIds(), message.presence()));
        return out;
    }

    private static List<ViewStreamMessage> splitPalette(ViewStreamMessage.Palette message, int bodyBudget) throws ViewStreamProtocolException {
        List<ViewStreamMessage> out = new ArrayList<ViewStreamMessage>();
        List<ViewStreamMessage.PaletteEntry> current = new ArrayList<ViewStreamMessage.PaletteEntry>();
        int header = 3;
        int used = header;
        for (ViewStreamMessage.PaletteEntry entry : message.entries()) {
            int size = ViewStreamWriter.varintSize(entry.id()) + 2 + entry.state().length() * 3;
            if (header + size > bodyBudget) {
                throw new ViewStreamProtocolException("palette entry " + entry.id() + " cannot fit a frame of " + bodyBudget);
            }
            if ((used + size > bodyBudget || current.size() == ViewStreamLimits.MAX_PALETTE_ENTRIES_PER_MESSAGE) && !current.isEmpty()) {
                out.add(new ViewStreamMessage.Palette(current));
                current = new ArrayList<ViewStreamMessage.PaletteEntry>();
                used = header;
            }
            current.add(entry);
            used += size;
        }
        if (!current.isEmpty() || out.isEmpty()) {
            out.add(new ViewStreamMessage.Palette(current));
        }
        return out;
    }

    private static List<ViewStreamMessage> splitPatch(ViewStreamMessage.PlatePatch message, int bodyBudget) throws ViewStreamProtocolException {
        int header = ViewStreamWriter.varintSize(message.portalKey()) + 4 + 4 + 2;
        List<List<ViewStreamMessage.PatchOp>> pieces = new ArrayList<List<ViewStreamMessage.PatchOp>>();
        List<ViewStreamMessage.PatchOp> current = new ArrayList<ViewStreamMessage.PatchOp>();
        int used = header;
        for (ViewStreamMessage.PatchOp op : message.ops()) {
            int size = opSize(op);
            if (header + size > bodyBudget) {
                throw new ViewStreamProtocolException("patch op for brick " + op.brickIndex() + " cannot fit a frame of " + bodyBudget);
            }
            if (used + size > bodyBudget && !current.isEmpty()) {
                pieces.add(current);
                current = new ArrayList<ViewStreamMessage.PatchOp>();
                used = header;
            }
            current.add(op);
            used += size;
        }
        if (!current.isEmpty() || pieces.isEmpty()) {
            pieces.add(current);
        }
        List<ViewStreamMessage> out = new ArrayList<ViewStreamMessage>(pieces.size());
        int last = pieces.size() - 1;
        for (int i = 0; i < pieces.size(); i++) {
            int to = i == last ? message.toRevision() : message.fromRevision();
            out.add(new ViewStreamMessage.PlatePatch(message.portalKey(), message.fromRevision(), to, pieces.get(i)));
        }
        return out;
    }

    private static int opSize(ViewStreamMessage.PatchOp op) {
        return switch (op) {
            case ViewStreamMessage.FullOp full -> 3 + 2 + BrickCodec.encodedSize(full.brick());
            case ViewStreamMessage.SparseOp sparse -> {
                int size = 3 + 2;
                for (int id : sparse.paletteIds()) {
                    size += 2 + ViewStreamWriter.varintSize(id);
                }
                yield size;
            }
            case ViewStreamMessage.ClearOp clear -> 3;
        };
    }
}
