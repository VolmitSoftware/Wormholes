package art.arcane.wormholes.portal;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.LocalPortalTransitRegistry.ReentryLatch;
import art.arcane.wormholes.portal.rtp.RtpTraversalHoldPolicy;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.wormholes.service.WormholesTelemetry;

final class LocalPortalDepartureHold
{
	static final long HOLD_NOTICE_DELAY_MILLIS = 500L;
	static final long HOLD_NOTICE_PERIOD_MILLIS = 1_000L;

	private final LocalPortal portal;
	private final LocalPortalRuntime runtime;

	LocalPortalDepartureHold(LocalPortal portal)
	{
		this(portal, LocalPortalRuntime.BUKKIT);
	}

	LocalPortalDepartureHold(LocalPortal portal, LocalPortalRuntime runtime)
	{
		this.portal = portal;
		this.runtime = runtime;
	}

	void startRtpTraversalHold(Entity entity, Traversive traversive)
	{
		PortalStructure portalStructure = portal.getStructure();
		World sourceWorld = portalStructure == null ? null : portalStructure.getWorld();
		UUID entityId = entity.getUniqueId();
		if(sourceWorld == null)
		{
			failRtpHold(entity, traversive, entityId, "the source world is unavailable");
			return;
		}
		Location anchor = entity.getLocation().clone();
		long startedAtMillis = System.currentTimeMillis();
		long[] lastNoticeMillis = new long[] {0L};
		Runnable[] step = new Runnable[1];
		step[0] = () ->
		{
			if(!entity.isValid())
			{
				abortRtpTraversal(entityId);
				return;
			}
			long nowMillis = System.currentTimeMillis();
			ReentryLatch latch = LocalPortalTransitRegistry.activeReentryLatch(entityId, nowMillis);
			boolean completed = latch != null && portal.getId().equals(latch.portalId());
			boolean inFlight = LocalPortalTransitRegistry.isTeleportInFlight(entityId, nowMillis);
			boolean sameWorld = sourceWorld.equals(entity.getWorld());
			Location current = entity.getLocation();
			double driftSquared = sameWorld ? current.distanceSquared(anchor) : Double.MAX_VALUE;
			double sideDistance = sameWorld ? LocalPortalTraversal.sourceSideDistance(traversive, current.toVector()) : 0.0D;
			switch(RtpTraversalHoldPolicy.decide(completed, inFlight, sameWorld, sideDistance, driftSquared, nowMillis - startedAtMillis))
			{
				case STOP_ARRIVED ->
				{
				}
				case CANCEL_RETREAT -> abortRtpTraversal(entityId);
				case BOUNCE_FAILED -> portal.traversal().bounceFailedRtpTraversal(entity, traversive);
				case BOUNCE_TIMEOUT ->
				{
					abortRtpTraversal(entityId);
					portal.traversal().bounceFailedRtpTraversal(entity, traversive);
				}
				case HOLD_FREE ->
				{
					if(!runtime.dispatch(entity, step[0], 1L))
					{
						failRtpHold(entity, traversive, entityId, "the entity scheduler rejected the hold");
					}
				}
				case HOLD_PIN ->
				{
					if(nowMillis - startedAtMillis >= HOLD_NOTICE_DELAY_MILLIS
							&& nowMillis - lastNoticeMillis[0] >= HOLD_NOTICE_PERIOD_MILLIS
							&& entity instanceof Player player)
					{
						lastNoticeMillis[0] = nowMillis;
						WormholesHud.hold(player, Wormholes.text().component(WormholesMessages.PORTAL_RTP_NOT_READY));
					}
					entity.setVelocity(new Vector(0, 0, 0));
					if(driftSquared > RtpTraversalHoldPolicy.LEASH_DRIFT_SQUARED)
					{
						Location snap = anchor.clone();
						snap.setYaw(current.getYaw());
						snap.setPitch(current.getPitch());
						entity.teleport(snap, PlayerTeleportEvent.TeleportCause.PLUGIN);
					}
					if(!runtime.dispatch(entity, step[0], 1L))
					{
						failRtpHold(entity, traversive, entityId, "the entity scheduler rejected the hold");
					}
				}
			}
		};
		if(!runtime.dispatch(entity, step[0], 1L))
		{
			failRtpHold(entity, traversive, entityId, "the entity scheduler rejected the hold");
		}
	}

	private void failRtpHold(Entity entity, Traversive traversive, UUID entityId, String reason)
	{
		abortRtpTraversal(entityId);
		Wormholes.w("Random teleport hold at portal " + portal.getId() + " ended early for " + entity.getName() + ": " + reason);
		if(entity.isValid())
		{
			portal.traversal().bounceFailedRtpTraversal(entity, traversive);
		}
		WormholesTelemetry.countFailure("TRAVERSAL_RTP_HOLD_FAILED");
	}

	private void abortRtpTraversal(UUID entityId)
	{
		if(Wormholes.rtpRuntime != null)
		{
			Wormholes.rtpRuntime.abortTraversal(entityId);
			return;
		}
		LocalPortalTransitRegistry.clearTeleportInFlight(entityId);
	}

	void startPlayerDepartureHold(Player player, Traversive traversive)
	{
		PortalStructure portalStructure = portal.getStructure();
		World sourceWorld = portalStructure == null ? null : portalStructure.getWorld();
		UUID playerId = player.getUniqueId();
		if(sourceWorld == null)
		{
			failDepartureHold(player, "the source world is unavailable");
			return;
		}
		Location anchor = player.getLocation().clone();
		long startedAtMillis = System.currentTimeMillis();
		long[] lastNoticeMillis = new long[] {0L};
		Runnable[] step = new Runnable[1];
		step[0] = () ->
		{
			if(!player.isValid())
			{
				return;
			}
			long nowMillis = System.currentTimeMillis();
			boolean inFlight = LocalPortalTransitRegistry.isTeleportInFlight(playerId, nowMillis);
			boolean sameWorld = sourceWorld.equals(player.getWorld());
			Location current = player.getLocation();
			double driftSquared = sameWorld ? current.distanceSquared(anchor) : Double.MAX_VALUE;
			double sideDistance = sameWorld ? LocalPortalTraversal.sourceSideDistance(traversive, current.toVector()) : 0.0D;
			switch(DepartureHoldPolicy.decide(inFlight, sameWorld, sideDistance, driftSquared, nowMillis - startedAtMillis))
			{
				case STOP ->
				{
				}
				case CANCEL_RETREAT -> abortDepartureHold(playerId);
				case HOLD_PIN ->
				{
					if(nowMillis - startedAtMillis >= HOLD_NOTICE_DELAY_MILLIS
							&& nowMillis - lastNoticeMillis[0] >= HOLD_NOTICE_PERIOD_MILLIS)
					{
						lastNoticeMillis[0] = nowMillis;
						WormholesHud.hold(player, Wormholes.text().component(WormholesMessages.PORTAL_TRANSFER_HOLDING));
					}
					player.setVelocity(new Vector(0, 0, 0));
					if(driftSquared > DepartureHoldPolicy.LEASH_DRIFT_SQUARED)
					{
						Location snap = anchor.clone();
						snap.setYaw(current.getYaw());
						snap.setPitch(current.getPitch());
						player.teleport(snap, PlayerTeleportEvent.TeleportCause.PLUGIN);
					}
					if(!runtime.dispatch(player, step[0], 1L))
					{
						failDepartureHold(player, "the entity scheduler rejected the hold");
					}
				}
			}
		};
		if(!runtime.dispatch(player, step[0], 1L))
		{
			failDepartureHold(player, "the entity scheduler rejected the hold");
		}
	}

	private void failDepartureHold(Player player, String reason)
	{
		abortDepartureHold(player.getUniqueId());
		Wormholes.w("Cross-server departure hold at portal " + portal.getId() + " ended early for " + player.getName() + ": " + reason);
		WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.PORTAL_TRANSFER_SOURCE_UNAVAILABLE));
		WormholesTelemetry.countFailure("TRAVERSAL_DEPARTURE_HOLD_FAILED");
	}

	private void abortDepartureHold(UUID playerId)
	{
		if(Wormholes.traversalService != null)
		{
			Wormholes.traversalService.cancelPendingHandoff(playerId);
			return;
		}
		LocalPortalTransitRegistry.clearTeleportInFlight(playerId);
	}
}
