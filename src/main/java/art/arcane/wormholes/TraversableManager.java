package art.arcane.wormholes;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesTelemetry;

public class TraversableManager implements Listener
{
	public record Movement(Player player, UUID worldId, double x, double y, double z,
		float yaw, float pitch, double velocityX, double velocityY, double velocityZ, long continuity)
	{
		public Location location(World world)
		{
			return new Location(world, x, y, z, yaw, pitch);
		}

		public Vector velocity()
		{
			return new Vector(velocityX, velocityY, velocityZ);
		}
	}

	private final Map<UUID, Movement> movements = new ConcurrentHashMap<>();
	private final Map<UUID, EntityContinuity> entityContinuities = new ConcurrentHashMap<>();
	private final AtomicLong continuitySequence = new AtomicLong();
	private final AtomicLong nextEntityPruneMillis = new AtomicLong();

	public TraversableManager()
	{
		Wormholes.v("Starting Traversable Manager");
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void on(PlayerMoveEvent e)
	{
		if(e.isCancelled() || e instanceof PlayerTeleportEvent || e.getTo() == null)
		{
			return;
		}
		Location from = e.getFrom();
		Location to = e.getTo();
		if(from.getWorld() != to.getWorld())
		{
			recordTeleport(e.getPlayer(), to);
			return;
		}
		if(from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())
		{
			return;
		}
		Player player = e.getPlayer();
		Movement previous = movements.get(player.getUniqueId());
		long continuity = previous != null && previous.player() == player
			? previous.continuity() : continuitySequence.incrementAndGet();
		movements.put(player.getUniqueId(), new Movement(player, to.getWorld().getUID(),
			to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch(),
			to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ(), continuity));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void on(PlayerTeleportEvent e)
	{
		if(!e.isCancelled() && e.getTo() != null)
		{
			recordTeleport(e.getPlayer(), e.getTo());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void on(PlayerRespawnEvent e)
	{
		recordTeleport(e.getPlayer(), e.getRespawnLocation());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void on(PlayerChangedWorldEvent e)
	{
		recordTeleport(e.getPlayer(), e.getPlayer().getLocation());
	}

	@EventHandler
	public void on(PlayerQuitEvent e)
	{
		UUID playerId = e.getPlayer().getUniqueId();
		Movement movement = movements.get(playerId);
		if(movement != null && movement.player() != e.getPlayer())
		{
			return;
		}
		if(movement != null && movement.player() == e.getPlayer())
		{
			movements.remove(playerId, movement);
		}
		LocalPortal.clearReentryLatch(playerId);
		LocalPortal.clearTeleportCooldown(playerId);
		if(LocalPortal.clearTeleportInFlight(playerId))
		{
			Wormholes.v("[traversal] released an in-flight traversal claim for quitting player " + playerId);
			WormholesTelemetry.countFailure("TRAVERSAL_QUIT_MID_TRANSIT");
		}
	}

	public Vector getVelocity(Player p)
	{
		Movement movement = movements.get(p.getUniqueId());
		return movement != null && movement.player() == p ? movement.velocity() : new Vector();
	}

	public Movement movement(UUID playerId)
	{
		return movements.get(playerId);
	}

	public Movement movement(Player player, Location location)
	{
		return movements.compute(player.getUniqueId(), (id, current) -> current != null && current.player() == player
			? current : new Movement(player, location.getWorld().getUID(),
				location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(),
				0.0D, 0.0D, 0.0D, continuitySequence.incrementAndGet()));
	}

	public Vector getVelocity(Entity i)
	{
		if(i instanceof Player)
		{
			return getVelocity((Player) i);
		}

		return i.getVelocity();
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void on(EntityTeleportEvent event)
	{
		entityContinuities.remove(event.getEntity().getUniqueId());
	}

	public EntityContinuity entityContinuity(Entity entity, Location location, long nowMillis)
	{
		long nextPrune = nextEntityPruneMillis.get();
		if(nowMillis >= nextPrune && nextEntityPruneMillis.compareAndSet(nextPrune, nowMillis + 5_000L))
		{
			entityContinuities.entrySet().removeIf(entry -> nowMillis - entry.getValue().capturedAtMillis() > 5_000L);
		}
		return entityContinuities.compute(entity.getUniqueId(), (id, previous) ->
			new EntityContinuity(entity, location.getWorld().getUID(),
				previous != null && previous.entity() == entity && previous.worldId().equals(location.getWorld().getUID())
					? previous.continuity() : continuitySequence.incrementAndGet(), nowMillis));
	}

	private void recordTeleport(Player player, Location target)
	{
		movements.put(player.getUniqueId(), new Movement(player, target.getWorld().getUID(),
			target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch(),
			0.0D, 0.0D, 0.0D, continuitySequence.incrementAndGet()));
	}

	public record EntityContinuity(Entity entity, UUID worldId, long continuity, long capturedAtMillis)
	{
	}
}
