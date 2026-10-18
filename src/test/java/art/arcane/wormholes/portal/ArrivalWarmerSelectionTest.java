package art.arcane.wormholes.portal;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.arrival.BukkitArrivalPreparation;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendProvider;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class ArrivalWarmerSelectionTest {
    @Test
    void fartherSiblingCannotReplaceTheNearestApproachButACloserCandidateCan() {
        World world = mock(World.class);
        Player player = player();
        ILocalPortal near = portal(world, 8, 2);
        ILocalPortal far = portal(world, 2, 1);
        ArrivalWarmer warmer = new ArrivalWarmer();
        assertTrue(warmer.selectApproach(near, player, new Location(world, 8.5, 191, 10), 0));
        for (int tick = 1; tick < 30; tick++) {
            assertTrue(warmer.selectApproach(near, player, new Location(world, 8.5, 191, 10), tick * 50L));
            assertFalse(warmer.selectApproach(far, player, new Location(world, 8.5, 191, 10), tick * 50L));
        }
        assertTrue(warmer.selectApproach(far, player, new Location(world, 8.5, 191, 4), 1500));
        assertFalse(warmer.selectApproach(near, player, new Location(world, 8.5, 191, 4), 1500));
    }

    @Test
    void equalDistanceSelectionIsStableRegardlessOfIterationOrder() {
        World world = mock(World.class);
        Player player = player();
        ILocalPortal first = portal(world, 2, 1);
        ILocalPortal second = portal(world, 8, 2);
        Location middle = new Location(world, 8.5, 191, 5.5);
        ArrivalWarmer warmer = new ArrivalWarmer();
        assertTrue(warmer.selectApproach(second, player, middle, 0));
        assertTrue(warmer.selectApproach(first, player, middle, 0));
        assertFalse(warmer.selectApproach(second, player, middle, 50));
        assertTrue(warmer.selectApproach(first, player, middle, 50));
        assertFalse(warmer.selectApproach(second, player, middle, 100));
    }

    @Test
    void expiredRetargetedClosedOrDifferentWorldSelectionsDoNotBlockAnotherPortal() {
        for (int reason = 0; reason < 5; reason++) {
            World world = mock(World.class);
            Player player = player();
            ILocalPortal near = portal(world, 8, 2);
            ILocalPortal far = portal(world, 2, 1);
            Location location = new Location(world, 8.5, 191, 10);
            ArrivalWarmer warmer = new ArrivalWarmer();
            assertTrue(warmer.selectApproach(near, player, location, 0));
            long next = 50;
            if (reason == 0) {
                next = 1000;
            } else if (reason == 1) {
                when(near.getTunnel().getTunnelType()).thenReturn(TunnelType.UNIVERSAL);
            } else if (reason == 2) {
                when(near.isOpen()).thenReturn(false);
            } else if (reason == 3) {
                World other = mock(World.class);
                far = portal(other, 2, 1);
                location.setWorld(other);
            } else {
                when(near.getTunnel().getDestination()).thenReturn(null);
            }
            assertTrue(warmer.selectApproach(far, player, location, next));
        }
    }

    @Test
    void reconnectExpiryQuitAndShutdownReleaseSelection() throws Exception {
        World world = mock(World.class);
        Player player = player();
        ILocalPortal near = portal(world, 8, 2);
        ILocalPortal far = portal(world, 2, 1);
        Location location = new Location(world, 8.5, 191, 10);
        ArrivalWarmer warmer = new ArrivalWarmer();
        assertTrue(warmer.selectApproach(near, player, location, 0));
        Player replacement = player();
        UUID playerId = player.getUniqueId();
        when(replacement.getUniqueId()).thenReturn(playerId);
        assertTrue(warmer.selectApproach(far, replacement, location, 50));
        warmer.sweep();
        assertEquals(0, selectionCount(warmer));
        assertTrue(warmer.selectApproach(near, player, location, System.currentTimeMillis()));
        warmer.forget(player.getUniqueId());
        assertEquals(0, selectionCount(warmer));
        assertTrue(warmer.selectApproach(near, player, location, System.currentTimeMillis()));
        warmer.shutdown();
        assertEquals(0, selectionCount(warmer));
    }

    @Test
    void selectionRunsOnThePlayerOwnerAndOrdinaryWarmingStillIncludesSiblings() {
        World world = mock(World.class);
        Player player = player();
        Location departure = new Location(world, 8.5, 191, 10);
        when(player.getLocation()).thenAnswer(call -> departure.clone());
        when(player.getVelocity()).thenReturn(new Vector());
        ILocalPortal near = portal(world, 8, 2);
        ILocalPortal far = portal(world, 2, 1);
        Location nearTarget = new Location(world, 104.5, 191, 8.5);
        Location farTarget = new Location(world, 200.5, 191, 8.5);
        ILocalPortal nearDestination = (ILocalPortal) near.getTunnel().getDestination();
        ILocalPortal farDestination = (ILocalPortal) far.getTunnel().getDestination();
        when(nearDestination.computeExitTarget(any())).thenReturn(nearTarget);
        when(nearDestination.getCenter()).thenReturn(nearTarget);
        when(farDestination.computeExitTarget(any())).thenReturn(farTarget);
        when(farDestination.getCenter()).thenReturn(farTarget);
        ArrivalWarmer warmer = spy(new ArrivalWarmer());
        doReturn(2).when(warmer).viewRadius(player);
        doNothing().when(warmer).warmAround(any(World.class), anyInt(), anyInt(), anyInt(), anyLong());
        Wormholes previous = Wormholes.instance;
        boolean previousEnabled = Settings.ARRIVAL_PREWARM_ON_INTEREST;
        Wormholes plugin = mock(Wormholes.class);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<BukkitArrivalPreparation> preparations = mockStatic(BukkitArrivalPreparation.class);
             MockedStatic<BukkitChunkPreSendProvider> chunks = mockStatic(BukkitChunkPreSendProvider.class)) {
            Wormholes.instance = plugin;
            Settings.ARRIVAL_PREWARM_ON_INTEREST = true;
            AtomicReference<Runnable> scheduled = new AtomicReference<>();
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class), eq(0L)))
                .thenAnswer(call -> { scheduled.set(call.getArgument(2)); return true; });
            warmer.warmImminent(near, player);
            verify(player, never()).getLocation();
            preparations.verify(() -> BukkitArrivalPreparation.prepare(any(Player.class), any(Location.class)), never());
            assertNotNull(scheduled.get());
            scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(player)).thenReturn(true);
            scheduled.get().run();
            warmer.warmImminent(far, player);
            preparations.verify(() -> BukkitArrivalPreparation.prepare(player, nearTarget));
            preparations.verify(() -> BukkitArrivalPreparation.prepare(player, farTarget), never());
            chunks.verify(() -> BukkitChunkPreSendProvider.preSend(player, nearTarget));
            chunks.verify(() -> BukkitChunkPreSendProvider.preSend(player, farTarget), never());
            verify(warmer).warmAround(world, 104, 8, 2, Settings.ARRIVAL_WARM_HOLD_MILLIS);
            verify(warmer).warmAround(world, 200, 8, 2, Settings.ARRIVAL_WARM_HOLD_MILLIS);
        } finally {
            Wormholes.instance = previous;
            Settings.ARRIVAL_PREWARM_ON_INTEREST = previousEnabled;
        }
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static ILocalPortal portal(World world, int z, long id) {
        ILocalPortal portal = mock(ILocalPortal.class);
        PortalStructure structure = mock(PortalStructure.class);
        ITunnel tunnel = mock(ITunnel.class);
        ILocalPortal destination = mock(ILocalPortal.class);
        when(portal.getId()).thenReturn(new UUID(0, id));
        when(portal.getStructure()).thenReturn(structure);
        when(portal.getFrame()).thenReturn(Frame.canonical(Face.S));
        when(portal.getOrigin()).thenReturn(new Vec3d(8.5, 194, z + 0.5));
        when(portal.isOpen()).thenReturn(true);
        when(portal.hasTunnel()).thenReturn(true);
        when(portal.getTunnel()).thenReturn(tunnel);
        when(tunnel.getDestination()).thenReturn(destination);
        when(destination.getId()).thenReturn(new UUID(1, id));
        when(structure.getWorld()).thenReturn(world);
        when(structure.getArea()).thenReturn(new Box(5, 12, 190, 198, z, z + 1));
        when(structure.getCaptureZone()).thenReturn(new Box(-3, 20, 182, 206, z - 8, z + 9));
        return portal;
    }

    private static int selectionCount(ArrivalWarmer warmer) throws Exception {
        Field field = ArrivalWarmer.class.getDeclaredField("approaches");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(warmer)).size();
    }
}
