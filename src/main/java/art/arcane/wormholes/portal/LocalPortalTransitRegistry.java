package art.arcane.wormholes.portal;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Entity;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;

final class LocalPortalTransitRegistry
{
	private static final long REENTRY_LATCH_TTL_MILLIS = 60_000L;
	private static final long REJECTED_REENTRY_LATCH_TTL_MILLIS = 2_500L;
	private static final long REENTRY_WAITING_EXIT_GRACE_MILLIS = 2_000L;
	private static final long TELEPORT_IN_FLIGHT_TTL_MILLIS = 30_000L;
	private static final ConcurrentHashMap<UUID, Long> TELEPORT_COOLDOWNS = new ConcurrentHashMap<UUID, Long>();
	private static final ConcurrentHashMap<UUID, ReentryLatch> REENTRY_LATCHES = new ConcurrentHashMap<UUID, ReentryLatch>();
	private static final ConcurrentHashMap<UUID, Long> TELEPORT_IN_FLIGHT = new ConcurrentHashMap<UUID, Long>();

	private LocalPortalTransitRegistry()
	{
	}

	static boolean isTeleportCoolingDown(UUID entityId, long now)
	{
		Long until = TELEPORT_COOLDOWNS.get(entityId);
		if(until == null)
		{
			return false;
		}
		if(until.longValue() <= now)
		{
			TELEPORT_COOLDOWNS.remove(entityId, until);
			return false;
		}
		return true;
	}

	static void markTeleportCooldown(UUID entityId, long now)
	{
		long cooldown = Settings.TELEPORT_COOLDOWN_MILLIS;
		if(cooldown <= 0L)
		{
			TELEPORT_COOLDOWNS.remove(entityId);
			return;
		}
		TELEPORT_COOLDOWNS.put(entityId, Long.valueOf(now + cooldown));
	}

	static boolean clearTeleportCooldown(UUID entityId)
	{
		return TELEPORT_COOLDOWNS.remove(entityId) != null;
	}

	static void markRefusedBounce(UUID entityId, UUID portalId)
	{
		markTeleportCooldown(entityId, System.currentTimeMillis());
		if(portalId != null)
		{
			latchRejectedReentry(entityId, portalId);
		}
	}

	static boolean markTeleportInFlight(UUID entityId, long now)
	{
		boolean[] acquired = new boolean[1];
		TELEPORT_IN_FLIGHT.compute(entityId, (id, stamped) ->
		{
			if(stamped != null && now - stamped.longValue() < TELEPORT_IN_FLIGHT_TTL_MILLIS)
			{
				return stamped;
			}
			acquired[0] = true;
			return Long.valueOf(now);
		});
		return acquired[0];
	}

	static boolean isTeleportInFlight(UUID entityId, long now)
	{
		Long stamped = TELEPORT_IN_FLIGHT.get(entityId);
		if(stamped == null)
		{
			return false;
		}
		if(now - stamped.longValue() >= TELEPORT_IN_FLIGHT_TTL_MILLIS)
		{
			TELEPORT_IN_FLIGHT.remove(entityId, stamped);
			return false;
		}
		return true;
	}

	static boolean hasTeleportInFlightEntry(UUID entityId)
	{
		return TELEPORT_IN_FLIGHT.containsKey(entityId);
	}

	static boolean clearTeleportInFlight(UUID entityId)
	{
		return TELEPORT_IN_FLIGHT.remove(entityId) != null;
	}

	static void latchReentry(UUID entityId, UUID portalId)
	{
		REENTRY_LATCHES.put(entityId, ReentryLatch.waiting(portalId, System.currentTimeMillis()));
	}

	static void latchRejectedReentry(UUID entityId, UUID portalId)
	{
		REENTRY_LATCHES.put(entityId, ReentryLatch.armed(portalId, System.currentTimeMillis()));
	}

	static boolean isReentryLatched(UUID entityId)
	{
		return REENTRY_LATCHES.containsKey(entityId);
	}

	static void clearReentryLatch(UUID entityId)
	{
		REENTRY_LATCHES.remove(entityId);
	}

	static ReentryLatch activeReentryLatch(UUID entityId, long now)
	{
		ReentryLatch latch = REENTRY_LATCHES.get(entityId);
		if(latch == null)
		{
			return null;
		}
		if(reentryLatchExpired(latch.rejected(), latch.stampMillis(), now))
		{
			REENTRY_LATCHES.remove(entityId, latch);
			return null;
		}
		return latch;
	}

	static boolean reentryLatchExpired(boolean rejected, long stampMillis, long now)
	{
		long ttl = rejected ? REJECTED_REENTRY_LATCH_TTL_MILLIS : REENTRY_LATCH_TTL_MILLIS;
		return stampMillis + ttl <= now;
	}

	static boolean shouldReleaseReentryLatchOutsidePortal(boolean armed, long stampMillis, long now)
	{
		return armed || now - stampMillis >= REENTRY_WAITING_EXIT_GRACE_MILLIS;
	}

	static void latchReentryIfInsidePortal(Entity entity)
	{
		if(entity == null || Wormholes.portalManager == null)
		{
			return;
		}
		if(isReentryLatched(entity.getUniqueId()))
		{
			return;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(portal instanceof LocalPortal local && local.traversal().isOccupyingPortal(entity))
			{
				latchReentry(entity.getUniqueId(), local.getId());
				return;
			}
		}
	}

	static void pruneTeleportCooldowns(long now)
	{
		if(TELEPORT_COOLDOWNS.size() < 256 && REENTRY_LATCHES.size() < 256)
		{
			return;
		}

		Iterator<Map.Entry<UUID, Long>> iterator = TELEPORT_COOLDOWNS.entrySet().iterator();
		while(iterator.hasNext())
		{
			Map.Entry<UUID, Long> entry = iterator.next();
			if(entry.getValue().longValue() <= now)
			{
				TELEPORT_COOLDOWNS.remove(entry.getKey(), entry.getValue());
			}
		}
		Iterator<Map.Entry<UUID, ReentryLatch>> latchIterator = REENTRY_LATCHES.entrySet().iterator();
		while(latchIterator.hasNext())
		{
			Map.Entry<UUID, ReentryLatch> entry = latchIterator.next();
			if(reentryLatchExpired(entry.getValue().rejected(), entry.getValue().stampMillis(), now))
			{
				REENTRY_LATCHES.remove(entry.getKey(), entry.getValue());
			}
		}
		Iterator<Map.Entry<UUID, Long>> inFlightIterator = TELEPORT_IN_FLIGHT.entrySet().iterator();
		while(inFlightIterator.hasNext())
		{
			Map.Entry<UUID, Long> entry = inFlightIterator.next();
			if(now - entry.getValue().longValue() >= TELEPORT_IN_FLIGHT_TTL_MILLIS)
			{
				TELEPORT_IN_FLIGHT.remove(entry.getKey(), entry.getValue());
			}
		}
	}

	static final class ReentryLatch
	{
		private final UUID portalId;
		private final long stampMillis;
		private final boolean rejected;
		private volatile boolean armed;

		private ReentryLatch(UUID portalId, long stampMillis, boolean rejected)
		{
			this.portalId = portalId;
			this.stampMillis = stampMillis;
			this.rejected = rejected;
			this.armed = rejected;
		}

		private static ReentryLatch waiting(UUID portalId, long stampMillis)
		{
			return new ReentryLatch(portalId, stampMillis, false);
		}

		private static ReentryLatch armed(UUID portalId, long stampMillis)
		{
			return new ReentryLatch(portalId, stampMillis, true);
		}

		UUID portalId()
		{
			return portalId;
		}

		long stampMillis()
		{
			return stampMillis;
		}

		boolean rejected()
		{
			return rejected;
		}

		boolean armed()
		{
			return armed;
		}

		void arm()
		{
			armed = true;
		}
	}
}
