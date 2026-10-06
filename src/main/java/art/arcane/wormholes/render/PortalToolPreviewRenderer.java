package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.ToolPreviewGeometry;
import art.arcane.wormholes.portal.ToolPreviewGeometry.Cell;
import art.arcane.wormholes.portal.ToolPreviewGeometry.Geometry;
import art.arcane.wormholes.portal.ToolPreviewGeometry.PreviewPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.optics.math.Axis;

public final class PortalToolPreviewRenderer
{
	private static final Particle.DustOptions OUTLINE_DUST = new Particle.DustOptions(Color.fromRGB(255, 190, 45), 1.1F);
	private static final Particle.DustTransition FILL_BLACK_TO_GOLD = new Particle.DustTransition(Color.fromRGB(8, 6, 2), Color.fromRGB(255, 176, 30), 0.9F);
	private static final Particle.DustTransition FILL_GOLD_TO_BLACK = new Particle.DustTransition(Color.fromRGB(255, 176, 30), Color.fromRGB(8, 6, 2), 0.9F);

	private final ConcurrentHashMap<UUID, CachedGeometry> geometryCache = new ConcurrentHashMap<UUID, CachedGeometry>();
	private final AtomicLong animationFrame = new AtomicLong();

	public void render(Player viewer, List<? extends ILocalPortal> portals)
	{
		Objects.requireNonNull(viewer, "viewer");
		Objects.requireNonNull(portals, "portals");
		if(!Settings.ENABLE_PARTICLES || portals.isEmpty())
		{
			return;
		}

		World viewerWorld = viewer.getWorld();
		Location viewerLocation = viewer.getLocation();
		UUID viewerWorldId = viewerWorld.getUID();
		ArrayList<RenderTarget> targets = new ArrayList<RenderTarget>(Math.min(portals.size(), 16));
		for(ILocalPortal portal : portals)
		{
			if(portal == null || portal.isDestroyed() || portal.getId() == null)
			{
				continue;
			}
			World portalWorld = portal.getWorld();
			if(portalWorld == null || !viewerWorldId.equals(portalWorld.getUID()))
			{
				continue;
			}
			PortalStructure structure = portal.getStructure();
			Frame frame = portal.getFrame();
			if(structure == null || frame == null)
			{
				continue;
			}
			Geometry geometry = geometryFor(portal.getId(), structure, frame.getNormal().getAxis());
			if(geometry.isEmpty())
			{
				continue;
			}
			double distanceSquared = geometry.distanceSquared(viewerLocation.getX(), viewerLocation.getY(), viewerLocation.getZ());
			if(distanceSquared > ToolPreviewGeometry.PREVIEW_RANGE * ToolPreviewGeometry.PREVIEW_RANGE)
			{
				continue;
			}
			targets.add(new RenderTarget(portal.getId(), geometry, distanceSquared));
		}
		if(targets.isEmpty())
		{
			return;
		}

		targets.sort(Comparator.comparingDouble(RenderTarget::distanceSquared));
		long frame = animationFrame.getAndIncrement();
		int outlineRemaining = ToolPreviewGeometry.MAX_OUTLINE_PARTICLES;
		int fillRemaining = ToolPreviewGeometry.MAX_FILL_PARTICLES;
		for(int i = 0; i < targets.size() && (outlineRemaining > 0 || fillRemaining > 0); i++)
		{
			RenderTarget target = targets.get(i);
			int remainingTargets = targets.size() - i;
			int outlineShare = ToolPreviewGeometry.fairShare(outlineRemaining, remainingTargets);
			int fillShare = ToolPreviewGeometry.fairShare(fillRemaining, remainingTargets);
			outlineRemaining -= emitOutline(viewer, viewerLocation, target, outlineShare, frame);
			fillRemaining -= emitFill(viewer, viewerLocation, target, fillShare, frame);
		}
	}

	public void invalidate(UUID portalId)
	{
		geometryCache.remove(Objects.requireNonNull(portalId, "portalId"));
	}

	public void clear()
	{
		geometryCache.clear();
	}

	Geometry geometryFor(UUID portalId, PortalStructure structure, Axis normalAxis)
	{
		Objects.requireNonNull(portalId, "portalId");
		Objects.requireNonNull(structure, "structure");
		Objects.requireNonNull(normalAxis, "normalAxis");
		while(true)
		{
			long revision = structure.getRevision();
			CachedGeometry current = geometryCache.get(portalId);
			if(current != null && current.revision() == revision && current.normalAxis() == normalAxis)
			{
				return current.geometry();
			}
			List<Vec3d> positions = structure.geometry().getBlockPositions();
			if(structure.getRevision() != revision)
			{
				continue;
			}
			Geometry built = ToolPreviewGeometry.build(positions, normalAxis);
			CachedGeometry resolved = geometryCache.compute(portalId, (ignored, cached) ->
			{
				if(cached != null && cached.revision() == revision && cached.normalAxis() == normalAxis)
				{
					return cached;
				}
				return new CachedGeometry(revision, normalAxis, built);
			});
			if(structure.getRevision() != revision)
			{
				geometryCache.remove(portalId, resolved);
				continue;
			}
			return resolved.geometry();
		}
	}

	int cachedPortalCount()
	{
		return geometryCache.size();
	}

	private static int emitOutline(Player viewer, Location viewerLocation, RenderTarget target, int budget, long frame)
	{
		List<PreviewPoint> points = target.geometry().outlinePoints();
		int count = Math.min(Math.max(0, budget), points.size());
		if(count == 0)
		{
			return 0;
		}
		double viewerX = viewerLocation.getX();
		double viewerY = viewerLocation.getY();
		double viewerZ = viewerLocation.getZ();
		int start = ToolPreviewGeometry.sampleStart(target.portalId(), frame, points.size());
		for(int i = 0; i < count; i++)
		{
			int index = (start + (int) (((long) i * points.size()) / count)) % points.size();
			PreviewPoint point = points.get(index);
			double offset = ToolPreviewGeometry.viewerSideOffset(viewerX, viewerY, viewerZ, target.geometry().normalAxis(), point.x(), point.y(), point.z());
			double x = point.x() + (target.geometry().normalAxis() == Axis.X ? offset : 0.0D);
			double y = point.y() + (target.geometry().normalAxis() == Axis.Y ? offset : 0.0D);
			double z = point.z() + (target.geometry().normalAxis() == Axis.Z ? offset : 0.0D);
			viewer.spawnParticle(Particle.DUST, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D, OUTLINE_DUST);
		}
		return count;
	}

	private static int emitFill(Player viewer, Location viewerLocation, RenderTarget target, int budget, long frame)
	{
		List<Cell> cells = target.geometry().cells();
		int count = Math.min(Math.max(0, budget), cells.size());
		if(count == 0)
		{
			return 0;
		}
		double viewerX = viewerLocation.getX();
		double viewerY = viewerLocation.getY();
		double viewerZ = viewerLocation.getZ();
		int start = ToolPreviewGeometry.sampleStart(target.portalId(), frame * 3L, cells.size());
		for(int i = 0; i < count; i++)
		{
			int index = (start + (int) (((long) i * cells.size()) / count)) % cells.size();
			Cell cell = cells.get(index);
			double angle = (frame * 0.47D) + (index * 2.399963229728653D) + (i * 0.71D);
			double first = 0.5D + (Math.cos(angle) * 0.32D);
			double second = 0.5D + (Math.sin(angle * 1.618033988749895D) * 0.32D);
			double x = cell.x() + (target.geometry().normalAxis() == Axis.X ? 0.5D : first);
			double y = cell.y() + (target.geometry().normalAxis() == Axis.Y ? 0.5D : target.geometry().normalAxis() == Axis.X ? first : second);
			double z = cell.z() + (target.geometry().normalAxis() == Axis.Z ? 0.5D : second);
			double offset = ToolPreviewGeometry.viewerSideOffset(viewerX, viewerY, viewerZ, target.geometry().normalAxis(), x, y, z);
			x += target.geometry().normalAxis() == Axis.X ? offset : 0.0D;
			y += target.geometry().normalAxis() == Axis.Y ? offset : 0.0D;
			z += target.geometry().normalAxis() == Axis.Z ? offset : 0.0D;
			Particle.DustTransition transition = ((frame + i) & 1L) == 0L ? FILL_BLACK_TO_GOLD : FILL_GOLD_TO_BLACK;
			viewer.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D, transition);
		}
		return count;
	}

	private record CachedGeometry(long revision, Axis normalAxis, Geometry geometry)
	{
	}

	private record RenderTarget(UUID portalId, Geometry geometry, double distanceSquared)
	{
	}

}
