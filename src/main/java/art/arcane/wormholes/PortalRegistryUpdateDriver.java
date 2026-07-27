package art.arcane.wormholes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalSaveSnapshot;
import art.arcane.wormholes.portal.PortalUpdateGate;

final class PortalRegistryUpdateDriver
{
	private static final int ATTENDANCE_REFRESH_INTERVAL_TICKS = 5;
	private static final long REFUSAL_REPORT_INTERVAL = 1200L;
	private static final long SAVE_RETRY_BASE_TICKS = 20L;
	private static final long SAVE_RETRY_MAX_TICKS = 600L;

	private final PortalRegistryAttendance attendance;
	private final boolean foliaRuntime;
	private final Map<UUID, Long> saveRetryAfterTick = new ConcurrentHashMap<UUID, Long>();
	private final Map<UUID, Integer> saveFailureCounts = new ConcurrentHashMap<UUID, Integer>();
	private final AtomicLong refusedSaveCount = new AtomicLong();
	private final AtomicLong nextRefusedSaveReportTick = new AtomicLong();
	private volatile long driverTick;
	private long refusedUpdates;

	PortalRegistryUpdateDriver(PortalRegistryAttendance attendance)
	{
		this.attendance = attendance;
		this.foliaRuntime = FoliaScheduler.isFoliaThreading(Bukkit.getServer());
		this.driverTick = 0L;
		this.refusedUpdates = 0L;
	}

	void tick(List<ILocalPortal> snapshot)
	{
		driverTick++;

		if(driverTick % ATTENDANCE_REFRESH_INTERVAL_TICKS == 0)
		{
			attendance.refresh(snapshot);
		}

		if(foliaRuntime)
		{
			for(ILocalPortal i : snapshot)
			{
				if(PortalUpdateGate.isDue(i.isOpen(), i.isAmbientAttended(), driverTick, PortalUpdateGate.staggerOffset(i.getId())))
				{
					updateLocalPortal(i);
				}
			}
			return;
		}

		Map<UUID, WorldBatch> byWorld = new HashMap<UUID, WorldBatch>();
		for(ILocalPortal i : snapshot)
		{
			if(!PortalUpdateGate.isDue(i.isOpen(), i.isAmbientAttended(), driverTick, PortalUpdateGate.staggerOffset(i.getId())))
			{
				continue;
			}

			Location center = i.getCenter();
			if(center == null || center.getWorld() == null)
			{
				continue;
			}

			UUID worldId = center.getWorld().getUID();
			WorldBatch batch = byWorld.get(worldId);
			if(batch == null)
			{
				batch = new WorldBatch(center, new ArrayList<ILocalPortal>());
				byWorld.put(worldId, batch);
			}
			batch.portals().add(i);
		}

		for(WorldBatch batch : byWorld.values())
		{
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, batch.anchor(), () ->
			{
				for(ILocalPortal portal : batch.portals())
				{
					try
					{
						runPortalUpdate(portal);
					}
					catch(Throwable e)
					{
						e.printStackTrace();
					}
				}
			});
			if(!scheduled)
			{
				reportRefusedUpdate(batch.portals().size());
			}
		}
	}

	private void updateLocalPortal(ILocalPortal portal)
	{
		Location center = portal.getCenter();
		if(center == null || center.getWorld() == null)
		{
			return;
		}

		if(!FoliaScheduler.runRegion(Wormholes.instance, center, () -> runPortalUpdate(portal)))
		{
			reportRefusedUpdate(1);
		}
	}

	private void reportRefusedUpdate(int portalCount)
	{
		if(refusedUpdates % REFUSAL_REPORT_INTERVAL == 0)
		{
			Wormholes.w("Region refused a portal update pass for " + portalCount + " portal(s); the update and its pending save are deferred to a later tick");
		}
		refusedUpdates++;
	}

	private void runPortalUpdate(ILocalPortal portal)
	{
		try
		{
			portal.update();
		}
		catch(Throwable e)
		{
			e.printStackTrace();
		}

		if(portal.needsSaving())
		{
			Long retryAfter = saveRetryAfterTick.get(portal.getId());
			if(retryAfter != null && driverTick < retryAfter)
			{
				return;
			}
			PortalSaveSnapshot save = portal.prepareSave();
			if(save == null)
			{
				return;
			}

			try
			{
				boolean scheduled = FoliaScheduler.runAsync(Wormholes.instance, () ->
				{
					try
					{
						portal.writeSave(save);
						clearSaveFailure(portal.getId());
					}
					catch(IOException e)
					{
						recordSaveFailure(portal.getId(), e);
					}
					catch(RuntimeException error)
					{
						recordSaveFailure(portal.getId(), error);
					}
					catch(Error error)
					{
						recordSaveFailure(portal.getId(), error);
						throw error;
					}
				});
				if(!scheduled)
				{
					portal.rejectSave();
					recordSaveFailure(portal.getId(), null);
					return;
				}
				if(Wormholes.portalSyncService != null)
				{
					try
					{
						Wormholes.portalSyncService.broadcastPortal(portal);
					}
					catch(Throwable error)
					{
						Wormholes.instance.getLogger().log(
							Level.WARNING,
							"Could not broadcast the accepted portal save for " + portal.getId(),
							error
						);
					}
				}
			}
			catch(RuntimeException | Error error)
			{
				portal.rejectSave();
				throw error;
			}
		}
	}

	private void recordSaveFailure(UUID portalId, Throwable error)
	{
		int failures = saveFailureCounts.merge(portalId, 1, Integer::sum);
		int shift = Math.min(5, failures - 1);
		long retryDelay = Math.min(SAVE_RETRY_MAX_TICKS, SAVE_RETRY_BASE_TICKS << shift);
		saveRetryAfterTick.put(portalId, driverTick + retryDelay);
		if(error != null)
		{
			Wormholes.instance.getLogger().log(
				Level.WARNING,
				"Could not persist portal " + portalId + "; retrying after " + retryDelay + " ticks",
				error
			);
			return;
		}
		long refused = refusedSaveCount.incrementAndGet();
		long tick = driverTick;
		long nextReport = nextRefusedSaveReportTick.get();
		if(tick >= nextReport && nextRefusedSaveReportTick.compareAndSet(nextReport, tick + REFUSAL_REPORT_INTERVAL))
		{
			Wormholes.w("Async scheduling has refused " + refused
				+ " portal save(s); dirty portals remain queued with bounded retry backoff");
			refusedSaveCount.set(0L);
		}
	}

	private void clearSaveFailure(UUID portalId)
	{
		saveRetryAfterTick.remove(portalId);
		saveFailureCounts.remove(portalId);
	}

	private record WorldBatch(Location anchor, List<ILocalPortal> portals)
	{
	}
}
