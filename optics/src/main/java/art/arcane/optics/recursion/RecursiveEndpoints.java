package art.arcane.optics.recursion;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.aperture.Endpoint;
import art.arcane.optics.aperture.EndpointDirectory;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.ProjectorPassRevision;
import art.arcane.optics.volume.PlaneWindow;
import art.arcane.optics.volume.ProjectionVolume;

public final class RecursiveEndpoints<W, P extends Endpoint> {
    private static final int BUCKET_SHIFT = 4;
    private static final int MAX_INDEXES_PER_PASS = 256;
    private static final int MAX_RETAINED_INDEXES = 16;
    private static final double CLIP_MARGIN = 1.0E-4D;
    private static final double CLIP_SLOPE_EPSILON = 1.0E-12D;

    private final EndpointDirectory<W, P> directory;
    private final Supplier<Options> options;
    private final HashMap<W, List<P>> candidatesByWorld;
    private final ArrayList<Index> indexes;
    private final double[] scratchRot;
    private final Index emptyIndex;
    private final Hit<W, P> maskHit;
    private Index lastIndex;
    private long portalSignature;

    public RecursiveEndpoints(EndpointDirectory<W, P> directory, Supplier<Options> options) {
        this.directory = directory;
        this.options = options;
        this.candidatesByWorld = new HashMap<W, List<P>>(4);
        this.indexes = new ArrayList<Index>(4);
        this.scratchRot = new double[3];
        this.emptyIndex = new Index();
        this.maskHit = Hit.mask(1.0D, false);
        this.lastIndex = null;
        this.portalSignature = 0L;
    }

    public void clear() {
        candidatesByWorld.clear();
        indexes.clear();
        lastIndex = null;
    }

    public void revalidate() {
        long signature = portalSignature();
        if (signature != portalSignature || indexes.size() > MAX_RETAINED_INDEXES) {
            clear();
            portalSignature = signature;
        }
    }

    private long portalSignature() {
        Options current = options.get();
        long hash = ProjectorPassRevision.mix(0x9E3779B97F4A7C15L, Double.doubleToLongBits(current.aperturePadding()));
        hash = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(current.depthBlocks()));
        List<P> portals = directory.endpoints();
        hash = ProjectorPassRevision.mix(hash, portals.size());
        for (P portal : portals) {
            hash = mixPortal(hash, portal);
        }
        return hash == 0L ? 1L : hash;
    }

    private long mixPortal(long hash, P portal) {
        if (portal == null) {
            return ProjectorPassRevision.mix(hash, 0L);
        }
        long mixed = mixIdentity(hash, portal);
        mixed = ProjectorPassRevision.mix(mixed, directory.eligible(portal) ? 1L : 2L);
        W world = directory.world(portal);
        mixed = ProjectorPassRevision.mix(mixed, world == null ? 0L : System.identityHashCode(world));
        CellAperture structure = directory.aperture(portal);
        if (structure != null) {
            mixed = ProjectorPassRevision.mix(mixed, structure.getRevision());
            mixed = mixBox(mixed, structure.getArea());
        }
        mixed = mixBox(mixed, directory.view(portal));
        boolean mirror = directory.mirror(portal);
        mixed = ProjectorPassRevision.mix(mixed, mirror ? 1L + directory.mirrorTurns(portal).getQuarterTurns() : 0L);
        P destination = directory.destination(portal);
        if (destination == null) {
            return ProjectorPassRevision.mix(mixed, 0L);
        }
        mixed = mixIdentity(mixed, destination);
        W destinationWorld = directory.world(destination);
        return ProjectorPassRevision.mix(mixed, destinationWorld == null ? 0L : System.identityHashCode(destinationWorld));
    }

    private static long mixIdentity(long hash, Endpoint portal) {
        UUID id = portal.id();
        long mixed = ProjectorPassRevision.mix(hash, id == null ? 0L : id.getMostSignificantBits());
        mixed = ProjectorPassRevision.mix(mixed, id == null ? 0L : id.getLeastSignificantBits());
        Vec3d origin = portal.origin();
        if (origin != null) {
            mixed = ProjectorPassRevision.mix(mixed, Double.doubleToLongBits(origin.getX()));
            mixed = ProjectorPassRevision.mix(mixed, Double.doubleToLongBits(origin.getY()));
            mixed = ProjectorPassRevision.mix(mixed, Double.doubleToLongBits(origin.getZ()));
        }
        Frame frame = portal.frame();
        return ProjectorPassRevision.mix(mixed, frame == null ? -1L
            : frame.getNormal().ordinal() | (frame.getRight().ordinal() << 3) | (frame.getUp().ordinal() << 6));
    }

    private static long mixBox(long hash, Box box) {
        if (box == null) {
            return ProjectorPassRevision.mix(hash, -1L);
        }
        long mixed = ProjectorPassRevision.mix(hash, Double.doubleToLongBits(box.getXa()));
        mixed = ProjectorPassRevision.mix(mixed, Double.doubleToLongBits(box.getXb()));
        mixed = ProjectorPassRevision.mix(mixed, Double.doubleToLongBits(box.getYa()));
        mixed = ProjectorPassRevision.mix(mixed, Double.doubleToLongBits(box.getYb()));
        mixed = ProjectorPassRevision.mix(mixed, Double.doubleToLongBits(box.getZa()));
        return ProjectorPassRevision.mix(mixed, Double.doubleToLongBits(box.getZb()));
    }

    public Index indexFor(W world, double eyeX, double eyeY, double eyeZ, P excludedPortal) {
        Index memo = lastIndex;
        if (memo != null && memo.matches(world, eyeX, eyeY, eyeZ, excludedPortal)) {
            return memo;
        }
        for (Index index : indexes) {
            if (index.matches(world, eyeX, eyeY, eyeZ, excludedPortal)) {
                lastIndex = index;
                return index;
            }
        }
        if (indexes.size() >= MAX_INDEXES_PER_PASS) {
            indexes.clear();
        }
        Index created = new Index(world, eyeX, eyeY, eyeZ, excludedPortal);
        indexes.add(created);
        lastIndex = created;
        return created;
    }

    public Index emptyIndex() {
        return emptyIndex;
    }

    public boolean reaches(W world, P excludedPortal, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (world == null) {
            return false;
        }
        for (P candidate : candidates(world)) {
            if (isExcluded(candidate, excludedPortal)) {
                continue;
            }
            if (overlaps(directory.view(candidate), minX, minY, minZ, maxX, maxY, maxZ)) {
                return true;
            }
        }
        return false;
    }

    private List<P> candidates(W world) {
        List<P> cached = candidatesByWorld.get(world);
        if (cached != null) {
            return cached;
        }

        List<P> found = new ArrayList<P>();
        for (P candidate : directory.endpoints()) {
            if (!isCandidate(candidate, world)) {
                continue;
            }
            found.add(candidate);
        }
        candidatesByWorld.put(world, found);
        return found;
    }

    private boolean isCandidate(P candidate, W world) {
        if (candidate == null || world == null) {
            return false;
        }
        if (!directory.eligible(candidate)) {
            return false;
        }
        W candidateWorld = directory.world(candidate);
        if (candidateWorld == null || !candidateWorld.equals(world)) {
            return false;
        }
        return true;
    }

    private boolean isExcluded(P candidate, P excludedPortal) {
        return candidate != null
            && excludedPortal != null
            && candidate.id() != null
            && candidate.id().equals(excludedPortal.id());
    }

    private static boolean overlaps(Box view, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return view != null
            && view.getXb() >= minX && view.getXa() <= maxX
            && view.getYb() >= minY && view.getYa() <= maxY
            && view.getZb() >= minZ && view.getZa() <= maxZ;
    }

    public static boolean clipLinear(double constant, double slope, double[] range) {
        if (slope > CLIP_SLOPE_EPSILON) {
            range[0] = Math.max(range[0], -constant / slope);
        } else if (slope < -CLIP_SLOPE_EPSILON) {
            range[1] = Math.min(range[1], -constant / slope);
        } else if (constant < -CLIP_MARGIN) {
            return false;
        }
        return range[0] <= range[1];
    }

    private static boolean clipAxis(double base, double direction, double low, double high, double[] range) {
        return clipLinear(base - low + CLIP_MARGIN, direction, range)
            && clipLinear(high + CLIP_MARGIN - base, -direction, range);
    }

    private static double rayPlaneT(double eyeSignedDistance, double pointSignedDistance) {
        double denominator = pointSignedDistance - eyeSignedDistance;
        if (Math.abs(denominator) < 1.0E-7D) {
            return -1.0D;
        }
        double t = -eyeSignedDistance / denominator;
        return t > 1.0E-7D && t < 1.0D ? t : -1.0D;
    }

    public final class Index {
        private final W world;
        private final UUID excludedPortalId;
        private final double eyeX;
        private final double eyeY;
        private final double eyeZ;
        private final ArrayList<Candidate> paths;
        private Long2ObjectOpenHashMap<ArrayList<Candidate>> buckets;

        private Index() {
            this.world = null;
            this.excludedPortalId = null;
            this.eyeX = Double.NaN;
            this.eyeY = Double.NaN;
            this.eyeZ = Double.NaN;
            this.paths = new ArrayList<Candidate>(0);
            this.buckets = null;
        }

        private Index(W world, double eyeX, double eyeY, double eyeZ, P excludedPortal) {
            this.world = world;
            this.excludedPortalId = excludedPortal == null ? null : excludedPortal.id();
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.paths = new ArrayList<Candidate>();
            this.buckets = null;
            for (P candidate : candidates(world)) {
                if (isExcluded(candidate, excludedPortal)) {
                    continue;
                }
                Candidate indexed = new Candidate(candidate, eyeX, eyeY, eyeZ);
                if (indexed.valid) {
                    paths.add(indexed);
                }
            }
        }

        private boolean matches(W world, double eyeX, double eyeY, double eyeZ, P excludedPortal) {
            UUID candidateExcludedId = excludedPortal == null ? null : excludedPortal.id();
            if (this.world == null ? world != null : !this.world.equals(world)) {
                return false;
            }
            if (excludedPortalId == null ? candidateExcludedId != null : !excludedPortalId.equals(candidateExcludedId)) {
                return false;
            }
            return Double.compare(this.eyeX, eyeX) == 0
                && Double.compare(this.eyeY, eyeY) == 0
                && Double.compare(this.eyeZ, eyeZ) == 0;
        }

        public List<Candidate> paths() {
            return paths;
        }

        public boolean isEmpty() {
            return paths.isEmpty();
        }

        public Hit<W, P> maskHit() {
            return maskHit;
        }

        public Reach reach(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                           int remainingDepth, List<Candidate> maskCandidates) {
            maskCandidates.clear();
            for (Candidate candidate : paths) {
                if (!overlaps(candidate.view, minX, minY, minZ, maxX, maxY, maxZ)) {
                    continue;
                }
                if (candidate.traversable && remainingDepth > 0) {
                    maskCandidates.clear();
                    return Reach.RECURSIVE;
                }
                maskCandidates.add(candidate);
            }
            return maskCandidates.isEmpty() ? Reach.NONE : Reach.MASK;
        }

        public Hit<W, P> find(double pointX, double pointY, double pointZ, int remainingDepth) {
            return find(pointX, pointY, pointZ, remainingDepth, null);
        }

        public Hit<W, P> find(double pointX, double pointY, double pointZ, int remainingDepth, RecursionPath visited) {
            if (paths.isEmpty()) {
                return null;
            }
            ArrayList<Candidate> bucketCandidates = buckets().get(CellKeys.pack(bucket(pointX), bucket(pointY), bucket(pointZ)));
            if (bucketCandidates == null) {
                return null;
            }
            Hit<W, P> best = null;
            for (Candidate candidate : bucketCandidates) {
                Hit<W, P> hit = candidate.hit(pointX, pointY, pointZ, remainingDepth, visited);
                if (hit == null) {
                    continue;
                }
                if (best == null || hit.rayT < best.rayT) {
                    best = hit;
                }
            }
            return best;
        }

        private Long2ObjectOpenHashMap<ArrayList<Candidate>> buckets() {
            Long2ObjectOpenHashMap<ArrayList<Candidate>> built = buckets;
            if (built != null) {
                return built;
            }
            built = new Long2ObjectOpenHashMap<ArrayList<Candidate>>();
            for (Candidate candidate : paths) {
                index(built, candidate);
            }
            buckets = built;
            return built;
        }

        private void index(Long2ObjectOpenHashMap<ArrayList<Candidate>> target, Candidate candidate) {
            int minX = bucket(candidate.view.getXa());
            int maxX = bucket(candidate.view.getXb());
            int minY = bucket(candidate.view.getYa());
            int maxY = bucket(candidate.view.getYb());
            int minZ = bucket(candidate.view.getZa());
            int maxZ = bucket(candidate.view.getZb());
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        long key = CellKeys.pack(x, y, z);
                        ArrayList<Candidate> bucketCandidates = target.get(key);
                        if (bucketCandidates == null) {
                            bucketCandidates = new ArrayList<Candidate>(2);
                            target.put(key, bucketCandidates);
                        }
                        bucketCandidates.add(candidate);
                    }
                }
            }
        }

        private int bucket(double coordinate) {
            return ((int) Math.floor(coordinate)) >> BUCKET_SHIFT;
        }
    }

    public final class Candidate {
        public final UUID portalId;
        public final Box view;
        public final W nestedWorld;
        public final P nestedDestination;
        private final PlaneWindow planeWindow;
        private final double originX;
        private final double originY;
        private final double originZ;
        private final double normalX;
        private final double normalY;
        private final double normalZ;
        private final double projectionNormalX;
        private final double projectionNormalY;
        private final double projectionNormalZ;
        private final OpticTransform toward;
        private final OpticTransform transform;
        public final double transformedEyeX;
        public final double transformedEyeY;
        public final double transformedEyeZ;
        private final double eyeX;
        private final double eyeY;
        private final double eyeZ;
        private final double eyeSignedDistance;
        private final double clearance;
        private final double maxDepth;
        private final boolean eyeFrontSide;
        public final boolean traversable;
        private final boolean valid;

        private Candidate(P candidate, double eyeX, double eyeY, double eyeZ) {
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.portalId = candidate == null ? null : candidate.id();
            if (candidate == null || candidate.origin() == null || candidate.frame() == null || directory.aperture(candidate) == null) {
                this.view = null;
                this.nestedWorld = null;
                this.nestedDestination = null;
                this.planeWindow = null;
                this.originX = 0.0D;
                this.originY = 0.0D;
                this.originZ = 0.0D;
                this.normalX = 0.0D;
                this.normalY = 0.0D;
                this.normalZ = 0.0D;
                this.projectionNormalX = 0.0D;
                this.projectionNormalY = 0.0D;
                this.projectionNormalZ = 0.0D;
                this.toward = null;
                this.transform = null;
                this.transformedEyeX = 0.0D;
                this.transformedEyeY = 0.0D;
                this.transformedEyeZ = 0.0D;
                this.eyeSignedDistance = 0.0D;
                this.clearance = 0.0D;
                this.maxDepth = 0.0D;
                this.eyeFrontSide = false;
                this.traversable = false;
                this.valid = false;
                return;
            }

            Box candidateView = directory.view(candidate);
            Frame frame = candidate.frame();
            double candidateOriginX = candidate.origin().getX();
            double candidateOriginY = candidate.origin().getY();
            double candidateOriginZ = candidate.origin().getZ();
            double frameNormalX = frame.getNormal().x();
            double frameNormalY = frame.getNormal().y();
            double frameNormalZ = frame.getNormal().z();
            double eyeRelX = eyeX - candidateOriginX;
            double eyeRelY = eyeY - candidateOriginY;
            double eyeRelZ = eyeZ - candidateOriginZ;
            boolean frontSide = ((eyeRelX * frameNormalX) + (eyeRelY * frameNormalY) + (eyeRelZ * frameNormalZ)) >= 0.0D;
            Frame candidateLocalFrame = frame.view(frontSide);
            double localProjectionNormalX = candidateLocalFrame.getNormal().x();
            double localProjectionNormalY = candidateLocalFrame.getNormal().y();
            double localProjectionNormalZ = candidateLocalFrame.getNormal().z();
            double signedEyeDistance = (eyeRelX * localProjectionNormalX) + (eyeRelY * localProjectionNormalY) + (eyeRelZ * localProjectionNormalZ);
            double candidateClearance = ProjectionVolume.portalPlaneClearance(directory.aperture(candidate).getArea(), frame);

            boolean mirror = directory.mirror(candidate);
            P destination = mirror ? candidate : directory.destination(candidate);
            W destinationWorld = destination == null ? null : directory.world(destination);
            OpticTransform stepToward = destinationWorld == null || destination.frame() == null || destination.origin() == null ? null
                : ViewWindow.of(mirror, mirror ? directory.mirrorTurns(candidate) : QuarterTurn.DEGREES_0, candidate.origin(), frame,
                    destination.origin(), destination.frame(), frontSide, 0.0D).toward();
            boolean canTraverse = stepToward != null;
            double nestedEyeX = 0.0D;
            double nestedEyeY = 0.0D;
            double nestedEyeZ = 0.0D;
            if (canTraverse) {
                stepToward.pointInto(eyeX, eyeY, eyeZ, scratchRot);
                nestedEyeX = scratchRot[0];
                nestedEyeY = scratchRot[1];
                nestedEyeZ = scratchRot[2];
            }

            this.view = candidateView;
            this.nestedWorld = destinationWorld;
            this.nestedDestination = destination;
            this.planeWindow = candidateView == null ? null : PlaneWindow.create(directory.aperture(candidate), directory.aperture(candidate).getArea(), candidateLocalFrame,
                candidateOriginX, candidateOriginY, candidateOriginZ, options.get().aperturePadding(),
                signedEyeDistance);
            this.originX = candidateOriginX;
            this.originY = candidateOriginY;
            this.originZ = candidateOriginZ;
            this.normalX = frameNormalX;
            this.normalY = frameNormalY;
            this.normalZ = frameNormalZ;
            this.projectionNormalX = localProjectionNormalX;
            this.projectionNormalY = localProjectionNormalY;
            this.projectionNormalZ = localProjectionNormalZ;
            this.toward = stepToward;
            this.transform = stepToward == null ? null : stepToward.inverse();
            this.transformedEyeX = nestedEyeX;
            this.transformedEyeY = nestedEyeY;
            this.transformedEyeZ = nestedEyeZ;
            this.eyeSignedDistance = signedEyeDistance;
            this.clearance = candidateClearance;
            this.maxDepth = options.get().depthBlocks() + candidateClearance;
            this.eyeFrontSide = frontSide;
            this.traversable = canTraverse;
            this.valid = candidateView != null && planeWindow != null;
        }

        public OpticTransform transform() {
            return transform;
        }

        public boolean covers(double pointX, double pointY, double pointZ) {
            return rayT(pointX, pointY, pointZ) > 0.0D;
        }

        public boolean clipLine(double baseX, double baseY, double baseZ,
                                double directionX, double directionY, double directionZ, double[] range) {
            if (!valid
                || !clipAxis(baseX, directionX, view.getXa(), view.getXb(), range)
                || !clipAxis(baseY, directionY, view.getYa(), view.getYb(), range)
                || !clipAxis(baseZ, directionZ, view.getZa(), view.getZb(), range)) {
                return false;
            }
            double signedBase = ((baseX - originX) * projectionNormalX) + ((baseY - originY) * projectionNormalY)
                + ((baseZ - originZ) * projectionNormalZ);
            double signedSlope = (directionX * projectionNormalX) + (directionY * projectionNormalY) + (directionZ * projectionNormalZ);
            return clipLinear(signedBase + maxDepth + CLIP_MARGIN, signedSlope, range)
                && clipLinear(CLIP_MARGIN - clearance - signedBase, -signedSlope, range)
                && planeWindow.clipRay(eyeX, eyeY, eyeZ, baseX, baseY, baseZ, directionX, directionY, directionZ,
                    signedBase, signedSlope, CLIP_MARGIN, range);
        }

        private double rayT(double pointX, double pointY, double pointZ) {
            if (!valid || !view.containsPrimitive(pointX, pointY, pointZ)) {
                return -1.0D;
            }
            double pointRelX = pointX - originX;
            double pointRelY = pointY - originY;
            double pointRelZ = pointZ - originZ;
            double pointDot = (pointRelX * normalX) + (pointRelY * normalY) + (pointRelZ * normalZ);
            if (!ProjectionVolume.projectsBehindPortalPlane(pointDot, eyeFrontSide, clearance)) {
                return -1.0D;
            }
            if (Math.abs(pointDot) > maxDepth) {
                return -1.0D;
            }
            double pointSignedDistance = (pointRelX * projectionNormalX) + (pointRelY * projectionNormalY) + (pointRelZ * projectionNormalZ);
            double rayT = rayPlaneT(eyeSignedDistance, pointSignedDistance);
            if (rayT <= 0.0D) {
                return -1.0D;
            }
            if (!planeWindow.containsRayIntersection(eyeX, eyeY, eyeZ, pointX, pointY, pointZ, pointSignedDistance)) {
                return -1.0D;
            }
            return rayT;
        }

        private Hit<W, P> hit(double pointX, double pointY, double pointZ, int remainingDepth, RecursionPath visited) {
            double rayT = rayT(pointX, pointY, pointZ);
            if (rayT <= 0.0D) {
                return null;
            }
            if (visited != null && visited.contains(portalId)) {
                return Hit.mask(rayT, true);
            }
            if (!traversable || remainingDepth <= 0) {
                return Hit.mask(rayT, false);
            }

            double[] next = new double[3];
            toward.pointInto(pointX, pointY, pointZ, next);
            return new Hit<>(portalId, nestedWorld, nestedDestination, transform, next[0], next[1], next[2],
                transformedEyeX, transformedEyeY, transformedEyeZ, rayT, true, false);
        }
    }

    /** Portal ids already entered by the sample being resolved, newest last. */
    public static final class RecursionPath {
        private static final int CAPACITY = 64;

        private final UUID[] ids = new UUID[CAPACITY];
        private int size;

        public void clear() {
            for (int index = 0; index < Math.min(size, CAPACITY); index++) {
                ids[index] = null;
            }
            size = 0;
        }

        public boolean contains(UUID portalId) {
            if (portalId == null) {
                return false;
            }
            for (int index = 0; index < Math.min(size, CAPACITY); index++) {
                if (portalId.equals(ids[index])) {
                    return true;
                }
            }
            return false;
        }

        public void push(UUID portalId) {
            if (size < CAPACITY) {
                ids[size] = portalId;
            }
            size++;
        }

        public void pop() {
            if (size <= 0) {
                return;
            }
            size--;
            if (size < CAPACITY) {
                ids[size] = null;
            }
        }
    }

    public static final class Hit<W, P> {
        public final UUID portalId;
        public final W world;
        public final P destinationPortal;
        public final OpticTransform transform;
        public final double pointX;
        public final double pointY;
        public final double pointZ;
        public final double eyeX;
        public final double eyeY;
        public final double eyeZ;
        private final double rayT;
        public final boolean traversable;
        public final boolean cycle;

        private Hit(UUID portalId,
                    W world,
                    P destinationPortal,
                    OpticTransform transform,
                    double pointX,
                    double pointY,
                    double pointZ,
                    double eyeX,
                    double eyeY,
                    double eyeZ,
                    double rayT,
                    boolean traversable,
                    boolean cycle) {
            this.portalId = portalId;
            this.world = world;
            this.destinationPortal = destinationPortal;
            this.transform = transform;
            this.pointX = pointX;
            this.pointY = pointY;
            this.pointZ = pointZ;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.rayT = rayT;
            this.traversable = traversable;
            this.cycle = cycle;
        }

        private static <W, P> Hit<W, P> mask(double rayT, boolean cycle) {
            return new Hit<>(null, null, null, null, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, rayT, false, cycle);
        }
    }

    public record Options(double aperturePadding, double depthBlocks) {
    }

    public enum Reach {
        NONE,
        MASK,
        RECURSIVE
    }
}
