package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.BukkitEntityRegistryHost;
import art.arcane.optics.volume.GazeScheduler;
import art.arcane.wormholes.platform.QueuedOpticsScheduler;

final class ProjectionInterestSetTest {
    private static final ILocalPortal NEAREST = portal("nearest");
    private static final ILocalPortal OVERLAPPING = portal("overlapping");
    private static final ILocalPortal FARTHER = portal("farther");
    private static final List<GazeScheduler.Candidate<ILocalPortal>> INTERESTED = List.of(
        candidate(NEAREST), candidate(OVERLAPPING), candidate(FARTHER));

    @Test
    void scarcePerObserverBudgetRotatesAcrossEquallyVisiblePortals() {
        ProjectionInterestSet set = newSet();
        UUID observer = UUID.randomUUID();

        assertSame(NEAREST, only(set.scheduleBlocks(observer, eye(1L), INTERESTED, 1, 1L)));
        assertSame(OVERLAPPING, only(set.scheduleBlocks(observer, eye(2L), INTERESTED, 1, 2L)));
        assertSame(FARTHER, only(set.scheduleBlocks(observer, eye(3L), INTERESTED, 1, 3L)));
        assertSame(NEAREST, only(set.scheduleBlocks(observer, eye(4L), INTERESTED, 1, 4L)));
    }

    @Test
    void scheduleStateIsTrackedPerObserver() {
        ProjectionInterestSet set = newSet();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertSame(NEAREST, only(set.scheduleBlocks(first, eye(1L), INTERESTED, 1, 1L)));
        assertSame(NEAREST, only(set.scheduleBlocks(second, eye(1L), INTERESTED, 1, 1L)));
        assertSame(OVERLAPPING, only(set.scheduleBlocks(first, eye(2L), INTERESTED, 1, 2L)));
        assertSame(OVERLAPPING, only(set.scheduleBlocks(second, eye(2L), INTERESTED, 1, 2L)));
    }

    @Test
    void forgettingAnObserverResetsItsScheduleHistory() {
        ProjectionInterestSet set = newSet();
        UUID observer = UUID.randomUUID();

        assertSame(NEAREST, only(set.scheduleBlocks(observer, eye(1L), INTERESTED, 1, 1L)));
        set.forgetObserver(observer);

        assertSame(NEAREST, only(set.scheduleBlocks(observer, eye(2L), INTERESTED, 1, 2L)));
    }

    @Test
    void wideBudgetServesTheLeastRecentlyRefreshedPortalNext() {
        ProjectionInterestSet set = newSet();
        UUID observer = UUID.randomUUID();

        List<ILocalPortal> first = set.scheduleBlocks(observer, eye(1L), INTERESTED, 2, 1L);
        assertEquals(2, first.size());
        assertSame(NEAREST, first.get(0));
        assertSame(OVERLAPPING, first.get(1));

        assertSame(FARTHER, only(set.scheduleBlocks(observer, eye(2L), INTERESTED, 1, 2L)));
    }

    @Test
    void zeroBudgetSchedulesNothing() {
        ProjectionInterestSet set = newSet();

        assertTrue(set.scheduleBlocks(UUID.randomUUID(), eye(1L), INTERESTED, 0, 1L).isEmpty());
    }

    @Test
    void freshSetTracksNoObservers() {
        ProjectionInterestSet set = newSet();

        assertTrue(set.isEmpty());
        assertTrue(set.observerIds().isEmpty());
        assertEquals(0, set.countSpoofedEntities());
    }

    private static ProjectionInterestSet newSet() {
        QueuedOpticsScheduler scheduler = new QueuedOpticsScheduler();
        return new ProjectionInterestSet(null, BukkitEntityRegistryHost.occlusion(BukkitEntityRegistryHost.PLUGIN_VISIBILITY, scheduler), null, null,
            () -> true, scheduler);
    }

    private static GazeScheduler.Eye eye(long tick) {
        return new GazeScheduler.Eye((tick & 1L) * 0.3D, 65.0D, 0.0D, 0.0F, 0.0F);
    }

    private static GazeScheduler.Candidate<ILocalPortal> candidate(ILocalPortal portal) {
        return new GazeScheduler.Candidate<ILocalPortal>(portal, UUID.nameUUIDFromBytes(portal.getName().getBytes()),
            -1.0D, 64.0D, 5.0D, 1.0D, 66.0D, 7.0D, false, false, false);
    }

    private static ILocalPortal only(List<ILocalPortal> slice) {
        assertEquals(1, slice.size());
        return slice.get(0);
    }

    private static ILocalPortal portal(String name) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "getName" -> name;
            case "equals" -> Boolean.valueOf(proxy == args[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> name;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (ILocalPortal) Proxy.newProxyInstance(ProjectionInterestSetTest.class.getClassLoader(),
            new Class<?>[] { ILocalPortal.class }, handler);
    }
}
