package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.World;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

final class RtpAttendanceRegistry
{
	private final RtpService service;
	private final RtpFailureThrottle failures;
	private final long idleMillis;
	private final Map<Key, Presence> presences;

	RtpAttendanceRegistry(RtpService service, RtpFailureThrottle failures, long idleMillis)
	{
		this.service = service;
		this.failures = failures;
		this.idleMillis = idleMillis;
		this.presences = new ConcurrentHashMap<Key, Presence>();
	}

	void touch(LocalPortal portal, UUID viewerId, long touchedAtMillis)
	{
		AxisAlignedBB view = Objects.requireNonNull(portal.getView(), "portal view");
		World world = Objects.requireNonNull(portal.getStructure().getWorld(), "portal source world");
		presences.put(new Key(portal.getId(), viewerId), new Presence(
				world.getUID(),
				view.getXa(),
				view.getXb(),
				view.getYa(),
				view.getYb(),
				view.getZa(),
				view.getZb(),
				touchedAtMillis));
		service.touchViewer(portal.getId(), viewerId).whenComplete((changed, failure) ->
		{
			if(failure != null)
			{
				failures.report("attendance-touch:" + portal.getId(), failure);
			}
		});
	}

	void departViewer(UUID viewerId)
	{
		List<Key> departures = new ArrayList<Key>();
		for(Key key : presences.keySet())
		{
			if(key.viewerId().equals(viewerId))
			{
				departures.add(key);
			}
		}
		depart(departures);
	}

	void departOutside(UUID viewerId, Location destination)
	{
		List<Key> departures = new ArrayList<Key>();
		for(Map.Entry<Key, Presence> entry : presences.entrySet())
		{
			if(entry.getKey().viewerId().equals(viewerId) && !entry.getValue().contains(destination))
			{
				departures.add(entry.getKey());
			}
		}
		depart(departures);
	}

	void sweep(long nowMillis)
	{
		List<Key> departures = new ArrayList<Key>();
		for(Map.Entry<Key, Presence> entry : presences.entrySet())
		{
			if(nowMillis - entry.getValue().touchedAtMillis() >= idleMillis)
			{
				departures.add(entry.getKey());
			}
		}
		depart(departures);
	}

	void forgetPortal(UUID portalId)
	{
		List<Key> removals = new ArrayList<Key>();
		for(Key key : presences.keySet())
		{
			if(key.portalId().equals(portalId))
			{
				removals.add(key);
			}
		}
		for(Key key : removals)
		{
			presences.remove(key);
		}
	}

	void clear()
	{
		presences.clear();
	}

	private void depart(List<Key> departures)
	{
		for(Key key : departures)
		{
			leave(key);
		}
	}

	private void leave(Key key)
	{
		Presence removed = presences.remove(key);
		if(removed == null)
		{
			return;
		}
		service.leaveViewer(key.portalId(), key.viewerId()).whenComplete((changed, failure) ->
		{
			if(failure != null)
			{
				failures.report("attendance-leave:" + key.portalId(), failure);
			}
		});
	}

	private record Key(UUID portalId, UUID viewerId)
	{
		private Key
		{
			Objects.requireNonNull(portalId, "portalId");
			Objects.requireNonNull(viewerId, "viewerId");
		}
	}

	private record Presence(
			UUID worldId,
			double minimumX,
			double maximumX,
			double minimumY,
			double maximumY,
			double minimumZ,
			double maximumZ,
			long touchedAtMillis)
	{
		private boolean contains(Location location)
		{
			return location.getWorld() != null
					&& worldId.equals(location.getWorld().getUID())
					&& location.getX() >= minimumX && location.getX() <= maximumX
					&& location.getY() >= minimumY && location.getY() <= maximumY
					&& location.getZ() >= minimumZ && location.getZ() <= maximumZ;
		}
	}
}
