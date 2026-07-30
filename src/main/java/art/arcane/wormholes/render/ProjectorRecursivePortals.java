package art.arcane.wormholes.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.bukkit.World;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectorRecursivePortals {
    private static final int BUCKET_SHIFT = 4;
    private static final int MAX_INDEXES_PER_PASS = 256;

    private final HashMap<World, List<ILocalPortal>> candidatesByWorld;
    private final ArrayList<Index> indexes;
    private final double[] scratchRot;
    private Index lastIndex;

    ProjectorRecursivePortals() {
        this.candidatesByWorld = new HashMap<World, List<ILocalPortal>>(4);
        this.indexes = new ArrayList<Index>(4);
        this.scratchRot = new double[3];
        this.lastIndex = null;
    }

    void clear() {
        candidatesByWorld.clear();
        indexes.clear();
        lastIndex = null;
    }

    Index indexFor(World world, double eyeX, double eyeY, double eyeZ, ILocalPortal excludedPortal) {
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

    private List<ILocalPortal> candidates(World world) {
        List<ILocalPortal> cached = candidatesByWorld.get(world);
        if (cached != null) {
            return cached;
        }

        List<ILocalPortal> found = new ArrayList<ILocalPortal>();
        if (Wormholes.portalManager != null) {
            for (ILocalPortal candidate : Wormholes.portalManager.getLocalPortals()) {
                if (!isCandidate(candidate, world)) {
                    continue;
                }
                found.add(candidate);
            }
        }
        candidatesByWorld.put(world, found);
        return found;
    }

    private static boolean isCandidate(ILocalPortal candidate, World world) {
        if (candidate == null || world == null) {
            return false;
        }
        if (!candidate.supportsProjections() || !candidate.isProjecting() || !candidate.isOpen() || candidate.blocksProjection()) {
            return false;
        }
        World candidateWorld = candidate.getWorld();
        if (candidateWorld == null || !candidateWorld.equals(world)) {
            return false;
        }
        return true;
    }

    private static boolean isExcluded(ILocalPortal candidate, ILocalPortal excludedPortal) {
        return candidate != null
            && excludedPortal != null
            && candidate.getId() != null
            && candidate.getId().equals(excludedPortal.getId());
    }

    private static double rayPlaneT(double eyeSignedDistance, double pointSignedDistance) {
        double denominator = pointSignedDistance - eyeSignedDistance;
        if (Math.abs(denominator) < 1.0E-7D) {
            return -1.0D;
        }
        double t = -eyeSignedDistance / denominator;
        return t > 1.0E-7D && t < 1.0D ? t : -1.0D;
    }

    final class Index {
        private final World world;
        private final UUID excludedPortalId;
        private final double eyeX;
        private final double eyeY;
        private final double eyeZ;
        private final Long2ObjectOpenHashMap<ArrayList<Candidate>> buckets;

        private Index(World world, double eyeX, double eyeY, double eyeZ, ILocalPortal excludedPortal) {
            this.world = world;
            this.excludedPortalId = excludedPortal == null ? null : excludedPortal.getId();
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.buckets = new Long2ObjectOpenHashMap<ArrayList<Candidate>>();
            for (ILocalPortal candidate : candidates(world)) {
                if (isExcluded(candidate, excludedPortal)) {
                    continue;
                }
                Candidate indexed = new Candidate(candidate, eyeX, eyeY, eyeZ);
                if (indexed.valid) {
                    index(indexed);
                }
            }
        }

        private boolean matches(World world, double eyeX, double eyeY, double eyeZ, ILocalPortal excludedPortal) {
            UUID candidateExcludedId = excludedPortal == null ? null : excludedPortal.getId();
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

        boolean isEmpty() {
            return buckets.isEmpty();
        }

        Hit find(double pointX, double pointY, double pointZ, int remainingDepth) {
            int bucketX = bucket(pointX);
            int bucketY = bucket(pointY);
            int bucketZ = bucket(pointZ);
            ArrayList<Candidate> bucketCandidates = buckets.get(ProjectionCellKey.pack(bucketX, bucketY, bucketZ));
            if (bucketCandidates == null) {
                return null;
            }
            Hit best = null;
            for (Candidate candidate : bucketCandidates) {
                Hit hit = candidate.hit(pointX, pointY, pointZ, remainingDepth);
                if (hit == null) {
                    continue;
                }
                if (best == null || hit.rayT < best.rayT) {
                    best = hit;
                }
            }
            return best;
        }

        private void index(Candidate candidate) {
            int minX = bucket(candidate.view.getXa());
            int maxX = bucket(candidate.view.getXb());
            int minY = bucket(candidate.view.getYa());
            int maxY = bucket(candidate.view.getYb());
            int minZ = bucket(candidate.view.getZa());
            int maxZ = bucket(candidate.view.getZb());
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        long key = ProjectionCellKey.pack(x, y, z);
                        ArrayList<Candidate> bucketCandidates = buckets.get(key);
                        if (bucketCandidates == null) {
                            bucketCandidates = new ArrayList<Candidate>(2);
                            buckets.put(key, bucketCandidates);
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

    private final class Candidate {
        private final AxisAlignedBB view;
        private final PortalFrame localFrame;
        private final PortalFrame remoteFrame;
        private final World nestedWorld;
        private final ILocalPortal nestedDestination;
        private final ProjectorPlaneWindow planeWindow;
        private final double originX;
        private final double originY;
        private final double originZ;
        private final double remoteOriginX;
        private final double remoteOriginY;
        private final double remoteOriginZ;
        private final double normalX;
        private final double normalY;
        private final double normalZ;
        private final double projectionNormalX;
        private final double projectionNormalY;
        private final double projectionNormalZ;
        private final double transformXX;
        private final double transformXY;
        private final double transformXZ;
        private final double transformYX;
        private final double transformYY;
        private final double transformYZ;
        private final double transformZX;
        private final double transformZY;
        private final double transformZZ;
        private final double transformedEyeX;
        private final double transformedEyeY;
        private final double transformedEyeZ;
        private final double eyeX;
        private final double eyeY;
        private final double eyeZ;
        private final double eyeSignedDistance;
        private final double clearance;
        private final double maxDepth;
        private final boolean eyeFrontSide;
        private final boolean traversable;
        private final boolean mirrorProjection;
        private final int mirrorRotationQuarterTurns;
        private final PortalFrame mirrorFrame;
        private final boolean valid;

        private Candidate(ILocalPortal candidate, double eyeX, double eyeY, double eyeZ) {
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            if (candidate == null || candidate.getOrigin() == null || candidate.getFrame() == null || candidate.getStructure() == null) {
                this.view = null;
                this.localFrame = null;
                this.remoteFrame = null;
                this.nestedWorld = null;
                this.nestedDestination = null;
                this.planeWindow = null;
                this.originX = 0.0D;
                this.originY = 0.0D;
                this.originZ = 0.0D;
                this.remoteOriginX = 0.0D;
                this.remoteOriginY = 0.0D;
                this.remoteOriginZ = 0.0D;
                this.normalX = 0.0D;
                this.normalY = 0.0D;
                this.normalZ = 0.0D;
                this.projectionNormalX = 0.0D;
                this.projectionNormalY = 0.0D;
                this.projectionNormalZ = 0.0D;
                this.transformXX = 0.0D;
                this.transformXY = 0.0D;
                this.transformXZ = 0.0D;
                this.transformYX = 0.0D;
                this.transformYY = 0.0D;
                this.transformYZ = 0.0D;
                this.transformZX = 0.0D;
                this.transformZY = 0.0D;
                this.transformZZ = 0.0D;
                this.transformedEyeX = 0.0D;
                this.transformedEyeY = 0.0D;
                this.transformedEyeZ = 0.0D;
                this.eyeSignedDistance = 0.0D;
                this.clearance = 0.0D;
                this.maxDepth = 0.0D;
                this.eyeFrontSide = false;
                this.traversable = false;
                this.mirrorProjection = false;
                this.mirrorRotationQuarterTurns = 0;
                this.mirrorFrame = null;
                this.valid = false;
                return;
            }

            AxisAlignedBB candidateView = candidate.getView();
            PortalFrame frame = candidate.getFrame();
            double candidateOriginX = candidate.getOrigin().getX();
            double candidateOriginY = candidate.getOrigin().getY();
            double candidateOriginZ = candidate.getOrigin().getZ();
            double frameNormalX = frame.getNormal().x();
            double frameNormalY = frame.getNormal().y();
            double frameNormalZ = frame.getNormal().z();
            double eyeRelX = eyeX - candidateOriginX;
            double eyeRelY = eyeY - candidateOriginY;
            double eyeRelZ = eyeZ - candidateOriginZ;
            boolean frontSide = ((eyeRelX * frameNormalX) + (eyeRelY * frameNormalY) + (eyeRelZ * frameNormalZ)) >= 0.0D;
            PortalFrame candidateLocalFrame = PortalProjector.viewFrame(frame, frontSide);
            double localProjectionNormalX = candidateLocalFrame.getNormal().x();
            double localProjectionNormalY = candidateLocalFrame.getNormal().y();
            double localProjectionNormalZ = candidateLocalFrame.getNormal().z();
            double signedEyeDistance = (eyeRelX * localProjectionNormalX) + (eyeRelY * localProjectionNormalY) + (eyeRelZ * localProjectionNormalZ);
            double candidateClearance = PortalProjector.portalPlaneClearance(candidate.getStructure().getArea(), frame);

            ILocalPortal destination;
            World destinationWorld;
            PortalFrame destinationFrame;
            double destinationOriginX;
            double destinationOriginY;
            double destinationOriginZ;
            boolean canTraverse;
            boolean mirrors;
            int mirrorQuarterTurns;
            ITunnel candidateTunnel = candidate.getTunnel();
            if (candidate.isMirrorMode()) {
                destination = candidate;
                destinationWorld = candidate.getWorld();
                destinationFrame = PortalProjector.viewFrame(frame.flipNormal(), frontSide);
                destinationOriginX = candidateOriginX;
                destinationOriginY = candidateOriginY;
                destinationOriginZ = candidateOriginZ;
                canTraverse = destinationWorld != null;
                mirrors = true;
                mirrorQuarterTurns = candidate.getMirrorRotation().getQuarterTurns();
            } else if (candidateTunnel != null && candidateTunnel.getDestination() instanceof ILocalPortal linkedDestination) {
                destination = linkedDestination;
                destinationWorld = linkedDestination.getWorld();
                destinationFrame = linkedDestination.getFrame() == null ? null : PortalProjector.viewFrame(linkedDestination.getFrame(), frontSide);
                destinationOriginX = linkedDestination.getOrigin() == null ? 0.0D : linkedDestination.getOrigin().getX();
                destinationOriginY = linkedDestination.getOrigin() == null ? 0.0D : linkedDestination.getOrigin().getY();
                destinationOriginZ = linkedDestination.getOrigin() == null ? 0.0D : linkedDestination.getOrigin().getZ();
                canTraverse = destinationWorld != null && destinationFrame != null && linkedDestination.getOrigin() != null;
                mirrors = false;
                mirrorQuarterTurns = 0;
            } else {
                destination = null;
                destinationWorld = null;
                destinationFrame = null;
                destinationOriginX = 0.0D;
                destinationOriginY = 0.0D;
                destinationOriginZ = 0.0D;
                canTraverse = false;
                mirrors = false;
                mirrorQuarterTurns = 0;
            }

            double matrixXX = 0.0D;
            double matrixXY = 0.0D;
            double matrixXZ = 0.0D;
            double matrixYX = 0.0D;
            double matrixYY = 0.0D;
            double matrixYZ = 0.0D;
            double matrixZX = 0.0D;
            double matrixZY = 0.0D;
            double matrixZZ = 0.0D;
            double nestedEyeX = 0.0D;
            double nestedEyeY = 0.0D;
            double nestedEyeZ = 0.0D;
            if (canTraverse) {
                if (mirrors) {
                    double[] matrixScratch = scratchRot;
                    PortalCoordMap.mirrorDisplayToSourceVectorInto(1.0D, 0.0D, 0.0D, frame, mirrorQuarterTurns, matrixScratch);
                    matrixXX = matrixScratch[0];
                    matrixYX = matrixScratch[1];
                    matrixZX = matrixScratch[2];
                    PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 1.0D, 0.0D, frame, mirrorQuarterTurns, matrixScratch);
                    matrixXY = matrixScratch[0];
                    matrixYY = matrixScratch[1];
                    matrixZY = matrixScratch[2];
                    PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 0.0D, 1.0D, frame, mirrorQuarterTurns, matrixScratch);
                    matrixXZ = matrixScratch[0];
                    matrixYZ = matrixScratch[1];
                    matrixZZ = matrixScratch[2];
                } else {
                    int fromRightX = candidateLocalFrame.getRight().x();
                    int fromRightY = candidateLocalFrame.getRight().y();
                    int fromRightZ = candidateLocalFrame.getRight().z();
                    int fromUpX = candidateLocalFrame.getUp().x();
                    int fromUpY = candidateLocalFrame.getUp().y();
                    int fromUpZ = candidateLocalFrame.getUp().z();
                    int fromNormalX = candidateLocalFrame.getNormal().x();
                    int fromNormalY = candidateLocalFrame.getNormal().y();
                    int fromNormalZ = candidateLocalFrame.getNormal().z();
                    int toRightX = destinationFrame.getRight().x();
                    int toRightY = destinationFrame.getRight().y();
                    int toRightZ = destinationFrame.getRight().z();
                    int toUpX = destinationFrame.getUp().x();
                    int toUpY = destinationFrame.getUp().y();
                    int toUpZ = destinationFrame.getUp().z();
                    int toNormalX = destinationFrame.getNormal().x();
                    int toNormalY = destinationFrame.getNormal().y();
                    int toNormalZ = destinationFrame.getNormal().z();

                    matrixXX = (fromRightX * toRightX) + (fromUpX * toUpX) + (fromNormalX * toNormalX);
                    matrixXY = (fromRightY * toRightX) + (fromUpY * toUpX) + (fromNormalY * toNormalX);
                    matrixXZ = (fromRightZ * toRightX) + (fromUpZ * toUpX) + (fromNormalZ * toNormalX);
                    matrixYX = (fromRightX * toRightY) + (fromUpX * toUpY) + (fromNormalX * toNormalY);
                    matrixYY = (fromRightY * toRightY) + (fromUpY * toUpY) + (fromNormalY * toNormalY);
                    matrixYZ = (fromRightZ * toRightY) + (fromUpZ * toUpY) + (fromNormalZ * toNormalY);
                    matrixZX = (fromRightX * toRightZ) + (fromUpX * toUpZ) + (fromNormalX * toNormalZ);
                    matrixZY = (fromRightY * toRightZ) + (fromUpY * toUpZ) + (fromNormalY * toNormalZ);
                    matrixZZ = (fromRightZ * toRightZ) + (fromUpZ * toUpZ) + (fromNormalZ * toNormalZ);
                }
                nestedEyeX = destinationOriginX + (eyeRelX * matrixXX) + (eyeRelY * matrixXY) + (eyeRelZ * matrixXZ);
                nestedEyeY = destinationOriginY + (eyeRelX * matrixYX) + (eyeRelY * matrixYY) + (eyeRelZ * matrixYZ);
                nestedEyeZ = destinationOriginZ + (eyeRelX * matrixZX) + (eyeRelY * matrixZY) + (eyeRelZ * matrixZZ);
            }

            this.view = candidateView;
            this.localFrame = candidateLocalFrame;
            this.remoteFrame = destinationFrame;
            this.nestedWorld = destinationWorld;
            this.nestedDestination = destination;
            this.planeWindow = candidateView == null ? null : ProjectorPlaneWindow.create(candidate.getStructure(), candidate.getStructure().getArea(), candidateLocalFrame,
                candidateOriginX, candidateOriginY, candidateOriginZ, Settings.PROJECTION_APERTURE_PADDING_BLOCKS,
                signedEyeDistance);
            this.originX = candidateOriginX;
            this.originY = candidateOriginY;
            this.originZ = candidateOriginZ;
            this.remoteOriginX = destinationOriginX;
            this.remoteOriginY = destinationOriginY;
            this.remoteOriginZ = destinationOriginZ;
            this.normalX = frameNormalX;
            this.normalY = frameNormalY;
            this.normalZ = frameNormalZ;
            this.projectionNormalX = localProjectionNormalX;
            this.projectionNormalY = localProjectionNormalY;
            this.projectionNormalZ = localProjectionNormalZ;
            this.transformXX = matrixXX;
            this.transformXY = matrixXY;
            this.transformXZ = matrixXZ;
            this.transformYX = matrixYX;
            this.transformYY = matrixYY;
            this.transformYZ = matrixYZ;
            this.transformZX = matrixZX;
            this.transformZY = matrixZY;
            this.transformZZ = matrixZZ;
            this.transformedEyeX = nestedEyeX;
            this.transformedEyeY = nestedEyeY;
            this.transformedEyeZ = nestedEyeZ;
            this.eyeSignedDistance = signedEyeDistance;
            this.clearance = candidateClearance;
            this.maxDepth = Settings.PROJECTION_DEPTH_BLOCKS + candidateClearance;
            this.eyeFrontSide = frontSide;
            this.traversable = canTraverse;
            this.mirrorProjection = mirrors;
            this.mirrorRotationQuarterTurns = mirrorQuarterTurns;
            this.mirrorFrame = mirrors ? frame : null;
            this.valid = candidateView != null && planeWindow != null;
        }

        private Hit hit(double pointX, double pointY, double pointZ, int remainingDepth) {
            if (!valid || !view.containsPrimitive(pointX, pointY, pointZ)) {
                return null;
            }

            double pointRelX = pointX - originX;
            double pointRelY = pointY - originY;
            double pointRelZ = pointZ - originZ;
            double pointDot = (pointRelX * normalX) + (pointRelY * normalY) + (pointRelZ * normalZ);
            if (!PortalProjector.projectsBehindPortalPlane(pointDot, eyeFrontSide, clearance)) {
                return null;
            }
            if (Math.abs(pointDot) > maxDepth) {
                return null;
            }

            double pointSignedDistance = (pointRelX * projectionNormalX) + (pointRelY * projectionNormalY) + (pointRelZ * projectionNormalZ);
            double rayT = rayPlaneT(eyeSignedDistance, pointSignedDistance);
            if (rayT <= 0.0D) {
                return null;
            }
            if (!planeWindow.containsRayIntersection(eyeX, eyeY, eyeZ, pointX, pointY, pointZ, pointSignedDistance)) {
                return null;
            }
            if (!traversable || remainingDepth <= 0) {
                return Hit.mask(rayT, false);
            }

            double nextPointX = remoteOriginX + (pointRelX * transformXX) + (pointRelY * transformXY) + (pointRelZ * transformXZ);
            double nextPointY = remoteOriginY + (pointRelX * transformYX) + (pointRelY * transformYY) + (pointRelZ * transformYZ);
            double nextPointZ = remoteOriginZ + (pointRelX * transformZX) + (pointRelY * transformZY) + (pointRelZ * transformZZ);
            return new Hit(nestedWorld, nestedDestination, localFrame, remoteFrame,
                nextPointX, nextPointY, nextPointZ,
                transformedEyeX, transformedEyeY, transformedEyeZ,
                rayT, true, false, mirrorProjection, mirrorRotationQuarterTurns, mirrorFrame);
        }
    }

    static final class Hit {
        final World world;
        final ILocalPortal destinationPortal;
        final PortalFrame localFrame;
        final PortalFrame remoteFrame;
        final double pointX;
        final double pointY;
        final double pointZ;
        final double eyeX;
        final double eyeY;
        final double eyeZ;
        private final double rayT;
        final boolean traversable;
        final boolean cycle;
        final boolean mirrorProjection;
        final int mirrorRotationQuarterTurns;
        final PortalFrame mirrorFrame;

        private Hit(World world,
                    ILocalPortal destinationPortal,
                    PortalFrame localFrame,
                    PortalFrame remoteFrame,
                    double pointX,
                    double pointY,
                    double pointZ,
                    double eyeX,
                    double eyeY,
                    double eyeZ,
                    double rayT,
                    boolean traversable,
                    boolean cycle,
                    boolean mirrorProjection,
                    int mirrorRotationQuarterTurns,
                    PortalFrame mirrorFrame) {
            this.world = world;
            this.destinationPortal = destinationPortal;
            this.localFrame = localFrame;
            this.remoteFrame = remoteFrame;
            this.pointX = pointX;
            this.pointY = pointY;
            this.pointZ = pointZ;
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.rayT = rayT;
            this.traversable = traversable;
            this.cycle = cycle;
            this.mirrorProjection = mirrorProjection;
            this.mirrorRotationQuarterTurns = mirrorRotationQuarterTurns;
            this.mirrorFrame = mirrorFrame;
        }

        private static Hit mask(double rayT, boolean cycle) {
            return new Hit(null, null, null, null,
                0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D,
                rayT, false, cycle, false, 0, null);
        }
    }
}
