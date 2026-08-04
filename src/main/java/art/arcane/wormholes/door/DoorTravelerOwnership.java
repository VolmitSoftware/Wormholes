package art.arcane.wormholes.door;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the player a door traveler acts on behalf of.
 *
 * <p>An arrow is not a person, so per-door access is judged against whoever
 * launched it. An owner that cannot be resolved - dispenser fire, a thrower who
 * logged off, an experience orb - is not gated: the door's access list has no
 * opinion about it.</p>
 */
final class DoorTravelerOwnership
{
	private DoorTravelerOwnership()
	{
	}

	static Optional<Player> responsiblePlayer(Server server, Entity traveler)
	{
		if(traveler instanceof Player player)
		{
			return Optional.of(player);
		}
		if(traveler instanceof Projectile projectile)
		{
			return projectile.getShooter() instanceof Player shooter ? Optional.of(shooter) : Optional.empty();
		}
		if(traveler instanceof Item item)
		{
			UUID thrower = item.getThrower();
			return thrower == null || server == null
				? Optional.empty()
				: Optional.ofNullable(server.getPlayer(thrower));
		}
		return Optional.empty();
	}
}
