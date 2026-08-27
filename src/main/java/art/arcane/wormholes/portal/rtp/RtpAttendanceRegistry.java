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
		UUID portalId = portal.getId();
		presencesByViewer.compute(viewerId, (ignored, current) ->
		{
			Map<UUID, Presence> viewerPresences = current;
			if(viewerPresences == null)
			{
				viewerPresences = new HashMap<UUID, Presence>();
			}
			Presence presence = viewerPresences.get(portalId);
			if(presence == null)
			{
				presence = new Presence();
				viewerPresences.put(portalId, presence);
				presence.update(world.getUID(), view, touchedAtMillis);
				presence.beginServiceTouch();
				touchService(portalId, viewerId, presence);
			}
			else
			{
				presence.update(world.getUID(), view, touchedAtMillis);
				RtpProjectionView.State state = service.projectionView(portalId, viewerId).state();
				if((state == RtpProjectionView.State.NONE
						|| state == RtpProjectionView.State.DENIED
						|| state == RtpProjectionView.State.FAILED)
						&& presence.beginServiceTouch())
				{
					touchService(portalId, viewerId, presence);
				}
			}
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

	private void touchService(UUID portalId, UUID viewerId, Presence presence)
	{
		service.touchViewer(portalId, viewerId).whenComplete((changed, failure) ->
		{
			presence.finishServiceTouch();
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

	private static final class Presence
	{
		private UUID worldId;
		private double minimumX;
		private double maximumX;
		private double minimumY;
		private double maximumY;
		private double minimumZ;
		private double maximumZ;
		private long touchedAtMillis;
		private boolean serviceTouchPending;

		private void update(UUID worldId, AxisAlignedBB view, long touchedAtMillis)
		{
			this.worldId = worldId;
			this.minimumX = view.getXa();
			this.maximumX = view.getXb();
			this.minimumY = view.getYa();
			this.maximumY = view.getYb();
			this.minimumZ = view.getZa();
			this.maximumZ = view.getZb();
			this.touchedAtMillis = touchedAtMillis;
		}

		private long touchedAtMillis()
		{
			return touchedAtMillis;
		}

		private synchronized boolean beginServiceTouch()
		{
			if(serviceTouchPending)
			{
				return false;
			}
			serviceTouchPending = true;
			return true;
		}

		private synchronized void finishServiceTouch()
		{
			serviceTouchPending = false;
		}

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
