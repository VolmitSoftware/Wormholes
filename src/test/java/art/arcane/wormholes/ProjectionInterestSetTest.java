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
import art.arcane.wormholes.render.EntityRenderLocalOcclusionArbiter;

final class ProjectionInterestSetTest {
    private static final ILocalPortal NEAREST = portal("nearest");
    private static final ILocalPortal OVERLAPPING = portal("overlapping");
    private static final ILocalPortal FARTHER = portal("farther");
    private static final List<ILocalPortal> INTERESTED = List.of(NEAREST, OVERLAPPING, FARTHER);

    @Test
    void scarcePerObserverBudgetRotatesAcrossThatObserversPortals() {
        ProjectionInterestSet set = newSet();
        UUID observer = UUID.randomUUID();

        assertSame(NEAREST, only(set.nextSlice(observer, INTERESTED, 1)));
        assertSame(OVERLAPPING, only(set.nextSlice(observer, INTERESTED, 1)));
        assertSame(FARTHER, only(set.nextSlice(observer, INTERESTED, 1)));
        assertSame(NEAREST, only(set.nextSlice(observer, INTERESTED, 1)));
    }

    @Test
    void cursorsAreTrackedPerObserver() {
        ProjectionInterestSet set = newSet();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertSame(NEAREST, only(set.nextSlice(first, INTERESTED, 1)));
        assertSame(NEAREST, only(set.nextSlice(second, INTERESTED, 1)));
        assertSame(OVERLAPPING, only(set.nextSlice(first, INTERESTED, 1)));
        assertSame(OVERLAPPING, only(set.nextSlice(second, INTERESTED, 1)));
    }

    @Test
    void forgettingAnObserverResetsItsCursor() {
        ProjectionInterestSet set = newSet();
        UUID observer = UUID.randomUUID();

        assertSame(NEAREST, only(set.nextSlice(observer, INTERESTED, 1)));
        set.forgetObserver(observer);

        assertSame(NEAREST, only(set.nextSlice(observer, INTERESTED, 1)));
    }

    @Test
    void wideBudgetAdvancesTheCursorByTheNumberOfPortalsServed() {
        ProjectionInterestSet set = newSet();
        UUID observer = UUID.randomUUID();

        List<ILocalPortal> first = set.nextSlice(observer, INTERESTED, 2);
        assertEquals(2, first.size());
        assertSame(NEAREST, first.get(0));
        assertSame(OVERLAPPING, first.get(1));

        assertSame(FARTHER, only(set.nextSlice(observer, INTERESTED, 1)));
    }

    @Test
    void freshSetTracksNoObservers() {
        ProjectionInterestSet set = newSet();

        assertTrue(set.isEmpty());
        assertTrue(set.observerIds().isEmpty());
        assertEquals(0, set.countSpoofedEntities());
    }

    private static ProjectionInterestSet newSet() {
        return new ProjectionInterestSet(null, new EntityRenderLocalOcclusionArbiter(), null, null, () -> true);
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
