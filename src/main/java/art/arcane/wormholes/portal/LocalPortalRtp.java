package art.arcane.wormholes.portal;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.JSONObject;

final class LocalPortalRtp
{
	private static final long UNREADY_NOTICE_PERIOD_MILLIS = 1_500L;

	private final LocalPortal portal;
	private final ConcurrentHashMap<UUID, Long> unreadyNoticeMillis = new ConcurrentHashMap<UUID, Long>();
	private final AtomicLong configurationRevision = new AtomicLong();
	private volatile RtpSettings rtpSettings;
	private volatile JSONObject unresolvedRtpJson;

	LocalPortalRtp(LocalPortal portal)
	{
		this.portal = portal;
	}

	void initializeDefaults(PortalType type)
	{
		rtpSettings = type == PortalType.RTP ? defaultRtpSettings() : null;
	}

	void ensureDefaults()
	{
		if(rtpSettings == null)
		{
			rtpSettings = defaultRtpSettings();
		}
	}

	RtpSettings getRtpSettings()
	{
		return rtpSettings;
	}

	long revision()
	{
		return configurationRevision.get();
	}

	void setRtpSettings(RtpSettings settings)
	{
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		World sourceWorld = portal.getStructure().getWorld();
		if(sourceWorld == null || !WorldIdentity.serialize(sourceWorld).equals(requiredSettings.getSourceWorldKey()))
		{
			throw new IllegalArgumentException("RTP settings source world must match the portal source world");
		}
		Location center = portal.getStructure().getCenter();
		World world = center == null ? null : center.getWorld();
		if(Wormholes.instance != null && world != null
				&& !FoliaScheduler.isOwnedByCurrentRegion(world, center.getBlockX() >> 4, center.getBlockZ() >> 4))
		{
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, center, () -> applyRtpSettings(requiredSettings));
			if(!scheduled)
			{
				throw new IllegalStateException("RTP settings update could not be routed to the source portal region");
			}
			return;
		}
		applyRtpSettings(requiredSettings);
	}

	void applyRtpSettings(RtpSettings settings)
	{
		boolean changed = !settings.equals(rtpSettings);
		rtpSettings = settings;
		unresolvedRtpJson = null;
		if(changed)
		{
			advanceConfigurationRevision();
			portal.save();
			if(Wormholes.rtpRuntime != null)
			{
				Wormholes.rtpRuntime.synchronize(portal);
			}
		}
	}

	void advanceConfigurationRevision()
	{
		configurationRevision.updateAndGet(value -> value == Long.MAX_VALUE ? 1L : value + 1L);
	}

	RtpSettings defaultRtpSettings()
	{
		PortalStructure structure = portal.getStructure();
		World world = structure == null ? null : structure.getWorld();
		return world == null ? null : RtpSettings.defaults(world);
	}

	World resolveRtpWorld(String worldKey)
	{
		if(worldKey == null || worldKey.isBlank())
		{
			return portal.getStructure().getWorld();
		}
		World resolved = WorldIdentity.resolve(worldKey).orElse(null);
		return PocketWorldService.isPocketWorld(resolved) ? null : resolved;
	}

	void save(JSONObject j)
	{
		if(rtpSettings != null)
		{
			j.put("rtp", rtpSettings.toJson());
			return;
		}
		JSONObject unresolved = unresolvedRtpJson;
		if(unresolved != null)
		{
			j.put("rtp", new JSONObject(unresolved.toString()));
		}
	}

	void load(JSONObject json)
	{
		unresolvedRtpJson = null;
		rtpSettings = readRtpSettings(json);
	}

	private RtpSettings readRtpSettings(JSONObject json)
	{
		if(!json.has("rtp"))
		{
			return portal.getType() == PortalType.RTP ? defaultRtpSettings() : null;
		}
		World world = portal.getStructure().getWorld();
		if(world == null)
		{
			unresolvedRtpJson = json.optJSONObject("rtp");
			Wormholes.w("Portal " + portal.getId() + " loaded without its world; RTP settings are preserved unread until the world is available");
			return null;
		}
		JSONObject stored = json.optJSONObject("rtp");
		if(stored == null)
		{
			Wormholes.w("Portal " + portal.getId() + " has malformed RTP settings; approved defaults will be persisted");
			return defaultRtpSettings();
		}
		return RtpSettings.fromJson(stored, this::resolveRtpWorld);
	}

	boolean requiresPersistenceNormalization(JSONObject json)
	{
		if(!json.has("rtp"))
		{
			return rtpSettings != null;
		}
		if(rtpSettings == null)
		{
			return unresolvedRtpJson == null;
		}
		JSONObject stored = json.optJSONObject("rtp");
		if(stored == null)
		{
			return true;
		}
		JSONObject canonicalStored = new JSONObject(stored.toString());
		JSONObject canonicalSettings = new JSONObject(rtpSettings.toJson().toString());
		return !canonicalStored.similar(canonicalSettings);
	}

	boolean normalizeState()
	{
		if(portal.getType() != PortalType.RTP)
		{
			return false;
		}
		boolean changed = false;
		if(portal.getTunnel() != null)
		{
			portal.linking().assignTunnel(null);
			changed = true;
		}
		if(portal.getDimensionalCounterpartId() != null)
		{
			portal.linking().assignDimensionalCounterpartId(null);
			changed = true;
		}
		if(portal.isMirrorMode())
		{
			portal.settings().assignMirrorMode(false);
			changed = true;
		}
		if(rtpSettings == null)
		{
			rtpSettings = defaultRtpSettings();
			changed = rtpSettings != null || changed;
		}
		return changed;
	}

	boolean runSourceTask(Runnable task)
	{
		PortalStructure structure = portal.getStructure();
		Location center = structure == null ? null : structure.getCenter();
		World world = center == null ? null : center.getWorld();
		if(Wormholes.instance == null || world == null)
		{
			task.run();
			return true;
		}
		if(FoliaScheduler.isOwnedByCurrentRegion(world, center.getBlockX() >> 4, center.getBlockZ() >> 4))
		{
			task.run();
			return true;
		}
		return FoliaScheduler.runRegion(Wormholes.instance, center, task);
	}

	void updateTick()
	{
		if(Wormholes.rtpRuntime == null)
		{
			portal.close();
			return;
		}
		Wormholes.rtpRuntime.synchronize(portal);
		Wormholes.rtpRuntime.tick(portal.getId());
		boolean shouldBeOpen = Wormholes.rtpRuntime.isReady(portal.getId());
		if(portal.isOpen())
		{
			if(portal.isAmbientAttended())
			{
				portal.playEffect(PortalEffect.AMBIENT_OPEN);
			}
			if(shouldBeOpen)
			{
				portal.traversal().updateCaptures(null, false);
			}
			else
			{
				portal.close();
			}
		}
		else
		{
			if(portal.isAmbientAttended())
			{
				portal.playEffect(PortalEffect.AMBIENT_CLOSED);
			}
			if(shouldBeOpen)
			{
				portal.open();
			}
			else
			{
				notifyUnreadyCrossings();
			}
		}
		if(Settings.DEBUG_RENDERING)
		{
			portal.playEffect(PortalEffect.AMBIENT_DEBUG);
		}
	}

	private void notifyUnreadyCrossings()
	{
		if(!portal.isAmbientAttended() || rtpSettings == null)
		{
			return;
		}
		PortalStructure portalStructure = portal.getStructure();
		if(portalStructure == null || portalStructure.getWorld() == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		for(Entity i : portalStructure.getCaptureZone().getEntities(portalStructure.getWorld()))
		{
			if(!(i instanceof Player player) || !portalStructure.contains(player.getLocation()))
			{
				continue;
			}
			Long last = unreadyNoticeMillis.get(player.getUniqueId());
			if(last != null && now - last.longValue() < UNREADY_NOTICE_PERIOD_MILLIS)
			{
				continue;
			}
			if(unreadyNoticeMillis.size() > 64)
			{
				unreadyNoticeMillis.entrySet().removeIf(entry -> now - entry.getValue().longValue() >= UNREADY_NOTICE_PERIOD_MILLIS);
			}
			unreadyNoticeMillis.put(player.getUniqueId(), Long.valueOf(now));
			WormholesAudience.sendActionBar(player, Wormholes.text().component(WormholesMessages.PORTAL_RTP_NOT_READY));
		}
	}

	boolean beginTraversal(Entity entity, long nowMillis)
	{
		Objects.requireNonNull(entity, "entity");
		if(portal.getType() != PortalType.RTP || !portal.isOpen() || !portal.canDepart(entity)
				|| entity.getVehicle() != null || !entity.getPassengers().isEmpty())
		{
			return false;
		}
		LocalPortalTransitRegistry.ReentryLatch latch = LocalPortalTransitRegistry.activeReentryLatch(entity.getUniqueId(), nowMillis);
		if(latch != null && portal.getId().equals(latch.portalId()))
		{
			return false;
		}
		if(LocalPortalTransitRegistry.isTeleportCoolingDown(entity.getUniqueId(), nowMillis))
		{
			return false;
		}
		return LocalPortalTransitRegistry.markTeleportInFlight(entity.getUniqueId(), nowMillis);
	}

	boolean canContinueTraversal(Entity entity)
	{
		Objects.requireNonNull(entity, "entity");
		return portal.getType() == PortalType.RTP
				&& entity.isValid()
				&& portal.canDepart(entity)
				&& entity.getVehicle() == null
				&& entity.getPassengers().isEmpty()
				&& LocalPortalTransitRegistry.hasTeleportInFlightEntry(entity.getUniqueId());
	}

	void cancelTraversal(Entity entity)
	{
		if(entity != null)
		{
			LocalPortalTransitRegistry.clearTeleportInFlight(entity.getUniqueId());
		}
	}

	void completeTraversal(Entity entity, Traversive traversive, PortalFrame targetFrame, Location target)
	{
		Entity requiredEntity = Objects.requireNonNull(entity, "entity");
		Traversive requiredTraversive = Objects.requireNonNull(traversive, "traversive");
		PortalFrame requiredTargetFrame = Objects.requireNonNull(targetFrame, "targetFrame");
		Location requiredTarget = Objects.requireNonNull(target, "target");
		if(!LocalPortalTransitRegistry.clearTeleportInFlight(requiredEntity.getUniqueId()))
		{
			return;
		}
		requiredEntity.setVelocity(requiredTraversive.getOutVelocity(requiredTargetFrame));
		Wormholes.v("QA_EVT {\"event\":\"rtp_traversal_complete\",\"status\":\"pass\",\"details\":\"arrival_confirmed\",\"context\":{\"portal\":\""
				+ portal.getId() + "\",\"frontSide\":" + requiredTraversive.isFrontSide() + ",\"targetNormal\":\""
				+ requiredTargetFrame.getNormal().name() + "\"}}");
		LocalPortalTransitRegistry.markTeleportCooldown(requiredEntity.getUniqueId(), System.currentTimeMillis());
		LocalPortalTransitRegistry.latchReentry(requiredEntity.getUniqueId(), portal.getId());
		WormholesTelemetry.countTraversal();
		if(Wormholes.instance != null)
		{
			World sourceWorld = portal.getStructure() == null ? null : portal.getStructure().getWorld();
			if(sourceWorld != null)
			{
				portal.playEffect(PortalEffect.PUSH, requiredTraversive.getInPoint().toLocation(sourceWorld));
			}
			if(requiredTarget.getWorld() != null)
			{
				portal.playEffect(PortalEffect.PUSH, requiredTarget);
			}
			if(requiredEntity instanceof Player player)
			{
				World source = portal.getStructure() == null ? null : portal.getStructure().getWorld();
				ArrivalTransition.apply(player, source != null && requiredTarget.getWorld() != null && !source.equals(requiredTarget.getWorld()));
				if(Wormholes.projectionManager != null)
				{
					Wormholes.projectionManager.reprimeArrival(player);
				}
			}
		}
	}
}
