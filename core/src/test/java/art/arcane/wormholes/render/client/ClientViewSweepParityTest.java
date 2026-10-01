package art.arcane.wormholes.render.client;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.Frustum4D;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectorFrameTransform;
import art.arcane.wormholes.render.ProjectorPlaneWindow;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

final class ClientViewSweepParityTest {
    private static final int EYES = 200;
    private static final double HYSTERESIS = ClientViewSweep.DEFAULT_HYSTERESIS_BLOCKS;
    private static final ProjectionRenderMode[] MODES = {ProjectionRenderMode.PANOPTIC, ProjectionRenderMode.VENTICULAR};

    @Test
    void irregularAperturesMatchPerCellRaysAcrossFramesPaddingAndHysteresis() {
        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            for (int rotation = 0; rotation < 4; rotation++, frame = frame.rotateClockwise()) {
                PortalGeometry aperture = irregularAperture(frame);
                GeometryVector origin = aperture.getArea().center();
                for (boolean front : new boolean[] {false, true}) {
                    for (double padding : new double[] {0.0D, 0.75D}) {
                        for (double hysteresis : new double[] {0.0D, HYSTERESIS}) {
                            ClientPortalGeometry geometry = ClientPortalGeometry.fromPortal(new ClientPortalGeometry.Source(aperture,
                                frame, front, false, 0, 2.0D, padding, 0.2D, 8, 0, ClientPortalGeometry.BLACKOUT_OFF, 0,
                                ClientPortalGeometry.MASK_AIR_PROJECT, ProjectedBlockClaim.LightingPolicy.LOCAL, 0,
                                ClientPortalGeometry.KIND_FRAME, 0, 0L, List.of())).orElseThrow();
                            PlateBox bounds = new PlateBox(-31, -34, -29, 25, 25, 25);
                            ClientViewSweep sweep = new ClientViewSweep(geometry, bounds, hysteresis);
                            LongOpenHashSet previous = new LongOpenHashSet();
                            for (int step = 0; step < 3; step++) {
                                double distance = (front ? 1.0D : -1.0D) * (1.0D + step * 3.0D);
                                double lateral = step * 0.45D - 0.35D;
                                GeometryVector eye = quantized(origin.getX() + normal.x() * distance + frame.getRight().x() * lateral,
                                    origin.getY() + normal.y() * distance + frame.getRight().y() * lateral,
                                    origin.getZ() + normal.z() * distance + frame.getRight().z() * lateral);
                                LongOpenHashSet expected = perCellMask(geometry, bounds, eye, padding + hysteresis);
                                assertTrue(!expected.isEmpty(), "the reference cone must include visible cells");
                                if (hysteresis > 0.0D && !previous.isEmpty()) {
                                    LongOpenHashSet retained = perCellMask(geometry, bounds, eye, padding + hysteresis * 2.0D);
                                    retained.retainAll(previous);
                                    expected.addAll(retained);
                                }
                                sweep.sweep(eye.getX(), eye.getY(), eye.getZ(), 0.0D, 0.0D, 0.0D);
                                LongArrayList actual = new LongArrayList();
                                sweep.appliedKeys(actual);
                                assertEquals(expected, new LongOpenHashSet(actual), normal + " rotation=" + rotation + " front=" + front
                                    + " padding=" + padding + " hysteresis=" + hysteresis + " step=" + step);
                                previous = expected;
                            }
                        }
                    }
                }
            }
        }
    }

    private static PortalGeometry irregularAperture(PortalFrame frame) {
        List<GeometryVector> cells = new ArrayList<GeometryVector>();
        for (int right = -2; right <= 2; right++) {
            for (int up = -2; up <= 2; up++) {
                if ((right == 0 && up == 0) || (up == 2 && Math.abs(right) == 2)) {
                    continue;
                }
                cells.add(new GeometryVector(-19 + frame.getRight().x() * right + frame.getUp().x() * up,
                    -22 + frame.getRight().y() * right + frame.getUp().y() * up,
                    -17 + frame.getRight().z() * right + frame.getUp().z() * up));
            }
        }
        PortalGeometry aperture = new PortalGeometry();
        aperture.setBlocks(cells);
        return aperture;
    }

    private static LongOpenHashSet perCellMask(ClientPortalGeometry geometry, PlateBox bounds, GeometryVector eye, double padding) {
        PortalGeometry aperture = geometry.aperture();
        AxisAlignedBB area = aperture.getArea();
        GeometryVector origin = area.center();
        PortalFrame frame = geometry.frame();
        Direction localNormal = frame.getNormal();
        PortalFrame projectionFrame = frame.view(geometry.frontSide());
        Direction normal = projectionFrame.getNormal();
        double eyeDot = dot(eye.getX() - origin.getX(), eye.getY() - origin.getY(), eye.getZ() - origin.getZ(), normal);
        double clearance = ProjectorFrameTransform.portalPlaneClearance(area, frame);
        ProjectorPlaneWindow window = ProjectorPlaneWindow.create(aperture, area, projectionFrame,
            origin.getX(), origin.getY(), origin.getZ(), padding, eyeDot);
        Frustum4D frustum = new Frustum4D(eye, aperture, new Frustum4D.Options(geometry.depthBlocks(), geometry.depthBlocks(),
            geometry.nearPlanePadding(), geometry.frustumCullingRatio(), padding));
        AxisAlignedBB region = frustum.getRegion();
        int minX = Math.max(bounds.minX(), ProjectorFrameTransform.minBlockForCenter(region.getXa()));
        int minY = Math.max(bounds.minY(), ProjectorFrameTransform.minBlockForCenter(region.getYa()));
        int minZ = Math.max(bounds.minZ(), ProjectorFrameTransform.minBlockForCenter(region.getZa()));
        int maxX = Math.min(bounds.minX() + bounds.sizeX() - 1, ProjectorFrameTransform.maxBlockForCenter(region.getXb()));
        int maxY = Math.min(bounds.minY() + bounds.sizeY() - 1, ProjectorFrameTransform.maxBlockForCenter(region.getYb()));
        int maxZ = Math.min(bounds.minZ() + bounds.sizeZ() - 1, ProjectorFrameTransform.maxBlockForCenter(region.getZb()));
        LongOpenHashSet expected = new LongOpenHashSet();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double cellDot = dot(x + 0.5D - origin.getX(), y + 0.5D - origin.getY(), z + 0.5D - origin.getZ(), localNormal);
                    if (!ProjectorFrameTransform.projectsBehindPortalPlane(cellDot, geometry.frontSide(), clearance)
                        || Math.abs(cellDot) > geometry.depthBlocks() + clearance) {
                        continue;
                    }
                    double signed = dot(x + 0.5D - origin.getX(), y + 0.5D - origin.getY(), z + 0.5D - origin.getZ(), normal);
                    if (window.containsRayIntersection(eye.getX(), eye.getY(), eye.getZ(), x + 0.5D, y + 0.5D, z + 0.5D, signed)) {
                        expected.add(ProjectionCellKey.pack(x, y, z));
                    }
                }
            }
        }
        return expected;
    }

    @Test
    void theSweepCoversEveryServerClaimAndStaysInsideTheFrustumDepthBox() {
        for (ClientSweepScene scene : List.of(ClientSweepScene.rtpWall(32, 24), ClientSweepScene.floorHatch(32, 24),
            ClientSweepScene.archedDoor(32, 24))) {
            Random random = new Random(0x5EEDL);
            long claims = 0L;
            long applied = 0L;
            List<String> failures = new ArrayList<String>();
            for (int sample = 0; sample < EYES; sample++) {
                GeometryVector eye = randomEye(scene, random);
                boolean frontSide = scene.eyeFrontSide(eye);
                ClientViewSweep sweep = new ClientViewSweep(scene.geometry(frontSide, ClientPortalGeometry.BLACKOUT_OFF),
                    scene.bounds(frontSide), HYSTERESIS);
                sweep.sweep(eye.getX(), eye.getY(), eye.getZ(), 0.0D, 0.0D, 0.0D);
                applied += sweep.appliedCount();
                for (ProjectionRenderMode mode : MODES) {
                    Long2ObjectOpenHashMap<ProjectedBlockClaim<String, ClientSweepScene.ContentView>> server = scene.serverClaims(eye, mode, false);
                    claims += server.size();
                    collectMissing(server, sweep, eye, mode, failures);
                }
                collectOutsideBox(scene, sweep.entered(), eye, frontSide, HYSTERESIS * 2.0D, failures);
            }
            assertTrue(failures.isEmpty(), failures.size() + " parity failures, first: " + failures.subList(0, Math.min(8, failures.size())));
            assertTrue(claims > EYES * 100L, "the scene must produce a meaningful claim set, got " + claims);
            assertTrue(applied >= claims / MODES.length, "the sweep must apply at least the server claims");
        }
    }

    @Test
    void aWalkingEyeWithHysteresisKeepsEveryServerClaimApplied() {
        ClientSweepScene scene = ClientSweepScene.rtpWall(32, 24);
        Random random = new Random(0xBADC0DEL);
        GeometryVector eye = new GeometryVector(6.05D, 65.6D, 0.5D);
        ClientViewSweep sweep = new ClientViewSweep(scene.geometry(true, ClientPortalGeometry.BLACKOUT_OFF), scene.bounds(true), HYSTERESIS);
        List<String> failures = new ArrayList<String>();
        long claims = 0L;
        for (int step = 0; step < 120; step++) {
            double velocityX = (random.nextDouble() - 0.5D) * 0.4D;
            double velocityY = (random.nextDouble() - 0.5D) * 0.1D;
            double velocityZ = (random.nextDouble() - 0.5D) * 0.4D;
            eye = new GeometryVector(clamp(eye.getX() + velocityX, 1.5D, 14.0D), clamp(eye.getY() + velocityY, 63.5D, 67.5D),
                clamp(eye.getZ() + velocityZ, -6.0D, 6.0D));
            sweep.sweep(eye.getX(), eye.getY(), eye.getZ(), velocityX, velocityY, velocityZ);
            GeometryVector lookahead = quantized(eye.getX() + velocityX, eye.getY() + velocityY, eye.getZ() + velocityZ);
            Long2ObjectOpenHashMap<ProjectedBlockClaim<String, ClientSweepScene.ContentView>> server =
                scene.serverClaims(lookahead, ProjectionRenderMode.PANOPTIC, false);
            claims += server.size();
            collectMissing(server, sweep, lookahead, ProjectionRenderMode.PANOPTIC, failures);
        }
        assertTrue(claims > 0L);
        assertTrue(failures.isEmpty(), failures.size() + " walk failures, first: " + failures.subList(0, Math.min(8, failures.size())));
    }

    static GeometryVector randomEye(ClientSweepScene scene, Random random) {
        Direction normal = scene.localFrame.getNormal();
        PortalFrame frame = scene.localFrame;
        double side = random.nextBoolean() ? 1.0D : -1.0D;
        double along = side * (0.3D + (random.nextDouble() * 20.0D));
        double right = (random.nextDouble() - 0.5D) * 24.0D;
        double up = (random.nextDouble() - 0.5D) * 16.0D;
        GeometryVector origin = scene.localOrigin;
        return quantized(origin.getX() + (normal.x() * along) + (frame.getRight().x() * right) + (frame.getUp().x() * up),
            origin.getY() + (normal.y() * along) + (frame.getRight().y() * right) + (frame.getUp().y() * up),
            origin.getZ() + (normal.z() * along) + (frame.getRight().z() * right) + (frame.getUp().z() * up));
    }

    static GeometryVector quantized(double x, double y, double z) {
        return new GeometryVector(Math.round(x * 20.0D) / 20.0D, Math.round(y * 20.0D) / 20.0D, Math.round(z * 20.0D) / 20.0D);
    }

    private static void collectMissing(Long2ObjectOpenHashMap<ProjectedBlockClaim<String, ClientSweepScene.ContentView>> server,
                                       ClientViewSweep sweep, GeometryVector eye, ProjectionRenderMode mode, List<String> failures) {
        for (Long2ObjectMap.Entry<ProjectedBlockClaim<String, ClientSweepScene.ContentView>> entry : server.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            if (!sweep.applied(x, y, z)) {
                failures.add(mode + " eye=" + eye + " missing " + x + "," + y + "," + z + " " + entry.getValue().getData());
            }
        }
    }

    private static void collectOutsideBox(ClientSweepScene scene, LongArrayList cells, GeometryVector eye, boolean frontSide,
                                          double hysteresis, List<String> failures) {
        double padding = ClientSweepScene.APERTURE_PADDING + hysteresis;
        AxisAlignedBB area = scene.aperture.getArea();
        Frustum4D frustum = new Frustum4D(eye, scene.aperture, new Frustum4D.Options(scene.depth, scene.depth,
            ClientSweepScene.NEAR_PLANE_PADDING, ClientSweepScene.CULLING_RATIO, padding));
        AxisAlignedBB region = frustum.getRegion();
        PortalFrame projectionFrame = scene.localFrame.view(frontSide);
        Direction projectionNormal = projectionFrame.getNormal();
        Direction localNormal = scene.localFrame.getNormal();
        GeometryVector origin = scene.localOrigin;
        double eyeDot = dot(eye.getX() - origin.getX(), eye.getY() - origin.getY(), eye.getZ() - origin.getZ(), projectionNormal);
        ProjectorPlaneWindow window = ProjectorPlaneWindow.create(scene.aperture, area, projectionFrame,
            origin.getX(), origin.getY(), origin.getZ(), padding, eyeDot);
        double clearance = ProjectorFrameTransform.portalPlaneClearance(area, scene.localFrame);
        for (int index = 0; index < cells.size(); index++) {
            long key = cells.getLong(index);
            double x = ProjectionCellKey.unpackX(key) + 0.5D;
            double y = ProjectionCellKey.unpackY(key) + 0.5D;
            double z = ProjectionCellKey.unpackZ(key) + 0.5D;
            double cellDot = dot(x - origin.getX(), y - origin.getY(), z - origin.getZ(), localNormal);
            double signed = dot(x - origin.getX(), y - origin.getY(), z - origin.getZ(), projectionNormal);
            boolean inRegion = x >= region.getXa() - 0.5D && x <= region.getXb() + 0.5D
                && y >= region.getYa() - 0.5D && y <= region.getYb() + 0.5D
                && z >= region.getZa() - 0.5D && z <= region.getZb() + 0.5D;
            boolean inDepth = ProjectorFrameTransform.projectsBehindPortalPlane(cellDot, frontSide, clearance)
                && Math.abs(cellDot) <= scene.depth + clearance;
            boolean inWindow = window.containsRayIntersection(eye.getX(), eye.getY(), eye.getZ(), x, y, z, signed);
            if (!inRegion || !inDepth || !inWindow) {
                failures.add("eye=" + eye + " applied outside the frustum-depth box at " + x + "," + y + "," + z
                    + " region=" + inRegion + " depth=" + inDepth + " window=" + inWindow);
            }
        }
    }

    private static double dot(double x, double y, double z, Direction direction) {
        return (x * direction.x()) + (y * direction.y()) + (z * direction.z());
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
