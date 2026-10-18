package art.arcane.wormholes.render.clientview;

import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

final class BukkitClientViewMeshQueueTest {
    private static final BlockBox CLIP = new BlockBox(0, 64, 16, 16, 16, 16);

    @Test
    void loadedSectionsWaitingForCaptureBudgetKeepTheirQueueStatusUntilFailure() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            when(fixture.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            ClientViewObserver observer = observer(fixture, List.of(fixture.portal), 1L);
            BukkitClientViewPortalAccess access = access(fixture);
            UUID portalId = fixture.portal.getId();
            assertFalse(access.meshSectionQueued(observer, portalId, CLIP));
            assertNull(access.meshSection(observer, portalId, CLIP, 128));
            PlateCaptureJob<?, ?, ?> capture = capture(fixture, 0);
            assertTrue(access.meshSectionQueued(observer, portalId, CLIP));
            assertFalse(access.meshSectionQueued(observer, portalId, new BlockBox(16, 64, 16, 16, 16, 16)));
            for (int tick = 0; tick < 1201; tick++) {
                assertEquals(0, capture.capture(0));
            }
            assertEquals(PlateCaptureJob.Phase.CAPTURING, capture.phase());
            assertTrue(access.meshSectionQueued(observer, portalId, CLIP));
            capture.abort();
            assertFalse(access.meshSectionQueued(observer, portalId, CLIP));
            fixture.plates.buildFailed(fixture.jobs.getFirst());
            assertFalse(access.meshSectionQueued(observer, portalId, CLIP));
        }
    }

    @Test
    void nestedSectionsUseTheReflectedSideAndDoNotBorrowTheRootCaptureStatus() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            ILocalPortal child = fixture.linkedPortal(2);
            ClientViewObserver observer = observer(fixture, List.of(fixture.portal, child), 1L);
            BukkitClientViewPortalAccess access = access(fixture);
            UUID parentId = fixture.portal.getId();
            UUID childId = child.getId();
            access.prepareNested(observer, parentId, null, parentId);
            assertNull(access.meshSection(observer, childId, CLIP, 128));
            assertTrue(access.meshSectionQueued(observer, childId, CLIP));
            assertFalse(access.nestedMeshSectionQueued(observer, parentId, childId, CLIP));
            assertNull(access.nestedMeshSection(observer, parentId, childId, CLIP, 128));
            assertTrue(access.nestedMeshSectionQueued(observer, parentId, childId, CLIP));
            assertEquals(2, fixture.jobs.size());
            assertNotEquals(fixture.jobs.get(0).key().frontSide(), fixture.jobs.get(1).key().frontSide());
            capture(fixture, 1).abort();
            assertFalse(access.nestedMeshSectionQueued(observer, parentId, childId, CLIP));
            assertTrue(access.meshSectionQueued(observer, childId, CLIP));
            observer.releaseNested(parentId);
            assertFalse(access.nestedMeshSectionQueued(observer, parentId, childId, CLIP));
        }
    }

    @Test
    void changingMirrorRotationDoesNotExtendTheNewSectionsDeadlineWithAnOldCapture() {
        try (ClientViewFixture fixture = new ClientViewFixture(ClientViewFixture.options(true, false, 100), ConnectionState.PLAY)) {
            ClientViewObserver observer = observer(fixture, List.of(fixture.portal), 1L);
            BukkitClientViewPortalAccess access = access(fixture);
            UUID portalId = fixture.portal.getId();
            assertNull(access.meshSection(observer, portalId, CLIP, 128));
            assertTrue(access.meshSectionQueued(observer, portalId, CLIP));
            when(fixture.portal.getMirrorRotation()).thenReturn(QuarterTurn.DEGREES_90);
            observer.beginFrame(fixture.player, fixture.eye, List.of(fixture.portal), List.of(fixture.portal), Map.of(), 2L);
            assertFalse(access.meshSectionQueued(observer, portalId, CLIP));
            assertNull(access.meshSection(observer, portalId, CLIP, 128));
            assertTrue(access.meshSectionQueued(observer, portalId, CLIP));
            assertEquals(2, fixture.jobs.size());
            assertNotEquals(fixture.jobs.get(0).key(), fixture.jobs.get(1).key());
            fixture.plates.invalidatePortal(portalId);
            assertFalse(access.meshSectionQueued(observer, portalId, CLIP));
        }
    }

    private static PlateCaptureJob<?, ?, ?> capture(ClientViewFixture fixture, int index) {
        ViewPlateBuilder.Job<BlockData, World> job = fixture.jobs.get(index);
        return assertInstanceOf(PlateCaptureJob.class, job);
    }

    private static ClientViewObserver observer(ClientViewFixture fixture, List<ILocalPortal> portals, long tick) {
        ClientViewObserver observer = new ClientViewObserver(fixture.playerId, fixture.user);
        observer.meshDepth(128);
        observer.beginFrame(fixture.player, fixture.eye, portals, portals, Map.of(), tick);
        return observer;
    }

    private static BukkitClientViewPortalAccess access(ClientViewFixture fixture) {
        ProjectionWorldViewProvider views = new ProjectionWorldViewProvider() {
            @Override
            public ProjectionWorldView view(World world) {
                return fixture.view;
            }

            @Override
            public boolean usesRegionSnapshots() {
                return false;
            }
        };
        return new BukkitClientViewPortalAccess(views, fixture.plates, ignored -> null, (player, portal) -> {}, () -> 1L);
    }
}
