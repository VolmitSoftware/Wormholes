package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

public final class PortalCandidateSnapshotTest {
    @Test
    public void projectionCandidatesPreserveExactAabbResultsAcrossCellBoundaries() {
        World world = world("world", UUID.fromString("7455a7a2-d6a4-4fe2-a113-7059ed841818"));
        World otherWorld = world("other", UUID.fromString("77b13a31-f21e-4ec4-9661-37e39d819b82"));
        ILocalPortal west = portal("west", world, new AxisAlignedBB(-192.0D, 0.0D, 0.0D, 255.0D, -129.0D, 0.0D));
        ILocalPortal center = portal("center", world, new AxisAlignedBB(-128.0D, 128.0D, 0.0D, 255.0D, -128.0D, 128.0D));
        ILocalPortal east = portal("east", world, new AxisAlignedBB(0.0D, 256.0D, 0.0D, 255.0D, 0.0D, 256.0D));
        ILocalPortal remote = portal("remote", otherWorld, new AxisAlignedBB(-512.0D, 512.0D, 0.0D, 255.0D, -512.0D, 512.0D));
        List<ILocalPortal> portals = List.of(west, center, east, remote);
        PortalCandidateSnapshot snapshot = PortalCandidateSnapshot.captureProjection(portals);
        List<Location> probes = List.of(
            new Location(world, -128.0D, 64.0D, -128.0D),
            new Location(world, -0.0001D, 64.0D, -0.0001D),
            new Location(world, 0.0D, 64.0D, 0.0D),
            new Location(world, 127.9999D, 64.0D, 127.9999D),
            new Location(world, 128.0D, 64.0D, 128.0D),
            new Location(world, 256.0D, 64.0D, 256.0D),
            new Location(world, 256.0001D, 64.0D, 256.0001D));

        for (Location probe : probes) {
            assertEquals(fullScan(portals, world, probe), filteredCandidates(snapshot, world, probe));
        }
    }

    @Test
    public void candidateOrderMatchesSourceOrderForStableDistanceTies() {
        World world = world("world", UUID.fromString("a7171e40-4391-4945-8054-07b7090125f9"));
        AxisAlignedBB view = new AxisAlignedBB(-32.0D, 32.0D, 0.0D, 255.0D, -32.0D, 32.0D);
        ILocalPortal first = portal("first", world, view);
        ILocalPortal second = portal("second", world, view);
        ILocalPortal third = portal("third", world, view);
        PortalCandidateSnapshot snapshot = PortalCandidateSnapshot.captureProjection(List.of(first, second, third));

        List<ILocalPortal> candidates = snapshot.candidates(world, new Location(world, 0.0D, 64.0D, 0.0D));

        assertEquals(List.of(first, second, third), candidates);
        assertThrows(UnsupportedOperationException.class, () -> candidates.add(first));
    }

    @Test
    public void oversizedViewsFallBackWithoutFalseNegativesOrReordering() {
        World world = world("world", UUID.fromString("26100f1d-0ea6-485f-8180-7401bc5fc140"));
        ILocalPortal first = portal("first", world, new AxisAlignedBB(-64.0D, 64.0D, 0.0D, 255.0D, -64.0D, 64.0D));
        ILocalPortal oversized = portal("oversized", world,
            new AxisAlignedBB(-100_000.0D, 100_000.0D, 0.0D, 255.0D, -100_000.0D, 100_000.0D));
        ILocalPortal third = portal("third", world, new AxisAlignedBB(-16.0D, 16.0D, 0.0D, 255.0D, -16.0D, 16.0D));
        List<ILocalPortal> portals = List.of(first, oversized, third);
        PortalCandidateSnapshot snapshot = PortalCandidateSnapshot.captureProjection(portals);
        Location near = new Location(world, 0.0D, 64.0D, 0.0D);
        Location distant = new Location(world, 90_000.0D, 64.0D, 90_000.0D);

        assertEquals(portals, filteredCandidates(snapshot, world, near));
        assertEquals(List.of(oversized), filteredCandidates(snapshot, world, distant));
    }

    @Test
    public void skinCandidatesUsePortalWorldWhenCenterIsUnavailable() {
        World world = world("world", UUID.fromString("67c070f1-bae1-46a8-9403-f839658aa896"));
        ILocalPortal portal = portal("skin", world, null,
            new AxisAlignedBB(-32.0D, 32.0D, 0.0D, 255.0D, -32.0D, 32.0D));

        PortalCandidateSnapshot projection = PortalCandidateSnapshot.captureProjection(List.of(portal));
        PortalCandidateSnapshot skins = PortalCandidateSnapshot.capturePortalWorld(List.of(portal));
        Location location = new Location(world, 0.0D, 64.0D, 0.0D);

        assertEquals(List.of(), projection.candidates(world, location));
        assertEquals(List.of(portal), skins.candidates(world, location));
    }

    @Test
    public void distantPortalsAreExcludedFromTheObserverCell() {
        World world = world("world", UUID.fromString("514bce02-8e4c-4211-a26c-64ea8b525ba4"));
        List<ILocalPortal> portals = new ArrayList<ILocalPortal>(1_000);
        for (int index = 0; index < 1_000; index++) {
            double coordinate = index * 512.0D + 16.0D;
            portals.add(portal("portal-" + index, world,
                new AxisAlignedBB(coordinate - 8.0D, coordinate + 8.0D, 0.0D, 255.0D,
                    coordinate - 8.0D, coordinate + 8.0D)));
        }
        PortalCandidateSnapshot snapshot = PortalCandidateSnapshot.captureProjection(portals);
        ILocalPortal expected = portals.get(500);
        Location center = expected.getCenter();

        assertEquals(List.of(expected), snapshot.candidates(world, center));
    }

    @Test
    public void toolCandidatesRetainPreviewRangeBeyondACustomActivationView() {
        World world = world("world", UUID.fromString("e959968b-36fb-491d-b18c-07e62828d31d"));
        AxisAlignedBB area = new AxisAlignedBB(0.0D, 1.0D, 64.0D, 66.0D, 0.0D, 1.0D);
        AxisAlignedBB activationView = new AxisAlignedBB(-8.0D, 9.0D, 56.0D, 74.0D, -8.0D, 9.0D);
        ILocalPortal portal = portal("small-activation", world,
            new Location(world, 0.5D, 65.0D, 0.5D), activationView, area);
        PortalCandidateSnapshot snapshot = PortalCandidateSnapshot.capturePortalWorld(List.of(portal), 32.0D);

        assertEquals(List.of(portal), snapshot.candidates(world, new Location(world, 8.49D, 65.0D, 0.5D)));
        assertEquals(List.of(portal), snapshot.candidates(world, new Location(world, 32.5D, 65.0D, 0.5D)));
        assertEquals(List.of(), snapshot.candidates(world, new Location(world, 256.0D, 65.0D, 0.5D)));
    }

    private static List<ILocalPortal> fullScan(List<ILocalPortal> portals, World world, Location location) {
        List<ILocalPortal> eligible = new ArrayList<ILocalPortal>();
        for (ILocalPortal portal : portals) {
            Location center = portal.getCenter();
            if (center != null && world.equals(center.getWorld()) && portal.getView().contains(location)) {
                eligible.add(portal);
            }
        }
        return eligible;
    }

    private static List<ILocalPortal> filteredCandidates(PortalCandidateSnapshot snapshot, World world, Location location) {
        List<ILocalPortal> eligible = new ArrayList<ILocalPortal>();
        for (ILocalPortal portal : snapshot.candidates(world, location)) {
            Location center = portal.getCenter();
            if (center != null && world.equals(center.getWorld()) && portal.getView().contains(location)) {
                eligible.add(portal);
            }
        }
        return eligible;
    }

    private static ILocalPortal portal(String name, World world, AxisAlignedBB view) {
        Location center = view == null ? null : new Location(world,
            (view.getXa() + view.getXb()) * 0.5D,
            (view.getYa() + view.getYb()) * 0.5D,
            (view.getZa() + view.getZb()) * 0.5D);
        return portal(name, world, center, view);
    }

    private static ILocalPortal portal(String name, World world, Location center, AxisAlignedBB view) {
        return portal(name, world, center, view, view);
    }

    private static ILocalPortal portal(String name, World world, Location center, AxisAlignedBB view, AxisAlignedBB area) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        return (ILocalPortal) Proxy.newProxyInstance(
            PortalCandidateSnapshotTest.class.getClassLoader(),
            new Class<?>[] {ILocalPortal.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getId" -> id;
                case "getName" -> name;
                case "getWorld" -> world;
                case "getCenter" -> center;
                case "getView" -> view;
                case "getArea" -> area;
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> name;
                default -> defaultValue(method);
            });
    }

    private static World world(String name, UUID id) {
        return (World) Proxy.newProxyInstance(
            PortalCandidateSnapshotTest.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> id;
                case "getName" -> name;
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> name;
                default -> defaultValue(method);
            });
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }
}
