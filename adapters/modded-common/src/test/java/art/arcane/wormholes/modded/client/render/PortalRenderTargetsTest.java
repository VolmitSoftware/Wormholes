package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import org.junit.Test;
import org.mockito.MockedConstruction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

public class PortalRenderTargetsTest {
    @Test
    public void stationaryTravelTargetIsPrivateAndReleasedWithoutClosingOrdinaryRoots() {
        try (MockedConstruction<TextureTarget> construction = mockConstruction(TextureTarget.class, (target, context) -> {
            target.width = (Integer) context.arguments().get(1);
            target.height = (Integer) context.arguments().get(2);
        }); PortalRenderTargets targets = new PortalRenderTargets()) {
            assertNull(targets.travel(-1));
            TextureTarget root = targets.scratch(0, 1920, 1080);
            TextureTarget travel = targets.travel(-1, 1920, 1080);
            assertNotSame(root, travel);
            assertSame(travel, targets.travel(-1, 1920, 1080));
            assertSame(travel, targets.travel(-1));
            assertSame(root, targets.scratch(0, 1920, 1080));
            assertEquals(2L * 1920 * 1080 * 8, targets.bytes());
            targets.releaseTravel(-1);
            verify(travel).destroyBuffers();
            assertNull(targets.travel(-1));
            assertSame(root, targets.scratch(0, 1920, 1080));
            assertEquals(1L * 1920 * 1080 * 8, targets.bytes());
        }
    }

    @Test
    public void rootsAndSiblingsReuseTargetsAtNativeResolution() {
        try (MockedConstruction<TextureTarget> construction = mockConstruction(TextureTarget.class, (target, context) -> {
            target.width = (Integer) context.arguments().get(1);
            target.height = (Integer) context.arguments().get(2);
        }); PortalRenderTargets targets = new PortalRenderTargets()) {
            TextureTarget layer = targets.layer(3840, 2160);
            TextureTarget root = targets.scratch(0, 3840, 2160);
            TextureTarget child = targets.scratch(1, 3840, 2160);
            for (int portal = 0; portal < 32; portal++) {
                assertSame(layer, targets.layer(3840, 2160));
                assertSame(root, targets.scratch(0, 3840, 2160));
                assertSame(child, targets.scratch(1, 3840, 2160));
            }
            assertEquals(3, construction.constructed().size());
            assertEquals(3L * 3840 * 2160 * 8, targets.bytes());
        }
    }

    @Test
    public void resizingAndClosingReleasesEveryOwnedAttachment() {
        try (MockedConstruction<TextureTarget> construction = mockConstruction(TextureTarget.class, (target, context) -> {
            target.width = (Integer) context.arguments().get(1);
            target.height = (Integer) context.arguments().get(2);
        })) {
            PortalRenderTargets targets = new PortalRenderTargets();
            TextureTarget oldLayer = targets.layer(1920, 1080);
            TextureTarget oldRoot = targets.scratch(0, 1920, 1080);
            TextureTarget layer = targets.layer(2560, 1440);
            TextureTarget root = targets.scratch(0, 2560, 1440);
            verify(oldLayer).destroyBuffers();
            verify(oldRoot).destroyBuffers();
            targets.close();
            verify(layer).destroyBuffers();
            verify(root).destroyBuffers();
            assertEquals(0L, targets.bytes());
        }
    }
}
