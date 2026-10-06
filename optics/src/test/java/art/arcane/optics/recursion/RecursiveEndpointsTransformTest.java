package art.arcane.optics.recursion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.aperture.Endpoint;
import art.arcane.optics.entity.ItemFrameTransform;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.volume.ViewVolume;

final class RecursiveEndpointsTransformTest {
    private static final long CANDIDATE_SWEEP = 0xDCDB1AB15D04A891L;
    private static final long PATH_SWEEP = 0x94641D8B7A2CC07AL;
    private static final double COMPOSITION_TOLERANCE = 1.0E-9D;
    static final String RESOURCE = "recursive-endpoints-golden.txt";
    static final String WORLD = "world";
    static final int SCENES = 12;
    static final int EYES_PER_SCENE = 24;
    static final int POINTS_PER_EYE = 48;
    static final int PATH_SCENES = 6;
    static final int PATH_EYES = 2;
    static final int PATH_SAMPLES = 2;

    @Test
    void candidateTransformsMatchTheRecordedPerCandidateMatrices() {
        double[] scratch = new double[3];
        long value = candidateSweep(new Probe() {
            @Override
            public void candidate(RecursiveEndpoints<String, ScenePortal>.Candidate candidate, double x, double y, double z, Digest digest) {
                digest.add(candidate.traversable ? 1L : 0L);
                digest.add(candidate.transformedEyeX);
                digest.add(candidate.transformedEyeY);
                digest.add(candidate.transformedEyeZ);
                if (!candidate.traversable) {
                    return;
                }
                candidate.transform().pointInto(x, y, z, scratch);
                digest.add(scratch);
                candidate.transform().vectorInto(x, y, z, scratch);
                digest.add(scratch);
            }

            @Override
            public void hit(RecursiveEndpoints.Hit<String, ScenePortal> hit, Digest digest) {
                digest.add(hit == null ? 0L : 1L);
                if (hit == null) {
                    return;
                }
                digest.add(hit.portalId == null ? 0L : hit.portalId.getMostSignificantBits());
                digest.add(hit.traversable ? 1L : 0L);
                digest.add(hit.cycle ? 1L : 0L);
                if (!hit.traversable) {
                    return;
                }
                digest.add(hit.pointX);
                digest.add(hit.pointY);
                digest.add(hit.pointZ);
                digest.add(hit.eyeX);
                digest.add(hit.eyeY);
                digest.add(hit.eyeZ);
                AxisPermutation permutation = hit.transform.permutation();
                digest.add(permutation.x().ordinal());
                digest.add(permutation.y().ordinal());
                digest.add(permutation.z().ordinal());
            }
        });
        assertEquals(Long.toHexString(CANDIDATE_SWEEP), Long.toHexString(value));
    }

    @Test
    void composedPathTransformsMatchTheRecordedSequentialPaths() throws IOException {
        List<String> rows = new ArrayList<String>();
        long value = pathSweep(new PathProbe() {
            @Override
            public EntityPath<String, ScenePortal> root(ScenePortal local, ScenePortal remote, boolean front, Vec3d eye, ViewVolume frustum,
                                                        RecursiveEndpoints<String, ScenePortal> portals) {
                OpticTransform transform = OpticTransform.between(remote.frame().view(front), remote.origin(), local.frame().view(front),
                    local.origin());
                return new EntityPath<>(new EntityPath.Root<>(local, remote, transform, eye, frustum, WORLD, 3), portals);
            }

            @Override
            public void record(EntityPath<String, ScenePortal> path, double x, double y, double z, Digest digest, double[] point) {
                OpticTransform transform = path.transform();
                double[] scratch = new double[3];
                digest.add(path.nested() ? 1L : 0L);
                path.point(x, y, z, point);
                transform.vectorInto(1.25D, -0.5D, 0.75D, scratch);
                digest.add(scratch);
                digest.add(transform.flipsWorldUp() ? 1L : 0L);
                for (Face face : Face.values()) {
                    digest.add(ItemFrameTransform.of(face, transform));
                }
                ItemFrameTransform.anchorInto(x, y, z, transform, scratch);
                digest.add(scratch);
                double[] visible = new double[3];
                boolean seen = path.visible(x, y, z, visible);
                digest.add(seen ? 1L : 0L);
                if (seen) {
                    digest.add(visible);
                }
            }
        }, rows);
        assertEquals(Long.toHexString(PATH_SWEEP), Long.toHexString(value));
        List<String> recorded = recordedRows();
        assertEquals(recorded.size(), rows.size());
        for (int index = 0; index < rows.size(); index++) {
            String[] expected = recorded.get(index).split(" ");
            String[] actual = rows.get(index).split(" ");
            for (int column = 1; column < 5; column++) {
                assertEquals(expected[column], actual[column], recorded.get(index));
            }
            for (int column = 5; column < 8; column++) {
                assertEquals(Double.parseDouble(expected[column]), Double.parseDouble(actual[column]), COMPOSITION_TOLERANCE, recorded.get(index));
            }
        }
    }

    static long candidateSweep(Probe probe) {
        Random random = new Random(0x2EC0125L);
        Digest digest = new Digest();
        for (int scene = 0; scene < SCENES; scene++) {
            Scene built = scene(random);
            RecursiveEndpoints<String, ScenePortal> portals = built.endpoints();
            for (int eye = 0; eye < EYES_PER_SCENE; eye++) {
                Vec3d eyePoint = near(random, built.portals().get(random.nextInt(built.portals().size())).origin(), 10.0D);
                RecursiveEndpoints<String, ScenePortal>.Index index = portals.indexFor(WORLD, eyePoint.x(), eyePoint.y(), eyePoint.z(), null);
                for (RecursiveEndpoints<String, ScenePortal>.Candidate candidate : index.paths()) {
                    Vec3d point = near(random, eyePoint, 30.0D);
                    probe.candidate(candidate, point.x(), point.y(), point.z(), digest);
                }
                for (int sample = 0; sample < POINTS_PER_EYE; sample++) {
                    Vec3d point = near(random, eyePoint, 20.0D);
                    probe.hit(index.find(point.x(), point.y(), point.z(), 3), digest);
                }
            }
        }
        return digest.value();
    }

    static long pathSweep(PathProbe probe, List<String> rows) {
        Random random = new Random(0x9A7B5L);
        Digest digest = new Digest();
        ViewVolume frustum = openFrustum();
        double[] point = new double[3];
        for (int scene = 0; scene < PATH_SCENES; scene++) {
            Scene built = scene(random);
            RecursiveEndpoints<String, ScenePortal> portals = built.endpoints();
            ScenePortal local = built.portals().get(0);
            ScenePortal remote = built.portals().get(1);
            for (int eye = 0; eye < PATH_EYES; eye++) {
                Vec3d eyePoint = near(random, local.origin(), 6.0D);
                Face normal = local.frame().getNormal();
                boolean front = (eyePoint.x() - local.origin().x()) * normal.x() + (eyePoint.y() - local.origin().y()) * normal.y()
                    + (eyePoint.z() - local.origin().z()) * normal.z() >= 0.0D;
                EntityPath<String, ScenePortal> root = probe.root(local, remote, front, eyePoint, frustum, portals);
                List<EntityPath<String, ScenePortal>> paths = new ArrayList<EntityPath<String, ScenePortal>>();
                paths.add(root);
                for (RecursiveEndpoints<String, ScenePortal>.Candidate candidate : root.index.paths()) {
                    EntityPath<String, ScenePortal> child = root.child(candidate, portals);
                    if (child != null) {
                        paths.add(child);
                        for (RecursiveEndpoints<String, ScenePortal>.Candidate grand : child.index.paths()) {
                            EntityPath<String, ScenePortal> nested = child.child(grand, portals);
                            if (nested != null) {
                                paths.add(nested);
                            }
                        }
                    }
                }
                for (int index = 0; index < paths.size(); index++) {
                    EntityPath<String, ScenePortal> path = paths.get(index);
                    for (int sample = 0; sample < PATH_SAMPLES; sample++) {
                        Vec3d source = near(random, remote.origin(), 20.0D);
                        probe.record(path, source.x(), source.y(), source.z(), digest, point);
                        rows.add("E " + scene + " " + eye + " " + index + " " + sample + " " + point[0] + " " + point[1] + " " + point[2]);
                    }
                }
            }
        }
        return digest.value();
    }

    private static List<String> recordedRows() throws IOException {
        List<String> rows = new ArrayList<String>();
        try (InputStream stream = RecursiveEndpointsTransformTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, RESOURCE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(line);
            }
        }
        return rows;
    }

    static Scene scene(Random random) {
        List<ScenePortal> portals = new ArrayList<ScenePortal>(6);
        for (int index = 0; index < 6; index++) {
            Face normal = Face.values()[random.nextInt(6)];
            Face up = perpendicular(random, normal);
            Frame frame = Frame.fromNormalUp(normal, up);
            int width = 1 + random.nextInt(4);
            int height = 2 + random.nextInt(3);
            int baseX = random.nextInt(48) - 24;
            int baseY = 48 + random.nextInt(32);
            int baseZ = random.nextInt(48) - 24;
            double sizeX = size(frame, Face.E, width, height);
            double sizeY = size(frame, Face.U, width, height);
            double sizeZ = size(frame, Face.S, width, height);
            Box area = new Box(baseX, baseX + sizeX, baseY, baseY + sizeY, baseZ, baseZ + sizeZ);
            ApertureCells structure = new ApertureCells();
            structure.setArea(area);
            Box view = new Box(area.getXa() - 24.0D, area.getXb() + 24.0D, area.getYa() - 24.0D, area.getYb() + 24.0D,
                area.getZa() - 24.0D, area.getZb() + 24.0D);
            boolean mirror = index == 4;
            QuarterTurn turns = QuarterTurn.values()[random.nextInt(4)];
            int link = switch (index) {
                case 0 -> 1;
                case 1 -> 0;
                case 2 -> 3;
                case 3 -> 2;
                case 5 -> 0;
                default -> -1;
            };
            portals.add(new ScenePortal(new UUID(random.nextLong(), random.nextLong()), frame, area.center(), structure, view, mirror,
                turns, link));
        }
        Scene scene = new Scene(portals);
        return scene;
    }

    static ViewVolume openFrustum() {
        ViewVolume frustum = mock(ViewVolume.class);
        when(frustum.containsPrimitive(anyDouble(), anyDouble(), anyDouble())).thenReturn(true);
        when(frustum.getRegion()).thenReturn(new Box(-1.0E6D, 1.0E6D, -1.0E6D, 1.0E6D, -1.0E6D, 1.0E6D));
        return frustum;
    }

    static Vec3d near(Random random, Vec3d center, double range) {
        return new Vec3d(center.x() + (random.nextDouble() * 2.0D - 1.0D) * range, center.y() + (random.nextDouble() * 2.0D - 1.0D) * range,
            center.z() + (random.nextDouble() * 2.0D - 1.0D) * range);
    }

    private static double size(Frame frame, Face axis, int width, int height) {
        if (frame.getRight().getAxis() == axis.getAxis()) {
            return width;
        }
        if (frame.getUp().getAxis() == axis.getAxis()) {
            return height;
        }
        return 1.0D;
    }

    private static Face perpendicular(Random random, Face normal) {
        while (true) {
            Face candidate = Face.values()[random.nextInt(6)];
            if (candidate.getAxis() != normal.getAxis()) {
                return candidate;
            }
        }
    }

    record ScenePortal(UUID id, Frame frame, Vec3d origin, ApertureCells structure, Box view, boolean mirror, QuarterTurn turns, int link)
        implements Endpoint {
    }

    record Scene(List<ScenePortal> portals) {
        RecursiveEndpoints<String, ScenePortal> endpoints() {
            return new RecursiveEndpoints<String, ScenePortal>(new Access(portals), () -> new RecursiveEndpoints.Options(0.25D, 24.0D));
        }
    }

    interface PathProbe {
        EntityPath<String, ScenePortal> root(ScenePortal local, ScenePortal remote, boolean front, Vec3d eye, ViewVolume frustum,
                                              RecursiveEndpoints<String, ScenePortal> portals);

        void record(EntityPath<String, ScenePortal> path, double x, double y, double z, Digest digest, double[] point);
    }

    interface Probe {
        void candidate(RecursiveEndpoints<String, ScenePortal>.Candidate candidate, double x, double y, double z, Digest digest);

        void hit(RecursiveEndpoints.Hit<String, ScenePortal> hit, Digest digest);
    }

    static final class Access implements RecursiveEndpoints.PortalAccess<String, ScenePortal> {
        private final List<ScenePortal> portals;

        Access(List<ScenePortal> portals) {
            this.portals = portals;
        }

        @Override
        public List<ScenePortal> portals() {
            return portals;
        }

        @Override
        public String world(ScenePortal portal) {
            return WORLD;
        }

        @Override
        public CellAperture structure(ScenePortal portal) {
            return portal.structure();
        }

        @Override
        public Box view(ScenePortal portal) {
            return portal.view();
        }

        @Override
        public boolean eligible(ScenePortal portal) {
            return true;
        }

        @Override
        public boolean mirror(ScenePortal portal) {
            return portal.mirror();
        }

        @Override
        public int mirrorQuarterTurns(ScenePortal portal) {
            return portal.turns().coherentFor(portal.frame()).getQuarterTurns();
        }

        @Override
        public ScenePortal destination(ScenePortal portal) {
            return portal.link() < 0 ? null : portals.get(portal.link());
        }
    }

    static final class Digest {
        private long value = 0xCBF29CE484222325L;

        void add(long bits) {
            value = (value ^ bits) * 0x100000001B3L;
        }

        void add(double value) {
            add(Double.doubleToLongBits(value + 0.0D));
        }

        void add(double[] point) {
            add(point[0]);
            add(point[1]);
            add(point[2]);
        }

        long value() {
            return value;
        }
    }
}
