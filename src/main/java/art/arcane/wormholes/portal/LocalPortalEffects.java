package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.render.clientview.BukkitClientView;
import art.arcane.wormholes.render.clientview.ClientViewEffects;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.AxisAlignedBB;

final class LocalPortalEffects
{
	private static final int AMBIENT_OUTLINE_MAX_POINTS = 96;
	private static final int AMBIENT_OUTLINE_OPEN_WINDOW = 32;
	private static final int AMBIENT_OUTLINE_CLOSED_WINDOW = 8;
	private static final int AMBIENT_CORNERS_CLOSED_WINDOW = 2;
	private static final float[] TRANSIT_PITCHES = {1.7F, 1.5F, 1.3F};

	private final LocalPortal portal;
	private final AtomicLong effectSequence = new AtomicLong();
	private final AmbientOutlineGeometry ambientOutline = new AmbientOutlineGeometry();
	private long ambientCursor;
	private long ambientSparkSequence;

	LocalPortalEffects(LocalPortal portal)
	{
		this.portal = portal;
	}

	long incrementSequence()
	{
		return effectSequence.incrementAndGet();
	}

	boolean isPortalSoundEnabled()
	{
		RtpSettings settings = portal.getRtpSettings();
		return portal.getType() != PortalType.RTP || settings == null || settings.isSoundEnabled();
	}

	void playEffect(PortalEffect effect, Location location)
	{
		playEffect(effect, location, null);
	}

	void playEffect(PortalEffect effect, Location location, Entity traveler)
	{
		playEffect(effect, location, traveler, traveler instanceof Player player && ClientViewEffects.seamless(player, portal.getId()));
	}

	void playEffect(PortalEffect effect, Location location, Entity traveler, boolean seamless)
	{
		switch(effect)
		{
			case PUSH:
				Player excluded = traveler instanceof Player player && seamless
					? player : null;
				if(Settings.ENABLE_PARTICLES && location != null && location.getWorld() != null)
				{
					ClientViewEffects.burst(location.getWorld(), Particle.SMOKE, location.getX(), location.getY(), location.getZ(),
						6, 0.0D, 0.0D, 0.0D, 0.01D, excluded == null ? null : excluded.getUniqueId());
				}
				if(location != null && location.getWorld() != null && isPortalSoundEnabled())
				{
					for(float pitch : TRANSIT_PITCHES)
					{
						ClientViewEffects.sound(location, Sound.ENTITY_ENDERMAN_TELEPORT.getKey().toString(), SoundCategory.MASTER,
							Settings.portalSoundVolume(0.5F), pitch + (float) (Math.random() * 0.2D),
							excluded == null ? null : excluded.getUniqueId());
					}
				}

				break;
			case REJECT:
				spawnSimpleParticle(location, Particle.SMOKE, 24, 0.08D);
				spawnRejectDust(location);
				if(location != null && location.getWorld() != null && isPortalSoundEnabled())
				{
					location.getWorld().playSound(location, Sound.BLOCK_ANVIL_LAND, Settings.portalSoundVolume(0.21f), 1.8f);
					location.getWorld().playSound(location, Sound.BLOCK_GLASS_BREAK, Settings.portalSoundVolume(0.18f), 0.7f);
				}
				break;
			case AMBIENT_CLOSED:
				renderAmbientParticles(false);

				break;
			case AMBIENT_OPEN:
				renderAmbientParticles(true);
				break;
			case CLOSE:
				long closeSequence = effectSequence.incrementAndGet();
				AxisAlignedBB closeArea = portal.getStructure().getArea();
				World closeWorld = portal.getStructure().getWorld();
				if(closeArea != null && closeWorld != null)
				{
					Location corner = new Location(closeWorld, Math.min(closeArea.getXa(), closeArea.getXb()), Math.min(closeArea.getYa(), closeArea.getYb()), Math.min(closeArea.getZa(), closeArea.getZb()));
					double sx = Math.abs(closeArea.getXb() - closeArea.getXa());
					double sy = Math.abs(closeArea.getYb() - closeArea.getYa());
					double sz = Math.abs(closeArea.getZb() - closeArea.getZa());
					FoliaScheduler.runRegion(Wormholes.instance, corner,
							() -> Wormholes.effectManager.playPortalClose(
									closeWorld,
									corner,
									sx,
									sy,
									sz,
									() -> effectSequence.get() == closeSequence,
									this::isPortalSoundEnabled), 1L);
				}
				break;
			case OPEN:
				long openSequence = effectSequence.incrementAndGet();
				AxisAlignedBB openArea = portal.getStructure().getArea();
				Location openCenter = portal.getStructure().getCenter();
				World openWorld = portal.getStructure().getWorld();
				if(openArea != null && openCenter != null && openWorld != null)
				{
					double sx = Math.abs(openArea.getXb() - openArea.getXa());
					double sy = Math.abs(openArea.getYb() - openArea.getYa());
					double sz = Math.abs(openArea.getZb() - openArea.getZa());
					FoliaScheduler.runRegion(Wormholes.instance, openCenter,
							() -> Wormholes.effectManager.playPortalOpen(
									openWorld,
									openCenter,
									sx,
									sy,
									sz,
									() -> effectSequence.get() == openSequence,
									this::isPortalSoundEnabled), 1L);
				}
				break;
			case AMBIENT_DEBUG:

				break;
			default:
				break;
		}
	}

	private void renderAmbientParticles(boolean open)
	{
		if(!Settings.ENABLE_PARTICLES)
		{
			return;
		}

		AmbientParticleStyle style = portal.getAmbientStyle();
		if(style == AmbientParticleStyle.OFF)
		{
			return;
		}

		touchClientViews();
		switch(style)
		{
			case SPARKS -> renderAmbientSparks(open);
			case CORNERS -> renderAmbientCorners(open);
			case OUTLINE -> renderAmbientOutline(open);
		}
	}

	private void renderAmbientSparks(boolean open)
	{
		int count = AmbientSparkCadence.burst(ambientSparkSequence++, Settings.AMBIENT_PARTICLE_INTERVAL_TICKS, open);
		if(count == 0)
		{
			return;
		}
		PortalStructure structure = portal.getStructure();
		World world = structure.getWorld();
		Location cell = world == null ? null : structure.randomCellCentre();
		if(cell == null)
		{
			return;
		}
		ambient(world, cell.getX(), cell.getY(), cell.getZ(),
				everyone -> everyone.spawnParticle(Particle.MYCELIUM, cell, count,
						AmbientSparkCadence.CELL_SPREAD, AmbientSparkCadence.CELL_SPREAD, AmbientSparkCadence.CELL_SPREAD, 0.0D),
				viewer -> viewer.spawnParticle(Particle.MYCELIUM, cell, count,
						AmbientSparkCadence.CELL_SPREAD, AmbientSparkCadence.CELL_SPREAD, AmbientSparkCadence.CELL_SPREAD, 0.0D));
	}

	private void spawnSimpleParticle(Location location, Particle particle, int amount, double extra)
	{
		if(!Settings.ENABLE_PARTICLES || location == null || location.getWorld() == null)
		{
			return;
		}
		ClientViewEffects.burst(location.getWorld(), particle, location.getX(), location.getY(), location.getZ(), amount, 0.0D, 0.0D, 0.0D, extra);
	}

	private void spawnRejectDust(Location location)
	{
		if(!Settings.ENABLE_PARTICLES || location == null || location.getWorld() == null)
		{
			return;
		}
		Particle.DustOptions options = new Particle.DustOptions(Color.fromRGB(255, 70, 70), 1.0F);
		ClientViewEffects.spawn(location.getWorld(), location.getX(), location.getY(), location.getZ(),
				world -> world.spawnParticle(Particle.DUST, location, 1, options),
				viewer -> viewer.spawnParticle(Particle.DUST, location, 1, options),
				ClientViewEmitters.dust(location.getX(), location.getY(), location.getZ(), 255, 70, 70));
	}

	private void renderAmbientCorners(boolean open)
	{
		PortalStructure structure = portal.getStructure();
		World world = structure.getWorld();
		if(world == null)
		{
			return;
		}

		List<Location> corners = new ArrayList<Location>(structure.getCorners());
		if(corners.isEmpty())
		{
			return;
		}

		Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(portal.getAmbientColor()), 1.0f);
		if(open)
		{
			for(Location corner : corners)
			{
				ambient(world, corner.getX(), corner.getY(), corner.getZ(),
						everyone -> everyone.spawnParticle(Particle.DUST, corner, 1, dust),
						viewer -> viewer.spawnParticle(Particle.DUST, corner, 1, dust));
			}
			return;
		}

		int start = (int) Math.floorMod(ambientCursor++, corners.size());
		int window = Math.min(AMBIENT_CORNERS_CLOSED_WINDOW, corners.size());
		for(int i = 0; i < window; i++)
		{
			Location corner = corners.get((start + i) % corners.size());
			ambient(world, corner.getX(), corner.getY(), corner.getZ(),
					everyone -> everyone.spawnParticle(Particle.DUST, corner, 1, dust),
					viewer -> viewer.spawnParticle(Particle.DUST, corner, 1, dust));
		}
	}

	private void renderAmbientOutline(boolean open)
	{
		PortalStructure structure = portal.getStructure();
		World world = structure.getWorld();
		PortalFrame frame = portal.getFrame();
		if(world == null || frame == null)
		{
			return;
		}

		List<double[]> points = ambientOutline.points(structure.getRevision(), frame.getNormal().getAxis(), structure.geometry());
		if(points.isEmpty())
		{
			return;
		}

		Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(portal.getAmbientColor()), 1.0f);
		int window = Math.min(open ? AMBIENT_OUTLINE_OPEN_WINDOW : AMBIENT_OUTLINE_CLOSED_WINDOW, AMBIENT_OUTLINE_MAX_POINTS);
		window = Math.min(window, points.size());
		int start = (int) Math.floorMod(ambientCursor++, points.size());
		for(int i = 0; i < window; i++)
		{
			double[] point = points.get((start + i) % points.size());
			ambient(world, point[0], point[1], point[2],
					everyone -> everyone.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0.0D, 0.0D, 0.0D, 0.0D, dust),
					viewer -> viewer.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0.0D, 0.0D, 0.0D, 0.0D, dust));
		}
	}

	private static void ambient(World world, double x, double y, double z, Consumer<World> everyone, Consumer<Player> viewer)
	{
		ClientViewEffects.spawn(world, x, y, z, everyone, viewer, null);
	}

	private void touchClientViews()
	{
		BukkitClientView clientView = ClientViewEffects.active();
		Location center = clientView == null ? null : portal.getCenter();
		if(center != null && center.getWorld() != null)
		{
			clientView.touchNear(center.getWorld(), center.getX(), center.getY(), center.getZ(), portal.getId());
		}
	}
}
