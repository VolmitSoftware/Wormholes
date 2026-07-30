package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

final class EffectVortexDirector
{
	private static final int SWIRL_TICKS = 20;
	private static final long VORTEX_MARKER_GRACE_MILLIS = 1500L;
	private final Map<VortexKey, VortexMarker> activeVortices = new ConcurrentHashMap<>();
	private final EffectDisplayRegistry displays;
	private final EffectPortalAnimator animator;

	EffectVortexDirector(EffectDisplayRegistry displays, EffectPortalAnimator animator)
	{
		this.displays = displays;
		this.animator = animator;
	}

	void clear()
	{
		activeVortices.clear();
	}

	void playVortex(World world, Location center, List<EffectManager.PortalBlockSnapshot> snapshots)
	{
		if(displays.isClosing() || world == null || center == null || snapshots == null || snapshots.isEmpty())
		{
			return;
		}
		if(!Settings.ENABLE_PARTICLES)
		{
			world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 0.55f, 0.55f);
			return;
		}

		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for(EffectManager.PortalBlockSnapshot snapshot : snapshots)
		{
			double x = Math.floor(snapshot.location().getX());
			double y = Math.floor(snapshot.location().getY());
			double z = Math.floor(snapshot.location().getZ());
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
		double extentX = maxX - minX;
		double extentY = maxY - minY;
		double extentZ = maxZ - minZ;
		final int normalAxis = (extentX <= extentY && extentX <= extentZ) ? 0 : (extentY <= extentZ ? 1 : 2);
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final double[] c = new double[] { center.getX(), center.getY(), center.getZ() };
		final double convergeSx = extentX + 1.0D;
		final double convergeSy = extentY + 1.0D;
		final double convergeSz = extentZ + 1.0D;

		int displayCap = EffectManager.formationDisplayCap(Settings.VISUAL_QUALITY_PROFILE);
		List<EffectManager.PortalBlockSnapshot> selected = selectFormationSnapshots(snapshots, center, planeA, planeB, displayCap);
		List<VortexBlock> vortex = new ArrayList<VortexBlock>(selected.size());
		for(EffectManager.PortalBlockSnapshot snapshot : selected)
		{
			Location loc = snapshot.location();
			double cornerX = Math.floor(loc.getX());
			double cornerY = Math.floor(loc.getY());
			double cornerZ = Math.floor(loc.getZ());
			double blockCenterX = cornerX + 0.5D;
			double blockCenterY = cornerY + 0.5D;
			double blockCenterZ = cornerZ + 0.5D;
			BlockData data = snapshot.data();
			Transformation initial = formationTransformation(center.getX(), center.getY(), center.getZ(), blockCenterX, blockCenterY, blockCenterZ, 1.0f, new Quaternionf());
			BlockDisplay display = world.spawn(center, BlockDisplay.class, e ->
			{
				e.setBlock(data);
				e.setBrightness(new Display.Brightness(15, 15));
				e.setPersistent(false);
				e.setViewRange(2.5f);
				e.setTransformation(initial);
			});
			displays.track(display);
			double[] blockCenter = new double[] { blockCenterX, blockCenterY, blockCenterZ };
			double a0 = blockCenter[planeA] - c[planeA];
			double b0 = blockCenter[planeB] - c[planeB];
			double normalOffset = blockCenter[normalAxis] - c[normalAxis];
			vortex.add(new VortexBlock(display, Math.atan2(b0, a0), Math.hypot(a0, b0), normalOffset,
					1.2D + (Math.random() * 1.0D), 1.2D + (Math.random() * 0.5D), 0.8D + (Math.random() * 0.5D),
					Math.random() * Math.PI * 2.0D, 0.2D + (Math.random() * 0.25D)));
		}

		world.spawnParticle(Particle.PORTAL, center, 12, 0.65, 0.8, 0.65, 0.35);
		world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 0.55f, 0.55f);
		world.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, EffectManager.openingSoundPlan().frameVolume(), 0.35f);

		VortexKey markerKey = vortexKey(world, center);
		VortexMarker marker = new VortexMarker(markerKey, world, center.getX(), center.getY(), center.getZ(),
				System.currentTimeMillis() + (SWIRL_TICKS * 50L) + VORTEX_MARKER_GRACE_MILLIS);
		activeVortices.put(markerKey, marker);

		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(displays.isClosing())
			{
				activeVortices.remove(markerKey, marker);
				removeVortex(vortex);
				return;
			}
			int t = tick[0]++;
			if(t >= SWIRL_TICKS)
			{
				removeVortex(vortex);
				playVortexConvergence(world, center, marker, convergeSx, convergeSy, convergeSz);
				if(!FoliaScheduler.runRegion(Wormholes.instance, center, () -> activeVortices.remove(markerKey, marker), VORTEX_MARKER_GRACE_MILLIS / 50L))
				{
					activeVortices.remove(markerKey, marker);
				}
				return;
			}
			double frac = (double) (t + 1) / (double) SWIRL_TICKS;
			double[] target = new double[3];
			int index = 0;
			for(VortexBlock block : vortex)
			{
				index++;
				if(!block.display().isValid())
				{
					continue;
				}
				double spin = frac * block.turns() * Math.PI * 2.0D;
				double angle = block.angle0() + spin;
				double radiusFactor = Math.pow(1.0D - frac, block.radialExponent());
				double radius = block.radius0() * radiusFactor;
				float scale = (float) Math.max(0.04D, Math.pow(1.0D - frac, block.shrinkExponent()));
				target[0] = c[0];
				target[1] = c[1];
				target[2] = c[2];
				target[normalAxis] += block.normalOffset0() * radiusFactor;
				target[planeA] += radius * Math.cos(angle);
				target[planeB] += radius * Math.sin(angle);
				float tiltAngle = (float) (block.wobbleAmplitude() * Math.sin((spin * 2.0D) + block.wobblePhase()));
				block.display().setInterpolationDelay(0);
				block.display().setInterpolationDuration(2);
				block.display().setTransformation(formationTransformation(center.getX(), center.getY(), center.getZ(), target[0], target[1], target[2], scale, axisRotation(normalAxis, tiltAngle)));
				if((t & 1) == 0)
				{
					Particle trail = (index & 1) == 0 ? Particle.PORTAL : Particle.REVERSE_PORTAL;
					world.spawnParticle(trail, target[0], target[1], target[2], 1, 0.04, 0.04, 0.04, 0.02);
				}
			}
			if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
			{
				activeVortices.remove(markerKey, marker);
				removeVortex(vortex);
			}
		};
		if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
		{
			activeVortices.remove(markerKey, marker);
			removeVortex(vortex);
		}
	}

	boolean captureOpening(
			World world,
			Location center,
			double sx,
			double sy,
			double sz,
			BooleanSupplier active,
			BooleanSupplier audible)
	{
		VortexMarker marker = findVortexMarker(world, center);
		if(marker == null)
		{
			return false;
		}
		if(marker.kawooshPlayed)
		{
			activeVortices.remove(marker.key, marker);
			return true;
		}
		marker.kawoosh = new KawooshRequest(center.clone(), sx, sy, sz, active, audible);
		return true;
	}

	private void playVortexConvergence(World world, Location center, VortexMarker marker, double sx, double sy, double sz)
	{
		if(displays.isClosing())
		{
			return;
		}
		marker.kawooshPlayed = true;
		KawooshRequest request = marker.kawoosh;
		if(request != null && request.active().getAsBoolean())
		{
			animator.playKawoosh(world, request.center(), request.sx(), request.sy(), request.sz(), request.active(), request.audible());
			return;
		}
		animator.playKawoosh(world, center, sx, sy, sz, () -> true, () -> true);
	}

	private VortexMarker findVortexMarker(World world, Location center)
	{
		long now = System.currentTimeMillis();
		for(Map.Entry<VortexKey, VortexMarker> entry : activeVortices.entrySet())
		{
			VortexMarker marker = entry.getValue();
			if(now >= marker.expiresAtMillis)
			{
				activeVortices.remove(entry.getKey(), marker);
				continue;
			}
			if(EffectManager.vortexMarkerMatches(marker.world.getUID(), marker.x, marker.y, marker.z, marker.expiresAtMillis, now, world.getUID(), center.getX(), center.getY(), center.getZ()))
			{
				return marker;
			}
		}
		return null;
	}

	private static VortexKey vortexKey(World world, Location center)
	{
		long x = center.getBlockX() & 0x3FFFFFFL;
		long y = center.getBlockY() & 0xFFFL;
		long z = center.getBlockZ() & 0x3FFFFFFL;
		return new VortexKey(world.getUID(), (y << 52) | (z << 26) | x);
	}

	private void removeVortex(List<VortexBlock> vortex)
	{
		for(VortexBlock block : vortex)
		{
			displays.remove(block.display());
		}
	}

	private static List<EffectManager.PortalBlockSnapshot> selectFormationSnapshots(List<EffectManager.PortalBlockSnapshot> snapshots, Location center, int planeA, int planeB, int cap)
	{
		if(snapshots.size() <= cap)
		{
			return List.copyOf(snapshots);
		}
		EffectManager.PortalBlockSnapshot[] sectors = new EffectManager.PortalBlockSnapshot[cap];
		double[] sectorRadius = new double[cap];
		Arrays.fill(sectorRadius, -1.0D);
		for(EffectManager.PortalBlockSnapshot snapshot : snapshots)
		{
			double offsetA = coordinate(snapshot.location(), planeA) + 0.5D - coordinate(center, planeA);
			double offsetB = coordinate(snapshot.location(), planeB) + 0.5D - coordinate(center, planeB);
			double normalizedAngle = (Math.atan2(offsetB, offsetA) + Math.PI) / (Math.PI * 2.0D);
			int sector = Math.min(cap - 1, Math.max(0, (int) Math.floor(normalizedAngle * cap)));
			double radius = (offsetA * offsetA) + (offsetB * offsetB);
			if(radius > sectorRadius[sector])
			{
				sectorRadius[sector] = radius;
				sectors[sector] = snapshot;
			}
		}
		List<EffectManager.PortalBlockSnapshot> selected = new ArrayList<EffectManager.PortalBlockSnapshot>(cap);
		for(EffectManager.PortalBlockSnapshot snapshot : sectors)
		{
			if(snapshot != null)
			{
				selected.add(snapshot);
			}
		}
		for(EffectManager.PortalBlockSnapshot candidate : snapshots)
		{
			if(selected.size() >= cap)
			{
				break;
			}
			if(!selected.contains(candidate))
			{
				selected.add(candidate);
			}
		}
		return selected;
	}

	private static Transformation formationTransformation(double anchorX, double anchorY, double anchorZ, double targetX, double targetY, double targetZ, float scale, Quaternionf rotation)
	{
		float half = 0.5f * scale;
		Vector3f pivot = rotation.transform(new Vector3f(half, half, half));
		Vector3f translation = new Vector3f((float) (targetX - anchorX) - pivot.x, (float) (targetY - anchorY) - pivot.y, (float) (targetZ - anchorZ) - pivot.z);
		return new Transformation(translation, rotation, new Vector3f(scale, scale, scale), new Quaternionf());
	}

	private static double coordinate(Location location, int axis)
	{
		return switch(axis)
		{
			case 0 -> location.getX();
			case 1 -> location.getY();
			default -> location.getZ();
		};
	}

	private static Quaternionf axisRotation(int axis, float angle)
	{
		return switch(axis)
		{
			case 0 -> new Quaternionf().rotationX(angle);
			case 2 -> new Quaternionf().rotationZ(angle);
			default -> new Quaternionf().rotationY(angle);
		};
	}

	private record KawooshRequest(
			Location center,
			double sx,
			double sy,
			double sz,
			BooleanSupplier active,
			BooleanSupplier audible)
	{
	}

	private record VortexBlock(BlockDisplay display, double angle0, double radius0, double normalOffset0, double turns, double radialExponent, double shrinkExponent, double wobblePhase, double wobbleAmplitude)
	{
	}

	private record VortexKey(UUID worldId, long packedPos)
	{
	}

	private static final class VortexMarker
	{
		private final VortexKey key;
		private final World world;
		private final double x;
		private final double y;
		private final double z;
		private final long expiresAtMillis;
		private volatile KawooshRequest kawoosh;
		private volatile boolean kawooshPlayed;

		private VortexMarker(VortexKey key, World world, double x, double y, double z, long expiresAtMillis)
		{
			this.key = key;
			this.world = world;
			this.x = x;
			this.y = y;
			this.z = z;
			this.expiresAtMillis = expiresAtMillis;
		}
	}
}
