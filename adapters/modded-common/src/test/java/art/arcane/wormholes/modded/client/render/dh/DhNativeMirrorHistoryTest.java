package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.stencil.NativeMirrorHistoryScene;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.world.IDhClientWorld;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DhNativeMirrorHistoryTest extends MinecraftTestBase {
    @Test
    public void sixFullPathLodOwnersSurviveDiscoveryAndReopenAndReleaseExactlyOnce() {
        DhPortalLodViews.clear();
        FullDataSourceProviderV2 provider = mock(FullDataSourceProviderV2.class);
        RenderParams parameters = parameters(provider);
        List<DhPortalLodViews.Selection> owners = new ArrayList<>(6);
        try (NativeMirrorHistoryScene scene = new NativeMirrorHistoryScene()) {
            scene.frame(0);
            List<DhPortalLodViews.PortalPath> initial = paths(scene.traversals());
            assertEquals(6, new HashSet<>(initial).size());
            for (DhPortalLodViews.PortalPath path : initial) {
                DhPortalLodViews.Selection owner = DhPortalLodViews.acquire(path, parameters, Vec3d.ZERO);
                owners.add(owner);
                owner.release();
            }
            assertEquals(6, new HashSet<>(owners).size());
            assertNotEquals(initial.getFirst(), DhPortalLodViews.mainPath(scene.level));
            scene.frame(2);
            reuse(parameters, paths(scene.traversals()), owners);
            scene.frame(4);
            reuse(parameters, paths(scene.traversals()), owners);
            scene.views.frame(scene.level, Vec3d.ZERO, List.of(), false, true, List.of(), null);
            scene.slots(40, 8);
            scene.frame(4);
            reuse(parameters, paths(scene.traversals()), owners);
            verify(provider, times(6)).addDataSourceUpdateListener(any());
            DhPortalLodViews.closeProvider(provider);
            verify(provider, times(6)).removeDataSourceUpdateListener(any());
            DhPortalLodViews.closeProvider(provider);
            DhPortalLodViews.clear();
            verify(provider, times(6)).removeDataSourceUpdateListener(any());
        } finally {
            DhPortalLodViews.clear();
        }
    }

    @Test
    public void realmReplacementAndReorderedParentsCannotReuseTheOldPath() {
        DhPortalLodViews.PortalPath previous;
        try (NativeMirrorHistoryScene scene = new NativeMirrorHistoryScene()) {
            scene.frame(4);
            previous = paths(scene.traversals()).get(2);
            PortalView a = scene.views.current().get(0);
            PortalView b = scene.views.current().get(1);
            assertNotEquals(path(List.of(a, b)), path(List.of(b, a)));
            assertNotEquals(path(List.of(a)), path(List.of(a, a)));
        }
        try (NativeMirrorHistoryScene replacement = new NativeMirrorHistoryScene()) {
            replacement.frame(4);
            assertNotEquals(previous, paths(replacement.traversals()).get(2));
        }
    }

    private static RenderParams parameters(FullDataSourceProviderV2 provider) {
        RenderParams parameters = new RenderParams();
        parameters.dhClientWorld = mock(IDhClientWorld.class);
        parameters.dhClientLevel = mock(IDhClientLevel.class);
        when(parameters.dhClientLevel.getFullDataProvider()).thenReturn(provider);
        return parameters;
    }

    private static void reuse(RenderParams parameters, List<DhPortalLodViews.PortalPath> paths,
                              List<DhPortalLodViews.Selection> owners) {
        for (int index = 0; index < paths.size(); index++) {
            DhPortalLodViews.Selection owner = DhPortalLodViews.acquire(paths.get(index), parameters, Vec3d.ZERO);
            assertSame(owners.get(index), owner);
            owner.release();
        }
    }

    private static List<DhPortalLodViews.PortalPath> paths(List<List<PortalView>> traversals) {
        List<DhPortalLodViews.PortalPath> paths = new ArrayList<>(traversals.size());
        for (List<PortalView> traversal : traversals) {
            paths.add(path(traversal));
        }
        return paths;
    }

    private static DhPortalLodViews.PortalPath path(List<PortalView> traversal) {
        DhPortalLodViews.PortalPath path = null;
        for (PortalView view : traversal) {
            path = DhPortalLodViews.path(path, view);
        }
        return path;
    }
}
