package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.ClientPortal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NativeMirrorHistoryTest extends MinecraftTestBase {
    @Test
    public void lateChildProofPreservesSelfTargetAndKeepsOpaqueRoutingProvenance() {
        ClientLevel level = level();
        ApertureDescriptor rootGeometry = geometry(0, 0L);
        ApertureDescriptor childGeometry = geometry(17, 77L);
        NativeMirrorHistory.Proof root = proof(level, rootGeometry, null);
        NativeMirrorHistory.Proof child = proof(level, childGeometry,
            NativeMirrorHistoryScene.environment(childGeometry, "minecraft:overworld"));
        assertNotNull(root);
        assertNotNull(child);
        assertEquals(root.key(), child.key());
        assertEquals(0L, root.targetIdentity());
        assertEquals(77L, child.targetIdentity());
        assertEquals(17, child.parentStreamKey());
        assertNull(proof(level, childGeometry, null));
        assertNull(proof(level, childGeometry, NativeMirrorHistoryScene.environment(childGeometry, "minecraft:the_end")));
        assertNull(proof(level, rootGeometry, NativeMirrorHistoryScene.environment(rootGeometry, "minecraft:the_end")));
        PortalView rootView = view(level, rootGeometry);
        assertNull(NativeMirrorHistory.prove(rootView, new ClientPortal(1, rootGeometry, 1, 0.0D), null, false));
    }

    @Test
    public void zeroTokenRequiresTheSameCompleteSelfTargetProofAsNonzeroTokens() {
        ClientLevel level = level();
        ApertureDescriptor child = geometry(17, 0L);
        assertNull(proof(level, child, null));
        EnvironmentState environment = NativeMirrorHistoryScene.environment(child, "minecraft:overworld");
        when(environment.scale()).thenReturn(2.0F);
        assertNull(proof(level, child, environment));
        environment = NativeMirrorHistoryScene.environment(child, "minecraft:overworld");
        when(environment.transform()).thenReturn(OpticTransform.IDENTITY);
        assertNull(proof(level, child, environment));
        environment = NativeMirrorHistoryScene.environment(child, "minecraft:overworld");
        assertNotNull(proof(level, child, environment));
    }

    @Test
    public void exactRealmReflectionSurfaceAndKindAreRequired() {
        ClientLevel level = level();
        ClientLevel replacement = level();
        ApertureDescriptor geometry = geometry(0, 0L);
        NativeMirrorHistory.Proof original = proof(level, geometry, null);
        assertNotEquals(original.key(), proof(replacement, geometry, null).key());
        PortalView crossWorld = new PortalView(PortalView.Kind.MIRROR, "mirror", level, replacement, PortalSurface.of(geometry),
            Similarity.of(PortalSurface.mirrorTransform(geometry), 1.0D), 3);
        assertNull(NativeMirrorHistory.prove(crossWorld, new ClientPortal(1, geometry, 1, 0.0D), null, true));
        for (String changed : List.of("mask", "shape", "side", "plane", "turns", "origin")) {
            ApertureDescriptor altered = altered(geometry, changed);
            assertNotEquals(changed, original.key(), proof(level, altered, null).key());
        }
        Similarity reflection = Similarity.of(PortalSurface.mirrorTransform(geometry), 1.0D);
        Vec3d offset = reflection.point(Vec3d.ZERO);
        for (PortalView altered : List.of(
            new PortalView(PortalView.Kind.ARM, "arm", level, level, PortalSurface.of(geometry), reflection, 3),
            new PortalView(PortalView.Kind.MIRROR, "scale", level, level, PortalSurface.of(geometry),
                Similarity.of(reflection.rigid(), 2.0D), 3),
            new PortalView(PortalView.Kind.MIRROR, "shift", level, level, PortalSurface.of(geometry),
                Similarity.of(OpticTransform.of(reflection.rigid().permutation(), offset.x() + 1.0E-9D, offset.y(), offset.z()), 1.0D), 3),
            new PortalView(PortalView.Kind.MIRROR, "placement", level, level,
                PortalSurface.of(geometry).through(Similarity.of(OpticTransform.of(OpticTransform.IDENTITY.permutation(), 4.0D, 0.0D, 0.0D), 1.0D))
                    .through(Similarity.IDENTITY),
                reflection, 3),
            new PortalView(PortalView.Kind.MIRROR, "reversed", level, level,
                PortalSurface.of(geometry).through(Similarity.IDENTITY), reflection, 3))) {
            assertNull(altered.key().toString(), NativeMirrorHistory.prove(altered, new ClientPortal(1, geometry, 1, 0.0D), null, true));
        }
    }

    @Test
    public void contradictoryTokensOrMultipleTargetsInOneBranchCannotCanonicalize() {
        ClientLevel level = level();
        NativeMirrorHistory.Proof root = proof(level, geometry(0, 0L), null);
        ApertureDescriptor firstGeometry = geometry(17, 77L);
        ApertureDescriptor secondGeometry = geometry(17, 78L);
        NativeMirrorHistory.Proof first = proof(level, firstGeometry, NativeMirrorHistoryScene.environment(firstGeometry, "minecraft:overworld"));
        NativeMirrorHistory.Proof second = proof(level, secondGeometry, NativeMirrorHistoryScene.environment(secondGeometry, "minecraft:overworld"));
        assertTrue(NativeMirrorHistory.unambiguous(Map.of("root", root, "first", first, "second", second)).isEmpty());
        ApertureDescriptor otherGeometry = NativeMirrorHistoryScene.geometry(46, 23, Face.N, 3, 18, 77L);
        NativeMirrorHistory.Proof other = proof(level, otherGeometry, NativeMirrorHistoryScene.environment(otherGeometry, "minecraft:overworld"));
        assertTrue(NativeMirrorHistory.unambiguous(Map.of("first", first, "other", other)).isEmpty());
        ApertureDescriptor independentParent = geometry(19, 77L);
        NativeMirrorHistory.Proof sibling = proof(level, independentParent,
            NativeMirrorHistoryScene.environment(independentParent, "minecraft:overworld"));
        assertEquals(3, NativeMirrorHistory.unambiguous(Map.of("root", root, "first", first, "sibling", sibling)).size());
    }

    @Test
    public void stagedDiscoveryAndReopeningPreserveSixPhysicalKeysWithoutReplacingRoutes() {
        try (NativeMirrorHistoryScene scene = new NativeMirrorHistoryScene()) {
            scene.frame(0);
            List<List<Object>> initial = identities(scene.traversals());
            PortalView root = scene.views.current().getFirst();
            scene.frame(0);
            assertSame(root, scene.views.current().getFirst());
            scene.frame(2);
            PortalView parent = scene.views.current().getFirst();
            PortalView child = scene.views.children(parent).getFirst();
            PortalView nativeRoot = scene.views.current().get(2);
            assertNotEquals(nativeRoot.key(), child.key());
            assertEquals(nativeRoot.historyKey(), child.historyKey());
            assertEquals(4, scene.views.streamKey(child));
            assertEquals(initial, identities(scene.traversals()));
            scene.frame(4);
            assertEquals(initial, identities(scene.traversals()));
            parent = scene.views.current().getFirst();
            child = scene.views.children(parent).getFirst();
            assertEquals(6, scene.views.streamKey(scene.views.children(child).getFirst()));
            scene.views.frame(scene.level, Vec3d.ZERO, List.of(), false, true, List.of(), null);
            scene.slots(40, 8);
            scene.frame(4);
            assertEquals(initial, identities(scene.traversals()));
            parent = scene.views.current().getFirst();
            child = scene.views.children(parent).getFirst();
            assertEquals(44, scene.views.streamKey(child));
            assertEquals(46, scene.views.streamKey(scene.views.children(child).getFirst()));
            assertSame(parent.key(), PortalViews.continuedKey(parent.key(), Map.of()));
            assertSame(parent.historyKey(), PortalViews.continuedKey(parent.historyKey(), Map.of()));
        }
    }

    @Test
    public void ambiguousRootAndContradictoryRootEnvironmentKeepRoutingIdentity() {
        try (NativeMirrorHistoryScene scene = new NativeMirrorHistoryScene()) {
            scene.roots.add(new ClientPortal(90, scene.roots.getFirst().geometry(), 1, 0.0D));
            scene.frame(0);
            PortalView ambiguous = scene.views.current().getFirst();
            assertSame(ambiguous.key(), ambiguous.historyKey());
            scene.slots(0, 1);
            scene.environments.put(1, NativeMirrorHistoryScene.environment(scene.roots.getFirst().geometry(), "minecraft:the_end"));
            scene.frame(0);
            PortalView contradictory = scene.views.current().getFirst();
            assertSame(contradictory.key(), contradictory.historyKey());
            assertFalse(PortalViews.sameCamera(contradictory, scene.views.current().get(1)));
        }
    }

    @Test
    public void affineAnnouncementsRemainStreamedAndCannotAcquireNativeMirrorProof() {
        try (NativeMirrorHistoryScene scene = new NativeMirrorHistoryScene()) {
            when(scene.session.affine(4)).thenReturn(mock(ViewStreamMessage.PortalAffine.class));
            scene.frame(4);
            PortalView parent = scene.views.current().getFirst();
            assertTrue(scene.views.children(parent).isEmpty());
            assertEquals(1, scene.views.streamKey(parent));
            assertTrue(scene.views.current().get(2).historyKey() instanceof NativeMirrorHistory.Key);
        }
    }

    @Test
    public void oppositeServedSidesKeepPrivateHistoriesWithoutConflictingOnOneSelfTarget() {
        ClientLevel level = level();
        ApertureDescriptor front = geometry(7, 3279357260576715132L);
        ApertureDescriptor back = altered(front, "side");
        back = new ApertureDescriptor(back.originX(), back.originY(), back.originZ(), back.facing(), back.frontSide(),
            back.quarterTurns(), back.mirror(), back.apertureWidth(), back.apertureHeight(), back.apertureMask(), back.shape(),
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 9, front.targetIdentity(), List.of());
        NativeMirrorHistory.Proof first = proof(level, front, NativeMirrorHistoryScene.environment(front, "minecraft:overworld"));
        NativeMirrorHistory.Proof second = proof(level, back, NativeMirrorHistoryScene.environment(back, "minecraft:overworld"));
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.key(), second.key());
        Map<Object, NativeMirrorHistory.Proof> proven = NativeMirrorHistory.unambiguous(Map.of("front", first, "back", second));
        assertEquals(2, proven.size());
        assertSame(first, proven.get("front"));
        assertSame(second, proven.get("back"));
    }

    private static List<List<Object>> identities(List<List<PortalView>> paths) {
        List<List<Object>> keys = new ArrayList<>(paths.size());
        for (List<PortalView> path : paths) {
            List<Object> traversal = new ArrayList<>(path.size());
            for (PortalView view : path) {
                traversal.add(view.historyKey());
            }
            keys.add(List.copyOf(traversal));
        }
        return List.copyOf(keys);
    }

    private static NativeMirrorHistory.Proof proof(ClientLevel level, ApertureDescriptor geometry, EnvironmentState environment) {
        return NativeMirrorHistory.prove(view(level, geometry), new ClientPortal(1, geometry, 1, 0.0D), environment, true);
    }

    private static PortalView view(ClientLevel level, ApertureDescriptor geometry) {
        return new PortalView(PortalView.Kind.MIRROR, "mirror", level, level, PortalSurface.of(geometry),
            Similarity.of(PortalSurface.mirrorTransform(geometry), 1.0D), 3);
    }

    private static ClientLevel level() {
        ClientLevel level = mock(ClientLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        return level;
    }

    private static ApertureDescriptor geometry(int parent, long target) {
        return NativeMirrorHistoryScene.geometry(40, 20, Face.N, 3, parent, target);
    }

    private static ApertureDescriptor altered(ApertureDescriptor geometry, String change) {
        return new ApertureDescriptor(geometry.originX() + (change.equals("origin") ? 1 : 0), geometry.originY(), geometry.originZ(),
            geometry.facing(), change.equals("side") != geometry.frontSide(), change.equals("turns") ? 1 : geometry.quarterTurns(),
            true, geometry.apertureWidth(), geometry.apertureHeight(), change.equals("mask") ? new long[]{510L} : geometry.apertureMask(),
            change.equals("shape") ? ShapeDescriptor.parse("circle") : geometry.shape(), 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0,
            change.equals("plane") ? 0.25D : geometry.planeOffset(), 0, 0L, List.of());
    }
}
