package art.arcane.wormholes.door;

import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

final class DoorTransitLedger
{
	private static final long TRANSIT_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(1L);

	private final Plugin plugin;
	private final ConcurrentHashMap<UUID, Entity> travelersInTransit;
	private final ConcurrentHashMap<UUID, Long> transitCooldowns;
	private final ConcurrentHashMap<UUID, Player> pocketRescues;
	private final Set<UUID> pocketEscapeGlitches;

	DoorTransitLedger(Plugin plugin)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		travelersInTransit = new ConcurrentHashMap<>();
		transitCooldowns = new ConcurrentHashMap<>();
		pocketRescues = new ConcurrentHashMap<>();
		pocketEscapeGlitches = ConcurrentHashMap.newKeySet();
	}

	boolean claim(Entity traveler)
	{
		return claim(traveler.getUniqueId(), traveler);
	}

	boolean claim(UUID travelerId, Entity traveler)
	{
		return travelersInTransit.putIfAbsent(
			Objects.requireNonNull(travelerId, "travelerId"),
			Objects.requireNonNull(traveler, "traveler")) == null;
	}

	void release(Entity traveler)
	{
		release(traveler.getUniqueId(), traveler);
	}

	void release(UUID travelerId, Entity traveler)
	{
		travelersInTransit.remove(
			Objects.requireNonNull(travelerId, "travelerId"),
			Objects.requireNonNull(traveler, "traveler"));
	}

	boolean isTraveling(UUID travelerId)
	{
		return travelersInTransit.containsKey(travelerId);
	}

	boolean hasActiveTransits()
	{
		return !travelersInTransit.isEmpty();
	}

	boolean claimRescue(Player player)
	{
		return pocketRescues.putIfAbsent(player.getUniqueId(), player) == null;
	}

	boolean isRescuing(UUID playerId)
	{
		return pocketRescues.containsKey(playerId);
	}

	boolean isActiveRescue(Player player)
	{
		UUID playerId = player.getUniqueId();
		return pocketRescues.get(playerId) == player && travelersInTransit.get(playerId) == player;
	}

	void releaseRescue(Player player)
	{
		UUID playerId = player.getUniqueId();
		pocketRescues.remove(playerId, player);
		travelersInTransit.remove(playerId, player);
		pocketEscapeGlitches.remove(playerId);
	}

	void markEscapeGlitch(UUID playerId)
	{
		pocketEscapeGlitches.add(playerId);
	}

	boolean consumeEscapeGlitch(UUID playerId)
	{
		return pocketEscapeGlitches.remove(playerId);
	}

	boolean hasCooldown(UUID travelerId, long now)
	{
		Long expiresAt = transitCooldowns.get(travelerId);
		if(expiresAt == null)
		{
			return false;
		}
		if(now < expiresAt)
		{
			return true;
		}
		transitCooldowns.remove(travelerId, expiresAt);
		return false;
	}

	void startCooldown(Entity traveler)
	{
		startCooldown(traveler.getUniqueId(), traveler);
	}

	void startCooldown(UUID travelerId, Entity traveler)
	{
		Objects.requireNonNull(travelerId, "travelerId");
		Objects.requireNonNull(traveler, "traveler");
		long expiresAt = System.nanoTime() + TRANSIT_COOLDOWN_NANOS;
		transitCooldowns.put(travelerId, expiresAt);
		Runnable cleanup = () -> transitCooldowns.remove(travelerId, expiresAt);
		boolean scheduled = false;
		try
		{
			scheduled = WormholesPlatform.scheduleEntity(plugin, traveler, cleanup, cleanup, 20L);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.FINE, "Could not schedule dimensional-door cooldown cleanup", ex);
		}
		if(!scheduled)
		{
			// no cleanup task will run for this entry; sweep already-expired ones so
			// despawned objects whose UUIDs are never queried again cannot accumulate
			purgeExpiredCooldowns();
		}
	}

	private void purgeExpiredCooldowns()
	{
		long now = System.nanoTime();
		transitCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
	}

	void forget(Player player)
	{
		UUID playerId = player.getUniqueId();
		travelersInTransit.remove(playerId, player);
		transitCooldowns.remove(playerId);
		pocketRescues.remove(playerId, player);
		pocketEscapeGlitches.remove(playerId);
	}

	void clear()
	{
		travelersInTransit.clear();
		transitCooldowns.clear();
		pocketRescues.clear();
		pocketEscapeGlitches.clear();
	}
}
