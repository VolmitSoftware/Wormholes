package art.arcane.wormholes.render.clientview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.ConnectionState;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.stream.SessionPalette;
import art.arcane.wormholes.render.ClientViewPortalSource;

final class ClientViewPortalSourceMirrorTest {
    @Test
    void nativeMeshWallMirrorsRenderTheCoherentServerProjectionForEveryRotation() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            ClientViewPortalSource source = new ClientViewPortalSource(fixture.portal, fixture.views, fixture.plates);
            SessionPalette palette = new SessionPalette();
            long tick = 0L;
            for (QuarterTurn rotation : List.of(QuarterTurn.DEGREES_0, QuarterTurn.DEGREES_90, QuarterTurn.DEGREES_180, QuarterTurn.DEGREES_270)) {
                when(fixture.portal.getMirrorRotation()).thenReturn(rotation);
                OpticTransform server = OpticTransform.mirror(fixture.portal.getFrame(), fixture.portal.getOrigin(),
                    rotation.coherentFor(fixture.portal.getFrame()));

                source.update(fixture.player, fixture.eye, null, ++tick, true);
                ViewWindow nativeWindow = source.transformFrame();
                ApertureDescriptor nativeGeometry = source.geometry(palette, 1L);
                source.update(fixture.player, fixture.eye, null, tick, false);
                ViewWindow packetWindow = source.transformFrame();
                ApertureDescriptor packetGeometry = source.geometry(palette, 1L);

                assertEquals(server, nativeWindow.transform(), rotation.toString());
                assertEquals(server, packetWindow.transform(), rotation.toString());
                assertEquals(packetGeometry.mirrorTransform(), nativeGeometry.mirrorTransform(), rotation.toString());
            }
        }
    }
}
