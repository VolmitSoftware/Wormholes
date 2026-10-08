package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.portal.ApertureKind;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientEndPortalSurfaceTest {
    @Test
    public void onlyRenderedManagedRootCellsHideTheirVanillaSurface() throws Exception {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        try {
            PortalScene scene = mock(PortalScene.class);
            when(scene.geometry()).thenReturn(geometry(ApertureKind.VANILLA_REPLACEMENT, 0));
            renderer.replaceScene(7, scene);
            Field portalsField = ClientPortalRenderer.class.getDeclaredField("portals");
            portalsField.setAccessible(true);
            Object portal = ((Int2ObjectMap<?>) portalsField.get(renderer)).get(7);
            Field rendered = portal.getClass().getDeclaredField("rendered");
            rendered.setAccessible(true);
            BlockPos open = new BlockPos(-1, 64, 0);
            assertFalse(renderer.coversEndPortalSurface(open));
            rendered.setBoolean(portal, true);
            assertTrue(renderer.coversEndPortalSurface(open));
            assertFalse(renderer.coversEndPortalSurface(new BlockPos(0, 64, 0)));
            assertFalse(renderer.coversEndPortalSurface(new BlockPos(-1, 65, 0)));
            assertFalse(renderer.coversEndPortalSurface(new BlockPos(3, 64, 0)));
            when(scene.geometry()).thenReturn(geometry(ApertureKind.FRAME, 0));
            assertFalse(renderer.coversEndPortalSurface(open));
            when(scene.geometry()).thenReturn(geometry(ApertureKind.VANILLA_REPLACEMENT, 9));
            assertFalse(renderer.coversEndPortalSurface(open));
            when(scene.geometry()).thenReturn(geometry(ApertureKind.VANILLA_REPLACEMENT, 0));
            renderer.featureFailed(7, new IllegalStateException("unavailable native view"));
            assertFalse(renderer.coversEndPortalSurface(open));
            renderer.remove(7);
            assertFalse(renderer.coversEndPortalSurface(open));
        } finally {
            renderer.clear();
        }
    }

    private static ApertureDescriptor geometry(int kind, int parent) {
        boolean[] cells = {true, true, true, true, false, true, true, true, true};
        return new ApertureDescriptor(-1, 64, -1, Face.U.ordinal(), true, 0, false,
            3, 3, ApertureDescriptor.apertureMask(3, 3, cells), ShapeDescriptor.FULL, 0, 0, 1, 64, 0,
            0, 0, 0, 0, 0, kind, 0.0D, parent, 1, List.of());
    }
}
