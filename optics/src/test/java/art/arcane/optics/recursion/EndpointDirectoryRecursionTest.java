package art.arcane.optics.recursion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.aperture.Endpoint;
import art.arcane.optics.aperture.EndpointDirectory;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class EndpointDirectoryRecursionTest {
    private static final String WORLD = "world";
    private static final double EYE_X = 1.0D;
    private static final double EYE_Y = 65.0D;
    private static final double EYE_Z = -5.0D;

    private Directory directory;
    private Gate front;
    private Gate back;
    private Gate route;

    @BeforeEach
    void fixture() {
        front = gate(Frame.canonical(Face.S), 0.0D);
        back = gate(Frame.canonical(Face.N), 4.0D);
        route = gate(Frame.canonical(Face.N), 40.0D);
        directory = new Directory(List.of(front, back, route));
        directory.destinations.put(front, back);
        directory.destinations.put(back, front);
    }

    @Test
    void revalidateKeepsTheIndexWhileNoEndpointChanged() {
        RecursiveEndpoints<String, Gate> recursive = recursive();
        recursive.revalidate();
        RecursiveEndpoints<String, Gate>.Index first = recursive.indexFor(WORLD, EYE_X, EYE_Y, EYE_Z, front);

        recursive.revalidate();

        assertSame(first, recursive.indexFor(WORLD, EYE_X, EYE_Y, EYE_Z, front));
    }

    @Test
    void revalidateRebuildsTheIndexWhenADestinationChanges() {
        RecursiveEndpoints<String, Gate> recursive = recursive();
        recursive.revalidate();
        RecursiveEndpoints<String, Gate>.Index first = recursive.indexFor(WORLD, EYE_X, EYE_Y, EYE_Z, front);

        directory.destinations.put(back, route);
        recursive.revalidate();

        assertNotSame(first, recursive.indexFor(WORLD, EYE_X, EYE_Y, EYE_Z, front));
    }

    @Test
    void revalidateRebuildsTheIndexWhenMirrorTurnsChange() {
        directory.mirrors.add(back);
        directory.turns.put(back, QuarterTurn.DEGREES_0);
        RecursiveEndpoints<String, Gate> recursive = recursive();
        recursive.revalidate();
        RecursiveEndpoints<String, Gate>.Index first = recursive.indexFor(WORLD, EYE_X, EYE_Y, EYE_Z, front);

        directory.turns.put(back, QuarterTurn.DEGREES_180);
        recursive.revalidate();

        assertNotSame(first, recursive.indexFor(WORLD, EYE_X, EYE_Y, EYE_Z, front));
    }

    @Test
    void revalidateRebuildsTheIndexWhenEligibilityChanges() {
        RecursiveEndpoints<String, Gate> recursive = recursive();
        recursive.revalidate();
        RecursiveEndpoints<String, Gate>.Index first = recursive.indexFor(WORLD, EYE_X, EYE_Y, EYE_Z, front);

        directory.ineligible.add(back);
        recursive.revalidate();

        assertNotSame(first, recursive.indexFor(WORLD, EYE_X, EYE_Y, EYE_Z, front));
    }

    @Test
    void ineligibleEndpointsNeverReachTheSampledVolume() {
        RecursiveEndpoints<String, Gate> recursive = recursive();
        assertTrue(recursive.reaches(WORLD, front, -2.0D, 63.0D, 3.0D, 4.0D, 67.0D, 5.0D));

        directory.ineligible.add(back);
        directory.ineligible.add(route);
        recursive.revalidate();

        assertFalse(recursive.reaches(WORLD, front, -2.0D, 63.0D, 3.0D, 4.0D, 67.0D, 5.0D));
    }

    private RecursiveEndpoints<String, Gate> recursive() {
        return new RecursiveEndpoints<String, Gate>(directory, () -> new RecursiveEndpoints.Options(0.75D, 64.0D));
    }

    private static Gate gate(Frame frame, double planeZ) {
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(new Box(0.0D, 1.999D, 64.0D, 65.999D, planeZ, planeZ + 0.999D));
        return new Gate(UUID.randomUUID(), frame, new Vec3d(1.0D, 65.0D, planeZ + 0.5D), aperture);
    }

    private record Gate(UUID id, Frame frame, Vec3d origin, ApertureCells cells) implements Endpoint {
    }

    private static final class Directory implements EndpointDirectory<String, Gate> {
        private final List<Gate> endpoints;
        private final Map<Gate, Gate> destinations = new HashMap<Gate, Gate>();
        private final Map<Gate, QuarterTurn> turns = new HashMap<Gate, QuarterTurn>();
        private final Set<Gate> mirrors = new HashSet<Gate>();
        private final Set<Gate> ineligible = new HashSet<Gate>();

        private Directory(List<Gate> endpoints) {
            this.endpoints = endpoints;
        }

        @Override
        public List<Gate> endpoints() {
            return endpoints;
        }

        @Override
        public String world(Gate endpoint) {
            return WORLD;
        }

        @Override
        public CellAperture aperture(Gate endpoint) {
            return endpoint.cells();
        }

        @Override
        public Box view(Gate endpoint) {
            Vec3d origin = endpoint.origin();
            return new Box(origin.x() - 32.0D, origin.x() + 32.0D, origin.y() - 32.0D, origin.y() + 32.0D,
                origin.z() - 32.0D, origin.z() + 32.0D);
        }

        @Override
        public boolean eligible(Gate endpoint) {
            return !ineligible.contains(endpoint);
        }

        @Override
        public boolean mirror(Gate endpoint) {
            return mirrors.contains(endpoint);
        }

        @Override
        public QuarterTurn mirrorTurns(Gate endpoint) {
            return turns.getOrDefault(endpoint, QuarterTurn.DEGREES_0);
        }

        @Override
        public Gate destination(Gate endpoint) {
            return destinations.get(endpoint);
        }
    }
}
