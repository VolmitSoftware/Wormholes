package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.BrickCodec;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewHandshake;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.PlatePatchEncoder;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.render.client.ClientPortalGeometry;

final class ClientModel {
    final Map<Integer, String> palette = new HashMap<Integer, String>();
    final Map<Integer, ClientPortalGeometry> portals = new HashMap<Integer, ClientPortalGeometry>();
    final Map<Integer, Brick[]> plates = new HashMap<Integer, Brick[]>();
    final Map<Integer, Integer> plateRevisions = new HashMap<Integer, Integer>();
    final Map<Long, Brick> brickCache = new HashMap<Long, Brick>();
    final Set<Integer> standbyKeys = new HashSet<Integer>();
    final List<ClientViewMessage> received = new ArrayList<ClientViewMessage>();
    final List<byte[]> outbound = new ArrayList<byte[]>();
    final Map<Integer, OpenPlate> open = new HashMap<Integer, OpenPlate>();
    final Map<ClientViewMessageType, Integer> closedByType = new EnumMap<ClientViewMessageType, Integer>(ClientViewMessageType.class);
    final List<ClientViewMessage.BrickMiss.Plate> pendingMisses = new ArrayList<ClientViewMessage.BrickMiss.Plate>();
    long caps;
    long salt;
    int lastSeq = -1;
    int lastClosedSeq = -1;
    int lastAckedSeq = -1;
    boolean autoMiss = true;
    boolean autoAck;
    boolean allowUnannouncedPlates;
    ClientViewMessage.Offer offer;
    ClientViewMessage.Accept accept;

    ClientModel() {
        resetPalette();
    }

    void receive(List<byte[]> frames) throws ClientViewProtocolException {
        for (byte[] frame : frames) {
            receive(frame);
        }
        frames.clear();
        if (!pendingMisses.isEmpty()) {
            outbound.add(ClientViewCodec.encodeC2S(new ClientViewMessage.BrickMiss(pendingMisses)));
            pendingMisses.clear();
        }
        if (autoAck && lastClosedSeq - lastAckedSeq > 0) {
            outbound.add(ack());
        }
    }

    void receive(byte[] frame) throws ClientViewProtocolException {
        ClientViewCodec.S2CFrame decoded = ClientViewCodec.decodeS2C(frame, caps);
        ClientViewMessage message = decoded.message();
        received.add(message);
        boolean last = decoded.last();
        switch (message) {
            case ClientViewMessage.Offer m -> offer = m;
            case ClientViewMessage.Accept m -> {
                accept = m;
                caps = m.caps();
                salt = m.hashSalt();
            }
            case ClientViewMessage.Decline m -> caps = 0L;
            case ClientViewMessage.Palette m -> {
                for (ClientViewMessage.PaletteEntry entry : m.entries()) {
                    String known = palette.putIfAbsent(entry.id(), entry.state());
                    if (known != null) {
                        assertEquals(known, entry.state(), "palette id " + entry.id() + " changed meaning");
                    }
                }
            }
            case ClientViewMessage.Portal m -> {
                assertKnown(m.geometry().blackoutState(), "PORTAL blackout");
                portals.put(m.portalKey(), m.geometry());
            }
            case ClientViewMessage.PortalDrop m -> {
                portals.remove(m.portalKey());
                plates.remove(m.portalKey());
                open.remove(m.portalKey());
                plateRevisions.remove(m.portalKey());
            }
            case ClientViewMessage.PlateBegin m -> {
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
                        pendingMisses.add(new ClientViewMessage.BrickMiss.Plate(m.portalKey(), m.plateRevision(),
                            ClientViewMessage.BrickMiss.Plate.bitsetFor(m.brickCount(), missed)));
                    }
                }
                assertTrue(!last, "PLATE_BEGIN must not close its group");
            }
            case ClientViewMessage.PlateBricks m -> {
                OpenPlate plate = open.get(m.portalKey());
                assertNotNull(plate, "PLATE_BRICKS without an open PLATE_BEGIN");
                assertEquals(plate.begin.plateRevision(), m.plateRevision());
                for (Brick brick : m.bricks()) {
                    assertBrickKnown(brick);
                    plate.bricks[brick.brickIndex()] = brick;
                }
            }
            case ClientViewMessage.PlateEnd m -> {
                assertTrue(last, "PLATE_END must close its group");
                OpenPlate plate = open.remove(m.portalKey());
                assertNotNull(plate, "PLATE_END without an open PLATE_BEGIN");
                commit(plate);
            }
            case ClientViewMessage.PlatePatch m -> {
                assertEquals(last, m.advances(), "only the PLATE_PATCH piece that closes its group advances the revision");
                Brick[] current = plates.get(m.portalKey());
                assertNotNull(current, "PLATE_PATCH for a plate the client does not hold");
                assertEquals(plateRevisions.get(m.portalKey()).intValue(), m.fromRevision());
                for (ClientViewMessage.PatchOp op : m.ops()) {
                    if (op instanceof ClientViewMessage.FullOp full) {
                        assertBrickKnown(full.brick());
                    } else if (op instanceof ClientViewMessage.SparseOp sparse) {
                        for (int id : sparse.paletteIds()) {
                            assertKnown(id, "sparse patch");
                        }
                    }
                }
                plates.put(m.portalKey(), PlatePatchEncoder.apply(current, m));
                plateRevisions.put(m.portalKey(), m.toRevision());
            }
            case ClientViewMessage.SessionReset m -> {
                portals.clear();
                plates.clear();
                plateRevisions.clear();
                open.clear();
                resetPalette();
            }
            default -> {
            }
        }
        if (message.type() == ClientViewMessageType.OFFER || message.type() == ClientViewMessageType.ACCEPT
            || message.type() == ClientViewMessageType.DECLINE) {
            return;
        }
        lastSeq = decoded.seq();
        if (last) {
            lastClosedSeq = decoded.seq();
            closedByType.merge(message.type(), 1, Integer::sum);
        }
    }

    byte[] hello(int dataVersion, long clientCaps, String brand, long nonceFound) throws ClientViewProtocolException {
        return ClientViewCodec.encodeC2S(ClientViewHandshake.clientHello(offer, dataVersion, clientCaps,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 256, nonceFound, brand));
    }

    byte[] ack() throws ClientViewProtocolException {
        return ack(lastClosedSeq);
    }

    byte[] ack(int seq) throws ClientViewProtocolException {
        lastAckedSeq = seq;
        return ClientViewCodec.encodeC2S(new ClientViewMessage.Ack(seq, 0, 1));
    }

    int closed(ClientViewMessageType type) {
        Integer count = closedByType.get(type);
        return count == null ? 0 : count;
    }

    List<ClientViewMessage> since(int index) {
        return new ArrayList<ClientViewMessage>(received.subList(index, received.size()));
    }

    int count(ClientViewMessageType type) {
        int count = 0;
        for (ClientViewMessage message : received) {
            if (message.type() == type) {
                count++;
            }
        }
        return count;
    }

    private void commit(OpenPlate plate) {
        ClientViewMessage.PlateBegin begin = plate.begin;
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
        palette.put(ClientViewProtocol.PALETTE_AIR, SessionPalette.AIR);
        palette.put(ClientViewProtocol.PALETTE_OCCLUDED, SessionPalette.OCCLUDED);
        palette.put(ClientViewProtocol.PALETTE_BACKING, SessionPalette.BACKING);
    }

    static final class OpenPlate {
        final ClientViewMessage.PlateBegin begin;
        final Brick[] bricks;
        boolean[] missed;

        OpenPlate(ClientViewMessage.PlateBegin begin) {
            this.begin = begin;
            this.bricks = new Brick[begin.brickCount()];
        }
    }
}
