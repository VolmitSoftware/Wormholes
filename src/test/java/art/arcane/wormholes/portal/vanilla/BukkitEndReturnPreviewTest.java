package art.arcane.wormholes.portal.vanilla;

import art.arcane.volmlib.nativelib.player.RespawnPoint;
import art.arcane.volmlib.nativelib.player.RespawnPolicy;
import art.arcane.wormholes.render.PortalProjector;
import art.arcane.optics.math.Face;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BukkitEndReturnPreviewTest {
    @Test
    void observersResolveIndependentlyAndUnchangedRefreshKeepsTheMeshRevision() {
        Host host = new Host();
        BukkitEndReturnPreview preview = new BukkitEndReturnPreview(host);
        Player first = host.player(10);
        Player second = host.player(20);
        assertNull(preview.target(first, 0));
        assertNull(preview.target(second, 0));
        host.complete(0, host.snapshots.get(first.getUniqueId()).personal().location(), false);
        host.complete(1, host.snapshots.get(second.getUniqueId()).personal().location(), false);
        PortalProjector.RtpProjectionTarget firstTarget = preview.target(first, 1);
        PortalProjector.RtpProjectionTarget secondTarget = preview.target(second, 1);
        assertEquals(10, firstTarget.originX());
        assertEquals(20, secondTarget.originX());
        assertNotEquals(firstTarget.routeRevision(), secondTarget.routeRevision());
        assertEquals(Face.U, firstTarget.frame().getNormal());
        assertSame(firstTarget, preview.target(first, 39));
        assertEquals(2, host.pending.size());
        assertSame(firstTarget, preview.target(first, 40));
        host.complete(2, host.snapshots.get(first.getUniqueId()).personal().location(), false);
        assertSame(firstTarget, preview.target(first, 41));
    }

    @Test
    void changedRespawnDiscardsAnEarlierValidationAndInvalidRespawnCanResolveToSharedSpawn() {
        Host host = new Host();
        BukkitEndReturnPreview preview = new BukkitEndReturnPreview(host);
        Player observer = host.player(10);
        RespawnPolicy initial = host.snapshots.get(observer.getUniqueId());
        preview.target(observer, 0);
        host.snapshots.put(observer.getUniqueId(), new RespawnPolicy(new RespawnPoint(host.world, 100, 64, 100, 0, 0), false, initial.shared()));
        assertNull(preview.target(observer, 1));
        host.complete(0, initial.personal().location(), false);
        assertNull(preview.target(observer, 2));
        host.complete(1, initial.shared().location(), true);
        PortalProjector.RtpProjectionTarget fallback = preview.target(observer, 3);
        assertEquals(initial.shared().location().getX(), fallback.originX());
        assertEquals(initial.shared().location().getY(), fallback.originY());
        assertEquals(initial.shared().location().getZ(), fallback.originZ());
        assertSame(fallback, preview.target(observer, 41));
        assertEquals(initial.shared().location(), host.fallbackInputs.get(2));
        host.complete(2, host.fallbackInputs.get(2), true);
        assertSame(fallback, preview.target(observer, 42));
    }

    @Test
    void forgottenObserversAndClosedServicesIgnorePendingCompletions() {
        Host host = new Host();
        BukkitEndReturnPreview preview = new BukkitEndReturnPreview(host);
        Player observer = host.player(10);
        preview.target(observer, 0);
        preview.forget(observer.getUniqueId());
        host.complete(0, host.snapshots.get(observer.getUniqueId()).personal().location(), false);
        assertNull(preview.target(observer, 1));
        preview.close();
        host.complete(1, host.snapshots.get(observer.getUniqueId()).personal().location(), false);
        assertNull(preview.target(observer, 2));
        assertEquals(2, host.pending.size());
    }

    @Test
    void failedValidationRetriesAtTheRefreshBoundaryWithoutUsingAStaleTarget() {
        Host host = new Host();
        BukkitEndReturnPreview preview = new BukkitEndReturnPreview(host);
        Player observer = host.player(10);
        preview.target(observer, 0);
        IllegalStateException failure = new IllegalStateException("unavailable destination");
        host.pending.get(0).completeExceptionally(failure);
        assertNull(preview.target(observer, 39));
        assertEquals(List.of(failure), host.failures);
        assertEquals(1, host.pending.size());
        assertNull(preview.target(observer, 40));
        assertEquals(2, host.pending.size());
        host.complete(1, host.snapshots.get(observer.getUniqueId()).personal().location(), false);
        assertEquals(10, preview.target(observer, 41).originX());
    }

    @Test
    void forcedSpawnPolicyIsPreservedWhenTheDestinationIsValidated() {
        Host host = new Host();
        Player observer = host.player(10);
        RespawnPolicy original = host.snapshots.get(observer.getUniqueId());
        RespawnPolicy forced = new RespawnPolicy(original.personal(), true, original.shared());
        host.snapshots.put(observer.getUniqueId(), forced);
        BukkitEndReturnPreview preview = new BukkitEndReturnPreview(host);
        assertNull(preview.target(observer, 0));
        assertSame(forced, host.validationInputs.get(0));
    }

    private static final class Host implements BukkitEndReturnPreview.Host {
        private final World world = mock(World.class);
        private final Map<UUID, RespawnPolicy> snapshots = new HashMap<>();
        private final List<CompletableFuture<BukkitEndReturnPreview.ResolvedSpawn>> pending = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
        private final List<Location> fallbackInputs = new ArrayList<>();
        private final List<RespawnPolicy> validationInputs = new ArrayList<>();

        private void complete(int index, Location location, boolean fallback) {
            pending.get(index).complete(new BukkitEndReturnPreview.ResolvedSpawn(location, fallback));
        }

        private Player player(int x) {
            Player player = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            snapshots.put(id, new RespawnPolicy(new RespawnPoint(world, x, 64, 20, 0, 0), false,
                new RespawnPoint(world, 200, 70, 300, 0, 0)));
            return player;
        }

        @Override
        public RespawnPolicy snapshot(Player observer) {
            return snapshots.get(observer.getUniqueId());
        }

        @Override
        public CompletableFuture<BukkitEndReturnPreview.ResolvedSpawn> resolve(RespawnPolicy snapshot, Location fallback) {
            CompletableFuture<BukkitEndReturnPreview.ResolvedSpawn> result = new CompletableFuture<>();
            pending.add(result);
            fallbackInputs.add(fallback);
            validationInputs.add(snapshot);
            return result;
        }

        @Override
        public void failed(UUID observerId, Throwable failure) {
            failures.add(failure);
        }
    }
}
