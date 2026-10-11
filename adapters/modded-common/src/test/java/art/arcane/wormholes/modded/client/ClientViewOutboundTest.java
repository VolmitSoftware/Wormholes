package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientViewOutboundTest {
    @Test
    public void repeatedPendingMeshAcknowledgementsDoNotAmplifyTheBacklog() {
        AtomicLong clock = new AtomicLong();
        List<ViewStreamMessage> sent = new ArrayList<>();
        ClientViewOutbound outbound = new ClientViewOutbound(sent::add, clock::get);
        for (int index = 0; index < 448; index++) {
            outbound.send(new ViewStreamMessage.MeshAck(1, 1, index, 0, 0, 1));
        }
        ViewStreamMessage.MeshAck ack = new ViewStreamMessage.MeshAck(2, 1, 0, 0, 0, 1);
        for (int index = 0; index < 10_000; index++) {
            outbound.send(ack);
        }
        assertEquals(1, outbound.pending());
        outbound.send(new ViewStreamMessage.MeshAck(2, 1, 0, 0, 0, 2));
        assertEquals(2, outbound.pending());
        clock.set(1_000_000_000L);
        outbound.flush();
        assertEquals(0, outbound.pending());
        assertEquals(ack, sent.get(448));
        assertEquals(2, ((ViewStreamMessage.MeshAck) sent.getLast()).revision());
        outbound.send(ack);
        assertEquals(ack, sent.getLast());
    }

    @Test
    public void aMeshBurstKeepsControlCapacityAndEventuallyDeliversEveryMessageInOrder() {
        AtomicLong clock = new AtomicLong();
        List<ViewStreamMessage> sent = new ArrayList<>();
        ClientViewOutbound outbound = new ClientViewOutbound(sent::add, clock::get);
        for (int index = 0; index < 1500; index++) {
            outbound.send(new ViewStreamMessage.MeshAck(1, 1, index, 0, 0, 1));
        }
        assertEquals(448, sent.size());
        ViewStreamMessage.Ack control = new ViewStreamMessage.Ack(1, 1, 1);
        outbound.send(control);
        assertEquals(control, sent.getLast());
        for (int tick = 0; tick < 400; tick++) {
            outbound.flush();
        }
        assertEquals(449, sent.size());
        clock.set(999_999_999L);
        outbound.flush();
        assertEquals(449, sent.size());
        for (int second = 1; second <= 4; second++) {
            clock.set(second * 1_000_000_000L);
            int before = sent.size();
            outbound.flush();
            assertTrue(sent.size() - before <= 448);
        }
        assertEquals(0, outbound.pending());
        int section = 0;
        for (ViewStreamMessage message : sent) {
            if (message instanceof ViewStreamMessage.MeshAck ack) {
                assertEquals(section++, ack.sectionX());
            }
        }
        assertEquals(1500, section);
    }

    @Test
    public void aLongStallDoesNotAccumulateCreditAndSessionCleanupRetainsTheConnectionBudget() {
        AtomicLong clock = new AtomicLong();
        List<ViewStreamMessage> sent = new ArrayList<>();
        ClientViewOutbound outbound = new ClientViewOutbound(sent::add, clock::get);
        for (int index = 0; index < 600; index++) {
            outbound.send(new ViewStreamMessage.MeshAck(1, 1, index, 0, 0, 1));
        }
        assertEquals(448, sent.size());
        outbound.clear();
        outbound.send(new ViewStreamMessage.MeshAck(2, 1, 0, 0, 0, 1));
        assertEquals(448, sent.size());
        clock.set(20_000_000_000L);
        for (int index = 0; index < 1000; index++) {
            outbound.send(new ViewStreamMessage.MeshAck(2, 1, index + 1, 0, 0, 1));
        }
        assertEquals(896, sent.size());
        assertEquals(553, outbound.pending());
    }
}
