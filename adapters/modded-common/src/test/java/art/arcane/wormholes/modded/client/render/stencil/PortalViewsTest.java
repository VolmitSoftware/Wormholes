package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.ClientPalette;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.WormholesClientConfig;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.LocalDestination;
import art.arcane.wormholes.network.client.LocalDestinationExtension;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class PortalViewsTest extends MinecraftTestBase {
    private static final Vec3d EYE = new Vec3d(1.0D, 65.0D, 4.0D);

    @Test
    public void canonicalMirrorTraversalResolvesLateInverseBranchesWithoutLosingTheirRoutes() throws Exception {
        try (CrossingMirrors mirrors = new CrossingMirrors()) {
            PortalViews views = mirrors.travel.scene.views;
            List<Object> keys = List.of(mirrors.first.historyKey(), mirrors.second.historyKey());
            mirrors.cross();
            mirrors.frame(List.of(), List.of());
            assertEquals(List.of(mirrors.first, mirrors.second), views.crossingTraversal(keys));
            assertNull(views.continuedTraversal(keys));
            environment(mirrors.travel.scene, mirrors.reverseRoot, "minecraft:overworld", translation(200.0D).rigid());
            mirrors.frame(List.of(), mirrors.portals());
            PortalViews.TraversalContinuation continuation = views.continuedTraversal(keys);
            assertEquals(List.of(mirrors.first, mirrors.second), continuation.previous());
            assertEquals(3, continuation.next().size());
            PortalView returning = continuation.next().getFirst();
            assertEquals(PortalView.Kind.RETURN, returning.kind());
            PortalView first = continuation.next().get(1);
            PortalView second = continuation.next().get(2);
            assertSame(views.children(returning).getFirst(), first);
            assertSame(views.children(first).getFirst(), second);
            assertEquals(mirrors.first.historyKey(), first.historyKey());
            assertEquals(mirrors.second.historyKey(), second.historyKey());
            assertNotEquals(mirrors.first.key(), first.key());
            assertEquals(20, views.streamKey(first));
            assertEquals(21, views.streamKey(second));
            mirrors.frame(List.of(mirrors.reverse), mirrors.portals());
            continuation = views.continuedTraversal(keys);
            assertEquals(PortalView.Kind.ARM, continuation.next().getFirst().kind());
            assertEquals(keys.get(0), continuation.next().get(1).historyKey());
            assertEquals(keys.get(1), continuation.next().get(2).historyKey());
            assertEquals(20, views.streamKey(continuation.next().get(1)));
        }
    }

    @Test
    public void preparationAnchorsSurviveDepartureAndLateChildrenButLoseInvalidReturnOrReachProof() throws Exception {
        try (CrossingMirrors mirrors = new CrossingMirrors()) {
            PortalViews views = mirrors.travel.scene.views;
            PortalView anchor = mirrors.first;
            assertTrue(views.retainsPreparation(anchor, mirrors.travel.scene.home, EYE, 128.0D, 112.0D));
            assertFalse(views.retainsPreparation(anchor, mirrors.travel.scene.home, EYE, 8.0D, 8.0D));
            assertFalse(views.retainsPreparation(anchor, mirrors.travel.scene.home, EYE, 128.0D, 1.0D));
            mirrors.cross();
            mirrors.frame(List.of(), List.of());
            Vec3d arrived = mirrors.travel.toward.point(EYE);
            assertNull(views.continuedTraversal(List.of(anchor.key())));
            assertTrue(views.retainsPreparation(anchor, mirrors.travel.destination, arrived, 128.0D, 112.0D));
            mirrors.discover();
            assertTrue(views.retainsPreparation(anchor, mirrors.travel.destination, arrived, 128.0D, 112.0D));
            assertFalse(views.retainsPreparation(anchor, mirrors.travel.destination,
                arrived.add(new Vec3d(256.0D, 0.0D, 0.0D)), 128.0D, 112.0D));
            views.resetReturn(mirrors.travel.destination);
            assertFalse(views.retainsPreparation(anchor, mirrors.travel.destination, arrived, 128.0D, 112.0D));
        }
    }

    @Test
    public void preparationClaimsRejectChangedRealmGeometryScaleAndAmbiguousInverse() throws Exception {
        for (String mismatch : List.of("realm", "geometry", "scale", "inverse", "duplicate-source")) {
            try (CrossingMirrors mirrors = new CrossingMirrors()) {
                PortalViews views = mirrors.travel.scene.views;
                PortalView anchor = mirrors.first;
                if (mismatch.equals("realm")) {
                    ClientLevel alias = mock(ClientLevel.class);
                    when(alias.dimension()).thenReturn(Level.OVERWORLD);
                    assertFalse(views.retainsPreparation(anchor, alias, EYE, 128.0D, 112.0D));
                } else if (mismatch.equals("geometry")) {
                    mirrors.firstRoot.geometry(mirrors.firstRoot.geometry().withShape(ShapeDescriptor.parse("circle")), 2);
                    mirrors.depart();
                    assertFalse(views.retainsPreparation(anchor, mirrors.travel.scene.home, EYE, 128.0D, 112.0D));
                } else if (mismatch.equals("scale")) {
                    PortalView altered = new PortalView(anchor.kind(), anchor.key(), anchor.source(), anchor.destination(),
                        anchor.surface(), Similarity.of(anchor.toDestination().rigid(), 2.0D), anchor.recursion());
                    assertFalse(views.retainsPreparation(altered, mirrors.travel.scene.home, EYE, 128.0D, 112.0D));
                } else if (mismatch.equals("duplicate-source")) {
                    PortalView duplicate = new PortalView(anchor.kind(), "duplicate", anchor.source(), anchor.destination(),
                        anchor.surface(), anchor.toDestination(), anchor.recursion());
                    assertFalse(PortalViews.uniqueSourceCamera(anchor, List.of(anchor, duplicate)));
                } else {
                    mirrors.cross();
                    TravelMessage.TravelBegin duplicate = mirrors.travel.reverse(UUID.randomUUID(), reverseGeometry(false));
                    mirrors.frame(List.of(mirrors.reverse, duplicate), mirrors.portals());
                    assertFalse(views.retainsPreparation(anchor, mirrors.travel.destination, mirrors.travel.toward.point(EYE),
                        128.0D, 112.0D));
                }
            }
        }
    }

    @Test
    public void linkedParentDiscoversPhysicalChildrenInItsDestinationWorld() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal child = new ClientPortal(8, reverseGeometry(true).withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            environment(travel.scene, child, "minecraft:overworld", translation(200.0D).rigid());
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);

            PortalView parent = travel.scene.views.current().getFirst();
            assertEquals(1, travel.scene.views.current().size());
            assertEquals(7, travel.scene.views.streamKey(parent));
            PortalView discovered = travel.scene.views.children(parent).getFirst();
            assertSame(travel.destination, discovered.source());
            assertSame(travel.scene.home, discovered.destination());
            assertSame(child.geometry(), discovered.surface().geometry());
            assertEquals(Similarity.IDENTITY, discovered.surface().placement());
            assertEquals(8, travel.scene.views.streamKey(discovered));
            assertFalse(discovered.surface().servesEye(EYE));
            Vec3d virtualEye = parent.toDestination().point(EYE);
            assertTrue(discovered.surface().servesEye(virtualEye));
            assertEquals(EYE, discovered.toDestination().point(virtualEye));
            assertEquals(3, discovered.recursion());
        }
    }

    @Test
    public void nativeChildrenKeepBranchIdentityAcrossStreamSlotsAndCloseWhenDetached() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal first = new ClientPortal(8, reverseGeometry(true).withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(first.portalKey(), first);
            environment(travel.scene, first, "minecraft:overworld", translation(200.0D).rigid());
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, first), null);
            PortalView parent = travel.scene.views.current().getFirst();
            PortalView original = travel.scene.views.children(parent).getFirst();

            ClientPortal replacement = new ClientPortal(9, first.geometry(), 2, 0.0D);
            travel.scene.session.portals().remove(first.portalKey());
            travel.scene.session.portals().put(replacement.portalKey(), replacement);
            environment(travel.scene, replacement, "minecraft:overworld", translation(200.0D).rigid());
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, replacement), null);
            assertSame(original, travel.scene.views.children(parent).getFirst());
            assertEquals(9, travel.scene.views.streamKey(original));

            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root), null);
            assertTrue(travel.scene.views.children(parent).isEmpty());
            assertEquals(0, travel.scene.views.streamKey(original));
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, replacement), null);
            PortalView reopened = travel.scene.views.children(parent).getFirst();
            assertNotSame(original, reopened);
            assertEquals(original.key(), reopened.key());
        }
    }

    @Test
    public void descendantKeysRewriteTheirParentOwnershipAtEveryDepth() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal child = new ClientPortal(8, reverseGeometry(true).withParent(7), 1, 0.0D);
            ClientPortal grandchild = new ClientPortal(9, geometry(16, true).withParent(8), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            travel.scene.session.portals().put(grandchild.portalKey(), grandchild);
            environment(travel.scene, child, "minecraft:overworld", translation(200.0D).rigid());
            environment(travel.scene, grandchild, "minecraft:overworld", PortalSurface.mirrorTransform(grandchild.geometry()));
            List<ClientPortal> portals = List.of(root, child, grandchild);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), true, true, portals, null);
            PortalView previous = travel.scene.views.current().getFirst();
            PortalView previousChild = travel.scene.views.children(previous).getFirst();
            PortalView previousGrandchild = travel.scene.views.children(previousChild).getFirst();
            TravelMessage.TravelBegin replacement = residentArm(UUID.randomUUID(), root.geometry());
            when(replacement.sourceToDestination()).thenReturn(travel.toward);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(replacement), true, true, portals, null);
            PortalView next = travel.scene.views.current().getFirst();
            PortalView nextChild = travel.scene.views.children(next).getFirst();
            PortalView nextGrandchild = travel.scene.views.children(nextChild).getFirst();
            Map<Object, Object> ownership = Map.of(previous.key(), next.key());
            assertNotEquals(previousChild.key(), nextChild.key());
            assertNotEquals(previousGrandchild.key(), nextGrandchild.key());
            assertEquals(nextChild.key(), PortalViews.continuedKey(previousChild.key(), ownership));
            assertEquals(nextGrandchild.key(), PortalViews.continuedKey(previousGrandchild.key(), ownership));
            assertEquals(previousChild.key(), PortalViews.continuedKey(previousChild.key(), Map.of()));
        }
    }

    @Test
    public void crossingContinuesANativeMirrorSuffixAsItsPhysicalRoot() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ApertureDescriptor mirrorGeometry = geometry(0, true);
            ClientPortal child = new ClientPortal(8, mirrorGeometry.withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            environment(travel.scene, child, "minecraft:the_end", PortalSurface.mirrorTransform(mirrorGeometry));
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), true, true, List.of(root, child), null);
            PortalView parent = travel.scene.views.current().getFirst();
            PortalView previous = travel.scene.views.children(parent).getFirst();
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            ClientPortal arrived = new ClientPortal(9, mirrorGeometry, 1, 0.0D);
            travel.scene.source(arrived, travel.destination);
            travel.scene.views.frame(travel.destination, travel.toward.point(EYE), List.of(), true, true, List.of(arrived), null);
            PortalView next = travel.scene.views.current().getFirst();
            List<PortalViews.TraversalContinuation> continuations = travel.scene.views.takeTraversalContinuations();
            assertEquals(1, continuations.size());
            assertEquals(List.of(parent, previous), continuations.getFirst().previous());
            assertEquals(List.of(next), continuations.getFirst().next());
            assertNotEquals(previous.key(), next.key());
            assertTrue(PortalViews.sameCamera(previous, next));
            assertTrue(travel.scene.views.takeTraversalContinuations().isEmpty());
        }
    }

    @Test
    public void coincidentIndependentArrivalCamerasCannotClaimOneNestedHistory() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ApertureDescriptor mirrorGeometry = geometry(0, true);
            ClientPortal child = new ClientPortal(8, mirrorGeometry.withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            environment(travel.scene, child, "minecraft:the_end", PortalSurface.mirrorTransform(mirrorGeometry));
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            TravelMessage.TravelBegin first = arm(UUID.randomUUID(), mirrorGeometry);
            TravelMessage.TravelBegin second = arm(UUID.randomUUID(), mirrorGeometry);
            for (TravelMessage.TravelBegin candidate : List.of(first, second)) {
                when(candidate.sourceWorld()).thenReturn("minecraft:the_end");
                when(candidate.sourceToDestination()).thenReturn(Similarity.of(PortalSurface.mirrorTransform(mirrorGeometry), 1.0D));
            }
            travel.scene.views.frame(travel.destination, travel.toward.point(EYE), List.of(first, second), false, true, List.of(), null);
            assertTrue(travel.scene.views.takeTraversalContinuations().isEmpty());
        }
    }

    @Test
    public void departedRootsExpandAfterTheReverseArmAndItsChildrenArriveOnLaterFrames() throws Exception {
        try (CrossingMirrors mirrors = new CrossingMirrors()) {
            mirrors.cross();
            mirrors.frame(List.of(), List.of());
            assertNull(mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key())));
            mirrors.frame(List.of(mirrors.reverse), List.of(mirrors.reverseRoot));
            assertNull(mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key())));
            mirrors.discover();
            PortalViews.TraversalContinuation continued = mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key()));
            assertEquals(List.of(mirrors.first), continued.previous());
            assertEquals(2, continued.next().size());
            assertEquals(mirrors.travel.scene.views.current().getFirst(), continued.next().getFirst());
            assertTrue(PortalViews.sameCamera(mirrors.first, continued.next().getLast()));
            assertNotEquals(mirrors.first.key(), continued.next().getLast().key());
            Vec3d arrivedEye = mirrors.travel.toward.point(EYE);
            assertEquals(mirrors.first.toDestination().point(EYE),
                continued.next().getLast().toDestination().point(continued.next().getFirst().toDestination().point(arrivedEye)));
        }
    }

    @Test
    public void returnRootDiscoversExactChildrenBeforeTheReverseArmArrives() throws Exception {
        try (CrossingMirrors mirrors = new CrossingMirrors()) {
            environment(mirrors.travel.scene, mirrors.reverseRoot, "minecraft:overworld", translation(200.0D).rigid());
            mirrors.cross();
            mirrors.frame(List.of(), List.of(mirrors.reverseRoot));
            assertEquals(List.of(mirrors.first), mirrors.travel.scene.views.crossingTraversal(List.of(mirrors.first.key())));
            assertNull(mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key())));
            mirrors.frame(List.of(), mirrors.portals());
            PortalViews.TraversalContinuation continued = mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key()));
            assertEquals(PortalView.Kind.RETURN, continued.next().getFirst().kind());
            assertEquals(9, mirrors.travel.scene.views.streamKey(continued.next().getFirst()));
            assertEquals(20, mirrors.travel.scene.views.streamKey(continued.next().getLast()));
            List<Object> returnKeys = List.of(continued.next().getFirst().key(), continued.next().getLast().key());
            mirrors.discover();
            PortalViews.TraversalContinuation armed = mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key()));
            assertEquals(PortalView.Kind.ARM, armed.next().getFirst().kind());
            assertEquals(armed.next().getLast().key(), PortalViews.continuedKey(returnKeys.getLast(),
                Map.of(returnKeys.getFirst(), armed.next().getFirst().key())));
            assertTrue(PortalViews.sameCamera(continued.next().getLast(), armed.next().getLast()));
        }
    }

    @Test
    public void placedReturnRootsRequireCurrentLocalEnvironmentAndExactPhysicalOpening() throws Exception {
        for (int mismatch = 0; mismatch < 8; mismatch++) {
            try (CrossingMirrors mirrors = new CrossingMirrors()) {
                if (mismatch == 0) {
                    streamEnvironment(mirrors.travel.scene, mirrors.reverseRoot, "minecraft:overworld", translation(200.0D).rigid());
                } else {
                    environment(mirrors.travel.scene, mirrors.reverseRoot, "minecraft:overworld", translation(200.0D).rigid());
                }
                if (mismatch == 1) {
                    mirrors.reverseRoot.geometry(mirrors.reverseRoot.geometry(), 2);
                } else if (mismatch == 2) {
                    streamEnvironment(mirrors.travel.scene, mirrors.reverseRoot, "minecraft:overworld", translation(201.0D).rigid());
                } else if (mismatch == 3) {
                    mirrors.reverseRoot.geometry(reverseGeometry(true), 1);
                } else if (mismatch == 4) {
                    mirrors.reverseRoot.geometry(reverseGeometry(false, 31L), 1);
                } else if (mismatch == 5) {
                    mirrors.reverseRoot.geometry(mirrors.reverseRoot.geometry().withShape(ShapeDescriptor.parse("circle")), 1);
                } else if (mismatch == 6) {
                    streamEnvironment(mirrors.travel.scene, mirrors.reverseRoot, "minecraft:the_end", translation(200.0D).rigid());
                } else if (mismatch == 7) {
                    when(mirrors.travel.scene.session.environment(9).scale()).thenReturn(2.0F);
                }
                mirrors.cross();
                mirrors.frame(List.of(), mirrors.portals());
                PortalView returning = mirrors.travel.scene.views.current().getFirst();
                assertEquals(PortalView.Kind.RETURN, returning.kind());
                assertEquals(0, mirrors.travel.scene.views.streamKey(returning));
                assertTrue(mirrors.travel.scene.views.children(returning).isEmpty());
                assertNull(mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key())));
            }
        }
    }

    @Test
    public void duplicatePlacedRootsNeverChooseOneWireBranch() throws Exception {
        try (CrossingMirrors mirrors = new CrossingMirrors()) {
            environment(mirrors.travel.scene, mirrors.reverseRoot, "minecraft:overworld", translation(200.0D).rigid());
            ClientPortal duplicate = new ClientPortal(30, mirrors.reverseRoot.geometry(), 1, 0.0D);
            mirrors.travel.scene.source(duplicate, mirrors.travel.destination);
            environment(mirrors.travel.scene, duplicate, "minecraft:overworld", translation(200.0D).rigid());
            mirrors.cross();
            mirrors.frame(List.of(), List.of(mirrors.reverseRoot, mirrors.firstChild, duplicate));
            PortalView returning = mirrors.travel.scene.views.current().getFirst();
            assertEquals(0, mirrors.travel.scene.views.streamKey(returning));
            assertTrue(mirrors.travel.scene.views.children(returning).isEmpty());
        }
    }

    @Test
    public void changedChildTargetCannotClaimTheSamePhysicalHistory() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal child = new ClientPortal(8, identity(reverseGeometry(true).withParent(7), 40L), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            environment(travel.scene, child, "minecraft:overworld", translation(200.0D).rigid());
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            PortalView previous = travel.scene.views.children(travel.scene.views.current().getFirst()).getFirst();
            child.geometry(identity(child.geometry(), 41L), 2);
            environment(travel.scene, child, "minecraft:overworld", translation(200.0D).rigid());
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            PortalView next = travel.scene.views.children(travel.scene.views.current().getFirst()).getFirst();
            assertFalse(PortalViews.sameCamera(previous, next));
        }
    }

    @Test
    public void recursiveCurrentRootsExpandToTheirExactNativeChildTraversal() throws Exception {
        try (CrossingMirrors mirrors = new CrossingMirrors()) {
            mirrors.cross();
            mirrors.discover();
            assertTrue(mirrors.second.surface().servesEye(mirrors.first.toDestination().point(EYE)));
            PortalViews.TraversalContinuation continued = mirrors.travel.scene.views.continuedTraversal(
                List.of(mirrors.first.key(), mirrors.second.key()));
            assertEquals(List.of(mirrors.first, mirrors.second), continued.previous());
            assertEquals(3, continued.next().size());
            assertTrue(PortalViews.sameCamera(mirrors.first, continued.next().get(1)));
            assertTrue(PortalViews.sameCamera(mirrors.second, continued.next().get(2)));
            assertEquals(21, mirrors.travel.scene.views.streamKey(continued.next().getLast()));
            PortalViews.TraversalContinuation independent = mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.second.key()));
            assertEquals(22, mirrors.travel.scene.views.streamKey(independent.next().getLast()));
            assertNotEquals(independent.next().getLast().key(), continued.next().getLast().key());
        }
    }

    @Test
    public void returnCrossingCollapsesTheExpandedHistoryAndInvalidatesOlderCandidates() throws Exception {
        try (CrossingMirrors mirrors = new CrossingMirrors()) {
            for (int crossing = 0; crossing < 3; crossing++) {
                mirrors.cross();
                mirrors.discover();
                PortalViews.TraversalContinuation expanded = mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key()));
                List<Object> expandedKeys = List.of(expanded.next().getFirst().key(), expanded.next().getLast().key());
                long epoch = mirrors.travel.scene.views.continuityEpoch();
                mirrors.travel.scene.views.crossed(mirrors.reverse, mirrors.travel.destination, mirrors.travel.scene.home, true);
                mirrors.depart();
                assertTrue(mirrors.travel.scene.views.continuityEpoch() > epoch);
                PortalViews.TraversalContinuation collapsed = mirrors.travel.scene.views.continuedTraversal(expandedKeys);
                assertEquals(expanded.next(), collapsed.previous());
                assertEquals(List.of(mirrors.first), collapsed.next());
                assertNull(mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key())));
            }
        }
    }

    @Test
    public void ambiguousReverseRootsAndCoincidentChildIdentitiesNeverBorrowHistory() throws Exception {
        for (boolean duplicateRoot : new boolean[]{true, false}) {
            try (CrossingMirrors mirrors = new CrossingMirrors()) {
                mirrors.cross();
                if (duplicateRoot) {
                    TravelMessage.TravelBegin duplicate = mirrors.travel.reverse(UUID.randomUUID(), reverseGeometry(false));
                    mirrors.frame(List.of(mirrors.reverse, duplicate), mirrors.portals());
                } else {
                    ApertureDescriptor geometry = mirrors.firstChild.geometry();
                    ClientPortal duplicate = new ClientPortal(23, identity(geometry, 88L), 1, 0.0D);
                    mirrors.travel.scene.session.portals().put(duplicate.portalKey(), duplicate);
                    environment(mirrors.travel.scene, duplicate, "minecraft:overworld", PortalSurface.mirrorTransform(geometry));
                    mirrors.frame(List.of(mirrors.reverse), List.of(mirrors.reverseRoot, mirrors.firstChild, duplicate));
                }
                assertNull(mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key())));
            }
        }
    }

    @Test
    public void changedChildGeometryTransformOrResidentCannotClaimDepartedHistory() throws Exception {
        for (int mismatch = 0; mismatch < 5; mismatch++) {
            try (CrossingMirrors mirrors = new CrossingMirrors()) {
                mirrors.cross();
                if (mismatch == 0) {
                    mirrors.firstChild.geometry(mirrors.firstChild.geometry().withShape(ShapeDescriptor.parse("circle")), 2);
                } else if (mismatch == 1) {
                    ApertureDescriptor changed = new ApertureDescriptor(16, 64, 0, Face.S.ordinal(), true, 0, true, 2, 3,
                        new long[]{31L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 9, 0L, List.of());
                    mirrors.firstChild.geometry(changed, 2);
                } else if (mismatch == 2) {
                    environment(mirrors.travel.scene, mirrors.firstChild, "minecraft:overworld", translation(1.0D).rigid());
                } else if (mismatch == 3) {
                    when(mirrors.travel.scene.session.environment(20).scale()).thenReturn(0.5F);
                } else {
                    ClientLevel replacement = mock(ClientLevel.class);
                    when(replacement.dimension()).thenReturn(Level.OVERWORLD);
                    when(mirrors.travel.scene.residents.level(2)).thenReturn(replacement);
                }
                mirrors.discover();
                assertNull(mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key())));
            }
        }
    }

    @Test
    public void rollbackReturnExpiryAndMissingReverseBranchInvalidateCrossingHistory() throws Exception {
        for (int invalidation = 0; invalidation < 3; invalidation++) {
            try (CrossingMirrors mirrors = new CrossingMirrors()) {
                mirrors.cross();
                if (invalidation == 0) {
                    mirrors.discover();
                    mirrors.travel.scene.views.resetReturn(mirrors.travel.destination);
                } else if (invalidation == 1) {
                    mirrors.travel.scene.views.frame(mirrors.travel.destination, new Vec3d(100.0D, 65.0D, 200.0D),
                        List.of(), true, true, List.of(), null);
                } else {
                    mirrors.discover();
                    mirrors.frame(List.of(), List.of());
                }
                long epoch = mirrors.travel.scene.views.continuityEpoch();
                mirrors.discover();
                assertEquals(epoch, mirrors.travel.scene.views.continuityEpoch());
                assertNull(mirrors.travel.scene.views.continuedTraversal(List.of(mirrors.first.key())));
            }
        }
    }

    @Test
    public void floorAndCeilingChildrenUseTranslatedVirtualEyeAndParentRecursionCeiling() throws Exception {
        for (Face face : List.of(Face.U, Face.D)) {
            try (TravelScene travel = new TravelScene()) {
                Similarity toward = Similarity.of(OpticTransform.of(OpticTransform.IDENTITY.permutation(), 0.0D, 200.0D, 0.0D), 1.0D);
                when(travel.forward.sourceToDestination()).thenReturn(toward);
                ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
                ApertureDescriptor opening = new ApertureDescriptor(0, 264, 0, face.ordinal(), face == Face.U, 0, false, 2, 3,
                    new long[]{63L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 7, 0, 0, 0, 0, 0, 0, 0.0D, 7, 0L, List.of());
                ClientPortal child = new ClientPortal(8, opening, 1, 0.0D);
                ClientPortal grandchild = new ClientPortal(9, opening.withParent(8), 1, 0.0D);
                travel.scene.source(root, travel.scene.home);
                travel.scene.session.portals().put(child.portalKey(), child);
                travel.scene.session.portals().put(grandchild.portalKey(), grandchild);
                environment(travel.scene, child, "minecraft:the_end", OpticTransform.IDENTITY);
                environment(travel.scene, grandchild, "minecraft:the_end", OpticTransform.IDENTITY);
                travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true,
                    List.of(root, child, grandchild), null);
                PortalView parent = travel.scene.views.current().getFirst();
                PortalView discovered = travel.scene.views.children(parent).getFirst();
                assertFalse(discovered.surface().servesEye(EYE));
                assertTrue(discovered.surface().servesEye(parent.toDestination().point(EYE)));
                assertEquals(3, discovered.recursion());
                PortalView second = travel.scene.views.children(discovered).getFirst();
                assertEquals(3, second.recursion());
                assertTrue(travel.scene.views.children(second).isEmpty());
                travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, false,
                    List.of(root, child, grandchild), null);
                assertTrue(travel.scene.views.children(travel.scene.views.current().getFirst()).isEmpty());
            }
        }
    }

    @Test
    public void unresolvedAndAmbiguousResidentWorldsStayAvailableForStreamedFallback() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal child = new ClientPortal(8, reverseGeometry(true).withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            environment(travel.scene, child, "minecraft:the_nether", OpticTransform.IDENTITY);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            PortalView parent = travel.scene.views.current().getFirst();
            assertTrue(travel.scene.views.children(parent).isEmpty());
            assertEquals(7, travel.scene.views.streamKey(parent));

            ClientLevel duplicate = mock(ClientLevel.class);
            when(duplicate.dimension()).thenReturn(Level.END);
            when(travel.scene.residents.level(3)).thenReturn(duplicate);
            TravelMessage.TravelBegin other = residentArm(UUID.randomUUID(), geometry(16, false));
            when(other.levelHandle()).thenReturn(3);
            child.geometry(child.geometry(), 2);
            environment(travel.scene, child, "minecraft:overworld", OpticTransform.IDENTITY);
            when(duplicate.dimension()).thenReturn(Level.OVERWORLD);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward, other), false, true, List.of(root, child), null);
            assertTrue(travel.scene.views.children(parent).isEmpty());
        }
    }

    @Test
    public void sameWorldFloorAndCeilingChildrenUseTheirParentsExactResidentCopy() throws Exception {
        for (Face face : List.of(Face.U, Face.D)) {
            try (TravelScene travel = new TravelScene()) {
                when(travel.destination.dimension()).thenReturn(Level.OVERWORLD);
                ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
                ApertureDescriptor opening = new ApertureDescriptor(0, 64, 200, face.ordinal(), face == Face.U, 0, false, 2, 3,
                    new long[]{63L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 7, 0L, List.of());
                ClientPortal child = new ClientPortal(8, opening, 1, 0.0D);
                ClientPortal grandchild = new ClientPortal(9, opening.withParent(8), 1, 0.0D);
                travel.scene.source(root, travel.scene.home);
                travel.scene.session.portals().put(child.portalKey(), child);
                travel.scene.session.portals().put(grandchild.portalKey(), grandchild);
                environment(travel.scene, child, "minecraft:overworld", OpticTransform.IDENTITY);
                environment(travel.scene, grandchild, "minecraft:overworld", OpticTransform.IDENTITY);
                travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true,
                    List.of(root, child, grandchild), null);
                PortalView parent = travel.scene.views.current().getFirst();
                PortalView discovered = travel.scene.views.children(parent).getFirst();
                assertSame(travel.destination, discovered.source());
                assertSame(travel.destination, discovered.destination());
                assertSame(travel.destination, travel.scene.views.children(discovered).getFirst().destination());
                assertEquals(8, travel.scene.views.streamKey(discovered));
            }
        }
    }

    @Test
    public void nativeMirrorChildReflectsTheParentsExactResidentCopyWithoutDimensionLookup() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            when(travel.destination.dimension()).thenReturn(Level.OVERWORLD);
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal child = new ClientPortal(8, geometry(16, true).withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            streamEnvironment(travel.scene, child, "minecraft:overworld", PortalSurface.mirrorTransform(child.geometry()));
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), true, true, List.of(root, child), null);
            PortalView parent = travel.scene.views.current().getFirst();
            PortalView mirror = travel.scene.views.children(parent).getFirst();
            assertEquals(PortalView.Kind.MIRROR, mirror.kind());
            assertSame(travel.destination, mirror.source());
            assertSame(travel.destination, mirror.destination());
            assertEquals(Similarity.of(PortalSurface.mirrorTransform(child.geometry()), 1.0D), mirror.toDestination());
            streamEnvironment(travel.scene, child, "minecraft:the_end", PortalSurface.mirrorTransform(child.geometry()));
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), true, true, List.of(root, child), null);
            assertTrue(travel.scene.views.children(parent).isEmpty());
        }
    }

    @Test
    public void provenChildrenResolveADifferentLocalResidentDimension() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientLevel nether = mock(ClientLevel.class);
            when(nether.dimension()).thenReturn(Level.NETHER);
            when(travel.scene.residents.level(3)).thenReturn(nether);
            TravelMessage.TravelBegin other = residentArm(UUID.randomUUID(), geometry(16, false));
            when(other.levelHandle()).thenReturn(3);
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal child = new ClientPortal(8, reverseGeometry(true).withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            environment(travel.scene, child, "minecraft:the_nether", OpticTransform.IDENTITY);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward, other), false, true, List.of(root, child), null);
            PortalView parent = travel.scene.views.current().getFirst();
            assertSame(nether, travel.scene.views.children(parent).getFirst().destination());
            when(travel.scene.residents.level(3)).thenReturn(null);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward, other), false, true, List.of(root, child), null);
            assertTrue(travel.scene.views.children(parent).isEmpty());
        }
    }

    @Test
    public void sameDimensionRemoteStreamsRequireLocalProofEvenWhenTheDimensionIsUnique() throws Exception {
        for (boolean sameDimensionCopy : new boolean[]{false, true}) {
            try (TravelScene travel = new TravelScene()) {
                if (sameDimensionCopy) {
                    when(travel.destination.dimension()).thenReturn(Level.OVERWORLD);
                }
                ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
                ClientPortal child = new ClientPortal(8, reverseGeometry(true).withParent(7), 1, 0.0D);
                travel.scene.source(root, travel.scene.home);
                travel.scene.session.portals().put(child.portalKey(), child);
                String world = travel.destination.dimension().identifier().toString();
                streamEnvironment(travel.scene, child, world, OpticTransform.IDENTITY);
                travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
                PortalView parent = travel.scene.views.current().getFirst();
                assertTrue(travel.scene.views.children(parent).isEmpty());
                assertEquals(7, travel.scene.views.streamKey(parent));
                assertSame(child, travel.scene.session.portal(8));
            }
        }
    }

    @Test
    public void nativeChildrenWaitForMatchingDestinationRevisionAndEnvironment() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal child = new ClientPortal(8, reverseGeometry(true).withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            environment(travel.scene, child, "minecraft:the_end", OpticTransform.IDENTITY);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            PortalView parent = travel.scene.views.current().getFirst();
            assertEquals(1, travel.scene.views.children(parent).size());
            child.geometry(child.geometry(), 2);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            assertTrue(travel.scene.views.children(parent).isEmpty());
            environment(travel.scene, child, "minecraft:the_end", OpticTransform.IDENTITY);
            streamEnvironment(travel.scene, child, "minecraft:overworld", OpticTransform.IDENTITY);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            assertTrue(travel.scene.views.children(parent).isEmpty());
            streamEnvironment(travel.scene, child, "minecraft:the_end", OpticTransform.IDENTITY);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            assertEquals(1, travel.scene.views.children(parent).size());
            travel.scene.session.handle(new ViewStreamMessage.PortalDrop(8), mock(ClientViewSession.Sink.class));
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            assertTrue(travel.scene.views.children(parent).isEmpty());
        }
    }

    @Test
    public void affineChildrenStayAvailableForStreamedFallback() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal child = new ClientPortal(8, reverseGeometry(true).withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            environment(travel.scene, child, "minecraft:overworld", translation(200.0D).rigid());
            ClientViewSession announced = mock(ClientViewSession.class);
            when(announced.matchesSource(7, travel.scene.home)).thenReturn(true);
            when(announced.environment(8)).thenReturn(travel.scene.session.environment(8));
            when(announced.affine(8)).thenReturn(mock(ViewStreamMessage.PortalAffine.class));
            when(travel.scene.client.session()).thenReturn(announced);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            PortalView parent = travel.scene.views.current().getFirst();
            assertEquals(7, travel.scene.views.streamKey(parent));
            assertTrue(travel.scene.views.children(parent).isEmpty());
        }
    }

    @Test
    public void ambiguousAndMismatchedStreamRootsDoNotBindChildren() throws Exception {
        try (TravelScene travel = new TravelScene()) {
            ClientPortal root = new ClientPortal(7, travel.forward.sourceGeometry(), 1, 0.0D);
            ClientPortal duplicate = new ClientPortal(9, root.geometry(), 1, 0.0D);
            ClientPortal child = new ClientPortal(8, reverseGeometry(true).withParent(7), 1, 0.0D);
            travel.scene.source(root, travel.scene.home);
            travel.scene.source(duplicate, travel.scene.home);
            travel.scene.session.portals().put(child.portalKey(), child);
            environment(travel.scene, child, "minecraft:overworld", translation(200.0D).rigid());
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child, duplicate), null);
            PortalView parent = travel.scene.views.current().getFirst();
            assertEquals(0, travel.scene.views.streamKey(parent));
            assertTrue(travel.scene.views.children(parent).isEmpty());

            environment(travel.scene, root, "minecraft:the_end", OpticTransform.IDENTITY);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            assertEquals(0, travel.scene.views.streamKey(parent));
            assertTrue(travel.scene.views.children(parent).isEmpty());
            environment(travel.scene, root, "minecraft:the_end", translation(-200.0D).rigid());
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), false, true, List.of(root, child), null);
            assertEquals(7, travel.scene.views.streamKey(parent));
            assertEquals(1, travel.scene.views.children(parent).size());
        }
    }

    @Test
    public void childScaleAppliesAroundItsSourceApertureCenterBeforeDestinationMapping() {
        ApertureDescriptor geometry = geometry(16, false);
        EnvironmentState environment = mock(EnvironmentState.class);
        when(environment.transform()).thenReturn(translation(200.0D).rigid());
        when(environment.scale()).thenReturn(0.5F);
        Similarity toward = PortalViews.toward(geometry, environment);
        Vec3d sourceCenter = new Vec3d(17.0D, 65.5D, geometry.planeCoordinate());
        Vec3d destinationCenter = environment.transform().inverse().point(sourceCenter);
        assertEquals(destinationCenter, toward.point(sourceCenter));
        assertEquals(destinationCenter.add(new Vec3d(4.0D, 6.0D, 8.0D)),
            toward.point(sourceCenter.add(new Vec3d(2.0D, 3.0D, 4.0D))));
        assertEquals(2.0D, toward.scale(), 0.0D);
    }

    @Test
    public void identicalMirrorSurfacesInDifferentWorldsKeepDistinctCameraKeys() {
        try (Scene scene = new Scene()) {
            ClientLevel destination = mock(ClientLevel.class);
            when(destination.dimension()).thenReturn(Level.END);
            ClientPortal home = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            ClientPortal remote = new ClientPortal(2, geometry(0, true), 1, 0.0D);
            scene.source(home, scene.home);
            scene.source(remote, destination);
            scene.views.frame(scene.home, EYE, List.of(), true, true, List.of(home, remote), null);
            Object original = scene.views.current().getFirst().key();
            scene.views.frame(destination, EYE, List.of(), true, true, List.of(home, remote), null);
            assertNotEquals(original, scene.views.current().getFirst().key());
        }
    }

    private static void environment(Scene scene, ClientPortal portal, String destination, OpticTransform transform) throws Exception {
        streamEnvironment(scene, portal, destination, transform);
        scene.session.handle(LocalDestinationExtension.INSTANCE.wrap(new LocalDestination(portal.portalKey(),
            portal.geometryRevision(), destination)), mock(ClientViewSession.Sink.class));
    }

    private static void streamEnvironment(Scene scene, ClientPortal portal, String destination, OpticTransform transform) throws Exception {
        EnvironmentState environment = mock(EnvironmentState.class);
        EnvironmentState.World world = mock(EnvironmentState.World.class);
        when(world.dimensionKey()).thenReturn(destination);
        when(environment.world()).thenReturn(world);
        when(environment.transform()).thenReturn(transform);
        when(environment.scale()).thenReturn(1.0F);
        scene.session.handle(new ViewStreamMessage.Environment(portal.portalKey(), environment), mock(ClientViewSession.Sink.class));
    }

    @Test
    public void secondCoplanarPortalOwnsReturnRegardlessOfArmOrder() {
        for (boolean reversed : new boolean[]{false, true}) {
            try (TravelScene travel = new TravelScene()) {
                TravelMessage.TravelBegin second = residentArm(UUID.randomUUID(), geometry(4, false));
                when(second.sourceToDestination()).thenReturn(travel.toward);
                List<TravelMessage.TravelBegin> arms = reversed ? List.of(second, travel.forward) : List.of(travel.forward, second);
                travel.scene.views.frame(travel.scene.home, new Vec3d(5.0D, 65.0D, 4.0D), arms, false, true, List.of(), null);
                PortalView entered = travel.scene.views.current().get(reversed ? 0 : 1);
                PortalView expected = PortalViews.futureTravelViews(entered).getLast();

                travel.scene.views.crossed(second, travel.scene.home, travel.destination, true);
                assertCamera(expected, travel.scene.views.follow(travel.destination));
                travel.scene.views.frame(travel.destination, travel.toward.point(new Vec3d(5.0D, 65.0D, 4.0D)),
                    List.of(), false, true, List.of(), null);
                assertEquals(1, travel.scene.views.current().size());
                assertCamera(expected, travel.scene.views.current().getFirst());
            }
        }
    }

    @Test
    public void crossingUsesArmSnapshotWhenNoSourceViewWasRendered() {
        try (TravelScene travel = new TravelScene()) {
            PortalView expected = PortalViews.futureTravelViews(travel.scene.views.current().getFirst()).getLast();
            travel.scene.views.frame(travel.scene.home, EYE, List.of(), false, true, List.of(), null);
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertCamera(expected, travel.scene.views.follow(travel.destination));
        }
    }

    @Test
    public void returnSurvivesArmRemovalAndResidentHandleReuseAfterCrossing() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            PortalView expected = travel.scene.views.follow(travel.destination);
            when(travel.scene.residents.level(1)).thenReturn(travel.scene.home);
            travel.scene.views.frame(travel.destination, travel.toward.point(EYE), List.of(), false, true, List.of(), null);
            assertSame(expected, travel.scene.views.current().getFirst());
        }
    }

    @Test
    public void crossingIntoANonmatchingResidentNeverBorrowsAnotherArm() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            ClientLevel unrelated = mock(ClientLevel.class);
            when(unrelated.dimension()).thenReturn(Level.END);
            travel.scene.views.crossed(travel.forward, travel.scene.home, unrelated, true);
            assertNull(travel.scene.views.follow(unrelated));
            travel.scene.views.frame(unrelated, EYE, List.of(), false, true, List.of(), null);
            assertTrue(travel.scene.views.current().isEmpty());
        }
    }

    @Test
    public void nonresidentArmCannotReusePreviousReturnOwnership() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            when(travel.forward.resident()).thenReturn(false);
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertNull(travel.scene.views.follow(travel.destination));
        }
    }

    @Test
    public void wrongSourceWorldAndUnavailableDepartedRendererCannotCreateReturn() {
        try (TravelScene travel = new TravelScene()) {
            when(travel.forward.sourceWorld()).thenReturn("minecraft:the_nether");
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertNull(travel.scene.views.follow(travel.destination));
            when(travel.forward.sourceWorld()).thenReturn("minecraft:overworld");
            travel.worlds.when(() -> ClientWorldLoader.residentRenderer(travel.scene.home)).thenReturn(null);
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertNull(travel.scene.views.follow(travel.destination));
        }
    }

    @Test
    public void unknownArmAndSameLevelMovementInvalidatePreviousReturn() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            assertTrue(travel.scene.views.follow(travel.destination) != null);
            travel.scene.views.crossed(null, travel.destination, travel.scene.home, true);
            assertNull(travel.scene.views.follow(travel.scene.home));
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            travel.scene.views.crossed(travel.reverse(UUID.randomUUID(), reverseGeometry(false)), travel.destination, travel.destination, true);
            assertNull(travel.scene.views.follow(travel.destination));
        }
    }

    @Test
    public void sameDimensionResidentSwapStillUsesExactLevelIdentity() {
        try (TravelScene travel = new TravelScene()) {
            when(travel.destination.dimension()).thenReturn(Level.OVERWORLD);
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            PortalView returning = travel.scene.views.follow(travel.destination);
            assertSame(travel.destination, returning.source());
            assertSame(travel.scene.home, returning.destination());
        }
    }

    @Test
    public void unannouncedHomeChangeDoesNotInferReturnFromPreviouslyRenderedArms() {
        try (TravelScene travel = new TravelScene()) {
            assertNull(travel.scene.views.follow(travel.destination));
            travel.scene.views.frame(travel.destination, travel.toward.point(EYE), List.of(), false, true, List.of(), null);
            assertTrue(travel.scene.views.current().isEmpty());
        }
    }

    @Test
    public void rollbackClearsReturnAndPendingCameraContinuationsWithoutReinferring() {
        try (TravelScene travel = new TravelScene()) {
            travel.arrive(List.of(travel.reverse(UUID.randomUUID(), reverseGeometry(false))));
            travel.scene.views.resetReturn(travel.scene.home);
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            assertNull(travel.scene.views.follow(travel.scene.home));
            travel.scene.views.frame(travel.destination, travel.toward.point(EYE), List.of(), false, true, List.of(), null);
            assertTrue(travel.scene.views.current().isEmpty());
        }
    }

    @Test
    public void latestCrossingOwnsReturnWithoutAnIntermediateRenderFrame() {
        try (TravelScene travel = new TravelScene()) {
            TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
            PortalView forwardReturn = travel.scene.views.follow(travel.destination);
            travel.scene.views.crossed(reverse, travel.destination, travel.scene.home, true);
            PortalView reverseReturn = travel.scene.views.follow(travel.scene.home);
            assertNotEquals(forwardReturn.key(), reverseReturn.key());
            assertSame(travel.scene.home, reverseReturn.source());
            assertSame(travel.destination, reverseReturn.destination());
            assertEquals(reverse.sourceGeometry(), reverseReturn.surface().geometry());
            travel.scene.views.frame(travel.scene.home, EYE, List.of(), false, true, List.of(), null);
            assertSame(reverseReturn, travel.scene.views.current().getFirst());
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
        }
    }

    @Test
    public void newCrossingExpiresThePreviousFramesCameraContinuations() {
        try (TravelScene travel = new TravelScene()) {
            TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.arrive(List.of(reverse));
            travel.scene.views.crossed(reverse, travel.destination, travel.scene.home, true);
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            assertTrue(travel.scene.views.follow(travel.scene.home) != null);
        }
    }

    @Test
    public void crossingKeepsCurrentRecursionChoiceWhenSourceViewIsMissing() {
        try (TravelScene travel = new TravelScene()) {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, false);
            assertEquals(1, travel.scene.views.follow(travel.destination).recursion());
        }
    }

    @Test
    public void matchingReverseArmContinuesAllFourCameraRolesAndRestoresThemOnReturn() {
        try (TravelScene travel = new TravelScene()) {
            PortalView original = travel.scene.views.current().getFirst();
            TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.arrive(List.of(reverse));
            assertEquals(1, travel.scene.views.current().size());
            PortalView armed = travel.scene.views.current().getFirst();
            assertEquals(PortalView.Kind.ARM, armed.kind());
            List<PortalViews.CameraContinuation> outbound = travel.scene.views.takeCameraContinuations();
            assertEquals(4, outbound.size());
            assertSame(original, outbound.getFirst().previous());
            assertSame(armed, outbound.getLast().next());
            for (PortalViews.CameraContinuation continuation : outbound) {
                assertTrue(PortalViews.sameCamera(continuation.previous(), continuation.next()));
                assertNotEquals(continuation.previous().key(), continuation.next().key());
            }
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            travel.scene.views.crossed(reverse, travel.destination, travel.scene.home, true);
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward, reverse), false, true, List.of(), null);
            assertEquals(original.key(), travel.scene.views.current().getFirst().key());
            List<PortalViews.CameraContinuation> inbound = travel.scene.views.takeCameraContinuations();
            assertEquals(4, inbound.size());
            for (int index = 0; index < 4; index++) {
                PortalViews.CameraContinuation first = outbound.get(index);
                PortalViews.CameraContinuation second = inbound.get(3 - index);
                assertEquals(first.next().key(), second.previous().key());
                assertEquals(first.previous().key(), second.next().key());
                assertTrue(PortalViews.sameCamera(second.previous(), second.next()));
            }
        }
    }

    @Test
    public void oppositeServedSideKeepsIndependentCameraBeforeMatchingSideArrives() {
        try (TravelScene travel = new TravelScene()) {
            UUID portal = UUID.randomUUID();
            travel.arrive(List.of(travel.reverse(portal, reverseGeometry(true))));
            assertEquals(2, travel.scene.views.current().size());
            PortalView independent = travel.scene.views.current().getFirst();
            PortalView returning = travel.scene.views.current().getLast();
            assertFalse(PortalViews.sameCamera(returning, independent));
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            travel.arrive(List.of(travel.reverse(portal, reverseGeometry(false))));
            List<PortalViews.CameraContinuation> continuations = travel.scene.views.takeCameraContinuations();
            assertEquals(4, continuations.size());
            for (PortalViews.CameraContinuation continuation : continuations) {
                assertNotEquals(independent.key(), continuation.previous().key());
                assertNotEquals(independent.key(), continuation.next().key());
            }
        }
    }

    @Test
    public void coincidentIndependentReversePortalsCannotClaimTheSameReturnHistory() {
        try (TravelScene travel = new TravelScene()) {
            TravelMessage.TravelBegin first = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            TravelMessage.TravelBegin second = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.arrive(List.of(first, second));
            assertEquals(3, travel.scene.views.current().size());
            assertNotEquals(travel.scene.views.current().getFirst().key(), travel.scene.views.current().get(1).key());
            assertEquals(PortalView.Kind.RETURN, travel.scene.views.current().getLast().kind());
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
        }
    }

    @Test
    public void unconsumedCameraContinuationsExpireAtTheNextFrame() {
        try (TravelScene travel = new TravelScene()) {
            TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
            travel.arrive(List.of(reverse));
            travel.arrive(List.of(reverse));
            assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
        }
    }

    @Test
    public void coincidentSurfaceWithDifferentDestinationTransformOrShapeCannotSuppressReturn() {
        for (int mismatch = 0; mismatch < 4; mismatch++) {
            try (TravelScene travel = new TravelScene()) {
                ApertureDescriptor geometry = reverseGeometry(false);
                if (mismatch == 2) {
                    geometry = geometry.withShape(ShapeDescriptor.parse("circle"));
                }
                if (mismatch == 3) {
                    geometry = reverseGeometry(false, 31L);
                }
                TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), geometry);
                if (mismatch == 0) {
                    when(travel.scene.residents.level(2)).thenReturn(mock(ClientLevel.class));
                }
                if (mismatch == 1) {
                    when(reverse.sourceToDestination()).thenReturn(translation(-190.0D));
                }
                travel.arrive(List.of(reverse));
                assertEquals(2, travel.scene.views.current().size());
                assertEquals(PortalView.Kind.RETURN, travel.scene.views.current().getLast().kind());
                assertTrue(travel.scene.views.takeCameraContinuations().isEmpty());
            }
        }
    }

    private static ApertureDescriptor reverseGeometry(boolean front) {
        return reverseGeometry(front, 63L);
    }

    private static Similarity translation(double z) {
        Frame frame = Frame.canonical(Face.S);
        return Similarity.of(OpticTransform.between(frame, new Vec3d(0, 0, 0), frame, new Vec3d(0, 0, z)), 1.0D);
    }

    private static ApertureDescriptor reverseGeometry(boolean front, long mask) {
        return new ApertureDescriptor(0, 64, 200, Face.S.ordinal(), front, 0, false, 2, 3, new long[]{mask}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
    }

    @Test
    public void futureTravelViewsMatchLiveCameraRootsBeforeAndAfterPrediction() {
        try (Scene scene = new Scene(); MockedStatic<ClientWorldLoader> worlds = mockStatic(ClientWorldLoader.class)) {
            ClientLevel destination = mock(ClientLevel.class);
            when(destination.dimension()).thenReturn(Level.END);
            when(scene.residents.level(1)).thenReturn(destination);
            LevelRenderer renderer = mock(LevelRenderer.class);
            worlds.when(() -> ClientWorldLoader.residentRenderer(scene.home)).thenReturn(renderer);
            TravelMessage.TravelBegin arm = residentArm(UUID.randomUUID(), geometry(0, false));
            PortalView original = scene.arm(arm);
            List<PortalView> future = PortalViews.futureTravelViews(original);
            assertEquals(3, future.size());
            scene.views.frame(scene.home, EYE, List.of(arm), false, true, List.of(),
                new PortalViews.Crossing(arm.token(), destination, original.surface(), arm.sourceToDestination()));
            assertCamera(future.get(1), crossing(scene.views));
            Vec3d destinationEye = original.toDestination().point(EYE);
            scene.views.crossed(arm, scene.home, destination, true);
            scene.views.frame(destination, destinationEye, List.of(arm), false, true, List.of(), null);
            PortalView returning = scene.views.current().getFirst();
            assertCamera(future.get(2), returning);
            scene.views.frame(destination, destinationEye, List.of(arm), false, true, List.of(),
                new PortalViews.Crossing(returning.key(), scene.home, returning.surface(), returning.toDestination()));
            assertCamera(future.getFirst(), crossing(scene.views));
        }
    }

    @Test
    public void futureCamerasKeepCoincidentPortalsSeparateAndIgnoreReplacementTokens() {
        try (Scene scene = new Scene()) {
            ClientLevel destination = mock(ClientLevel.class);
            when(destination.dimension()).thenReturn(Level.END);
            when(scene.residents.level(1)).thenReturn(destination);
            UUID portal = UUID.randomUUID();
            TravelMessage.TravelBegin arm = residentArm(portal, geometry(0, false));
            List<PortalView> first = PortalViews.futureTravelViews(scene.arm(arm));
            List<PortalView> refreshed = PortalViews.futureTravelViews(scene.arm(residentArm(portal, presentation(arm.sourceGeometry()))));
            List<PortalView> coincident = PortalViews.futureTravelViews(scene.arm(residentArm(UUID.randomUUID(), arm.sourceGeometry())));
            for (int index = 0; index < first.size(); index++) {
                assertEquals(first.get(index).key(), refreshed.get(index).key());
                assertNotEquals(first.get(index).key(), coincident.get(index).key());
            }
        }
    }

    private static PortalView crossing(PortalViews views) {
        return views.current().stream().filter(view -> view.kind() == PortalView.Kind.CROSSING).findFirst().orElseThrow();
    }

    private static void assertCamera(PortalView expected, PortalView actual) {
        assertEquals(expected.kind(), actual.kind());
        assertEquals(expected.key(), actual.key());
        assertSame(expected.source(), actual.source());
        assertSame(expected.destination(), actual.destination());
        assertEquals(expected.surface().geometry(), actual.surface().geometry());
        assertEquals(expected.surface().placement(), actual.surface().placement());
        assertEquals(expected.surface().reversed(), actual.surface().reversed());
        assertEquals(expected.toDestination(), actual.toDestination());
    }

    private static TravelMessage.TravelBegin residentArm(UUID portal, ApertureDescriptor geometry) {
        TravelMessage.TravelBegin arm = arm(portal, geometry);
        when(arm.resident()).thenReturn(true);
        when(arm.levelHandle()).thenReturn(1);
        when(arm.sourceToDestination()).thenReturn(Similarity.of(OpticTransform.IDENTITY, 2.0D));
        return arm;
    }

    @Test
    public void mirrorRootsFollowTheirSourceAcrossPhysicalHomeChanges() {
        try (Scene scene = new Scene()) {
            ClientLevel destination = mock(ClientLevel.class);
            when(destination.dimension()).thenReturn(Level.END);
            ClientPortal sourceMirror = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            ClientPortal destinationMirror = new ClientPortal(2, geometry(16, true), 1, 0.0D);
            ClientPortal nestedMirror = new ClientPortal(3, geometry(32, true).withParent(2), 1, 0.0D);
            scene.source(sourceMirror, scene.home);
            scene.source(destinationMirror, destination);
            scene.source(nestedMirror, destination);
            List<ClientPortal> portals = List.of(sourceMirror, destinationMirror, nestedMirror);

            scene.views.frame(scene.home, EYE, List.of(), true, true, portals, null);
            assertEquals(1, scene.views.current().size());
            PortalView original = scene.views.current().getFirst();
            assertSame(sourceMirror.geometry(), original.surface().geometry());
            assertSame(scene.home, original.source());

            scene.views.frame(destination, EYE, List.of(), true, true, portals, null);
            assertEquals(1, scene.views.current().size());
            PortalView arrived = scene.views.current().getFirst();
            assertEquals(PortalView.Kind.MIRROR, arrived.kind());
            assertSame(destinationMirror.geometry(), arrived.surface().geometry());
            assertSame(destination, arrived.source());
            assertSame(destination, arrived.destination());
            assertNotEquals(original.key(), arrived.key());
            assertTrue(scene.views.takeCameraContinuations().isEmpty());

            scene.views.frame(scene.home, EYE, List.of(), true, true, portals, null);
            assertEquals(1, scene.views.current().size());
            PortalView returned = scene.views.current().getFirst();
            assertSame(sourceMirror.geometry(), returned.surface().geometry());
            assertSame(scene.home, returned.source());
            assertEquals(original.key(), returned.key());
            assertNotSame(original, returned);
        }
    }

    @Test
    public void mirrorRootsWithoutSourceIdentityAreExcluded() {
        try (Scene scene = new Scene()) {
            ClientPortal mirror = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            scene.session.portals().put(mirror.portalKey(), mirror);
            scene.views.frame(scene.home, EYE, List.of(), true, true, List.of(mirror), null);
            assertTrue(scene.views.current().isEmpty());
        }
    }

    @Test
    public void mirrorsKeepPhysicalIdentityAcrossRevisionsAndNewStreamSlots() {
        try (Scene scene = new Scene()) {
            ClientPortal portal = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            PortalView original = scene.mirror(portal, true);
            portal.geometry(geometry(0, true), 2);
            assertSame(original, scene.mirror(portal, true));
            scene.views.frame(scene.home, EYE, List.of(), false, true, List.of(), null);
            assertTrue(scene.views.current().isEmpty());
            PortalView reopened = scene.mirror(new ClientPortal(9, geometry(0, true), 7, 0.0D), true);
            assertNotSame(original, reopened);
            assertEquals(original.key(), reopened.key());
            assertEquals(original.key().hashCode(), reopened.key().hashCode());
        }
    }

    @Test
    public void mirrorPresentationAndRecursionChangesRefreshTheViewWithoutChangingItsCameraKey() {
        try (Scene scene = new Scene()) {
            ClientPortal portal = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            PortalView original = scene.mirror(portal, true);
            ApertureDescriptor next = presentation(portal.geometry());
            portal.geometry(next, 2);
            PortalView refreshed = scene.mirror(portal, true);
            assertNotSame(original, refreshed);
            assertEquals(original.key(), refreshed.key());
            assertSame(next, refreshed.surface().geometry());
            assertEquals(7, refreshed.recursion());
            PortalView shallow = scene.mirror(portal, false);
            assertNotSame(refreshed, shallow);
            assertEquals(refreshed.key(), shallow.key());
            assertEquals(1, shallow.recursion());
            assertSame(shallow, scene.mirror(portal, false));
        }
    }

    @Test
    public void differentPhysicalMirrorsAndChangedShapesKeepDistinctKeys() {
        try (Scene scene = new Scene()) {
            ClientPortal first = new ClientPortal(1, geometry(0, true), 1, 0.0D);
            ClientPortal second = new ClientPortal(2, geometry(16, true), 1, 0.0D);
            scene.source(first, scene.home);
            scene.source(second, scene.home);
            scene.views.frame(scene.home, EYE, List.of(), true, true, List.of(first, second), null);
            PortalView original = scene.views.current().getFirst();
            assertNotEquals(original.key(), scene.views.current().get(1).key());
            first.geometry(first.geometry().withShape(ShapeDescriptor.parse("circle")), 2);
            assertNotEquals(original.key(), scene.mirror(first, true).key());
        }
    }

    @Test
    public void mirrorMaskAndPlaneChangesInvalidateCameraIdentity() {
        try (Scene scene = new Scene()) {
            PortalView original = scene.mirror(new ClientPortal(1, geometry(0, true), 1, 0.0D), true);
            ApertureDescriptor masked = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, true, 2, 3,
                new long[]{31L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
            assertNotEquals(original.key(), scene.mirror(new ClientPortal(1, masked, 2, 0.0D), true).key());
            ApertureDescriptor shifted = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, true, 2, 3,
                new long[]{63L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.25D, 0, 0L, List.of());
            assertNotEquals(original.key(), scene.mirror(new ClientPortal(1, shifted, 3, 0.0D), true).key());
        }
    }

    @Test
    public void armsKeepPhysicalIdentityAcrossNewTokensAndPresentationChanges() {
        try (Scene scene = new Scene()) {
            UUID portal = UUID.randomUUID();
            TravelMessage.TravelBegin first = arm(portal, geometry(0, false));
            PortalView original = scene.arm(first);
            TravelMessage.TravelBegin second = arm(portal, presentation(first.sourceGeometry()));
            assertNotEquals(first.token(), second.token());
            PortalView refreshed = scene.arm(second);
            assertNotSame(original, refreshed);
            assertEquals(original.key(), refreshed.key());
            assertEquals(original.key().hashCode(), refreshed.key().hashCode());
            assertSame(second.sourceGeometry(), refreshed.surface().geometry());
            assertEquals(7, refreshed.recursion());
        }
    }

    @Test
    public void armPortalGeometryAndTransformChangesInvalidateCameraIdentity() {
        try (Scene scene = new Scene()) {
            UUID portal = UUID.randomUUID();
            PortalView original = scene.arm(arm(portal, geometry(0, false)));
            assertNotEquals(original.key(), scene.arm(arm(UUID.randomUUID(), geometry(0, false))).key());
            assertNotEquals(original.key(), scene.arm(arm(portal, geometry(16, false))).key());
            TravelMessage.TravelBegin changed = arm(portal, geometry(0, false));
            when(changed.sourceToDestination()).thenReturn(Similarity.of(OpticTransform.IDENTITY, 2.0D));
            assertNotEquals(original.key(), scene.arm(changed).key());
        }
    }

    @Test
    public void crossingWithTheSameTokenRefreshesItsTransformAndSurface() {
        try (Scene scene = new Scene()) {
            ClientLevel source = mock(ClientLevel.class);
            ApertureDescriptor geometry = geometry(0, false);
            scene.views.frame(scene.home, EYE, List.of(), false, true, List.of(),
                new PortalViews.Crossing("crossing", source, PortalSurface.of(geometry), Similarity.IDENTITY));
            PortalView original = scene.views.current().getFirst();
            Similarity toward = Similarity.of(OpticTransform.IDENTITY, 2.0D);
            scene.views.frame(scene.home, EYE, List.of(), false, true, List.of(),
                new PortalViews.Crossing("crossing", source, PortalSurface.of(geometry), toward));
            PortalView refreshed = scene.views.current().getFirst();
            assertNotSame(original, refreshed);
            assertEquals(original.key(), refreshed.key());
            assertEquals(toward, refreshed.surface().placement());
            assertEquals(toward.inverse(), refreshed.toDestination());
            ApertureDescriptor next = geometry.withShape(ShapeDescriptor.parse("circle"));
            scene.views.frame(scene.home, EYE, List.of(), false, true, List.of(),
                new PortalViews.Crossing("crossing", source, PortalSurface.of(next), toward));
            assertNotSame(refreshed, scene.views.current().getFirst());
            assertSame(next, scene.views.current().getFirst().surface().geometry());
        }
    }

    private static TravelMessage.TravelBegin arm(UUID portal, ApertureDescriptor geometry) {
        TravelMessage.TravelBegin arm = mock(TravelMessage.TravelBegin.class);
        when(arm.token()).thenReturn(UUID.randomUUID());
        when(arm.sourcePortal()).thenReturn(portal);
        when(arm.sourceWorld()).thenReturn("minecraft:overworld");
        when(arm.sourceGeometry()).thenReturn(geometry);
        when(arm.sourceToDestination()).thenReturn(Similarity.IDENTITY);
        return arm;
    }

    private static ApertureDescriptor geometry(int x, boolean mirror) {
        return new ApertureDescriptor(x, 64, 0, Face.S.ordinal(), true, 0, mirror, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
    }

    private static ApertureDescriptor presentation(ApertureDescriptor geometry) {
        return new ApertureDescriptor(geometry.originX(), geometry.originY(), geometry.originZ(), geometry.facing(), geometry.frontSide(),
            geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(), geometry.apertureHeight(), geometry.apertureMask(),
            geometry.shape(), 0.25F, 0.5F, 0.75F, 32, 7, 2, 8, 1, 1, 7, 6, geometry.planeOffset(), 0, 99L,
            List.of(geometry.withDepth(8)));
    }

    private static ApertureDescriptor identity(ApertureDescriptor geometry, long identity) {
        return new ApertureDescriptor(geometry.originX(), geometry.originY(), geometry.originZ(), geometry.facing(), geometry.frontSide(),
            geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(), geometry.apertureHeight(), geometry.apertureMask(),
            geometry.shape(), 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, geometry.planeOffset(), geometry.parentPortalKey(), identity, List.of());
    }

    private static final class CrossingMirrors implements AutoCloseable {
        private final TravelScene travel = new TravelScene();
        private final ClientPortal firstRoot = new ClientPortal(10, geometry(16, true), 1, 0.0D);
        private final ClientPortal secondRoot = new ClientPortal(11,
            new ApertureDescriptor(48, 64, 8, Face.S.ordinal(), false, 0, true, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
                0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of()), 1, 0.0D);
        private final TravelMessage.TravelBegin reverse = travel.reverse(UUID.randomUUID(), reverseGeometry(false));
        private final ClientPortal reverseRoot = new ClientPortal(9, reverse.sourceGeometry(), 1, 0.0D);
        private final ClientPortal firstChild = new ClientPortal(20, firstRoot.geometry().withParent(9), 1, 0.0D);
        private final ClientPortal secondChild = new ClientPortal(22, secondRoot.geometry().withParent(9), 1, 0.0D);
        private final ClientPortal grandchild = new ClientPortal(21, secondRoot.geometry().withParent(20), 1, 0.0D);
        private PortalView first;
        private PortalView second;

        private CrossingMirrors() throws Exception {
            travel.scene.source(firstRoot, travel.scene.home);
            travel.scene.source(secondRoot, travel.scene.home);
            travel.scene.source(reverseRoot, travel.destination);
            for (ClientPortal portal : List.of(firstChild, secondChild, grandchild)) {
                travel.scene.session.portals().put(portal.portalKey(), portal);
            }
            environment(travel.scene, firstChild, "minecraft:overworld", PortalSurface.mirrorTransform(firstChild.geometry()));
            environment(travel.scene, secondChild, "minecraft:overworld", PortalSurface.mirrorTransform(secondChild.geometry()));
            environment(travel.scene, grandchild, "minecraft:overworld", PortalSurface.mirrorTransform(grandchild.geometry()));
            depart();
        }

        private void depart() {
            travel.scene.views.frame(travel.scene.home, EYE, List.of(travel.forward), true, true, List.of(firstRoot, secondRoot), null);
            first = travel.scene.views.current().get(1);
            second = travel.scene.views.current().get(2);
        }

        private void cross() {
            travel.scene.views.crossed(travel.forward, travel.scene.home, travel.destination, true);
        }

        private void frame(List<TravelMessage.TravelBegin> arms, List<ClientPortal> portals) {
            travel.scene.views.frame(travel.destination, travel.toward.point(EYE), arms, true, true, portals, null);
        }

        private List<ClientPortal> portals() {
            return List.of(reverseRoot, firstChild, secondChild, grandchild);
        }

        private void discover() {
            frame(List.of(reverse), portals());
        }

        @Override
        public void close() {
            travel.close();
        }
    }

    private static final class Scene implements AutoCloseable {
        private final ClientLevel home = mock(ClientLevel.class);
        private final ResidentLevels residents = mock(ResidentLevels.class);
        private final PortalViews views = new PortalViews(residents);
        private final MockedStatic<PortalBackends> backends = mockStatic(PortalBackends.class);
        private final WormholesClient client = mock(WormholesClient.class);
        private final MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
        private final ClientViewSession session;

        private Scene() {
            when(home.dimension()).thenReturn(Level.OVERWORLD);
            backends.when(PortalBackends::pipeline).thenReturn(PipelineBackend.NONE);
            WormholesClientConfig config = new WormholesClientConfig();
            config.normalize();
            session = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
            session.accept(new ViewStreamMessage.Accept(1, ViewStreamCapability.CLIENT_MIRROR.mask() | ClientViewExtensions.LOCAL_DESTINATION, 20,
                ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
            clients.when(WormholesClient::instance).thenReturn(client);
            when(client.session()).thenReturn(session);
        }

        private void source(ClientPortal portal, ClientLevel source) {
            session.portals().put(portal.portalKey(), portal);
            session.bindSourceWorld(source.dimension().identifier().toString());
        }

        private PortalView mirror(ClientPortal portal, boolean recursion) {
            source(portal, home);
            views.frame(home, EYE, List.of(), true, recursion, List.of(portal), null);
            return views.current().getFirst();
        }

        private PortalView arm(TravelMessage.TravelBegin arm) {
            views.frame(home, EYE, List.of(arm), false, true, List.of(), null);
            return views.current().getFirst();
        }

        @Override
        public void close() {
            views.clear();
            backends.close();
            clients.close();
        }
    }

    private static final class TravelScene implements AutoCloseable {
        private final Scene scene = new Scene();
        private final MockedStatic<ClientWorldLoader> worlds = mockStatic(ClientWorldLoader.class);
        private final ClientLevel destination = mock(ClientLevel.class);
        private final TravelMessage.TravelBegin forward = residentArm(UUID.randomUUID(), geometry(0, false));
        private final Similarity toward = translation(200.0D);
        private boolean arrived;

        private TravelScene() {
            when(destination.dimension()).thenReturn(Level.END);
            when(scene.residents.level(1)).thenReturn(destination);
            when(scene.residents.level(2)).thenReturn(scene.home);
            LevelRenderer renderer = mock(LevelRenderer.class);
            worlds.when(() -> ClientWorldLoader.residentRenderer(scene.home)).thenReturn(renderer);
            worlds.when(() -> ClientWorldLoader.residentRenderer(destination)).thenReturn(renderer);
            when(forward.sourceToDestination()).thenReturn(toward);
            scene.arm(forward);
        }

        private TravelMessage.TravelBegin reverse(UUID portal, ApertureDescriptor geometry) {
            TravelMessage.TravelBegin reverse = residentArm(portal, geometry);
            when(reverse.sourceWorld()).thenReturn("minecraft:the_end");
            when(reverse.levelHandle()).thenReturn(2);
            when(reverse.sourceToDestination()).thenReturn(translation(-200.0D));
            return reverse;
        }

        private void arrive(List<TravelMessage.TravelBegin> arms) {
            if (!arrived) {
                scene.views.crossed(forward, scene.home, destination, true);
                arrived = true;
            }
            scene.views.frame(destination, toward.point(EYE), arms, false, true, List.of(), null);
        }

        @Override
        public void close() {
            scene.close();
            worlds.close();
        }
    }
}
