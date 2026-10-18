package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.arrival.BukkitArrivalPreparation;
import art.arcane.wormholes.chunk.presend.BukkitChunkPreSendProvider;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.wormholes.util.BukkitGeometry;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.optics.math.Box;

public final class ArrivalWarmer
{
	private static final long APPROACH_LIFETIME_MILLIS = 1_000L;
	private final ConcurrentHashMap<ChunkKey, WarmHold> holds = new ConcurrentHashMap<ChunkKey, WarmHold>();
	private final ConcurrentHashMap<UUID, Long> destinationThrottle = new ConcurrentHashMap<UUID, Long>();
	private final ConcurrentHashMap<UUID, Long> imminentThrottle = new ConcurrentHashMap<UUID, Long>();
	private final ConcurrentHashMap<UUID, Approach> approaches = new ConcurrentHashMap<>();

	public void warmAround(World world, int blockX, int blockZ)
	{
		warmAround(world, blockX, blockZ, Settings.ARRIVAL_WARM_RADIUS_CHUNKS, Settings.ARRIVAL_WARM_HOLD_MILLIS);
	}

	public void warmAround(World world, int blockX, int blockZ, int radiusChunks, long holdMillis)
	{
		if(world == null || radiusChunks < 0 || holdMillis <= 0L)
		{
			return;
		}
		int centerX = blockX >> 4;
		int centerZ = blockZ >> 4;
		long expiry = System.currentTimeMillis() + holdMillis;
		for(int dx = -radiusChunks; dx <= radiusChunks; dx++)
		{
			for(int dz = -radiusChunks; dz <= radiusChunks; dz++)
			{
				retain(world, centerX + dx, centerZ + dz, expiry);
			}
		}
	}

	public void warmDestinationOf(ILocalPortal source)
	{
		if(source == null || !Settings.ARRIVAL_PREWARM_ON_INTEREST || !source.hasTunnel())
		{
			return;
		}
		ITunnel tunnel = source.getTunnel();
		if(tunnel == null || tunnel.getTunnelType() == TunnelType.UNIVERSAL)
		{
			return;
		}
		IPortal destinationPortal = tunnel.getDestination();
		if(!(destinationPortal instanceof ILocalPortal))
		{
			return;
		}
		ILocalPortal destination = (ILocalPortal) destinationPortal;
		long now = System.currentTimeMillis();
		Long nextAllowed = destinationThrottle.get(destination.getId());
		if(nextAllowed != null && nextAllowed.longValue() > now)
		{
			return;
		}
		destinationThrottle.put(destination.getId(), Long.valueOf(now + Settings.ARRIVAL_WARM_THROTTLE_MILLIS));
		Location center = destination.getCenter();
		if(center == null || center.getWorld() == null)
		{
			return;
		}
		warmAround(center.getWorld(), center.getBlockX(), center.getBlockZ());
	}

	public void warmImminent(ILocalPortal source, Player viewer)
	{
		if(source == null || viewer == null || !Settings.ARRIVAL_PREWARM_ON_INTEREST || !source.hasTunnel()
			|| BukkitArrivalPreparation.claimed(viewer))
		{
			return;
		}
		if(!FoliaScheduler.isOwnedByCurrentRegion(viewer))
		{
			if(Wormholes.instance != null)
			{
				FoliaScheduler.runEntity(Wormholes.instance, viewer, () -> warmImminent(source, viewer), 0L);
			}
			return;
		}
		ITunnel tunnel = source.getTunnel();
		if(tunnel == null || tunnel.getTunnelType() == TunnelType.UNIVERSAL)
		{
			return;
		}
		IPortal destinationPortal = tunnel.getDestination();
		if(!(destinationPortal instanceof ILocalPortal))
		{
			return;
		}
		ILocalPortal destination = (ILocalPortal) destinationPortal;
		long now = System.currentTimeMillis();
		if(selectApproach(source, viewer, viewer.getLocation(), now))
		{
			Location preparationTarget = predictedArrival(source, destination, viewer);
			if(preparationTarget != null && preparationTarget.getWorld() != null)
			{
				BukkitArrivalPreparation.prepare(viewer, preparationTarget);
				BukkitChunkPreSendProvider.preSend(viewer, preparationTarget);
			}
		}
		Long nextAllowed = imminentThrottle.get(destination.getId());
		if(nextAllowed != null && nextAllowed.longValue() > now)
		{
			return;
		}
		imminentThrottle.put(destination.getId(), Long.valueOf(now + Settings.ARRIVAL_WARM_THROTTLE_MILLIS));
		Location center = destination.getCenter();
		if(center == null || center.getWorld() == null)
		{
			return;
		}
		warmAround(center.getWorld(), center.getBlockX(), center.getBlockZ(), viewRadius(viewer), Settings.ARRIVAL_WARM_HOLD_MILLIS);
	}

	boolean selectApproach(ILocalPortal source, Player viewer, Location departure, long now)
	{
		if(!availableApproach(source, departure))
		{
			return false;
		}
		Approach selected = approaches.compute(viewer.getUniqueId(), (id, existing) ->
		{
			if(existing != null && existing.player == viewer && existing.source == source)
			{
				existing.observedAt = now;
				return existing;
			}
			if(existing != null && existing.player == viewer && now - existing.observedAt < APPROACH_LIFETIME_MILLIS
				&& !existing.source.getId().equals(source.getId()) && availableApproach(existing.source, departure))
			{
				double candidateDistance = approachDistance(source, departure);
				double existingDistance = approachDistance(existing.source, departure);
				if(candidateDistance > existingDistance || candidateDistance == existingDistance
					&& source.getId().compareTo(existing.source.getId()) > 0)
				{
					return existing;
				}
			}
			Approach replacement = new Approach(viewer, source);
			replacement.observedAt = now;
			return replacement;
		});
		return selected.source == source;
	}

	private static boolean availableApproach(ILocalPortal source, Location departure)
	{
		PortalStructure structure = source.getStructure();
		ITunnel tunnel = source.getTunnel();
		return source.isOpen() && !source.isMirrorMode() && tunnel != null
			&& tunnel.getTunnelType() != TunnelType.UNIVERSAL && tunnel.getDestination() instanceof ILocalPortal
			&& structure != null && structure.getWorld() == departure.getWorld() && structure.getCaptureZone() != null
			&& structure.getCaptureZone().containsPrimitive(departure.getX(), departure.getY(), departure.getZ());
	}

	private static double approachDistance(ILocalPortal source, Location departure)
	{
		Box area = source.getStructure().getArea();
		double x = Math.max(area.getXa() - departure.getX(), Math.max(0.0D, departure.getX() - area.getXb()));
		double y = Math.max(area.getYa() - departure.getY(), Math.max(0.0D, departure.getY() - area.getYb()));
		double z = Math.max(area.getZa() - departure.getZ(), Math.max(0.0D, departure.getZ() - area.getZb()));
		return x * x + y * y + z * z;
	}

	public void forget(UUID playerId)
	{
		approaches.remove(playerId);
	}

	static Location predictedArrival(ILocalPortal source, ILocalPortal destination, Player viewer)
	{
		Location departure = viewer.getLocation();
		Vector point = departure.toVector();
		Vector normal = BukkitGeometry.bukkit(source.getFrame().getNormal());
		double distance = point.clone().subtract(BukkitGeometry.bukkit(source.getOrigin())).dot(normal);
		Vector velocity = Wormholes.traversableManager == null
			? viewer.getVelocity() : Wormholes.traversableManager.getVelocity(viewer);
		double normalSpeed = velocity.dot(normal);
		Vector projected = distance * normalSpeed < 0.0D
			? point.clone().add(velocity.clone().multiply(Math.ceil(Math.abs(distance / normalSpeed))))
			: point.clone().subtract(normal.clone().multiply(distance + Math.copySign(0.05D, distance)));
		PlaneCrossing crossing = PlaneCrossing.create(source.getFrame(), source.getOrigin(),
			new PlaneCrossing.Motion(BukkitGeometry.vector(point), BukkitGeometry.vector(projected),
				BukkitGeometry.vector(velocity), BukkitGeometry.vector(departure.getDirection())));
		return destination.computeExitTarget(new Traversive(viewer, TraversableType.ENTITY, crossing.frame(),
			BukkitGeometry.bukkit(crossing.origin()), projected, velocity, departure.getDirection(), crossing.frontSide(), source.getId()));
	}

	public int viewRadius(Player viewer)
	{
		int distance = WormholesPlatform.sendViewDistance(viewer);
		if(distance <= 0)
		{
			distance = Bukkit.getViewDistance();
		}
		int radius = Math.max(distance, Settings.ARRIVAL_WARM_RADIUS_CHUNKS);
		return Math.min(radius, Settings.ARRIVAL_WARM_MAX_RADIUS_CHUNKS);
	}

	private void retain(World world, int chunkX, int chunkZ, long expiry)
	{
		ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
		WarmHold existing = holds.get(key);
		if(existing != null)
		{
			existing.extend(expiry);
			return;
		}
		ChunkLease lease = BukkitChunkLeaseProvider.registry().retain(world, world.getUID(), chunkX, chunkZ);
		WarmHold hold = new WarmHold(world, chunkX, chunkZ, expiry, lease);
		WarmHold prior = holds.putIfAbsent(key, hold);
		if(prior != null)
		{
			lease.close();
			prior.extend(expiry);
			return;
		}
		lease.ready().thenAccept(ready ->
		{
			if(!ready.booleanValue())
			{
				holds.remove(key, hold);
			}
		});
	}

	public void sweep()
	{
		long now = System.currentTimeMillis();
		for(UUID playerId : approaches.keySet())
		{
			approaches.computeIfPresent(playerId, (id, approach) ->
				now - approach.observedAt >= APPROACH_LIFETIME_MILLIS ? null : approach);
		}
		List<WarmHold> expired = null;
		for(WarmHold hold : holds.values())
		{
			if(hold.expiryMillis <= now)
			{
				if(expired == null)
				{
					expired = new ArrayList<WarmHold>();
				}
				expired.add(hold);
			}
		}
		if(expired != null)
		{
			for(WarmHold hold : expired)
			{
				ChunkKey key = new ChunkKey(hold.world.getUID(), hold.chunkX, hold.chunkZ);
				if(!holds.remove(key, hold))
				{
					continue;
				}
				if(hold.expiryMillis > now)
				{
					holds.putIfAbsent(key, hold);
					continue;
				}
				releaseTicket(hold);
			}
		}
		if(destinationThrottle.size() > 256)
		{
			destinationThrottle.values().removeIf(until -> until.longValue() <= now);
		}
		if(imminentThrottle.size() > 256)
		{
			imminentThrottle.values().removeIf(until -> until.longValue() <= now);
		}
	}

	private void releaseTicket(WarmHold hold)
	{
		hold.lease.close();
	}

	public void shutdown()
	{
		List<WarmHold> all = new ArrayList<WarmHold>(holds.values());
		holds.clear();
		destinationThrottle.clear();
		imminentThrottle.clear();
		approaches.clear();
		for(WarmHold hold : all)
		{
			releaseTicket(hold);
		}
	}

	private record ChunkKey(UUID worldId, int chunkX, int chunkZ)
	{
	}

	private static final class Approach
	{
		private final Player player;
		private final ILocalPortal source;
		private volatile long observedAt;

		private Approach(Player player, ILocalPortal source)
		{
			this.player = player;
			this.source = source;
		}
	}

	private static final class WarmHold
	{
		private final World world;
		private final int chunkX;
		private final int chunkZ;
		private final ChunkLease lease;
		private volatile long expiryMillis;

		private WarmHold(World world, int chunkX, int chunkZ, long expiryMillis, ChunkLease lease)
		{
			this.world = world;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.lease = lease;
			this.expiryMillis = expiryMillis;
		}

		private void extend(long expiry)
		{
			if(expiry > expiryMillis)
			{
				expiryMillis = expiry;
			}
		}
	}
}
