package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.Axis;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MinecraftPortalToolPreview {
    private static final double PREVIEW_RANGE = 32.0D;
    private static final int MAX_OUTLINE_PARTICLES = 96;
    private static final int MAX_FILL_PARTICLES = 32;
    private static final int OUTLINE_SAMPLES_PER_EDGE = 4;
    private static final double SURFACE_OFFSET = 0.04D;
    private static final DustParticleOptions OUTLINE_DUST = new DustParticleOptions(0xFFBE2D, 1.1F);
    private static final DustColorTransitionOptions FILL_BLACK_TO_GOLD = new DustColorTransitionOptions(0x080602, 0xFFB01E, 0.9F);
    private static final DustColorTransitionOptions FILL_GOLD_TO_BLACK = new DustColorTransitionOptions(0xFFB01E, 0x080602, 0.9F);

    private final WormholesModRuntime runtime;
    private final Map<UUID, CachedGeometry> geometryCache = new HashMap<>();
    private long animationFrame;

    MinecraftPortalToolPreview(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    void render(ServerPlayer viewer, List<MinecraftPortal> portals) {
        if (!runtime.configuration().settings().getMain().enableParticles || portals.isEmpty()) {
            return;
        }
        ArrayList<RenderTarget> targets = new ArrayList<>(Math.min(portals.size(), 16));
        for (MinecraftPortal portal : portals) {
            Geometry geometry = geometryFor(portal);
            if (geometry.isEmpty()) {
                continue;
            }
            double distanceSquared = geometry.distanceSquared(viewer.getX(), viewer.getY(), viewer.getZ());
            if (distanceSquared > PREVIEW_RANGE * PREVIEW_RANGE) {
                continue;
            }
            targets.add(new RenderTarget(portal.getId(), geometry, distanceSquared));
        }
        if (targets.isEmpty()) {
            return;
        }
        targets.sort(Comparator.comparingDouble(RenderTarget::distanceSquared));
        long frame = animationFrame++;
        int outlineRemaining = MAX_OUTLINE_PARTICLES;
        int fillRemaining = MAX_FILL_PARTICLES;
        for (int i = 0; i < targets.size() && (outlineRemaining > 0 || fillRemaining > 0); i++) {
            RenderTarget target = targets.get(i);
            int remainingTargets = targets.size() - i;
            outlineRemaining -= emitOutline(viewer, target, fairShare(outlineRemaining, remainingTargets), frame);
            fillRemaining -= emitFill(viewer, target, fairShare(fillRemaining, remainingTargets), frame);
        }
    }

    void retain(List<MinecraftPortal> portals) {
        if (geometryCache.isEmpty()) {
            return;
        }
        HashSet<UUID> live = new HashSet<>(portals.size() * 2);
        for (MinecraftPortal portal : portals) {
            live.add(portal.getId());
        }
        geometryCache.keySet().retainAll(live);
    }

    void clear() {
        geometryCache.clear();
    }

    private Geometry geometryFor(MinecraftPortal portal) {
        long revision = portal.getGeometry().getRevision();
        Axis normalAxis = portal.getFrame().getNormal().getAxis();
        CachedGeometry cached = geometryCache.get(portal.getId());
        if (cached != null && cached.revision() == revision && cached.normalAxis() == normalAxis) {
            return cached.geometry();
        }
        Geometry built = buildGeometry(portal.getGeometry().getBlockPositions(), normalAxis);
        geometryCache.put(portal.getId(), new CachedGeometry(revision, normalAxis, built));
        return built;
    }

    static Geometry buildGeometry(List<GeometryVector> blockPositions, Axis normalAxis) {
        LongOpenHashSet occupied = new LongOpenHashSet(Math.max(16, blockPositions.size() * 2));
        ArrayList<Cell> cells = new ArrayList<>(blockPositions.size());
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (GeometryVector position : blockPositions) {
            int x = position.getBlockX();
            int y = position.getBlockY();
            int z = position.getBlockZ();
            if (!occupied.add(packCell(x, y, z))) {
                continue;
            }
            cells.add(new Cell(x, y, z));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        if (cells.isEmpty()) {
            return new Geometry(normalAxis, List.of(), List.of(), 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        ArrayList<PreviewPoint> outline = new ArrayList<>(Math.max(16, cells.size() * 8));
        for (Cell cell : cells) {
            addCellBoundary(outline, occupied, cell, normalAxis);
        }
        return new Geometry(normalAxis, List.copyOf(outline), List.copyOf(cells),
            minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
    }

    private static void addCellBoundary(List<PreviewPoint> outline, LongOpenHashSet occupied, Cell cell, Axis normalAxis) {
        int x = cell.x();
        int y = cell.y();
        int z = cell.z();
        switch (normalAxis) {
            case X -> {
                if (!occupied.contains(packCell(x, y - 1, z))) {
                    addLine(outline, x + 0.5D, y, z, x + 0.5D, y, z + 1.0D);
                }
                if (!occupied.contains(packCell(x, y + 1, z))) {
                    addLine(outline, x + 0.5D, y + 1.0D, z, x + 0.5D, y + 1.0D, z + 1.0D);
                }
                if (!occupied.contains(packCell(x, y, z - 1))) {
                    addLine(outline, x + 0.5D, y, z, x + 0.5D, y + 1.0D, z);
                }
                if (!occupied.contains(packCell(x, y, z + 1))) {
                    addLine(outline, x + 0.5D, y, z + 1.0D, x + 0.5D, y + 1.0D, z + 1.0D);
                }
            }
            case Y -> {
                if (!occupied.contains(packCell(x - 1, y, z))) {
                    addLine(outline, x, y + 0.5D, z, x, y + 0.5D, z + 1.0D);
                }
                if (!occupied.contains(packCell(x + 1, y, z))) {
                    addLine(outline, x + 1.0D, y + 0.5D, z, x + 1.0D, y + 0.5D, z + 1.0D);
                }
                if (!occupied.contains(packCell(x, y, z - 1))) {
                    addLine(outline, x, y + 0.5D, z, x + 1.0D, y + 0.5D, z);
                }
                if (!occupied.contains(packCell(x, y, z + 1))) {
                    addLine(outline, x, y + 0.5D, z + 1.0D, x + 1.0D, y + 0.5D, z + 1.0D);
                }
            }
            case Z -> {
                if (!occupied.contains(packCell(x - 1, y, z))) {
                    addLine(outline, x, y, z + 0.5D, x, y + 1.0D, z + 0.5D);
                }
                if (!occupied.contains(packCell(x + 1, y, z))) {
                    addLine(outline, x + 1.0D, y, z + 0.5D, x + 1.0D, y + 1.0D, z + 0.5D);
                }
                if (!occupied.contains(packCell(x, y - 1, z))) {
                    addLine(outline, x, y, z + 0.5D, x + 1.0D, y, z + 0.5D);
                }
                if (!occupied.contains(packCell(x, y + 1, z))) {
                    addLine(outline, x, y + 1.0D, z + 0.5D, x + 1.0D, y + 1.0D, z + 0.5D);
                }
            }
        }
    }

    private static void addLine(List<PreviewPoint> points, double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int sample = 0; sample < OUTLINE_SAMPLES_PER_EDGE; sample++) {
            double t = (sample + 0.5D) / OUTLINE_SAMPLES_PER_EDGE;
            points.add(new PreviewPoint(x0 + ((x1 - x0) * t), y0 + ((y1 - y0) * t), z0 + ((z1 - z0) * t)));
        }
    }

    private static int emitOutline(ServerPlayer viewer, RenderTarget target, int budget, long frame) {
        List<PreviewPoint> points = target.geometry().outlinePoints();
        int count = Math.min(Math.max(0, budget), points.size());
        if (count == 0) {
            return 0;
        }
        Axis normalAxis = target.geometry().normalAxis();
        int start = sampleStart(target.portalId(), frame, points.size());
        for (int i = 0; i < count; i++) {
            int index = (start + (int) (((long) i * points.size()) / count)) % points.size();
            PreviewPoint point = points.get(index);
            double offset = viewerSideOffset(viewer, normalAxis, point.x(), point.y(), point.z());
            particle(viewer, OUTLINE_DUST,
                point.x() + (normalAxis == Axis.X ? offset : 0.0D),
                point.y() + (normalAxis == Axis.Y ? offset : 0.0D),
                point.z() + (normalAxis == Axis.Z ? offset : 0.0D));
        }
        return count;
    }

    private static int emitFill(ServerPlayer viewer, RenderTarget target, int budget, long frame) {
        List<Cell> cells = target.geometry().cells();
        int count = Math.min(Math.max(0, budget), cells.size());
        if (count == 0) {
            return 0;
        }
        Axis normalAxis = target.geometry().normalAxis();
        int start = sampleStart(target.portalId(), frame * 3L, cells.size());
        for (int i = 0; i < count; i++) {
            int index = (start + (int) (((long) i * cells.size()) / count)) % cells.size();
            Cell cell = cells.get(index);
            double angle = (frame * 0.47D) + (index * 2.399963229728653D) + (i * 0.71D);
            double first = 0.5D + (Math.cos(angle) * 0.32D);
            double second = 0.5D + (Math.sin(angle * 1.618033988749895D) * 0.32D);
            double x = cell.x() + (normalAxis == Axis.X ? 0.5D : first);
            double y = cell.y() + (normalAxis == Axis.Y ? 0.5D : normalAxis == Axis.X ? first : second);
            double z = cell.z() + (normalAxis == Axis.Z ? 0.5D : second);
            double offset = viewerSideOffset(viewer, normalAxis, x, y, z);
            particle(viewer, ((frame + i) & 1L) == 0L ? FILL_BLACK_TO_GOLD : FILL_GOLD_TO_BLACK,
                x + (normalAxis == Axis.X ? offset : 0.0D),
                y + (normalAxis == Axis.Y ? offset : 0.0D),
                z + (normalAxis == Axis.Z ? offset : 0.0D));
        }
        return count;
    }

    private static void particle(ServerPlayer viewer, ParticleOptions particle, double x, double y, double z) {
        viewer.connection.send(new ClientboundLevelParticlesPacket(particle, false, false, x, y, z, 0.0F, 0.0F, 0.0F, 0.0F, 1));
    }

    private static int fairShare(int remaining, int remainingTargets) {
        if (remaining <= 0 || remainingTargets <= 0) {
            return 0;
        }
        return Math.max(1, remaining / remainingTargets);
    }

    private static int sampleStart(UUID portalId, long frame, int size) {
        long mixed = frame * 0x9E3779B97F4A7C15L;
        mixed ^= portalId.getMostSignificantBits();
        mixed = Long.rotateLeft(mixed, 21) ^ portalId.getLeastSignificantBits();
        return Math.floorMod(mixed, size);
    }

    private static double viewerSideOffset(ServerPlayer viewer, Axis normalAxis, double x, double y, double z) {
        double viewerCoordinate = switch (normalAxis) {
            case X -> viewer.getX();
            case Y -> viewer.getY();
            case Z -> viewer.getZ();
        };
        double planeCoordinate = switch (normalAxis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
        return viewerCoordinate >= planeCoordinate ? SURFACE_OFFSET : -SURFACE_OFFSET;
    }

    private static long packCell(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
    }

    private record CachedGeometry(long revision, Axis normalAxis, Geometry geometry) {
    }

    private record RenderTarget(UUID portalId, Geometry geometry, double distanceSquared) {
    }

    record PreviewPoint(double x, double y, double z) {
    }

    record Cell(int x, int y, int z) {
    }

    record Geometry(Axis normalAxis, List<PreviewPoint> outlinePoints, List<Cell> cells,
                    double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean isEmpty() {
            return cells.isEmpty();
        }

        double distanceSquared(double x, double y, double z) {
            double dx = axisDistance(x, minX, maxX);
            double dy = axisDistance(y, minY, maxY);
            double dz = axisDistance(z, minZ, maxZ);
            return (dx * dx) + (dy * dy) + (dz * dz);
        }

        private static double axisDistance(double coordinate, double min, double max) {
            if (coordinate < min) {
                return min - coordinate;
            }
            if (coordinate > max) {
                return coordinate - max;
            }
            return 0.0D;
        }
    }
}
