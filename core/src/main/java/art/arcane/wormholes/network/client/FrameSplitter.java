package art.arcane.wormholes.network.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import art.arcane.wormholes.network.view.EntityVisual;

public final class FrameSplitter {
    private final int maxFrameBytes;
    private final boolean deflate;

    public FrameSplitter(int maxFrameBytes, boolean deflate) {
        this.maxFrameBytes = ClientViewProtocol.clampMaxFrameBytes(maxFrameBytes);
        this.deflate = deflate;
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    public boolean deflate() {
        return deflate;
    }

    public List<byte[]> split(List<ClientViewMessage> group, IntSupplier sequence) throws ClientViewProtocolException {
        return split(group, sequence, true);
    }

    public List<byte[]> split(List<ClientViewMessage> group, IntSupplier sequence, boolean closeGroup) throws ClientViewProtocolException {
        List<ClientViewMessage> pieces = new ArrayList<ClientViewMessage>(group.size());
        int bodyBudget = maxFrameBytes - ClientViewProtocol.S2C_HEADER_BYTES;
        for (ClientViewMessage message : group) {
            pieces.addAll(pieces(message, bodyBudget));
        }
        List<byte[]> frames = new ArrayList<byte[]>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            int flags = closeGroup && i == pieces.size() - 1 ? ClientViewProtocol.FLAG_LAST : 0;
            byte[] frame = ClientViewCodec.encodeS2C(pieces.get(i), sequence.getAsInt(), flags, deflate);
            if (frame.length > maxFrameBytes) {
                throw new ClientViewProtocolException(pieces.get(i).type() + " frame of " + frame.length + " bytes exceeds " + maxFrameBytes);
            }
            frames.add(frame);
        }
        return frames;
    }

    public List<ClientViewMessage> pieces(ClientViewMessage message, int bodyBudget) throws ClientViewProtocolException {
        return switch (message) {
            case ClientViewMessage.PlateBricks bricks -> splitBricks(bricks, bodyBudget);
            case ClientViewMessage.Palette palette -> splitPalette(palette, bodyBudget);
            case ClientViewMessage.PlatePatch patch -> splitPatch(patch, bodyBudget);
            case ClientViewMessage.EntityFrame frame -> splitEntityFrame(frame, bodyBudget);
            default -> {
                int size = ClientViewCodec.encodeBody(message).length;
                if (size > bodyBudget) {
                    throw new ClientViewProtocolException(message.type() + " body of " + size + " bytes cannot be split to fit " + bodyBudget);
                }
                yield List.of(message);
            }
        };
    }

    private static List<ClientViewMessage> splitBricks(ClientViewMessage.PlateBricks message, int bodyBudget) throws ClientViewProtocolException {
        int header = ClientViewWriter.varintSize(message.portalKey()) + 4 + 2;
        List<ClientViewMessage> out = new ArrayList<ClientViewMessage>();
        List<Brick> current = new ArrayList<Brick>();
        int used = header;
        for (Brick brick : message.bricks()) {
            int size = 2 + BrickCodec.encodedSize(brick);
            if (header + size > bodyBudget) {
                throw new ClientViewProtocolException("brick " + brick.brickIndex() + " of " + size + " bytes cannot fit a frame of " + bodyBudget);
            }
            if (used + size > bodyBudget && !current.isEmpty()) {
                out.add(new ClientViewMessage.PlateBricks(message.portalKey(), message.plateRevision(), current));
                current = new ArrayList<Brick>();
                used = header;
            }
            current.add(brick);
            used += size;
        }
        if (!current.isEmpty() || out.isEmpty()) {
            out.add(new ClientViewMessage.PlateBricks(message.portalKey(), message.plateRevision(), current));
        }
        return out;
    }

    private static List<ClientViewMessage> splitEntityFrame(ClientViewMessage.EntityFrame message, int bodyBudget)
        throws ClientViewProtocolException {
        if (ClientViewCodec.encodeBody(message).length <= bodyBudget) {
            return List.of(message);
        }
        int header = ClientViewWriter.varintSize(message.portalKey()) + 4 + 1;
        int unchanged = 2;
        int presence = message.presence() ? 2 + message.presentIds().size() * 16 : unchanged;
        if (header + presence > bodyBudget) {
            throw new ClientViewProtocolException("entity presence list of " + message.presentIds().size() + " ids cannot fit a frame of " + bodyBudget);
        }
        List<ClientViewMessage> out = new ArrayList<ClientViewMessage>();
        List<EntityVisual> current = new ArrayList<EntityVisual>();
        int used = header;
        for (EntityVisual visual : message.entities()) {
            int length = ClientViewCodec.entityBytes(visual).length;
            int size = ClientViewWriter.varintSize(length) + length;
            if (header + size + unchanged > bodyBudget) {
                throw new ClientViewProtocolException("entity " + visual.id() + " of " + size + " bytes cannot fit a frame of " + bodyBudget);
            }
            if (used + size + unchanged > bodyBudget && !current.isEmpty()) {
                out.add(new ClientViewMessage.EntityFrame(message.portalKey(), message.entitySeq(), current, List.of(), false));
                current = new ArrayList<EntityVisual>();
                used = header;
            }
            current.add(visual);
            used += size;
        }
        if (used + presence > bodyBudget) {
            out.add(new ClientViewMessage.EntityFrame(message.portalKey(), message.entitySeq(), current, List.of(), false));
            current = List.of();
        }
        out.add(new ClientViewMessage.EntityFrame(message.portalKey(), message.entitySeq(), current, message.presentIds(), message.presence()));
        return out;
    }

    private static List<ClientViewMessage> splitPalette(ClientViewMessage.Palette message, int bodyBudget) throws ClientViewProtocolException {
        List<ClientViewMessage> out = new ArrayList<ClientViewMessage>();
        List<ClientViewMessage.PaletteEntry> current = new ArrayList<ClientViewMessage.PaletteEntry>();
        int header = 3;
        int used = header;
        for (ClientViewMessage.PaletteEntry entry : message.entries()) {
            int size = ClientViewWriter.varintSize(entry.id()) + 2 + entry.state().length() * 3;
            if (header + size > bodyBudget) {
                throw new ClientViewProtocolException("palette entry " + entry.id() + " cannot fit a frame of " + bodyBudget);
            }
            if ((used + size > bodyBudget || current.size() == ClientViewProtocol.MAX_PALETTE_ENTRIES_PER_MESSAGE) && !current.isEmpty()) {
                out.add(new ClientViewMessage.Palette(current));
                current = new ArrayList<ClientViewMessage.PaletteEntry>();
                used = header;
            }
            current.add(entry);
            used += size;
        }
        if (!current.isEmpty() || out.isEmpty()) {
            out.add(new ClientViewMessage.Palette(current));
        }
        return out;
    }

    private static List<ClientViewMessage> splitPatch(ClientViewMessage.PlatePatch message, int bodyBudget) throws ClientViewProtocolException {
        int header = ClientViewWriter.varintSize(message.portalKey()) + 4 + 4 + 2;
        List<List<ClientViewMessage.PatchOp>> pieces = new ArrayList<List<ClientViewMessage.PatchOp>>();
        List<ClientViewMessage.PatchOp> current = new ArrayList<ClientViewMessage.PatchOp>();
        int used = header;
        for (ClientViewMessage.PatchOp op : message.ops()) {
            int size = opSize(op);
            if (header + size > bodyBudget) {
                throw new ClientViewProtocolException("patch op for brick " + op.brickIndex() + " cannot fit a frame of " + bodyBudget);
            }
            if (used + size > bodyBudget && !current.isEmpty()) {
                pieces.add(current);
                current = new ArrayList<ClientViewMessage.PatchOp>();
                used = header;
            }
            current.add(op);
            used += size;
        }
        if (!current.isEmpty() || pieces.isEmpty()) {
            pieces.add(current);
        }
        List<ClientViewMessage> out = new ArrayList<ClientViewMessage>(pieces.size());
        int last = pieces.size() - 1;
        for (int i = 0; i < pieces.size(); i++) {
            int to = i == last ? message.toRevision() : message.fromRevision();
            out.add(new ClientViewMessage.PlatePatch(message.portalKey(), message.fromRevision(), to, pieces.get(i)));
        }
        return out;
    }

    private static int opSize(ClientViewMessage.PatchOp op) {
        return switch (op) {
            case ClientViewMessage.FullOp full -> 3 + 2 + BrickCodec.encodedSize(full.brick());
            case ClientViewMessage.SparseOp sparse -> {
                int size = 3 + 2;
                for (int id : sparse.paletteIds()) {
                    size += 2 + ClientViewWriter.varintSize(id);
                }
                yield size;
            }
            case ClientViewMessage.ClearOp clear -> 3;
        };
    }
}
