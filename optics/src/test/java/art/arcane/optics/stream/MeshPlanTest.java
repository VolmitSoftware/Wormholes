package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;
import art.arcane.optics.client.MeshPlan;

final class MeshPlanTest {
    @Test
    void apertureNeighborhoodDoesNotDuplicateProjectedRows() {
        for (Face direction : Face.values()) {
            for (boolean front : new boolean[] {false, true}) {
                ApertureDescriptor geometry = new ApertureDescriptor(-17, 63, -33, direction.ordinal(), front,
                    0, false, 9, 5, new long[] {(1L << 45) - 1}, 0, 0.75F, 1, 160,
                    0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 1, List.of());
                Box area = geometry.apertureArea();
                Vec3d center = new Vec3d((area.getXa() + area.getXb()) / 2,
                    (area.getYa() + area.getYb()) / 2, (area.getZa() + area.getZb()) / 2);
                Vec3d planeEye = new Vec3d(direction.x() != 0 ? geometry.planeCoordinate() : center.x(),
                    direction.y() != 0 ? geometry.planeCoordinate() : center.y(),
                    direction.z() != 0 ? geometry.planeCoordinate() : center.z());
                for (Vec3d eye : List.of(planeEye, center.add(new Vec3d(100, -80, 140)))) {
                    List<MeshPlan.Section> sections = MeshPlan.visible(geometry, eye);
                    Set<MeshPlan.Coordinate> unique = new HashSet<>();
                    double previous = -1;
                    for (MeshPlan.Section section : sections) {
                        assertTrue(unique.add(section.coordinate()), direction + " " + section);
                        assertTrue(section.distance() >= previous);
                        previous = section.distance();
                    }
                    BlockBox bounds = MeshPlan.bounds(geometry);
                    for (int x = Math.max(bounds.minX() >> 4, ((int) Math.floor(area.getXa()) - 32) >> 4);
                         x <= Math.min((bounds.minX() + bounds.sizeX() - 1) >> 4, ((int) Math.floor(area.getXb()) + 32) >> 4); x++) {
                        for (int y = Math.max(bounds.minY() >> 4, ((int) Math.floor(area.getYa()) - 32) >> 4);
                             y <= Math.min((bounds.minY() + bounds.sizeY() - 1) >> 4, ((int) Math.floor(area.getYb()) + 32) >> 4); y++) {
                            for (int z = Math.max(bounds.minZ() >> 4, ((int) Math.floor(area.getZa()) - 32) >> 4);
                                 z <= Math.min((bounds.minZ() + bounds.sizeZ() - 1) >> 4, ((int) Math.floor(area.getZb()) + 32) >> 4); z++) {
                                assertTrue(unique.contains(new MeshPlan.Coordinate(x, y, z)));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void planeBlocksRemainScheduledAtEverySectionEdgeAndFacing() {
        for (Face direction : Face.values()) {
            for (boolean front : new boolean[] {false, true}) {
                for (int origin : new int[] {-17, -16, -1, 0, 15, 16}) {
                    ApertureDescriptor geometry = new ApertureDescriptor(origin, origin, origin, direction.ordinal(), front,
                        0, false, 1, 1, new long[] {1}, 0, 0.75F, 1, 32, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 1, List.of());
                    String context = direction + " front=" + front + " origin=" + origin;
                    BlockBox bounds = MeshPlan.bounds(geometry);
                    assertTrue(bounds.index(origin, origin, origin) >= 0, context);
                    int eyeSide = front ? 1 : -1;
                    assertEquals(-1, bounds.index(origin + direction.x() * eyeSide,
                        origin + direction.y() * eyeSide, origin + direction.z() * eyeSide), context);
                    Vec3d eye = new Vec3d(origin + 0.5 + direction.x() * eyeSide * 5,
                        origin + 0.5 + direction.y() * eyeSide * 5, origin + 0.5 + direction.z() * eyeSide * 5);
                    assertTrue(MeshPlan.visible(geometry, eye).stream()
                        .anyMatch(section -> section.clip().index(origin, origin, origin) >= 0), context);
                }
            }
        }
    }

    @Test
    void everyFacingAndSideIncludesApertureRaysThroughFullRequestedDepth() {
        SessionPortal portal = new SessionPortal("plan", -96);
        ApertureDescriptor original = portal.geometry(new SessionPalette());
        for (Face direction : Face.values()) {
            for (boolean front : new boolean[] {false, true}) {
                ApertureDescriptor geometry = new ApertureDescriptor(-85, -67, -20, direction.ordinal(), front, 0, false, 3, 3,
                    original.apertureMask(), 0, 0.75F, 1, 64, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 1, List.of());
                Box area = geometry.apertureArea();
                Vec3d center = new Vec3d((area.getXa() + area.getXb()) / 2,
                    (area.getYa() + area.getYb()) / 2, (area.getZa() + area.getZb()) / 2);
                int side = front ? 1 : -1;
                Vec3d eye = center.add(new Vec3d(direction.x(), direction.y(), direction.z()).multiply(side * 5));
                Set<MeshPlan.Coordinate> visible = new HashSet<MeshPlan.Coordinate>();
                for (MeshPlan.Section section : MeshPlan.visible(geometry, eye)) {
                    visible.add(section.coordinate());
                }
                for (int corner = 0; corner < 8; corner++) {
                    Vec3d point = new Vec3d((corner & 1) == 0 ? area.getXa() : area.getXb(),
                        (corner & 2) == 0 ? area.getYa() : area.getYb(), (corner & 4) == 0 ? area.getZa() : area.getZb());
                    for (int depth : new int[] {4, 20, 60}) {
                        Vec3d projected = eye.add(point.subtract(eye).multiply(1 + depth / 5.0));
                        MeshPlan.Coordinate expected = new MeshPlan.Coordinate(projected.getBlockX() >> 4,
                            projected.getBlockY() >> 4, projected.getBlockZ() >> 4);
                        assertTrue(visible.contains(expected), direction + " front=" + front + " depth=" + depth + " " + expected);
                    }
                }
            }
        }
    }

    @Test
    void nearestSectionsArriveFirstWithoutLosingFarCoverage() {
        ApertureDescriptor geometry = new SessionPortal("depth", 0).geometry(new SessionPalette()).withDepth(512);
        Vec3d eye = new Vec3d(15, 67, 10);
        List<MeshPlan.Section> sections = MeshPlan.visible(geometry, eye);
        double previous = -1;
        for (MeshPlan.Section section : sections) {
            assertTrue(section.distance() >= previous);
            previous = section.distance();
        }
        assertTrue(sections.getFirst().distance() < 1024);
        assertTrue(sections.stream().anyMatch(section -> Math.abs((section.z() << 4) - 10) >= 496));
    }

    @Test
    void neighborhoodAroundApertureStaysScheduledFromObliqueViews() {
        ApertureDescriptor geometry = new SessionPortal("nearby", 0).geometry(new SessionPalette()).withDepth(208);
        Vec3d firstEye = new Vec3d(-150, 67, 15);
        Vec3d secondEye = new Vec3d(150, 67, 15);
        Set<MeshPlan.Coordinate> first = new HashSet<MeshPlan.Coordinate>();
        Set<MeshPlan.Coordinate> second = new HashSet<MeshPlan.Coordinate>();
        MeshPlan.visible(geometry, firstEye).forEach(section -> first.add(section.coordinate()));
        MeshPlan.visible(geometry, secondEye).forEach(section -> second.add(section.coordinate()));
        for (int x = -1; x <= 1; x++) {
            for (int y = 3; y <= 5; y++) {
                for (int z = 1; z <= 3; z++) {
                    MeshPlan.Coordinate coordinate = new MeshPlan.Coordinate(x, y, z);
                    assertTrue(first.contains(coordinate), coordinate.toString());
                    assertTrue(second.contains(coordinate), coordinate.toString());
                }
            }
        }
    }

    @Test
    void fullDistanceKeepsFarSectionsBeyondFormerMemoryEstimate() {
        ApertureDescriptor geometry = new SessionPortal("capacity", 0).geometry(new SessionPalette()).withDepth(512);
        List<MeshPlan.Section> sections = MeshPlan.visible(geometry, new Vec3d(11, 67, 15));
        assertTrue(sections.size() > 4096, "full coverage must not stop at the former count budget");
        assertTrue(sections.stream().anyMatch(section -> Math.abs((section.z() << 4) - 15) >= 496));
        for (MeshPlan.Section section : sections) {
            assertEquals(4096, section.clip().cells());
        }
    }
}
