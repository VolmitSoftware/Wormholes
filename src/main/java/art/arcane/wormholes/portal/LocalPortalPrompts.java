package art.arcane.wormholes.portal;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.md_5.bungee.api.ChatColor;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.geometry.Raycast;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.volmlib.util.scheduling.AR;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.FinalBoolean;
import art.arcane.wormholes.util.PhantomSpinner;

final class LocalPortalPrompts
{
	private static final long SHORT_TITLE_FRESH_LOOK_MILLIS = 400L;
	private static final long SHORT_TITLE_FADE_IN_MILLIS = 250L;
	private static final long SHORT_TITLE_STAY_MILLIS = 350L;
	private static final long SHORT_TITLE_FADE_OUT_MILLIS = 250L;

	private final LocalPortal portal;
	private final ConcurrentHashMap<UUID, ShortTitleHold> shortTitleHolds = new ConcurrentHashMap<UUID, ShortTitleHold>();
	private final PhantomSpinner spinner = new PhantomSpinner(ChatColor.YELLOW, ChatColor.GOLD, ChatColor.RED);
	private boolean progressing = false;
	private String progress = "Idle";
	private Player directionChanger = null;
	private Direction chosenDirection = null;
	private Vector chosenLook = null;

	LocalPortalPrompts(LocalPortal portal)
	{
		this.portal = portal;
	}

	void showProgress(String text)
	{
		progressing = true;
		progress = text;
	}

	void hideProgress()
	{
		progressing = false;
	}

	boolean isShowingProgress()
	{
		return progressing;
	}

	String getCurrentProgress()
	{
		return progress;
	}

	void onLooking(Player p, boolean holdingWand)
	{
		if(holdingWand)
		{
			if(isShowingProgress())
			{
				sendShortTitle(p, spinner.toString() + ChatColor.RESET + ChatColor.GRAY + progress);
			}

			else
			{
				sendShortTitle(p, portal.getRouter(false));
			}
		}
		else if(portal.isPublicLookLabel())
		{
			sendShortTitle(p, ChatColor.GOLD + "" + ChatColor.BOLD + portal.getName());
		}
	}

	boolean isLookingAt(Player p)
	{
		if(directionChanger != null && p.equals(directionChanger))
		{
			return false;
		}

		if(p.getWorld().equals(portal.getStructure().getWorld()))
		{
			if(p.getLocation().distanceSquared(portal.getStructure().getCenter()) < 64)
			{
				FinalBoolean hit = new FinalBoolean(false);

				new Raycast(p.getEyeLocation(), p.getEyeLocation().clone().add(p.getLocation().getDirection().clone().multiply(16)), 0.9)
				{
					@Override
					public boolean shouldContinue(Location l)
					{
						if(portal.getStructure().contains(l))
						{
							hit.set(true);
							return false;
						}

						return true;
					}
				};

				return hit.get();
			}
		}

		return false;
	}

	void uiChangeDirection(Player p)
	{
		for(Component line : Wormholes.text().components(WormholesMessages.PORTAL_PROMPT_DIRECTION))
		{
			WormholesAudience.sendMessage(p, line);
		}
		chosenDirection = Direction.closest(p.getLocation().getDirection());
		chosenLook = p.getLocation().getDirection();
		directionChanger = p;

		new AR()
		{
			@Override
			public void run()
			{
				if(directionChanger == null)
				{
					cancel();
					return;
				}

				if(!p.isOnline())
				{
					directionChanger = null;
					WormholesHud.releaseDirection(p);
					cancel();
					return;
				}

				FoliaScheduler.runEntity(Wormholes.instance, p, () ->
				{
					if(directionChanger == null)
					{
						return;
					}

					chosenDirection = Direction.closest(p.getLocation().getDirection());
					chosenLook = p.getLocation().getDirection();
					sendDirectionTitle(p, ChatColor.GRAY + "" + ChatColor.BOLD + LocalPortalText.directionLabel(chosenDirection));
				});
			}
		};
	}

	void handleInteract(PlayerInteractEvent e)
	{
		if(directionChanger == null)
		{
			return;
		}

		if(directionChanger.equals(e.getPlayer()))
		{
			if(e.getAction().equals(Action.LEFT_CLICK_AIR) || e.getAction().equals(Action.LEFT_CLICK_BLOCK))
			{
				e.setCancelled(true);

				boolean cancelled = e.getPlayer().isSneaking();
				if(!cancelled)
				{
					if(chosenDirection == null)
					{
						chosenDirection = Direction.closest(e.getPlayer().getLocation().getDirection());
						chosenLook = e.getPlayer().getLocation().getDirection();
					}
					portal.setFrame(PortalFrame.fromDirectionAndLook(chosenDirection, chosenLook));
					Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
							WormholesMessages.PORTAL_DIRECTION_CHANGED,
							LocalPortalText.arguments("portal", portal.getName(), "direction", LocalPortalText.directionLabel(portal.getDirection()))), portal.getStructure().getCenter());
				}

				directionChanger = null;
				chosenDirection = null;
				chosenLook = null;
				WormholesHud.releaseDirection(e.getPlayer());
				WormholesAudience.sendMessage(e.getPlayer(), Wormholes.text().component(
						cancelled ? WormholesMessages.PORTAL_DIRECTION_CANCELLED : WormholesMessages.PORTAL_DIRECTION_SET));
			}
		}
	}

	private void sendShortTitle(Player player, String legacy)
	{
		Title title = buildShortTitle(player, legacy);
		if(title != null)
		{
			WormholesHud.lookSubtitle(player, title);
		}
	}

	private void sendDirectionTitle(Player player, String legacy)
	{
		Title title = buildShortTitle(player, legacy);
		if(title != null)
		{
			WormholesHud.directionTitle(player, title);
		}
	}

	private Title buildShortTitle(Player player, String legacy)
	{
		long now = System.currentTimeMillis();
		UUID playerId = player.getUniqueId();
		ShortTitleHold previous = shortTitleHolds.get(playerId);
		boolean freshLook = previous == null || now - previous.lastSendMillis() > SHORT_TITLE_FRESH_LOOK_MILLIS;
		long lookStart = freshLook ? now : previous.lookStartMillis();
		shortTitleHolds.put(playerId, new ShortTitleHold(lookStart, now));
		if(shortTitleHolds.size() > 64)
		{
			shortTitleHolds.entrySet().removeIf(entry -> now - entry.getValue().lastSendMillis() > 10_000L);
		}
		if(!freshLook && now - lookStart < SHORT_TITLE_FADE_IN_MILLIS)
		{
			return null;
		}
		Component subtitle = LegacyComponentSerializer.legacySection().deserialize(legacy);
		Title.Times times = Title.Times.times(
				freshLook ? Duration.ofMillis(SHORT_TITLE_FADE_IN_MILLIS) : Duration.ZERO,
				Duration.ofMillis(SHORT_TITLE_STAY_MILLIS),
				Duration.ofMillis(SHORT_TITLE_FADE_OUT_MILLIS));
		return Title.title(Component.empty(), subtitle, times);
	}

	private record ShortTitleHold(long lookStartMillis, long lastSendMillis)
	{
	}
}
