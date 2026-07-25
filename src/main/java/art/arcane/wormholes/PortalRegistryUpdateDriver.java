package art.arcane.wormholes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalUpdateGate;

final class PortalRegistryUpdateDriver
{
	private static final int ATTENDANCE_REFRESH_INTERVAL_TICKS = 5;
	private static final long REFUSAL_REPORT_INTERVAL = 1200L;

	private final PortalRegistryAttendance attendance;
	private final boolean foliaRuntime;
	private long driverTick;
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

	private static void runPortalUpdate(ILocalPortal portal)
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
			portal.willSave();

			if(Wormholes.portalSyncService != null)
			{
				Wormholes.portalSyncService.broadcastPortal(portal);
			}

			boolean scheduled = FoliaScheduler.runAsync(Wormholes.instance, () ->
			{
				try
				{
					portal.saveNow();
				}
				catch(IOException e)
				{
					e.printStackTrace();
				}
			});
			if(!scheduled)
			{
				try
				{
					portal.saveNow();
				}
				catch(IOException e)
				{
					e.printStackTrace();
				}
			}
		}
	}

	private record WorldBatch(Location anchor, List<ILocalPortal> portals)
	{
	}
}
