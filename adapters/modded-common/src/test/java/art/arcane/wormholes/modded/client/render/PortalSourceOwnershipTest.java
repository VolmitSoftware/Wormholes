package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.portal.ApertureKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class PortalSourceOwnershipTest extends MinecraftTestBase {
    @Test
    public void physicalSourceIdentityAndExpiredMetadataGateEveryScene() {
        ClientLevel source = mock(ClientLevel.class);
        ClientLevel destination = mock(ClientLevel.class);
        PortalScene scene = mock(PortalScene.class);
        when(scene.sourceLevel()).thenReturn(source);
        assertTrue(ClientPortalRenderer.sourceCurrent(scene, source));
        assertFalse(ClientPortalRenderer.sourceCurrent(scene, destination));
        when(scene.sourceExpired()).thenReturn(true);
        assertFalse(ClientPortalRenderer.sourceCurrent(scene, source));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void promotionDiscardsPreparedVisibilityWhileKeepingResidentScenes() throws Exception {
        Constructor<ClientPortalRenderer> constructor = ClientPortalRenderer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ClientPortalRenderer renderer = constructor.newInstance();
        Minecraft minecraft = mock(Minecraft.class);
        ClientLevel source = mock(ClientLevel.class);
        ClientLevel destination = mock(ClientLevel.class);
        PortalScene scene = mock(PortalScene.class);
        when(scene.sourceLevel()).thenReturn(source);
        when(scene.geometry()).thenReturn(geometry());
        PortalStencilRenderer stencil = mock(PortalStencilRenderer.class);
        try (MockedStatic<Minecraft> game = mockStatic(Minecraft.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
             MockedStatic<PortalStencilRenderer> stencils = mockStatic(PortalStencilRenderer.class)) {
            game.when(Minecraft::getInstance).thenReturn(minecraft);
            stencils.when(PortalStencilRenderer::instance).thenReturn(stencil);
            renderer.replaceScene(7, scene);
            Map<Integer, Object> portals = (Map<Integer, Object>) field("portals").get(renderer);
            List<Object> visible = (List<Object>) field("visible").get(renderer);
            List<Object> shaded = (List<Object>) field("shaded").get(renderer);
            Object portal = portals.get(7);
            visible.add(portal);
            shaded.add(portal);
            field("frameLevel").set(renderer, source);
            minecraft.level = destination;
            Method current = ClientPortalRenderer.class.getDeclaredMethod("frameCurrent");
            current.setAccessible(true);
            assertFalse((boolean) current.invoke(renderer));
            assertTrue(visible.isEmpty());
            assertTrue(shaded.isEmpty());
            assertSame(portal, portals.get(7));
            assertFalse(renderer.available(7));
            minecraft.level = source;
            assertTrue(renderer.available(7));
        } finally {
            renderer.clear();
        }
    }

    private static Field field(String name) throws Exception {
        Field field = ClientPortalRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static ApertureDescriptor geometry() {
        return new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false,
            1, 2, new long[]{3}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0,
            ApertureKind.FRAME, 0.0D, 0, 1, List.of());
    }
}
