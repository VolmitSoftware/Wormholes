package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.ToolPreviewGeometry;
import art.arcane.wormholes.portal.ToolPreviewGeometry.Cell;
import art.arcane.wormholes.portal.ToolPreviewGeometry.Geometry;
import art.arcane.wormholes.portal.ToolPreviewGeometry.PreviewPoint;

import art.arcane.optics.math.Axis;
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
            if (distanceSquared > ToolPreviewGeometry.PREVIEW_RANGE * ToolPreviewGeometry.PREVIEW_RANGE) {
                continue;
            }
            targets.add(new RenderTarget(portal.getId(), geometry, distanceSquared));
        }
        if (targets.isEmpty()) {
            return;
        }
        targets.sort(Comparator.comparingDouble(RenderTarget::distanceSquared));
        long frame = animationFrame++;
        int outlineRemaining = ToolPreviewGeometry.MAX_OUTLINE_PARTICLES;
        int fillRemaining = ToolPreviewGeometry.MAX_FILL_PARTICLES;
        for (int i = 0; i < targets.size() && (outlineRemaining > 0 || fillRemaining > 0); i++) {
            RenderTarget target = targets.get(i);
            int remainingTargets = targets.size() - i;
            outlineRemaining -= emitOutline(viewer, target, ToolPreviewGeometry.fairShare(outlineRemaining, remainingTargets), frame);
            fillRemaining -= emitFill(viewer, target, ToolPreviewGeometry.fairShare(fillRemaining, remainingTargets), frame);
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
        Geometry built = ToolPreviewGeometry.build(portal.getGeometry().getBlockPositions(), normalAxis);
        geometryCache.put(portal.getId(), new CachedGeometry(revision, normalAxis, built));
        return built;
    }

    private static int emitOutline(ServerPlayer viewer, RenderTarget target, int budget, long frame) {
        List<PreviewPoint> points = target.geometry().outlinePoints();
        int count = Math.min(Math.max(0, budget), points.size());
        if (count == 0) {
            return 0;
        }
        Axis normalAxis = target.geometry().normalAxis();
        double viewerX = viewer.getX();
        double viewerY = viewer.getY();
        double viewerZ = viewer.getZ();
        int start = ToolPreviewGeometry.sampleStart(target.portalId(), frame, points.size());
        for (int i = 0; i < count; i++) {
            int index = (start + (int) (((long) i * points.size()) / count)) % points.size();
            PreviewPoint point = points.get(index);
            double offset = ToolPreviewGeometry.viewerSideOffset(viewerX, viewerY, viewerZ, normalAxis, point.x(), point.y(), point.z());
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
        double viewerX = viewer.getX();
        double viewerY = viewer.getY();
        double viewerZ = viewer.getZ();
        int start = ToolPreviewGeometry.sampleStart(target.portalId(), frame * 3L, cells.size());
        for (int i = 0; i < count; i++) {
            int index = (start + (int) (((long) i * cells.size()) / count)) % cells.size();
            Cell cell = cells.get(index);
            double angle = (frame * 0.47D) + (index * 2.399963229728653D) + (i * 0.71D);
            double first = 0.5D + (Math.cos(angle) * 0.32D);
            double second = 0.5D + (Math.sin(angle * 1.618033988749895D) * 0.32D);
            double x = cell.x() + (normalAxis == Axis.X ? 0.5D : first);
            double y = cell.y() + (normalAxis == Axis.Y ? 0.5D : normalAxis == Axis.X ? first : second);
            double z = cell.z() + (normalAxis == Axis.Z ? 0.5D : second);
            double offset = ToolPreviewGeometry.viewerSideOffset(viewerX, viewerY, viewerZ, normalAxis, x, y, z);
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

    private record CachedGeometry(long revision, Axis normalAxis, Geometry geometry) {
    }

    private record RenderTarget(UUID portalId, Geometry geometry, double distanceSquared) {
    }

}
