package art.arcane.wormholes.door;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.door.DoorPortalVisualService.PortalPlaneGeometry;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.service.WormholesHud;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

final class DoorAccessFeedback
{
	static final long DENY_COOLDOWN_MILLIS = 1500L;

	private static final int COOLDOWN_PRUNE_THRESHOLD = 256;
	private static final int DENY_DUST_COUNT = 24;
	private static final int DENY_SMOKE_COUNT = 12;
	private static final double DENY_SMOKE_SPREAD = 0.25D;
	private static final double DENY_SMOKE_EXTRA = 0.05D;
	private static final float DENY_BASS_VOLUME = 1.0F;
	private static final float DENY_BASS_PITCH = 0.5F;
	private static final float DENY_THUD_VOLUME = 0.8F;
	private static final float DENY_THUD_PITCH = 0.55F;
	private static final Particle.DustOptions DENY_DUST =
		new Particle.DustOptions(Color.fromRGB(255, 70, 70), 1.0F);

	private final Plugin plugin;
	private final ConcurrentHashMap<UUID, Long> denyCooldowns;

	DoorAccessFeedback(Plugin plugin)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		denyCooldowns = new ConcurrentHashMap<>();
	}

	static boolean isCoolingDown(Long nextAllowedMillis, long nowMillis)
	{
		return nextAllowedMillis != null && nextAllowedMillis.longValue() > nowMillis;
	}

	static long nextAllowedMillis(long nowMillis)
	{
		return nowMillis + DENY_COOLDOWN_MILLIS;
	}

	void deny(Player player, PlacedDoorEndpoint endpoint, DoorwayPlane plane, World world)
	{
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(endpoint, "endpoint");
		if(!claimDeny(player.getUniqueId(), System.currentTimeMillis()))
		{
			return;
		}
		WormholesHud.notice(player, Wormholes.text().component(WormholesMessages.DOOR_ACCESS_DENIED));
		if(world == null)
		{
			return;
		}
		int chunkX = endpoint.position().x() >> 4;
		int chunkZ = endpoint.position().z() >> 4;
		if(WormholesPlatform.isOwnedByCurrentRegion(world, chunkX, chunkZ))
		{
			playDenyEffect(world, endpoint, plane);
			return;
		}
		if(!FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, () -> playDenyEffect(world, endpoint, plane)))
		{
			playDenyEffect(world, endpoint, plane);
		}
	}

	void forget(UUID playerId)
	{
		denyCooldowns.remove(playerId);
	}

	void clear()
	{
		denyCooldowns.clear();
	}

	private boolean claimDeny(UUID playerId, long nowMillis)
	{
		if(isCoolingDown(denyCooldowns.get(playerId), nowMillis))
		{
			return false;
		}
		denyCooldowns.put(playerId, Long.valueOf(nextAllowedMillis(nowMillis)));
		if(denyCooldowns.size() > COOLDOWN_PRUNE_THRESHOLD)
		{
			denyCooldowns.values().removeIf(until -> until.longValue() <= nowMillis);
		}
		return true;
	}

	private void playDenyEffect(World world, PlacedDoorEndpoint endpoint, DoorwayPlane plane)
	{
		int blockX = plane == null ? endpoint.position().x() : plane.blockX();
		int blockY = plane == null ? endpoint.position().y() : plane.blockY();
		int blockZ = plane == null ? endpoint.position().z() : plane.blockZ();
		try
		{
			Location head = new Location(world, blockX + 0.5D, blockY + 1.0D, blockZ + 0.5D);
			world.playSound(
				head,
				DimensionalDoorSounds.denyBassSound(),
				SoundCategory.BLOCKS,
				Settings.portalSoundVolume(DENY_BASS_VOLUME),
				DENY_BASS_PITCH);
			world.playSound(
				head,
				DimensionalDoorSounds.denyThudSound(),
				SoundCategory.BLOCKS,
				Settings.portalSoundVolume(DENY_THUD_VOLUME),
				DENY_THUD_PITCH);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not play a dimensional-door access deny sound", ex);
		}
		if(!Settings.ENABLE_PARTICLES)
		{
			return;
		}
		try
		{
			spawnDenyParticles(world, blockX, blockY, blockZ, plane);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not spawn dimensional-door access deny particles", ex);
		}
	}

	private static void spawnDenyParticles(World world, int blockX, int blockY, int blockZ, DoorwayPlane plane)
	{
		double anchorX = blockX + 0.5D;
		double anchorZ = blockZ + 0.5D;
		ThreadLocalRandom random = ThreadLocalRandom.current();
		PortalPlaneGeometry geometry = plane == null
			? null
			: DoorPortalVisualService.planeGeometry(plane, Door.Hinge.LEFT);
		BlockFace panelFace = plane == null ? null : DoorPortalVisualService.panelFace(plane);
		for(int index = 0; index < DENY_DUST_COUNT; index++)
		{
			double u = random.nextDouble();
			double v = random.nextDouble();
			if(geometry == null)
			{
				world.spawnParticle(
					Particle.DUST,
					anchorX,
					blockY + (v * 2.0D),
					anchorZ,
					1, 0.2D, 0.0D, 0.2D, 0.0D, DENY_DUST);
				continue;
			}
			double[] point = DoorPortalAnimation.scatterPoint(geometry, panelFace, u, v);
			world.spawnParticle(
				Particle.DUST,
				anchorX + point[0],
				blockY + point[1],
				anchorZ + point[2],
				1, 0.0D, 0.0D, 0.0D, 0.0D, DENY_DUST);
		}
		world.spawnParticle(
			Particle.SMOKE,
			anchorX,
			blockY + 1.0D,
			anchorZ,
			DENY_SMOKE_COUNT,
			DENY_SMOKE_SPREAD,
			DENY_SMOKE_SPREAD,
			DENY_SMOKE_SPREAD,
			DENY_SMOKE_EXTRA);
	}
}
