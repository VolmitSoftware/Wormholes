package art.arcane.wormholes.door;

import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Objects;
import java.util.logging.Level;

final class DoorTravelerService
{
	private final Plugin plugin;
	private final DoorStateGuard guard;

	DoorTravelerService(Plugin plugin, DoorStateGuard guard)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.guard = Objects.requireNonNull(guard, "guard");
	}

	boolean scheduleWithRetirement(Entity traveler, Runnable task, Runnable retired)
	{
		if(guard.closed() || !plugin.isEnabled())
		{
			return false;
		}
		try
		{
			return WormholesPlatform.scheduleEntity(plugin, traveler, task, retired, 0L);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not schedule dimensional-door entity work", ex);
			return false;
		}
	}

	void settle(Entity traveler)
	{
		settle(traveler, null);
	}

	/**
	 * Applies the arrival state for one traveler. A null velocity lands the
	 * traveler at rest, which is what a player or mob expects; an object traveler
	 * supplies its rotated momentum so it keeps flying out of the far door.
	 */
	void settle(Entity traveler, DoorVec3 velocity)
	{
		try
		{
			traveler.setVelocity(velocity == null
				? new Vector()
				: new Vector(velocity.x(), velocity.y(), velocity.z()));
			traveler.setFallDistance(0.0F);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not settle a dimensional-door traveler after teleport", ex);
		}
		try
		{
			if(traveler instanceof Player player)
			{
				player.playSound(
					player.getLocation(),
					DimensionalDoorSounds.teleportSound(),
					SoundCategory.PLAYERS,
					Settings.portalSoundVolume(1.0F),
					1.0F);
			}
			else
			{
				traveler.getWorld().playSound(
					traveler.getLocation(),
					DimensionalDoorSounds.teleportSound(),
					SoundCategory.NEUTRAL,
					Settings.portalSoundVolume(1.0F),
					1.0F);
			}
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not play a dimensional-door teleport sound", ex);
		}
	}

	void message(Entity traveler, TextKey key)
	{
		if(!(traveler instanceof Player player))
		{
			return;
		}
		Runnable delivery = () -> WormholesAudience.sendMessage(player, Wormholes.text().component(
			WormholesMessages.DOOR_TRANSIT_MESSAGE,
			WormholesLocalization.args(MessageArgument.untrusted("message", Wormholes.text().plain(key)))));
		if(!FoliaScheduler.runEntity(plugin, player, delivery))
		{
			delivery.run();
		}
	}
}
