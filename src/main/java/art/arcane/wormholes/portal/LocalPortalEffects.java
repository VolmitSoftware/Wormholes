package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.M;
import art.arcane.wormholes.util.MSound;
import art.arcane.wormholes.util.ParticleEffect;

final class LocalPortalEffects
{
	private static final int AMBIENT_OUTLINE_MAX_POINTS = 96;
	private static final int AMBIENT_OUTLINE_OPEN_WINDOW = 32;
	private static final int AMBIENT_OUTLINE_CLOSED_WINDOW = 8;
	private static final int AMBIENT_CORNERS_CLOSED_WINDOW = 2;

	private final LocalPortal portal;
	private final AtomicLong effectSequence = new AtomicLong();
	private final AmbientOutlineGeometry ambientOutline = new AmbientOutlineGeometry();
	private long ambientCursor;

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

	void phase(Axis a, ParticleEffect e, Location l, float scale)
	{
		KList<Vector> vxz = new KList<Vector>();

		for(Direction i : Direction.values())
		{
			if(i.getAxis().equals(a))
			{
				continue;
			}

			vxz.add(i.toVector());
		}

		int k = 1;

		if(M.r(0.7))
		{
			k++;

			if(M.r(0.4))
			{
				k++;

				if(M.r(0.2))
				{
					k++;
				}
			}
		}

		for(int i = 0; i < 64; i++)
		{
			Vector vx = new Vector(0, 0, 0);

			for(int j = 0; j < 18; j++)
			{
				vx.add(vxz.getRandom());
			}

			e.display(vx.clone().normalize(), 0.5f * scale, l, 32);

			if(k > 1)
			{
				e.display(vx.clone().normalize(), 1f * scale, l, 32);

				if(k > 2)
				{
					e.display(vx.clone().normalize(), 1.5f * scale, l, 32);

					if(k > 3)
					{
						e.display(vx.clone().normalize(), 2.0f * scale, l, 32);
					}
				}
			}
		}
	}

	void playEffect(PortalEffect effect, Location location)
	{
		switch(effect)
		{
			case PUSH:
				ParticleEffect.SMOKE.display(0.01f, 6, location, 32);
				if(location != null && location.getWorld() != null && isPortalSoundEnabled())
				{
					location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.7f + (float) (Math.random() * 0.2));
					location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.5f + (float) (Math.random() * 0.2));
					location.getWorld().playSound(location, MSound.ENDERMAN_TELEPORT.bukkitSound(), 0.5f, 1.3f + (float) (Math.random() * 0.2));
				}

				break;
			case REJECT:
				ParticleEffect.SMOKE.display(0.08f, 24, location, 32);
				ParticleEffect.REDSTONE.display(new ParticleEffect.OrdinaryColor(255, 70, 70), location, 32);
				if(location != null && location.getWorld() != null && isPortalSoundEnabled())
				{
					location.getWorld().playSound(location, MSound.ANVIL_LAND.bukkitSound(), 0.7f, 1.8f);
					location.getWorld().playSound(location, MSound.GLASS.bukkitSound(), 0.6f, 0.7f);
				}
				break;
			case AMBIENT_CLOSED:
				renderAmbientParticles(false);

				break;
			case AMBIENT_OPEN:
				renderAmbientParticles(true);

				if(isPortalSoundEnabled() && M.r(0.01))
				{
					portal.getStructure().getCenter().getWorld().playSound(portal.getStructure().getCenter(), Sound.BLOCK_LAVA_AMBIENT, 0.25f, 0.025f);
				}

				if(isPortalSoundEnabled() && M.r(0.01))
				{
					portal.getStructure().getCenter().getWorld().playSound(portal.getStructure().getCenter(), MSound.PORTAL.bukkitSound(), 0.25f, 0.025f);
				}

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

		switch(portal.getAmbientStyle())
		{
			case OFF ->
			{
			}
			case SPARKS -> renderAmbientSparks(open);
			case CORNERS -> renderAmbientCorners(open);
			case OUTLINE -> renderAmbientOutline(open);
		}
	}

	private void renderAmbientSparks(boolean open)
	{
		int count = open ? 4 : 1;
		for(int i = 0; i < count; i++)
		{
			ParticleEffect.TOWN_AURA.display(0f, 1, portal.getStructure().randomLocation(), 16);
		}
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
				world.spawnParticle(Particle.DUST, corner, 1, dust);
			}
			return;
		}

		int start = (int) Math.floorMod(ambientCursor++, corners.size());
		int window = Math.min(AMBIENT_CORNERS_CLOSED_WINDOW, corners.size());
		for(int i = 0; i < window; i++)
		{
			world.spawnParticle(Particle.DUST, corners.get((start + i) % corners.size()), 1, dust);
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

		List<double[]> points = ambientOutline.points(structure.getRevision(), frame.getNormal().getAxis(), structure.getBlockPositions());
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
			world.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0.0D, 0.0D, 0.0D, 0.0D, dust);
		}
	}
}
