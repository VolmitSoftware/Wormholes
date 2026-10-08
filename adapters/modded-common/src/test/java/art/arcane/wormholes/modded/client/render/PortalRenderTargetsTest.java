package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class PortalRenderTargetsTest {
    @Test
    public void resizingClosesOnlyTheSkyBoundToTheReplacedTextureAndRecreatesItLazily() {
        Minecraft minecraft = mock(Minecraft.class);
        try (MockedStatic<Minecraft> singleton = mockStatic(Minecraft.class);
             MockedConstruction<TextureTarget> textures = mockConstruction(TextureTarget.class, (target, context) -> {
                 target.width = (Integer) context.arguments().get(1);
                 target.height = (Integer) context.arguments().get(2);
             });
             MockedConstruction<SkyRenderer> skies = mockConstruction(SkyRenderer.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(minecraft);
            PortalRenderTargets targets = new PortalRenderTargets();
            TextureTarget root = targets.scratch(0, 1920, 1080);
            targets.scratch(1, 1920, 1080);
            TextureTarget travel = targets.travel(-1, 1920, 1080);
            SkyRenderer rootSky = targets.sky(0);
            SkyRenderer childSky = targets.sky(1);
            SkyRenderer travelSky = targets.travelSky(-1);
            assertSame(rootSky, targets.sky(0));
            assertSame(travelSky, targets.travelSky(-1));
            TextureTarget resizedRoot = targets.scratch(0, 1280, 720);
            TextureTarget resizedTravel = targets.travel(-1, 1280, 720);
            verify(root).destroyBuffers();
            verify(travel).destroyBuffers();
            verify(rootSky).close();
            verify(travelSky).close();
            verify(childSky, never()).close();
            assertEquals(3, skies.constructed().size());
            assertEquals(8L * (2L * 1280 * 720 + 1920L * 1080), targets.bytes());
            SkyRenderer nextRootSky = targets.sky(0);
            SkyRenderer nextTravelSky = targets.travelSky(-1);
            assertNotSame(rootSky, nextRootSky);
            assertNotSame(travelSky, nextTravelSky);
            assertSame(childSky, targets.sky(1));
            targets.close();
            targets.close();
            verify(nextRootSky, times(1)).close();
            verify(childSky, times(1)).close();
            verify(resizedRoot, times(1)).destroyBuffers();
            verify(resizedTravel, times(1)).destroyBuffers();
            assertEquals(0L, targets.bytes());
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
