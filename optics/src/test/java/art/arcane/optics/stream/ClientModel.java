package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.internal.stream.PlatePatchEncoder;

final class ClientModel {
    final ViewStreamCodec codec;
    final Map<Integer, String> palette = new HashMap<Integer, String>();
    final Map<Integer, ApertureDescriptor> portals = new HashMap<Integer, ApertureDescriptor>();
    final Map<Integer, Brick[]> plates = new HashMap<Integer, Brick[]>();
    final Map<Integer, Integer> plateRevisions = new HashMap<Integer, Integer>();
    final Map<Long, Brick> brickCache = new HashMap<Long, Brick>();
    final Set<Integer> standbyKeys = new HashSet<Integer>();
    final List<ViewStreamMessage> received = new ArrayList<ViewStreamMessage>();
    final List<byte[]> outbound = new ArrayList<byte[]>();
    final Map<Integer, OpenPlate> open = new HashMap<Integer, OpenPlate>();
    final Map<Integer, Integer> closedById = new HashMap<Integer, Integer>();
    final List<ViewStreamMessage.BrickMiss.Plate> pendingMisses = new ArrayList<ViewStreamMessage.BrickMiss.Plate>();
    long caps;
    long salt;
    int lastSeq = -1;
    int lastClosedSeq = -1;
    int lastAckedSeq = -1;
    boolean autoMiss = true;
    boolean autoAck;
    boolean allowUnannouncedPlates;
    ViewStreamMessage.Offer offer;
    ViewStreamMessage.Accept accept;

    ClientModel(ViewStreamCodec codec) {
        this.codec = codec;
        resetPalette();
    }

    void receive(List<byte[]> frames) throws ViewStreamProtocolException {
        for (byte[] frame : frames) {
            receive(frame);
        }
        frames.clear();
        if (!pendingMisses.isEmpty()) {
            outbound.add(codec.encodeC2S(new ViewStreamMessage.BrickMiss(pendingMisses)));
            pendingMisses.clear();
        }
        if (autoAck && lastClosedSeq - lastAckedSeq > 0) {
            outbound.add(ack());
        }
    }

    void receive(byte[] frame) throws ViewStreamProtocolException {
        ViewStreamCodec.S2CFrame decoded = codec.decodeS2C(frame, caps);
        ViewStreamMessage message = decoded.message();
        received.add(message);
        boolean last = decoded.last();
        switch (message) {
            case ViewStreamMessage.Offer m -> offer = m;
            case ViewStreamMessage.Accept m -> {
                accept = m;
                caps = m.caps();
                salt = m.hashSalt();
            }
            case ViewStreamMessage.Decline m -> caps = 0L;
            case ViewStreamMessage.Palette m -> {
                for (ViewStreamMessage.PaletteEntry entry : m.entries()) {
                    String known = palette.putIfAbsent(entry.id(), entry.state());
                    if (known != null) {
                        assertEquals(known, entry.state(), "palette id " + entry.id() + " changed meaning");
                    }
                }
            }
            case ViewStreamMessage.Portal m -> {
                assertKnown(m.geometry().blackoutState(), "PORTAL blackout");
                portals.put(m.portalKey(), m.geometry());
            }
            case ViewStreamMessage.PortalDrop m -> {
                portals.remove(m.portalKey());
                plates.remove(m.portalKey());
                open.remove(m.portalKey());
                plateRevisions.remove(m.portalKey());
            }
            case ViewStreamMessage.PlateBegin m -> {
                if (!portals.containsKey(m.portalKey())) {
                    assertTrue(allowUnannouncedPlates, "PLATE_BEGIN for key " + m.portalKey() + " before its PORTAL");
                    standbyKeys.add(m.portalKey());
                }
                assertKnown(m.backingState(), "backing state");
                OpenPlate plate = new OpenPlate(m);
                open.put(m.portalKey(), plate);
                if (m.hasHashes()) {
                    boolean[] missed = new boolean[m.brickCount()];
                    for (int i = 0; i < m.brickCount(); i++) {
                        missed[i] = !brickCache.containsKey(m.brickHashes()[i]);
                    }
                    plate.missed = missed;
                    if (autoMiss) {
                        pendingMisses.add(new ViewStreamMessage.BrickMiss.Plate(m.portalKey(), m.plateRevision(),
                            ViewStreamMessage.BrickMiss.Plate.bitsetFor(m.brickCount(), missed)));
                    }
                }
                assertTrue(!last, "PLATE_BEGIN must not close its group");
            }
            case ViewStreamMessage.PlateBricks m -> {
                OpenPlate plate = open.get(m.portalKey());
                assertNotNull(plate, "PLATE_BRICKS without an open PLATE_BEGIN");
                assertEquals(plate.begin.plateRevision(), m.plateRevision());
                for (Brick brick : m.bricks()) {
                    assertBrickKnown(brick);
                    plate.bricks[brick.brickIndex()] = brick;
                }
            }
            case ViewStreamMessage.PlateEnd m -> {
                assertTrue(last, "PLATE_END must close its group");
                OpenPlate plate = open.remove(m.portalKey());
                assertNotNull(plate, "PLATE_END without an open PLATE_BEGIN");
                commit(plate);
            }
            case ViewStreamMessage.PlatePatch m -> {
                assertEquals(last, m.advances(), "only the PLATE_PATCH piece that closes its group advances the revision");
                Brick[] current = plates.get(m.portalKey());
                assertNotNull(current, "PLATE_PATCH for a plate the client does not hold");
                assertEquals(plateRevisions.get(m.portalKey()).intValue(), m.fromRevision());
                for (ViewStreamMessage.PatchOp op : m.ops()) {
                    if (op instanceof ViewStreamMessage.FullOp full) {
                        assertBrickKnown(full.brick());
                    } else if (op instanceof ViewStreamMessage.SparseOp sparse) {
                        for (int id : sparse.paletteIds()) {
                            assertKnown(id, "sparse patch");
                        }
                    }
                }
                plates.put(m.portalKey(), PlatePatchEncoder.apply(current, m));
                plateRevisions.put(m.portalKey(), m.toRevision());
            }
            case ViewStreamMessage.SessionReset m -> {
                portals.clear();
                plates.clear();
                plateRevisions.clear();
                open.clear();
                resetPalette();
            }
            default -> {
            }
        }
        ViewStreamMessageType type = message instanceof ViewStreamMessage.Projection projection ? projection.type() : null;
        if (type == ViewStreamMessageType.OFFER || type == ViewStreamMessageType.ACCEPT || type == ViewStreamMessageType.DECLINE) {
            return;
        }
        lastSeq = decoded.seq();
        if (last) {
            lastClosedSeq = decoded.seq();
            closedById.merge(message.id(), 1, Integer::sum);
        }
    }

    byte[] hello(int dataVersion, long clientCaps, String brand, long nonceFound) throws ViewStreamProtocolException {
        return codec.encodeC2S(ViewStreamHandshake.clientHello(offer, dataVersion, clientCaps,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 256, nonceFound, brand));
    }

    byte[] ack() throws ViewStreamProtocolException {
        return ack(lastClosedSeq);
    }

    byte[] ack(int seq) throws ViewStreamProtocolException {
        lastAckedSeq = seq;
        return codec.encodeC2S(new ViewStreamMessage.Ack(seq, 0, 1));
    }

    int closed(ViewStreamMessageType type) {
        return closed(type.id());
    }

    int closed(int id) {
        Integer count = closedById.get(id);
        return count == null ? 0 : count;
    }

    List<ViewStreamMessage> since(int index) {
        return new ArrayList<ViewStreamMessage>(received.subList(index, received.size()));
    }

    int count(ViewStreamMessageType type) {
        return count(type.id());
    }

    int count(int id) {
        int count = 0;
        for (ViewStreamMessage message : received) {
            if (message.id() == id) {
                count++;
            }
        }
        return count;
    }

    private void commit(OpenPlate plate) {
        ViewStreamMessage.PlateBegin begin = plate.begin;
        Brick[] bricks = plate.bricks;
        for (int i = 0; i < bricks.length; i++) {
            if (bricks[i] != null) {
                if (begin.hasHashes()) {
                    brickCache.put(begin.brickHashes()[i], bricks[i]);
                }
                continue;
            }
            if (!begin.hasHashes()) {
                fail("brick " + i + " of key " + begin.portalKey() + " never arrived");
            }
            Brick cached = brickCache.get(begin.brickHashes()[i]);
            assertNotNull(cached, "brick " + i + " neither sent nor cached");
            bricks[i] = cached.withIndex(i);
        }
        plates.put(begin.portalKey(), bricks);
        plateRevisions.put(begin.portalKey(), begin.plateRevision());
    }

    private void assertBrickKnown(Brick brick) {
        for (int id : BrickCodec.unpack(brick)) {
            assertKnown(id, "brick " + brick.brickIndex());
        }
    }

    private void assertKnown(int id, String where) {
        assertTrue(palette.containsKey(id), where + " references palette id " + id + " before any PALETTE carried it");
    }

    private void resetPalette() {
        palette.clear();
        palette.put(ViewStreamLimits.PALETTE_AIR, SessionPalette.AIR);
        palette.put(ViewStreamLimits.PALETTE_OCCLUDED, SessionPalette.OCCLUDED);
        palette.put(ViewStreamLimits.PALETTE_BACKING, SessionPalette.BACKING);
    }

    static final class OpenPlate {
        final ViewStreamMessage.PlateBegin begin;
        final Brick[] bricks;
        boolean[] missed;

        OpenPlate(ViewStreamMessage.PlateBegin begin) {
            this.begin = begin;
            this.bricks = new Brick[begin.brickCount()];
        }
    }
}
