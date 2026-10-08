package art.arcane.wormholes.portal;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import art.arcane.wormholes.Settings;

public final class ArrivalTransition
{
	private ArrivalTransition()
	{
	}

	public static void apply(Player player, boolean reloadExpected)
	{
		apply(player, reloadExpected, Settings.ARRIVAL_TRANSITION_MASK_TICKS);
	}

	/** Masks the arrival for {@code ticks}; the transit lane passes the adaptive size, callers without one pass the fixed setting. */
	public static void apply(Player player, boolean reloadExpected, int ticks)
	{
		if(player == null || !reloadExpected || !Settings.ARRIVAL_TRANSITION_MASK || ticks <= 0)
		{
			return;
		}
		player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0, false, false, false));
	}
}
