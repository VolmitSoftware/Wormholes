package art.arcane.wormholes.portal.rtp;

import java.util.HashMap;
import java.util.Iterator;
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
	private final Map<UUID, Map<UUID, Presence>> presencesByViewer;

	RtpAttendanceRegistry(RtpService service, RtpFailureThrottle failures, long idleMillis)
	{
		this.service = service;
		this.failures = failures;
		this.idleMillis = idleMillis;
		presencesByViewer = new ConcurrentHashMap<UUID, Map<UUID, Presence>>();
	}

	void touch(LocalPortal portal, UUID viewerId, long touchedAtMillis)
	{
		AxisAlignedBB view = Objects.requireNonNull(portal.getView(), "portal view");
		World world = Objects.requireNonNull(portal.getStructure().getWorld(), "portal source world");
		Presence presence = new Presence(
				world.getUID(),
				view.getXa(),
				view.getXb(),
				view.getYa(),
				view.getYb(),
				view.getZa(),
				view.getZb(),
				touchedAtMillis);
		UUID portalId = portal.getId();
		presencesByViewer.compute(viewerId, (ignored, current) ->
		{
			Map<UUID, Presence> viewerPresences = current;
			if(viewerPresences == null)
			{
				viewerPresences = new HashMap<UUID, Presence>();
			}
			viewerPresences.put(portalId, presence);
			touch(portalId, viewerId);
			return viewerPresences;
		});
	}

	void departViewer(UUID viewerId)
	{
		presencesByViewer.computeIfPresent(viewerId, (ignored, viewerPresences) ->
		{
			for(UUID portalId : viewerPresences.keySet())
			{
				leave(portalId, viewerId);
			}
			return null;
		});
	}

	void departOutside(UUID viewerId, Location destination)
	{
		presencesByViewer.computeIfPresent(viewerId, (ignored, viewerPresences) ->
		{
			Iterator<Map.Entry<UUID, Presence>> iterator = viewerPresences.entrySet().iterator();
			while(iterator.hasNext())
			{
				Map.Entry<UUID, Presence> entry = iterator.next();
				if(!entry.getValue().contains(destination))
				{
					leave(entry.getKey(), viewerId);
					iterator.remove();
				}
			}
			return viewerPresences.isEmpty() ? null : viewerPresences;
		});
	}

	void sweep(long nowMillis)
	{
		for(UUID viewerId : presencesByViewer.keySet())
		{
			presencesByViewer.computeIfPresent(viewerId, (ignored, viewerPresences) ->
			{
				Iterator<Map.Entry<UUID, Presence>> iterator = viewerPresences.entrySet().iterator();
				while(iterator.hasNext())
				{
					Map.Entry<UUID, Presence> entry = iterator.next();
					if(nowMillis - entry.getValue().touchedAtMillis() >= idleMillis)
					{
						leave(entry.getKey(), viewerId);
						iterator.remove();
					}
				}
				return viewerPresences.isEmpty() ? null : viewerPresences;
			});
		}
	}

	void forgetPortal(UUID portalId)
	{
		for(UUID viewerId : presencesByViewer.keySet())
		{
			presencesByViewer.computeIfPresent(viewerId, (ignored, viewerPresences) ->
			{
				viewerPresences.remove(portalId);
				return viewerPresences.isEmpty() ? null : viewerPresences;
			});
		}
	}

	void clear()
	{
		presencesByViewer.clear();
	}

	private void touch(UUID portalId, UUID viewerId)
	{
		service.touchViewer(portalId, viewerId).whenComplete((changed, failure) ->
		{
			if(failure != null)
			{
				failures.report("attendance-touch:" + portalId, failure);
			}
		});
	}

	private void leave(UUID portalId, UUID viewerId)
	{
		service.leaveViewer(portalId, viewerId).whenComplete((changed, failure) ->
		{
			if(failure != null)
			{
				failures.report("attendance-leave:" + portalId, failure);
			}
		});
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
