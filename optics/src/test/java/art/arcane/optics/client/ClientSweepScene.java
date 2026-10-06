package art.arcane.optics.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.optics.frame.DirectionMapping;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.claim.ProjectionBlackout;
import art.arcane.optics.view.BlockStates;
import art.arcane.optics.scan.CellScan;
import art.arcane.optics.frame.ProjectorFrameTransform;
import art.arcane.optics.volume.FrustumFit;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.scan.ProjectorSampleMemo;
import art.arcane.optics.scan.ProjectorSampler;
import art.arcane.optics.scan.ScanDestination;
import art.arcane.optics.occlusion.ProjectorViewOcclusion;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.ClientSweepPalette;

public final class ClientSweepScene {
    static final String AIR = "minecraft:air";
    static final String STONE = "minecraft:stone";
    static final String GRASS = "minecraft:grass_block";
    static final String ORE = "minecraft:diamond_ore";
    static final String GLASS = "minecraft:glass";
    static final String LEAVES = "minecraft:oak_leaves";
    static final String LOCAL_WALL = "minecraft:bricks";
    static final String BLACKOUT = "minecraft:black_concrete";
    static final double NEAR_PLANE_PADDING = 2.0D;
    static final double APERTURE_PADDING = 0.75D;
    static final double CULLING_RATIO = 0.2D;
    static final int WORLD_MIN_Y = -64;
    static final int WORLD_MAX_Y = 319;
    private static final Set<String> OCCLUDING = Set.of(STONE, GRASS, ORE, LOCAL_WALL, BLACKOUT);

    final ApertureCells aperture;
    final Frame localFrame;
    final Frame remoteFrame;
    final Vec3 localOrigin;
    final Vec3 remoteOrigin;
    final int depth;
    final int lateral;
    private final SceneView localView;
    private final SceneView destView;
    private final ScanPortal localPortal;
    private final ScanPortal remotePortal;

    ClientSweepScene(ApertureCells aperture, Frame localFrame, Frame remoteFrame, Vec3 remoteOrigin,
                     int depth, int lateral) {
        this.aperture = aperture;
        this.localFrame = localFrame;
        this.remoteFrame = remoteFrame;
        this.localOrigin = aperture.getArea().center();
        this.remoteOrigin = remoteOrigin;
        this.depth = depth;
        this.lateral = lateral;
        this.localView = new SceneView(UUID.fromString("00000000-0000-0000-0000-00000000000a"), ClientSweepScene::localContent);
        this.destView = new SceneView(UUID.fromString("00000000-0000-0000-0000-00000000000b"), ClientSweepScene::destinationContent);
        this.localPortal = new ScanPortal(UUID.fromString("00000000-0000-0000-0000-000000000001"), localOrigin, localFrame);
        this.remotePortal = new ScanPortal(UUID.fromString("00000000-0000-0000-0000-000000000002"), remoteOrigin, remoteFrame);
    }

    static ClientSweepScene rtpWall(int depth, int lateral) {
        return new ClientSweepScene(cuboid(new Box(0.0D, 0.999D, 64.0D, 66.999D, -1.0D, 1.999D)),
            Frame.canonical(Face.E), Frame.canonical(Face.N),
            new Vec3(1000.4995D, 70.4995D, 500.4995D), depth, lateral);
    }

    static ClientSweepScene floorHatch(int depth, int lateral) {
        return new ClientSweepScene(cuboid(new Box(-2.0D, 2.999D, 67.0D, 67.999D, -2.0D, 2.999D)),
            Frame.canonical(Face.U), Frame.canonical(Face.S),
            new Vec3(-300.4995D, 71.4995D, 90.4995D), depth, lateral);
    }

    static ClientSweepScene archedDoor(int depth, int lateral) {
        List<Vec3> cells = new ArrayList<Vec3>();
        for (int y = 64; y <= 68; y++) {
            for (int x = -2; x <= 2; x++) {
                boolean corner = y == 68 && Math.abs(x) == 2;
                boolean mullion = y == 66 && x == 0;
                if (!corner && !mullion) {
                    cells.add(new Vec3(x, y, 4));
                }
            }
        }
        ApertureCells aperture = new ApertureCells();
        aperture.setBlocks(cells);
        return new ClientSweepScene(aperture, Frame.canonical(Face.S), Frame.canonical(Face.W),
            new Vec3(-700.4995D, 69.4995D, 30.4995D), depth, lateral);
    }

    boolean eyeFrontSide(Vec3 eye) {
        Face normal = localFrame.getNormal();
        return ((eye.getX() - localOrigin.getX()) * normal.x()) + ((eye.getY() - localOrigin.getY()) * normal.y())
            + ((eye.getZ() - localOrigin.getZ()) * normal.z()) >= 0.0D;
    }

    Long2ObjectOpenHashMap<ProjectedBlockClaim<String, SceneView>> serverClaims(Vec3 eye, ProjectionRenderMode mode,
                                                                               boolean blackout) {
        StringBlocks blocks = new StringBlocks();
        ProjectorSampleMemo<String, String, SceneView> memo = new ProjectorSampleMemo<String, String, SceneView>(blocks, () -> null);
        RecursiveEndpoints<Object, ScanPortal> recursive = new RecursiveEndpoints<Object, ScanPortal>(new NoPortals(),
            () -> new RecursiveEndpoints.Options(APERTURE_PADDING, depth));
        ProjectorSampler<String, String, Object, ScanPortal, SceneView> sampler = new ProjectorSampler<String, String, Object, ScanPortal, SceneView>(
            new ProjectorSampler.Options<String, String, Object, ScanPortal, SceneView>(memo, recursive, ignored -> null, ignored -> null));
        ProjectionBlackout<String> seal = new Seal(blackout);
        ProjectorViewOcclusion.BlockOcclusion<String> occlusion = OCCLUDING::contains;
        CellScan.ScanSettings settings = new CellScan.ScanSettings(0, 1.0D, APERTURE_PADDING, false, false, 0, true);
        CellScan<String, String, Object, ScanPortal, SceneView> scan = new CellScan<String, String, Object, ScanPortal, SceneView>(
            new CellScan.Context<String, String, Object, ScanPortal, SceneView>(localPortal, aperture, sampler, memo, seal,
                occlusion, () -> settings));
        FrustumFit fit = new FrustumFit(new FrustumFit.Options(0, NEAR_PLANE_PADDING, CULLING_RATIO, APERTURE_PADDING));
        ViewVolume frustum = fit.fit(aperture, localFrame, eye, depth, lateral);
        scan.run(new Destination(), null, eye, frustum, depth, true, false, true, mode.scanMode(), null, false,
            LodPolicy.NONE);
        return new Long2ObjectOpenHashMap<ProjectedBlockClaim<String, SceneView>>(scan.claims());
    }

    ApertureDescriptor geometry(boolean frontSide, int blackoutPolicy) {
        return ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(aperture, localFrame, frontSide, false, 0,
            NEAR_PLANE_PADDING, APERTURE_PADDING, CULLING_RATIO, depth, 0, blackoutPolicy, ClientSweepPalette.BLACKOUT_ID,
            ApertureDescriptor.MASK_AIR_PROJECT, ProjectedBlockClaim.LightingPolicy.SOURCE, 0, ApertureDescriptor.KIND_RTP, 0.0D, 0, 0L,
            List.of())).orElseThrow();
    }

    PlateBox bounds(boolean frontSide) {
        PlateBox plate = plateBox(aperture.getArea(), localFrame, localOrigin, frontSide, depth, lateral, APERTURE_PADDING);
        int minY = Math.max(plate.minY(), WORLD_MIN_Y);
        int maxY = Math.min(plate.minY() + plate.sizeY() - 1, WORLD_MAX_Y);
        return PlateBox.spanning(plate.minX(), minY, plate.minZ(), plate.minX() + plate.sizeX() - 1, maxY,
            plate.minZ() + plate.sizeZ() - 1);
    }

    String destinationAt(boolean frontSide, int x, int y, int z) {
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        transform.configure(localFrame.view(frontSide), remoteFrame.view(frontSide),
            localOrigin.getX(), localOrigin.getY(), localOrigin.getZ(),
            remoteOrigin.getX(), remoteOrigin.getY(), remoteOrigin.getZ());
        double[] remote = new double[3];
        transform.apply(x + 0.5D, y + 0.5D, z + 0.5D, remote);
        return destinationContent((int) Math.floor(remote[0]), (int) Math.floor(remote[1]), (int) Math.floor(remote[2]));
    }

    static String localAt(int x, int y, int z) {
        return localContent(x, y, z);
    }

    static boolean occluding(String state) {
        return OCCLUDING.contains(state);
    }

    public static PlateBox plateBox(Box area, Frame localFrame, Vec3 origin, boolean frontSide,
                             double depthBlocks, double lateralBlocks, double aperturePadding) {
        int[] axisMin = new int[3];
        int[] axisMax = new int[3];
        Frame projectionFrame = localFrame.view(frontSide);
        double clearance = ProjectorFrameTransform.portalPlaneClearance(area, localFrame);
        double maxDepth = depthBlocks + clearance;
        Face normal = localFrame.getNormal();
        int normalAxis = ApertureDescriptor.axisOf(normal);
        double facing = normalAxis == 0 ? normal.x() : normalAxis == 1 ? normal.y() : normal.z();
        double originNormal = normalAxis == 0 ? origin.getX() : normalAxis == 1 ? origin.getY() : origin.getZ();
        double signedMin = frontSide ? -maxDepth : clearance;
        double signedMax = frontSide ? -clearance : maxDepth;
        double centerA = originNormal + (signedMin / facing);
        double centerB = originNormal + (signedMax / facing);
        axisMin[normalAxis] = ProjectorFrameTransform.minBlockForCenter(Math.min(centerA, centerB));
        axisMax[normalAxis] = ProjectorFrameTransform.maxBlockForCenter(Math.max(centerA, centerB));
        double pad = Math.max(0.0D, lateralBlocks) + Math.max(0.0D, aperturePadding);
        for (Face lateralDirection : new Face[] {projectionFrame.getRight(), projectionFrame.getUp()}) {
            int axis = ApertureDescriptor.axisOf(lateralDirection);
            double areaMin = axis == 0 ? area.getXa() : axis == 1 ? area.getYa() : area.getZa();
            double areaMax = axis == 0 ? area.getXb() : axis == 1 ? area.getYb() : area.getZb();
            axisMin[axis] = ProjectorFrameTransform.minBlockForCenter(areaMin - pad);
            axisMax[axis] = ProjectorFrameTransform.maxBlockForCenter(areaMax + pad);
        }
        return PlateBox.spanning(axisMin[0], axisMin[1], axisMin[2], axisMax[0], axisMax[1], axisMax[2]);
    }

    private static ApertureCells cuboid(Box area) {
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(area);
        return aperture;
    }

    private static String localContent(int x, int y, int z) {
        if (y < 63) {
            return STONE;
        }
        if (y > 90) {
            return AIR;
        }
        int hash = hash(x, y, z, 0x51F15EEDL);
        if ((hash & 7) == 0) {
            return LOCAL_WALL;
        }
        if ((hash & 31) == 5) {
            return GLASS;
        }
        return AIR;
    }

    private static String destinationContent(int x, int y, int z) {
        int surface = 66 + (Math.floorMod(hash(x >> 3, 0, z >> 3, 0x7E57L), 7) - 3);
        int hash = hash(x, y, z, 0xD0E5L);
        if (y > surface) {
            if (y <= surface + 5 && (hash & 15) == 3) {
                return LEAVES;
            }
            if (y <= surface + 3 && (hash & 63) == 9) {
                return GLASS;
            }
            return AIR;
        }
        if (y == surface) {
            return GRASS;
        }
        if ((hash % 13) == 0) {
            return AIR;
        }
        if ((hash & 31) == 7) {
            return ORE;
        }
        return STONE;
    }

    private static int hash(int x, int y, int z, long salt) {
        long h = salt;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 23) ^ (y * 0xC2B2AE3D27D4EB4FL);
        h = Long.rotateLeft(h, 29) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (int) (h & 0x7FFFFFFF);
    }

    public static final class SceneView implements ContentView<String, String> {
        private final UUID worldId;
        private final CellContent content;

        public SceneView(UUID worldId, CellContent content) {
            this.worldId = worldId;
            this.content = content;
        }

        @Override
        public UUID worldId() {
            return worldId;
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            return "minecraft:plains";
        }

        @Override
        public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
            return null;
        }

        @Override
        public int getLight(int x, int y, int z) {
            return ContentView.packLight(15, 0);
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }

        @Override
        public String sampleMaterial(int x, int y, int z) {
            return sampleBlockData(x, y, z);
        }

        @Override
        public int getMinHeight() {
            return WORLD_MIN_Y;
        }

        @Override
        public int getMaxHeight() {
            return WORLD_MAX_Y + 1;
        }

        @Override
        public String sampleBlockData(int x, int y, int z) {
            return content.at(x, y, z);
        }

        @Override
        public boolean isChunkReady(int x, int z) {
            return true;
        }

        @Override
        public void requestChunk(int x, int z) {
        }

        @Override
        public long getRevision() {
            return 0L;
        }
    }

    public interface CellContent {
        String at(int x, int y, int z);
    }

    public static final class StringBlocks implements BlockStates<String, String> {
        private static final String OCCLUDED_STAND_IN = "wormholes:occluded";

        @Override
        public String air() {
            return AIR;
        }

        @Override
        public String occluded() {
            return OCCLUDED_STAND_IN;
        }

        @Override
        public boolean isOccluded(String block) {
            return OCCLUDED_STAND_IN.equals(block);
        }

        @Override
        public String material(String block) {
            return block;
        }

        @Override
        public String materialName(String material) {
            return material;
        }

        @Override
        public boolean blockEntityCandidate(String material) {
            return false;
        }

        @Override
        public boolean isAir(String material) {
            return AIR.equals(material);
        }

        @Override
        public boolean isOccluding(String material) {
            return OCCLUDING.contains(material);
        }

        @Override
        public boolean requiresTransform(String block) {
            return false;
        }

        @Override
        public String transform(String block, DirectionMapping mapping) {
            return block;
        }
    }

    private record Seal(boolean enabled) implements ProjectionBlackout<String> {
        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public String data() {
            return BLACKOUT;
        }
    }

    static final class ScanPortal implements IPortal {
        private final UUID id;
        private final Vec3 origin;
        private final Frame frame;
        private String name;

        private ScanPortal(UUID id, Vec3 origin, Frame frame) {
            this.id = id;
            this.origin = origin;
            this.frame = frame;
            this.name = id.toString();
        }

        @Override
        public Face getDirection() {
            return frame.getNormal();
        }

        @Override
        public Frame getFrame() {
            return frame;
        }

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setName(String name) {
            this.name = name;
        }

        @Override
        public boolean isRemote() {
            return false;
        }

        @Override
        public Vec3 getOrigin() {
            return origin;
        }
    }

    private static final class NoPortals implements RecursiveEndpoints.PortalAccess<Object, ScanPortal> {
        @Override
        public List<ScanPortal> portals() {
            return List.of();
        }

        @Override
        public Object world(ScanPortal portal) {
            return null;
        }

        @Override
        public CellAperture structure(ScanPortal portal) {
            return null;
        }

        @Override
        public Box view(ScanPortal portal) {
            return null;
        }

        @Override
        public boolean eligible(ScanPortal portal) {
            return false;
        }

        @Override
        public boolean mirror(ScanPortal portal) {
            return false;
        }

        @Override
        public int mirrorQuarterTurns(ScanPortal portal) {
            return 0;
        }

        @Override
        public ScanPortal destination(ScanPortal portal) {
            return null;
        }
    }

    private final class Destination implements ScanDestination<ScanPortal, SceneView> {
        @Override
        public SceneView localView() {
            return localView;
        }

        @Override
        public SceneView destView() {
            return destView;
        }

        @Override
        public ScanPortal dest() {
            return remotePortal;
        }

        @Override
        public IPortal destAnchor() {
            return remotePortal;
        }

        @Override
        public double originX() {
            return remoteOrigin.getX();
        }

        @Override
        public double originY() {
            return remoteOrigin.getY();
        }

        @Override
        public double originZ() {
            return remoteOrigin.getZ();
        }

        @Override
        public boolean mirrorMode() {
            return false;
        }

        @Override
        public int mirrorRotationQuarterTurns() {
            return 0;
        }
    }
}
