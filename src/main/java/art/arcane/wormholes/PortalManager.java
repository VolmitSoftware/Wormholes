package art.arcane.wormholes;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import art.arcane.wormholes.portal.DimensionalTunnel;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.BukkitRtpRuntime;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.J;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

public class PortalManager implements Listener
{
	private static final int LOAD_RETRY_INTERVAL_TICKS = 20;
	private static final int LOAD_RETRY_ATTEMPTS = 30;
	private static final int INITIAL_LOAD_RETRY_INTERVAL_TICKS = 100;
	private static final int INITIAL_LOAD_RETRY_MAX_INTERVAL_TICKS = 1200;
	private static final int PARTIAL_LOAD_RETRY_ATTEMPTS = 6;
	private static final int PARTIAL_LOAD_RETRY_INTERVAL_TICKS = 200;

	private final Map<UUID, ILocalPortal> portals;
	private volatile List<ILocalPortal> portalSnapshot = List.of();
	private final PortalRegistryAttendance attendance = new PortalRegistryAttendance();
	private final PortalRegistryStorage storage = new PortalRegistryStorage();
	private final PortalRegistryPendingFiles pendingPortalFiles;
	private final PortalRegistryUpdateDriver updateDriver;
	private final AtomicBoolean initialLoadRetryQueued;
	private final AtomicBoolean initialLoadRejectionReported;
	private final AtomicBoolean partialLoadRetryQueued;
	private volatile boolean initialLoadComplete;
	private int initialLoadFailureCount;
	private int partialLoadRetriesRemaining;

	public PortalManager()
	{
		Wormholes.v("Starting Portal Manager");
		portals = new ConcurrentHashMap<UUID, ILocalPortal>();
		pendingPortalFiles = new PortalRegistryPendingFiles();
		updateDriver = new PortalRegistryUpdateDriver(attendance);
		initialLoadRetryQueued = new AtomicBoolean();
		initialLoadRejectionReported = new AtomicBoolean();
		partialLoadRetryQueued = new AtomicBoolean();
		initialLoadComplete = false;
		initialLoadFailureCount = 0;
		partialLoadRetriesRemaining = PARTIAL_LOAD_RETRY_ATTEMPTS;
		int loadDelay = Bukkit.getWorlds().isEmpty() ? 40 : 1;
		scheduleInitialLoadAttempt(loadDelay);
		schedulePendingLoadRetry(LOAD_RETRY_ATTEMPTS);
		J.ar(() -> updateLocalPortals(), 0);
	}

	@EventHandler
	public void on(WorldLoadEvent e)
	{
		loadPendingPortals();
		BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
		if(runtime != null)
		{
			for(ILocalPortal portal : getLocalPortals())
			{
				if(portal instanceof LocalPortal localPortal)
				{
					runtime.synchronize(localPortal);
				}
			}
		}
	}

	@EventHandler
	public void on(WorldUnloadEvent e)
	{
		BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
		if(runtime != null)
		{
			runtime.worldUnloaded(e.getWorld().getUID());
		}
	}

	@EventHandler
	public void on(PlayerJoinEvent e)
	{
		attendance.record(e.getPlayer(), e.getPlayer().getLocation());
	}

	@EventHandler
	public void on(PlayerMoveEvent e)
	{
		attendance.record(e.getPlayer(), e.getTo());
		BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
		if(runtime != null && e.getTo() != null)
		{
			runtime.viewerMoved(e.getPlayer(), e.getTo());
		}
	}

	@EventHandler
	public void on(PlayerChangedWorldEvent e)
	{
		attendance.record(e.getPlayer(), e.getPlayer().getLocation());
		BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
		if(runtime != null)
		{
			runtime.leaveViewer(e.getPlayer().getUniqueId());
		}
	}

	@EventHandler
	public void on(PlayerQuitEvent e)
	{
		attendance.forget(e.getPlayer().getUniqueId());
		BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
		if(runtime != null)
		{
			runtime.leaveViewer(e.getPlayer().getUniqueId());
		}
	}

	private void loadExistingPortals()
	{
		Wormholes.v("Loading existing portals (worlds available: " + Bukkit.getWorlds().size() + ")...");
		PortalRegistryStorage.PortalFileListing listing;
		try
		{
			listing = storage.listPortalFiles();
		}
		catch(IOException error)
		{
			initialLoadFailureCount++;
			if(initialLoadFailureCount == 1 || initialLoadFailureCount % 5 == 0)
			{
				Wormholes.instance.getLogger().log(Level.SEVERE,
					"Could not enumerate stored portals; portal updates remain paused and loading will retry.",
					error);
			}
			if(initialLoadComplete)
			{
				schedulePartialLoadRetry();
			}
			else
			{
				int shift = Math.min(3, initialLoadFailureCount - 1);
				long retryDelay = Math.min(
					INITIAL_LOAD_RETRY_MAX_INTERVAL_TICKS,
					(long) INITIAL_LOAD_RETRY_INTERVAL_TICKS << shift
				);
				scheduleInitialLoadAttempt(retryDelay);
			}
			return;
		}
		initialLoadFailureCount = 0;
		List<File> portalFiles = listing.files();

		int found = 0;
		int loaded = 0;
		int skipped = 0;

		for(File k : portalFiles)
		{
			found++;
			PortalLoadResult result = loadPortal(k);
			if(result == PortalLoadResult.LOADED)
			{
				loaded++;
			}
			else if(result == PortalLoadResult.PENDING_WORLD)
			{
				pendingPortalFiles.add(k);
				skipped++;
			}
			else
			{
				skipped++;
			}
		}

		initialLoadComplete = true;
		if(!listing.failures().isEmpty())
		{
			Wormholes.instance.getLogger().log(
				Level.SEVERE,
				"Loaded readable portal files but skipped " + listing.failures().size()
					+ " path(s) that could not be enumerated.",
				listing.aggregateFailure()
			);
			schedulePartialLoadRetry();
		}
		else
		{
			partialLoadRetriesRemaining = PARTIAL_LOAD_RETRY_ATTEMPTS;
		}
		Wormholes.v("Portal load complete: " + loaded + " loaded, " + skipped + " skipped (of " + found + " files), pending=" + pendingPortalFiles.size());
	}

	private void schedulePartialLoadRetry()
	{
		if(partialLoadRetriesRemaining <= 0 || !Wormholes.instance.isEnabled()
			|| !partialLoadRetryQueued.compareAndSet(false, true))
		{
			return;
		}
		int attempt = PARTIAL_LOAD_RETRY_ATTEMPTS - partialLoadRetriesRemaining;
		long retryDelay = Math.min(
			INITIAL_LOAD_RETRY_MAX_INTERVAL_TICKS,
			(long) PARTIAL_LOAD_RETRY_INTERVAL_TICKS << Math.min(3, attempt)
		);
		boolean scheduled = FoliaScheduler.runGlobal(Wormholes.instance, () ->
		{
			partialLoadRetryQueued.set(false);
			loadExistingPortals();
		}, retryDelay);
		if(scheduled)
		{
			partialLoadRetriesRemaining--;
			return;
		}
		CompletableFuture.delayedExecutor(1L, TimeUnit.SECONDS).execute(() ->
		{
			partialLoadRetryQueued.set(false);
			schedulePartialLoadRetry();
		});
	}

	private void scheduleInitialLoadAttempt(long delayTicks)
	{
		if(initialLoadComplete || !Wormholes.instance.isEnabled())
		{
			return;
		}
		if(FoliaScheduler.runGlobal(Wormholes.instance, this::loadExistingPortals, delayTicks))
		{
			initialLoadRetryQueued.set(false);
			initialLoadRejectionReported.set(false);
			return;
		}
		if(!initialLoadRetryQueued.compareAndSet(false, true))
		{
			return;
		}
		if(initialLoadRejectionReported.compareAndSet(false, true))
		{
			Wormholes.instance.getLogger().warning(
				"Global scheduler refused portal loading; a scheduler submission retry will run in one second.");
		}
		CompletableFuture.delayedExecutor(1L, TimeUnit.SECONDS).execute(() ->
		{
			initialLoadRetryQueued.set(false);
			scheduleInitialLoadAttempt(0L);
		});
	}

	private synchronized PortalLoadResult loadPortal(File k)
	{
		try
		{
			PortalRegistryStorage.StoredPortal stored = storage.readPortal(k);

			if(stored.pendingWorld())
			{
				Wormholes.w("Skipping portal " + k.getName() + " - world '" + stored.worldKey() + "' is not loaded");
				return PortalLoadResult.PENDING_WORLD;
			}

			if(stored.portal() == null)
			{
				Wormholes.w("Skipping portal " + k.getName() + " - structure world resolved to null after load");
				return PortalLoadResult.FAILED;
			}

			ILocalPortal portal = stored.portal();
			addLocalPortal(portal);
			Wormholes.v("Loaded portal " + portal.getId() + " (" + portal.getName() + ") in " + stored.worldKey());
			return PortalLoadResult.LOADED;
		}
		catch(Throwable e)
		{
			Wormholes.f("Failed to load portal file " + k.getName());
			e.printStackTrace();
			return PortalLoadResult.FAILED;
		}
	}

	private synchronized void loadPendingPortals()
	{
		if(pendingPortalFiles.isEmpty())
		{
			return;
		}

		pendingPortalFiles.resolve(file -> loadPortal(file) != PortalLoadResult.PENDING_WORLD);
	}

	private void schedulePendingLoadRetry(int remainingAttempts)
	{
		if(remainingAttempts <= 0)
		{
			return;
		}
		J.s(() ->
		{
			loadPendingPortals();
			if(!pendingPortalFiles.isEmpty())
			{
				schedulePendingLoadRetry(remainingAttempts - 1);
			}
		}, LOAD_RETRY_INTERVAL_TICKS);
	}

	public void saveAll()
	{
		for(ILocalPortal i : getLocalPortals())
		{
			i.save();
		}
	}

	public void saveAllNow()
	{
		for(ILocalPortal i : getLocalPortals())
		{
			try
			{
				i.saveNow();
			}

			catch(IOException e)
			{
				e.printStackTrace();
			}
		}
	}

	public void updateLocalPortals()
	{
		if(!initialLoadComplete)
		{
			return;
		}

		updateDriver.tick(getLocalPortals());
	}

	public List<ILocalPortal> getLocalPortals()
	{
		return portalSnapshot;
	}

	public boolean hasLocalPortal(UUID id)
	{
		return portals.containsKey(id);
	}

	public ILocalPortal getLocalPortal(UUID id)
	{
		return portals.get(id);
	}

	public boolean hasLocalPortal(IPortal portal)
	{
		return hasLocalPortal(portal.getId());
	}

	public synchronized void addLocalPortal(ILocalPortal portal)
	{
		if(portals.putIfAbsent(portal.getId(), portal) == null)
		{
			refreshPortalSnapshot();
			Wormholes.instance.registerListener(portal);
			BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
			if(runtime != null && portal instanceof LocalPortal localPortal)
			{
				runtime.synchronize(localPortal);
			}

			if(Wormholes.portalSyncService != null)
			{
				Wormholes.portalSyncService.broadcastPortal(portal);
			}
			syncGatewayTickets();
		}
	}

	public synchronized void removeLocalPortal(UUID portal)
	{
		ILocalPortal existing = portals.get(portal);
		BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
		if(existing != null && runtime != null)
		{
			runtime.unregister(portal);
		}
		ILocalPortal removed = portals.remove(portal);
		if(removed != null)
		{
			Wormholes.instance.unregisterListener(removed);

			if(Wormholes.portalSyncService != null)
			{
				Wormholes.portalSyncService.broadcastRemove(portal);
			}
		}

		refreshPortalSnapshot();
		syncGatewayTickets();
	}

	public void removeLocalPortal(IPortal portal)
	{
		removeLocalPortal(portal.getId());
	}

	public synchronized int deleteAllPortals()
	{
		List<ILocalPortal> snapshot = getLocalPortals();

		for(ILocalPortal portal : snapshot)
		{
			BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
			if(runtime != null)
			{
				runtime.unregister(portal.getId());
			}
			if(Wormholes.projectionManager != null)
			{
				Wormholes.projectionManager.removeProjector(portal);
			}
			Wormholes.instance.unregisterListener(portal);
			if(Wormholes.portalSyncService != null)
			{
				Wormholes.portalSyncService.broadcastRemove(portal.getId());
			}
		}

		portals.clear();
		refreshPortalSnapshot();
		pendingPortalFiles.clear();
		initialLoadComplete = true;
		storage.deletePortalFolder();
		syncGatewayTickets();
		return snapshot.size();
	}

	public int getTotalPortalCount()
	{
		return getLocalPortals().size();
	}

	public int getAccessableCount(PortalType t)
	{
		if(t.equals(PortalType.GATEWAY))
		{
			return getGatewayCount();
		}

		return getTotalPortalCount() - getGatewayCount();
	}

	public List<GatewayPortalInfo> listGatewayPortalsNear(Location origin, double radius)
	{
		if(origin == null || origin.getWorld() == null)
		{
			return List.of();
		}
		double radiusSquared = radius * radius;
		List<GatewayPortalInfo> matches = new ArrayList<GatewayPortalInfo>();
		for(ILocalPortal portal : getLocalPortals())
		{
			if(!portal.isGateway())
			{
				continue;
			}
			Location center = portal.getCenter();
			if(center == null || center.getWorld() == null || !center.getWorld().equals(origin.getWorld()))
			{
				continue;
			}
			double dx = center.getX() - origin.getX();
			double dy = center.getY() - origin.getY();
			double dz = center.getZ() - origin.getZ();
			double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
			if(distanceSquared > radiusSquared)
			{
				continue;
			}
			Direction normal = portal.getFrame() == null ? null : portal.getFrame().getNormal();
			double nx = normal == null ? 0.0D : normal.x();
			double ny = normal == null ? 0.0D : normal.y();
			double nz = normal == null ? 0.0D : normal.z();
			matches.add(new GatewayPortalInfo(portal.getId(), center.getX(), center.getY(), center.getZ(), nx, ny, nz));
		}
		return matches;
	}

	public record GatewayPortalInfo(UUID portalId, double centerX, double centerY, double centerZ, double normalX, double normalY, double normalZ)
	{
	}

	public int getGatewayCount()
	{
		int g = 0;

		for(ILocalPortal i : getLocalPortals())
		{
			if(i.isGateway())
			{
				g++;
			}
		}

		return g;
	}

	public File getSaveFile(UUID id)
	{
		return storage.saveFile(id);
	}

	public synchronized boolean deletePersistedPairedPortal(UUID portalId, UUID expectedCounterpartId)
	{
		if(portalId == null || expectedCounterpartId == null)
		{
			return false;
		}
		File file = storage.saveFile(portalId);
		ILocalPortal loaded = portals.get(portalId);
		if(loaded != null)
		{
			if(expectedCounterpartId.equals(loaded.getDimensionalCounterpartId()))
			{
				loaded.destroy();
				return true;
			}
			return false;
		}
		if(!file.isFile())
		{
			pendingPortalFiles.remove(file);
			return false;
		}
		if(!storage.isPairedCounterpart(file, portalId, expectedCounterpartId))
		{
			return false;
		}
		pendingPortalFiles.remove(file);
		return storage.deletePortalFile(file, portalId, expectedCounterpartId);
	}

	public synchronized boolean clearPersistedPairedPortalReference(UUID portalId, UUID expectedCounterpartId)
	{
		if(portalId == null || expectedCounterpartId == null)
		{
			return false;
		}
		ILocalPortal loaded = portals.get(portalId);
		if(loaded != null)
		{
			boolean reciprocal = expectedCounterpartId.equals(loaded.getDimensionalCounterpartId());
			if(!reciprocal && loaded.getTunnel() instanceof DimensionalTunnel dimensionalTunnel)
			{
				reciprocal = expectedCounterpartId.equals(dimensionalTunnel.getDestinationId());
			}
			if(reciprocal)
			{
				loaded.unlink();
				return true;
			}
			return false;
		}
		File file = storage.saveFile(portalId);
		if(!file.isFile())
		{
			return false;
		}
		return storage.clearPairedReference(file, portalId, expectedCounterpartId);
	}

	public void shutDown()
	{
		Wormholes.v("Shutting down portal manager");
		saveAllNow();
		BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
		if(runtime != null)
		{
			for(ILocalPortal portal : getLocalPortals())
			{
				runtime.unregister(portal.getId());
			}
		}
		clearRegistry();
	}

	private synchronized void clearRegistry()
	{
		pendingPortalFiles.clear();
		portals.clear();
		refreshPortalSnapshot();
	}

	private synchronized void refreshPortalSnapshot()
	{
		portalSnapshot = List.copyOf(portals.values());
	}

	private void syncGatewayTickets()
	{
		ViewServer activeViewServer = Wormholes.viewServer;
		if(activeViewServer != null)
		{
			activeViewServer.syncGatewayTickets();
		}
	}

	private enum PortalLoadResult
	{
		LOADED,
		PENDING_WORLD,
		FAILED
	}
}
