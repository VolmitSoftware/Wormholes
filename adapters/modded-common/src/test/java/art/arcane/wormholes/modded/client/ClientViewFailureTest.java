package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientViewChannel;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.PlateSectionBox;
import art.arcane.optics.client.ClientSweep;
import art.arcane.optics.math.BlockBox;
import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import art.arcane.wormholes.network.client.ClientViewExtensions;

public class ClientViewFailureTest extends MinecraftTestBase {
    private static final double EYE_X = ClientViewHarness.EYE_X;
    private static final double EYE_Y = ClientViewHarness.EYE_Y;
    private static final double EYE_Z = ClientViewHarness.EYE_Z;

    @After
    public void deactivate() {
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
    }

    @Test
    public void aPlateBoxTheSweepCannotHoldIsRefusedInsteadOfCrashingTheTick() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.receive(new ViewStreamMessage.Portal(ClientViewHarness.PORTAL_KEY, 1, ClientViewHarness.geometry()), 0);
        BlockBox cells = new BlockBox(-8, 56, 0, 300, 200, 300);
        assertTrue(cells.cells() > ClientSweep.MAX_BOUNDS_CELLS);
        PlateSectionBox sections = PlateSectionBox.snap(cells);
        long[] hashes = new long[sections.brickCount()];
        for (int index = 0; index < hashes.length; index++) {
            hashes[index] = 0x9000L + index;
        }
        harness.receive(new ViewStreamMessage.PlateBegin(ClientViewHarness.PORTAL_KEY, 1, sections, cells, ClientViewHarness.STONE_ID,
            sections.brickCount(), hashes), 0);
        harness.receive(new ViewStreamMessage.PlateBricks(ClientViewHarness.PORTAL_KEY, 1, List.of()), 0);
        harness.receive(new ViewStreamMessage.PlateEnd(ClientViewHarness.PORTAL_KEY, 1), ViewStreamLimits.FLAG_LAST);

        harness.tick(EYE_X, EYE_Y, EYE_Z);

        assertTrue(harness.sent.contains(new ViewStreamMessage.PlateRefused(ClientViewHarness.PORTAL_KEY, 1)));
        assertFalse(harness.sent.stream().anyMatch(message -> message instanceof ViewStreamMessage.BrickMiss));
        assertFalse(harness.session.portal(ClientViewHarness.PORTAL_KEY).ready());
        assertEquals(0, harness.session.plates().size());
    }

    @Test
    public void aRuntimeFailureWhileHandlingOneMessageCountsAsAProtocolFailure() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        harness.scene.failGameTime = true;
        harness.receive(new ViewStreamMessage.Atmosphere(ClientViewHarness.PORTAL_KEY, 18000L, 0.8F, 0.5F,
            ViewStreamMessage.Atmosphere.FLAG_WEATHER), ViewStreamLimits.FLAG_LAST);

        harness.tick(EYE_X, EYE_Y, EYE_Z);

        harness.scene.failGameTime = false;
        assertEquals(1L, harness.tick.protocolFailures());
        assertEquals(1L, harness.session.protocolFailures());
        assertEquals(ClientViewSession.State.NATIVE_RECOVERING, harness.session.state());
        assertEquals(0, harness.tick.overlay().size());
    }

    @Test
    public void aSenderThatThrowsKeepsNativeSelectionUnavailableForRecovery() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        harness.tick.sender(message -> {
            throw new UnsupportedOperationException("Payload " + ClientViewChannel.CHANNEL + " may not be sent to the server!");
        });
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertTrue(harness.tick.sendFailures() > 0L);

        harness.tick(EYE_X, EYE_Y, EYE_Z);

        assertEquals(ClientViewSession.State.NATIVE_RECOVERING, harness.session.state());
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(0, harness.surface.changedCells());
        assertTrue(harness.session.portals().isEmpty());
    }

    @Test
    public void aLaterOfferNegotiatesAgainAfterASendFailure() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        boolean[] blocked = {true};
        harness.tick.sender(message -> {
            if (blocked[0]) {
                throw new IllegalStateException("no connection");
            }
            harness.sent.add(message);
        });
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertEquals(ClientViewSession.State.NATIVE_RECOVERING, harness.session.state());

        blocked[0] = false;
        harness.receive(offer(), ViewStreamLimits.FLAG_LAST);
        harness.receive(new ViewStreamMessage.Accept(2, ViewStreamCapability.ALL, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 9L, 8),
            ViewStreamLimits.FLAG_LAST);
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);

        assertEquals(ClientViewSession.State.CLIENT_VIEW, harness.session.state());
        assertEquals(0L, harness.tick.sendFailures());
        assertEquals(0, harness.surface.changedCells());
        assertFalse(harness.acks().isEmpty());
    }

    @Test
    public void anOfferReplyThatCannotBeSentKeepsAcceptedNativeSelection() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        byte[] offer = ClientViewExtensions.CODEC.encodeS2C(offer(), 1, ViewStreamLimits.FLAG_LAST);

        harness.receiver.receive(offer, bytes -> {
            throw new UnsupportedOperationException("Payload " + ClientViewChannel.CHANNEL + " may not be sent to the server!");
        });

        assertEquals(ClientViewSession.State.NATIVE_RECOVERING, harness.session.state());
    }

    @Test
    public void aNewOfferClearsThePreviousServersPortalsBeforeTheNextStream() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness();
        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertTrue(harness.surface.changedCells() > 0);
        ClientPortal previous = harness.session.portal(ClientViewHarness.PORTAL_KEY);
        int acks = harness.acks().size();

        harness.receive(offer(), ViewStreamLimits.FLAG_LAST);
        harness.receive(new ViewStreamMessage.Accept(2, ClientViewHarness.PLATE_CAPS, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 9L, 8),
            ViewStreamLimits.FLAG_LAST);
        harness.tick(EYE_X, EYE_Y, EYE_Z);

        assertEquals(ClientViewSession.State.CLIENT_VIEW, harness.session.state());
        assertEquals(0, harness.tick.overlay().size());
        assertEquals(0, harness.surface.changedCells());
        assertTrue(harness.session.portals().isEmpty());
        assertEquals(0, harness.session.plates().size());
        assertFalse(harness.session.palette().known(ClientViewHarness.STONE_ID));
        assertEquals(acks, harness.acks().size());

        harness.stream();
        harness.tick(EYE_X, EYE_Y, EYE_Z);

        assertTrue(harness.surface.changedCells() > 0);
        assertNotSame(previous, harness.session.portal(ClientViewHarness.PORTAL_KEY));
    }

    @Test
    public void malformedNativeStreamRetriesHelloAtBoundedCadenceThenAcceptsFreshMesh() throws ViewStreamProtocolException {
        ClientViewHarness harness = new ClientViewHarness(ViewStreamCapability.ALL);
        harness.receive(offer(), 0);
        harness.receive(new ViewStreamMessage.Accept(2, ViewStreamCapability.ALL, 20, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 9L, 8), 0);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        harness.sent.clear();
        harness.receiver.receive(new byte[] {(byte) 255}, null);
        for (int i = 0; i < 40; i++) {
            harness.tick(EYE_X, EYE_Y, EYE_Z);
            assertEquals(ClientViewSession.State.NATIVE_RECOVERING, harness.session.state());
            assertTrue(harness.session.nativeSelected());
        }
        assertEquals(2L, harness.sent.stream().filter(message -> message instanceof ViewStreamMessage.Hello).count());
        harness.receive(new ViewStreamMessage.SessionReset(ViewStreamMessage.ResetReason.PROTOCOL), 0);
        harness.receive(new ViewStreamMessage.Portal(ClientViewHarness.PORTAL_KEY, 2, ClientViewHarness.geometry()), 0);
        harness.receive(new ViewStreamMessage.MeshBegin(ClientViewHarness.PORTAL_KEY, 2, new BlockBox(-16, 48, -16, 48, 48, 48), 27), 0);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertEquals(ClientViewSession.State.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.session.meshes().view(ClientViewHarness.PORTAL_KEY) != null);
        assertEquals(0, harness.surface.changedCells());
        harness.receive(new ViewStreamMessage.SessionReset(ViewStreamMessage.ResetReason.OVERLOAD), 0);
        harness.tick(EYE_X, EYE_Y, EYE_Z);
        assertEquals(ClientViewSession.State.CLIENT_VIEW, harness.session.state());
        assertTrue(harness.session.nativeSelected());
    }

    private static ViewStreamMessage.Offer offer() {
        return new ViewStreamMessage.Offer(ViewStreamLimits.WIRE_VERSION, 1, ViewStreamCapability.ALL, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 0L);
    }
}
