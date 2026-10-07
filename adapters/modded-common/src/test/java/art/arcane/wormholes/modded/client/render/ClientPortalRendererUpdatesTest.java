package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.portal.ApertureKind;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.SectionPos;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientPortalRendererUpdatesTest {
    private static final long SECTION = SectionPos.asLong(0, 4, 0);

    @Test
    public void continualChangesPublishCompletedSnapshotsAndKeepTheNextRebuildPending() throws Exception {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        try {
            PortalScene scene = scene();
            renderer.replaceScene(7, scene);
            Object portal = ((Int2ObjectMap<?>) field(renderer, "portals")).get(7);
            Long2ObjectMap<?> sections = (Long2ObjectMap<?>) field(portal, "sections");
            LongSet dirty = (LongSet) field(portal, "dirty");
            Object previous = null;
            for (long compiled = 1; compiled <= 12; compiled++) {
                dirty.remove(SECTION);
                when(scene.revision(SECTION)).thenReturn(compiled + 1);
                if ((compiled & 1) == 0) {
                    renderer.invalidate(7, SECTION, true);
                }
                PortalSectionMesh mesh = mesh();
                complete(renderer, portal, compiled, 0, mesh);
                Object displayed = sections.get(SECTION);
                assertNotSame(previous, displayed);
                assertEquals(compiled, field(displayed, "revision"));
                assertTrue(dirty.contains(SECTION));
                assertTrue(renderer.available(7));
                verify(mesh).close();
                previous = displayed;
            }
            when(scene.revision(SECTION)).thenReturn(12L);
            renderer.invalidate(7, SECTION, true);
            complete(renderer, portal, 12, 0, mesh());
            assertNotSame(previous, sections.get(SECTION));
            assertTrue(dirty.contains(SECTION));
            dirty.remove(SECTION);
            previous = sections.get(SECTION);
            PortalSectionMesh older = mesh();
            complete(renderer, portal, 11, 0, older);
            assertSame(previous, sections.get(SECTION));
            verify(older).close();
            assertEquals(0, field(renderer, "pendingBuilds"));
        } finally {
            renderer.clear();
        }
    }

    @Test
    public void removedSectionsAndObsoleteSceneGenerationsCannotPublishCompletedMeshes() throws Exception {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        renderer.clear();
        try {
            PortalScene scene = scene();
            renderer.replaceScene(7, scene);
            Object portal = ((Int2ObjectMap<?>) field(renderer, "portals")).get(7);
            Long2ObjectMap<?> sections = (Long2ObjectMap<?>) field(portal, "sections");
            when(scene.revision(SECTION)).thenReturn(-1L);
            PortalSectionMesh removed = mesh();
            complete(renderer, portal, 1, 0, removed);
            assertTrue(sections.isEmpty());
            verify(removed).close();
            when(scene.revision(SECTION)).thenReturn(1L);
            PortalSectionMesh obsolete = mesh();
            complete(renderer, portal, 1, -1, obsolete);
            assertTrue(sections.isEmpty());
            verify(obsolete).close();
            renderer.remove(7);
            PortalSectionMesh inactive = mesh();
            complete(renderer, portal, 1, 0, inactive);
            assertTrue(sections.isEmpty());
            verify(inactive).close();
        } finally {
            renderer.clear();
        }
    }

    private static PortalScene scene() {
        PortalScene scene = mock(PortalScene.class);
        when(scene.geometry()).thenReturn(new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false,
            1, 2, new long[]{3}, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of()));
        return scene;
    }

    private static PortalSectionMesh mesh() {
        PortalSectionMesh mesh = mock(PortalSectionMesh.class);
        when(mesh.meshes()).thenReturn(Map.of());
        return mesh;
    }

    private static void complete(ClientPortalRenderer renderer, Object portal, long revision, int generation,
                                 PortalSectionMesh mesh) throws Exception {
        ((LongOpenHashSet) field(portal, "building")).add(SECTION);
        Field pending = ClientPortalRenderer.class.getDeclaredField("pendingBuilds");
        pending.setAccessible(true);
        pending.setInt(renderer, 1);
        Method finish = ClientPortalRenderer.class.getDeclaredMethod("finish", portal.getClass(), long.class, long.class,
            int.class, PortalScene.MeshIdentity.class, PortalSectionMesh.class, Throwable.class);
        finish.setAccessible(true);
        finish.invoke(renderer, portal, SECTION, revision, generation, null, mesh, null);
        assertTrue(((LongOpenHashSet) field(portal, "building")).isEmpty());
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
