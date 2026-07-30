package art.arcane.wormholes;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.wormholes.render.PortalToolPreviewRenderer;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.volmlib.util.scheduling.AR;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.Area;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.util.MSound;
import art.arcane.wormholes.util.ParticleEffect;

public class EffectManager implements Listener
{
	private static final int LOOKING_SCAN_INTERVAL_TICKS = 3;
	private static final int SYNC_SWEEP_INTERVAL_TICKS = 15;
	private static final double VORTEX_MATCH_RADIUS_SQUARED = 16.0D;
	private final Map<UUID, Boolean> portalSyncActive = new ConcurrentHashMap<>();
	private final EffectDisplayRegistry displays = new EffectDisplayRegistry();
	private final EffectPortalAnimator animator = new EffectPortalAnimator(displays);
	private final EffectVortexDirector vortexDirector = new EffectVortexDirector(displays, animator);
	private final PortalToolPreviewRenderer portalToolPreviewRenderer;

	public EffectManager()
	{
		Wormholes.v("Starting Effect Manager");
		portalToolPreviewRenderer = new PortalToolPreviewRenderer();
		displays.cleanupOrphaned();

		new AR(LOOKING_SCAN_INTERVAL_TICKS)
		{
			@Override
			public void run()
			{
				if(Wormholes.portalManager == null)
				{
					return;
				}
				List<ILocalPortal> portals = Wormholes.portalManager.getLocalPortals();
				if(portals.isEmpty())
				{
					return;
				}
				for(Player player : Bukkit.getOnlinePlayers())
				{
					FoliaScheduler.runEntity(Wormholes.instance, player, () -> scanLookingPortalsFor(player, portals));
				}
			}
		};

		new AR(SYNC_SWEEP_INTERVAL_TICKS)
		{
			@Override
			public void run()
			{
				sweepRemoteSync();
			}
		};
	}

	public void shutdown()
	{
		Set<UUID> pending = displays.beginShutdown();
		if(pending == null)
		{
			return;
		}
		portalSyncActive.clear();
		vortexDirector.clear();
		portalToolPreviewRenderer.clear();
		displays.drain(pending);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPluginDisable(PluginDisableEvent event)
	{
		if(event.getPlugin() == Wormholes.instance)
		{
			shutdown();
		}
	}

	public boolean isPortalSyncing(UUID portalId)
	{
		return portalId != null && Boolean.TRUE.equals(portalSyncActive.get(portalId));
	}

	public static boolean isPortalEffectEntity(Entity entity)
	{
		return EffectDisplayRegistry.isEffectEntity(entity);
	}

	public void trackTemporaryDisplay(Display display)
	{
		displays.track(display);
	}

	public void removeTemporaryDisplay(Entity display)
	{
		displays.remove(display);
	}

	private void sweepRemoteSync()
	{
		RemoteViewCache cache = Wormholes.remoteViewCache;
		if(cache == null || Wormholes.portalManager == null || Wormholes.instance == null)
		{
			portalSyncActive.clear();
			return;
		}

		Set<UUID> currentPortalIds = new HashSet<>();
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(portal == null || portal.getId() == null)
			{
				continue;
			}
			UUID portalKey = portal.getId();
			currentPortalIds.add(portalKey);
			if(!portal.isOpen() || !portal.hasTunnel())
			{
				portalSyncActive.remove(portalKey);
				continue;
			}
			ITunnel tunnel = portal.getTunnel();
			if(!(tunnel instanceof UniversalTunnel))
			{
				portalSyncActive.remove(portalKey);
				continue;
			}
			IPortal destination = tunnel.getDestination();
			if(!(destination instanceof RemotePortal remote) || remote.getId() == null)
			{
				portalSyncActive.remove(portalKey);
				continue;
			}
			Location center = portal.getCenter();
			if(center == null || center.getWorld() == null)
			{
				portalSyncActive.remove(portalKey);
				continue;
			}

			boolean ready = cache.isViewReady(remote.getId());
			boolean syncing = !ready && cache.hasSlicesFor(remote.getId());
			if(syncing)
			{
				boolean justStarted = portalSyncActive.put(portalKey, Boolean.TRUE) == null;
				FoliaScheduler.runRegion(Wormholes.instance, center, () -> playPortalSyncing(center, justStarted));
			}
			else if(ready && Boolean.TRUE.equals(portalSyncActive.remove(portalKey)))
			{
				FoliaScheduler.runRegion(Wormholes.instance, center, () -> playPortalSyncComplete(center));
			}
			else
			{
				portalSyncActive.remove(portalKey);
			}
		}
		portalSyncActive.keySet().removeIf(portalId -> !currentPortalIds.contains(portalId));
	}

	private void playPortalSyncing(Location center, boolean justStarted)
	{
		World world = center.getWorld();
		if(world == null)
		{
			return;
		}
		if(Settings.ENABLE_PARTICLES)
		{
			world.spawnParticle(Particle.PORTAL, center, 4, 0.45, 0.65, 0.45, 0.18);
			world.spawnParticle(Particle.REVERSE_PORTAL, center, 1, 0.2, 0.35, 0.2, 0.01);
		}
		if(justStarted)
		{
			world.playSound(center, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, 0.3f, 1.7f);
		}
	}

	private void playPortalSyncComplete(Location center)
	{
		World world = center.getWorld();
		if(world == null)
		{
			return;
		}
		if(Settings.ENABLE_PARTICLES)
		{
			world.spawnParticle(Particle.REVERSE_PORTAL, center, 12, 0.4, 0.6, 0.4, 0.4);
		}
		world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 0.55f, 1.5f);
	}

	private void scanLookingPortalsFor(Player player, List<ILocalPortal> portals)
	{
		ItemStack mainHandItem = player.getInventory().getItemInMainHand();
		ItemStack offHandItem = player.getInventory().getItemInOffHand();
		if(!Wormholes.blockManager.isPortalTool(mainHandItem) && !Wormholes.blockManager.isPortalTool(offHandItem))
		{
			return;
		}

		portalToolPreviewRenderer.render(player, portals);
		for(ILocalPortal portal : portals)
		{
			if(portal.isLookingAt(player))
			{
				Location portalCenter = portal.getCenter();
				if(portalCenter == null)
				{
					continue;
				}

				FoliaScheduler.runRegion(Wormholes.instance, portalCenter, () -> portal.onLooking(player, true));
			}
		}
	}

	@EventHandler
	public void on(PlayerInteractEvent e)
	{
		Action action = e.getAction();
		boolean isLeft = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
		boolean isRight = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
		if(!isLeft && !isRight)
		{
			return;
		}

		Player player = e.getPlayer();
		ItemStack handItem = player.getInventory().getItemInMainHand();
		if(!Wormholes.blockManager.isWand(handItem))
		{
			return;
		}

		if(action == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null && Wormholes.blockManager.getBlock(e.getClickedBlock()) != null)
		{
			return;
		}

		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!portal.isLookingAt(player))
			{
				continue;
			}

			e.setCancelled(true);
			portal.onWanded(player);
			return;
		}
	}

	public void playNotificationFail(String message, Player p)
	{
		Component component = LegacyComponentSerializer.legacySection().deserialize(message).colorIfAbsent(NamedTextColor.RED);
		WormholesHud.notice(p, component);
	}

	public void playNotificationFail(String message, Location l)
	{
		for(Player i : new Area(l, 24).getNearbyPlayers())
		{
			playNotificationFail(message, i);
		}
	}

	public void playNotificationSuccess(String message, Location l)
	{
		for(Player i : new Area(l, 24).getNearbyPlayers())
		{
			playNotificationSuccess(message, i);
		}
	}

	public void playNotificationSuccess(String message, Player p)
	{
		Component component = LegacyComponentSerializer.legacySection().deserialize(message).colorIfAbsent(NamedTextColor.GREEN);
		WormholesHud.notice(p, component);
	}

	public void playGlitchOut(Location location)
	{
		if(location == null)
		{
			return;
		}
		World world = location.getWorld();
		if(world == null)
		{
			return;
		}
		Location center = location.clone().add(0.0, 1.0, 0.0);
		if(Settings.ENABLE_PARTICLES)
		{
			world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0);
			world.spawnParticle(Particle.REVERSE_PORTAL, center, 40, 0.35, 0.7, 0.35, 0.25);
			world.spawnParticle(Particle.PORTAL, center, 24, 0.3, 0.6, 0.3, 0.5);
			world.spawnParticle(Particle.ELECTRIC_SPARK, center, 18, 0.4, 0.8, 0.4, 0.15);
		}
		world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.6f, 0.5f + ((float) (Math.random() * 0.15)));
		world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1.0f, 0.5f);
		world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.8f, 0.55f + ((float) (Math.random() * 0.2)));
	}

	public void playPortalBlockPlaced(Block block)
	{
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.FRAME_FILL.bukkitSound(), SoundCategory.BLOCKS, 0.65f, 1.1f + ((float) (Math.random() * 0.2)));
	}

	public void playPortalBlockDestroyed(Block block)
	{
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.GLASS.bukkitSound(), SoundCategory.BLOCKS, 0.5f, 1.55f + ((float) (Math.random() * 0.2)));
	}

	public void playPortalFailOpen(Set<Block> blocks)
	{
		Block block = new KList<Block>(blocks).getRandom();

		for(int i = 0; i < 4; i++)
		{
			block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.AMBIENCE_CAVE.bukkitSound(), 2.5f, 0.5f + ((float) (Math.random() * 1.45)));
		}
	}

	public void playPortalFailRefund(Block block)
	{
		ParticleEffect.EXPLOSION_LARGE.display(0f, 1, block.getLocation().clone().add(0.5, 0.5, 0.5), 32);
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.EXPLODE.bukkitSound(), 0.7f, (float) (1.6 + ((float) (Math.random() * 0.35))));
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.GLASS.bukkitSound(), 1.2f, (float) (0.25 + ((float) (Math.random() * 0.95))));
	}

	public void playPortalVortex(World world, Location center, List<PortalBlockSnapshot> snapshots)
	{
		vortexDirector.playVortex(world, center, snapshots);
	}

	public void playPortalClose(World world, Location corner, double sx, double sy, double sz, BooleanSupplier active)
	{
		playPortalClose(world, corner, sx, sy, sz, active, () -> true);
	}

	public void playPortalClose(
			World world,
			Location corner,
			double sx,
			double sy,
			double sz,
			BooleanSupplier active,
			BooleanSupplier audible)
	{
		animator.playClose(world, corner, sx, sy, sz, active, audible);
	}

	public void playPortalOpen(World world, Location center, double sx, double sy, double sz, BooleanSupplier active)
	{
		playPortalOpen(world, center, sx, sy, sz, active, () -> true);
	}

	public void playPortalOpen(
			World world,
			Location center,
			double sx,
			double sy,
			double sz,
			BooleanSupplier active,
			BooleanSupplier audible)
	{
		if(displays.isClosing() || world == null || center == null || active == null || audible == null || !active.getAsBoolean())
		{
			return;
		}
		if(!Settings.ENABLE_PARTICLES)
		{
			if(audible.getAsBoolean())
			{
				world.playSound(center, MSound.FRAME_SPAWN.bukkitSound(), SoundCategory.BLOCKS, openingSoundPlan().frameVolume(), 0.35f);
			}
			animator.playKawooshSounds(world, center, active, audible);
			return;
		}
		if(vortexDirector.captureOpening(world, center, sx, sy, sz, active, audible))
		{
			return;
		}
		if(audible.getAsBoolean())
		{
			world.playSound(center, MSound.FRAME_SPAWN.bukkitSound(), SoundCategory.BLOCKS, openingSoundPlan().frameVolume(), 0.35f);
		}
		animator.playOpenPrelude(world, center, sx, sy, sz, active, audible);
	}

	public void playPortalDeletion(World world, Location corner, double sx, double sy, double sz)
	{
		playPortalClose(world, corner, sx, sy, sz, () -> true);
	}

	static int formationDisplayCap(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> 8;
			case BALANCED -> 16;
			case AUTO -> 18;
			case CINEMATIC -> 24;
		};
	}

	static CloseEffectPlan closeEffectPlan(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> new CloseEffectPlan(4, 3, 14);
			case BALANCED -> new CloseEffectPlan(6, 4, 22);
			case AUTO -> new CloseEffectPlan(6, 4, 26);
			case CINEMATIC -> new CloseEffectPlan(8, 5, 30);
		};
	}

	static KawooshPlan kawooshPlan(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> new KawooshPlan(2, 6, 20, 8, 2);
			case BALANCED -> new KawooshPlan(3, 9, 40, 18, 4);
			case AUTO -> new KawooshPlan(3, 11, 44, 20, 5);
			case CINEMATIC -> new KawooshPlan(3, 14, 48, 24, 6);
		};
	}

	static int openingRingPoints(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> 6;
			case BALANCED -> 10;
			case AUTO -> 12;
			case CINEMATIC -> 16;
		};
	}

	static OpeningSoundPlan openingSoundPlan()
	{
		return new OpeningSoundPlan(0.65f, 0.75f, 0.65f, 0.25f);
	}

	static double ellipseRadius(double halfA, double halfB, double angle)
	{
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return 1.0D / Math.sqrt((cos * cos) / (halfA * halfA) + (sin * sin) / (halfB * halfB));
	}

	static double[] outwardShardVelocity(int normalAxis, int planeA, int planeB, double radialA, double radialB, double normalDirection)
	{
		double[] velocity = new double[3];
		velocity[normalAxis] = normalDirection * 0.65D;
		velocity[planeA] = radialA;
		velocity[planeB] = radialB;
		return velocity;
	}

	static boolean vortexMarkerMatches(UUID markerWorldId, double markerX, double markerY, double markerZ, long expiresAtMillis, long nowMillis, UUID worldId, double x, double y, double z)
	{
		if(nowMillis >= expiresAtMillis)
		{
			return false;
		}
		if(!markerWorldId.equals(worldId))
		{
			return false;
		}
		double dx = markerX - x;
		double dy = markerY - y;
		double dz = markerZ - z;
		return (dx * dx) + (dy * dy) + (dz * dz) <= VORTEX_MATCH_RADIUS_SQUARED;
	}

	public record PortalBlockSnapshot(Location location, BlockData data)
	{
	}

	record CloseEffectPlan(int branches, int segments, int shards)
	{
	}

	record KawooshPlan(int arms, int armPoints, int impactReverse, int impactEndRod, int surgeCount)
	{
	}

	record OpeningSoundPlan(float frameVolume, float portalImpactVolume, float beaconImpactVolume, float sonicBoomVolume)
	{
	}
}
