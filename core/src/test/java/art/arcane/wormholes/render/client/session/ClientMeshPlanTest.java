package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

final class ClientMeshPlanTest {
    @Test
    void apertureNeighborhoodDoesNotDuplicateProjectedRows() {
        for (Direction direction : Direction.values()) {
            for (boolean front : new boolean[] {false, true}) {
                ClientPortalGeometry geometry = new ClientPortalGeometry(-17, 63, -33, direction.ordinal(), front,
                    0, false, 9, 5, new long[] {(1L << 45) - 1}, 0, 0.75F, 1, 160,
                    0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 1, List.of());
                AxisAlignedBB area = geometry.apertureArea();
                GeometryVector center = new GeometryVector((area.getXa() + area.getXb()) / 2,
                    (area.getYa() + area.getYb()) / 2, (area.getZa() + area.getZb()) / 2);
                GeometryVector planeEye = new GeometryVector(direction.x() != 0 ? geometry.planeCoordinate() : center.x(),
                    direction.y() != 0 ? geometry.planeCoordinate() : center.y(),
                    direction.z() != 0 ? geometry.planeCoordinate() : center.z());
                for (GeometryVector eye : List.of(planeEye, center.add(new GeometryVector(100, -80, 140)))) {
                    List<ClientMeshPlan.Section> sections = ClientMeshPlan.visible(geometry, eye);
                    Set<ClientMeshPlan.Coordinate> unique = new HashSet<>();
                    double previous = -1;
                    for (ClientMeshPlan.Section section : sections) {
                        assertTrue(unique.add(section.coordinate()), direction + " " + section);
                        assertTrue(section.distance() >= previous);
                        previous = section.distance();
                    }
                    PlateBox bounds = ClientMeshPlan.bounds(geometry);
                    for (int x = Math.max(bounds.minX() >> 4, ((int) Math.floor(area.getXa()) - 32) >> 4);
                         x <= Math.min((bounds.minX() + bounds.sizeX() - 1) >> 4, ((int) Math.floor(area.getXb()) + 32) >> 4); x++) {
                        for (int y = Math.max(bounds.minY() >> 4, ((int) Math.floor(area.getYa()) - 32) >> 4);
                             y <= Math.min((bounds.minY() + bounds.sizeY() - 1) >> 4, ((int) Math.floor(area.getYb()) + 32) >> 4); y++) {
                            for (int z = Math.max(bounds.minZ() >> 4, ((int) Math.floor(area.getZa()) - 32) >> 4);
                                 z <= Math.min((bounds.minZ() + bounds.sizeZ() - 1) >> 4, ((int) Math.floor(area.getZb()) + 32) >> 4); z++) {
                                assertTrue(unique.contains(new ClientMeshPlan.Coordinate(x, y, z)));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void planeBlocksRemainScheduledAtEverySectionEdgeAndFacing() {
        for (Direction direction : Direction.values()) {
            for (boolean front : new boolean[] {false, true}) {
                for (int origin : new int[] {-17, -16, -1, 0, 15, 16}) {
                    ClientPortalGeometry geometry = new ClientPortalGeometry(origin, origin, origin, direction.ordinal(), front,
                        0, false, 1, 1, new long[] {1}, 0, 0.75F, 1, 32, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 1, List.of());
                    String context = direction + " front=" + front + " origin=" + origin;
                    PlateBox bounds = ClientMeshPlan.bounds(geometry);
                    assertTrue(bounds.index(origin, origin, origin) >= 0, context);
                    int eyeSide = front ? 1 : -1;
                    assertEquals(-1, bounds.index(origin + direction.x() * eyeSide,
                        origin + direction.y() * eyeSide, origin + direction.z() * eyeSide), context);
                    GeometryVector eye = new GeometryVector(origin + 0.5 + direction.x() * eyeSide * 5,
                        origin + 0.5 + direction.y() * eyeSide * 5, origin + 0.5 + direction.z() * eyeSide * 5);
                    assertTrue(ClientMeshPlan.visible(geometry, eye).stream()
                        .anyMatch(section -> section.clip().index(origin, origin, origin) >= 0), context);
                }
            }
        }
    }

    @Test
    void everyFacingAndSideIncludesApertureRaysThroughFullRequestedDepth() {
        SessionPortal portal = new SessionPortal("plan", -96);
        ClientPortalGeometry original = portal.geometry(new SessionPalette());
        for (Direction direction : Direction.values()) {
            for (boolean front : new boolean[] {false, true}) {
                ClientPortalGeometry geometry = new ClientPortalGeometry(-85, -67, -20, direction.ordinal(), front, 0, false, 3, 3,
                    original.apertureMask(), 0, 0.75F, 1, 64, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 1, List.of());
                AxisAlignedBB area = geometry.apertureArea();
                GeometryVector center = new GeometryVector((area.getXa() + area.getXb()) / 2,
                    (area.getYa() + area.getYb()) / 2, (area.getZa() + area.getZb()) / 2);
                int side = front ? 1 : -1;
                GeometryVector eye = center.add(new GeometryVector(direction.x(), direction.y(), direction.z()).multiply(side * 5));
                Set<ClientMeshPlan.Coordinate> visible = new HashSet<ClientMeshPlan.Coordinate>();
                for (ClientMeshPlan.Section section : ClientMeshPlan.visible(geometry, eye)) {
                    visible.add(section.coordinate());
                }
                for (int corner = 0; corner < 8; corner++) {
                    GeometryVector point = new GeometryVector((corner & 1) == 0 ? area.getXa() : area.getXb(),
                        (corner & 2) == 0 ? area.getYa() : area.getYb(), (corner & 4) == 0 ? area.getZa() : area.getZb());
                    for (int depth : new int[] {4, 20, 60}) {
                        GeometryVector projected = eye.add(point.subtract(eye).multiply(1 + depth / 5.0));
                        ClientMeshPlan.Coordinate expected = new ClientMeshPlan.Coordinate(projected.getBlockX() >> 4,
                            projected.getBlockY() >> 4, projected.getBlockZ() >> 4);
                        assertTrue(visible.contains(expected), direction + " front=" + front + " depth=" + depth + " " + expected);
                    }
                }
            }
        }
    }

    @Test
    void nearestSectionsArriveFirstWithoutLosingFarCoverage() {
        ClientPortalGeometry geometry = new SessionPortal("depth", 0).geometry(new SessionPalette()).withDepth(512);
        GeometryVector eye = new GeometryVector(15, 67, 10);
        List<ClientMeshPlan.Section> sections = ClientMeshPlan.visible(geometry, eye);
        double previous = -1;
        for (ClientMeshPlan.Section section : sections) {
            assertTrue(section.distance() >= previous);
            previous = section.distance();
        }
        assertTrue(sections.getFirst().distance() < 1024);
        assertTrue(sections.stream().anyMatch(section -> Math.abs((section.z() << 4) - 10) >= 496));
    }

    @Test
    void neighborhoodAroundApertureStaysScheduledFromObliqueViews() {
        ClientPortalGeometry geometry = new SessionPortal("nearby", 0).geometry(new SessionPalette()).withDepth(208);
        GeometryVector firstEye = new GeometryVector(-150, 67, 15);
        GeometryVector secondEye = new GeometryVector(150, 67, 15);
        Set<ClientMeshPlan.Coordinate> first = new HashSet<ClientMeshPlan.Coordinate>();
        Set<ClientMeshPlan.Coordinate> second = new HashSet<ClientMeshPlan.Coordinate>();
        ClientMeshPlan.visible(geometry, firstEye).forEach(section -> first.add(section.coordinate()));
        ClientMeshPlan.visible(geometry, secondEye).forEach(section -> second.add(section.coordinate()));
        for (int x = -1; x <= 1; x++) {
            for (int y = 3; y <= 5; y++) {
                for (int z = 1; z <= 3; z++) {
                    ClientMeshPlan.Coordinate coordinate = new ClientMeshPlan.Coordinate(x, y, z);
                    assertTrue(first.contains(coordinate), coordinate.toString());
                    assertTrue(second.contains(coordinate), coordinate.toString());
                }
            }
        }
    }

    @Test
    void fullDistanceKeepsFarSectionsBeyondFormerMemoryEstimate() {
        ClientPortalGeometry geometry = new SessionPortal("capacity", 0).geometry(new SessionPalette()).withDepth(512);
        List<ClientMeshPlan.Section> sections = ClientMeshPlan.visible(geometry, new GeometryVector(11, 67, 15));
        assertTrue(sections.size() > 4096, "full coverage must not stop at the former count budget");
        assertTrue(sections.stream().anyMatch(section -> Math.abs((section.z() << 4) - 15) >= 496));
        for (ClientMeshPlan.Section section : sections) {
            assertEquals(4096, section.clip().cells());
        }
    }
}
