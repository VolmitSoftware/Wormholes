package art.arcane.wormholes;

import java.util.function.BooleanSupplier;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

final class EffectPortalAnimator
{
	private static final int OPEN_PRELUDE_TICKS = 18;
	private static final int KAWOOSH_TICKS = 12;
	private static final int KAWOOSH_SURGE_TICKS = 5;
	private static final int KAWOOSH_BOOM_DELAY_TICKS = 5;
	private static final double KAWOOSH_TWIST_RADIANS = Math.PI * 1.25D;
	private static final int CLOSE_CRACK_TICKS = 10;
	private final EffectDisplayRegistry displays;

	EffectPortalAnimator(EffectDisplayRegistry displays)
	{
		this.displays = displays;
	}

	void playClose(
			World world,
			Location corner,
			double sx,
			double sy,
			double sz,
			BooleanSupplier active,
			BooleanSupplier audible)
	{
		if(displays.isClosing() || world == null || corner == null || active == null || audible == null || !active.getAsBoolean())
		{
			return;
		}

		double[] ext = new double[] { sx, sy, sz };
		final int normalAxis = (ext[0] <= ext[1] && ext[0] <= ext[2]) ? 0 : (ext[1] <= ext[2] ? 1 : 2);
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final double[] cc = new double[] { corner.getX() + (sx / 2.0D), corner.getY() + (sy / 2.0D), corner.getZ() + (sz / 2.0D) };
		Location center = new Location(world, cc[0], cc[1], cc[2]);
		final double halfA = Math.max(0.3D, ext[planeA] / 2.0D);
		final double halfB = Math.max(0.3D, ext[planeB] / 2.0D);
		EffectManager.CloseEffectPlan effectPlan = EffectManager.closeEffectPlan(Settings.VISUAL_QUALITY_PROFILE);
		if(audible.getAsBoolean())
		{
			world.playSound(center, Sound.BLOCK_ENDER_CHEST_CLOSE, SoundCategory.BLOCKS, Settings.portalSoundVolume(1.2f), 0.55f);
		}
		if(!Settings.ENABLE_PARTICLES)
		{
			if(audible.getAsBoolean())
			{
				world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, Settings.portalSoundVolume(1.0f), 0.9f);
			}
			return;
		}

		float thickness = 0.18f;
		float paneA = (float) Math.max(0.25D, ext[planeA]);
		float paneB = (float) Math.max(0.25D, ext[planeB]);
		BlockData glass = Material.GLASS.createBlockData();
		BlockDisplay pane = world.spawn(center, BlockDisplay.class, e ->
		{
			e.setBlock(glass);
			e.setBrightness(new Display.Brightness(15, 15));
			e.setPersistent(false);
			e.setViewRange(2.5f);
			e.setTransformation(centeredPaneTransform(normalAxis, planeA, planeB, thickness, paneA, paneB));
		});
		displays.track(pane);

		double[] crackAngle = new double[effectPlan.branches()];
		double[] crackReach = new double[effectPlan.branches()];
		double[] crackBend = new double[effectPlan.branches()];
		for(int i = 0; i < effectPlan.branches(); i++)
		{
			crackAngle[i] = ((Math.PI * 2.0D * i) / effectPlan.branches()) + ((Math.random() - 0.5D) * 0.45D);
			crackReach[i] = 0.6D + (Math.random() * 0.4D);
			crackBend[i] = (Math.random() - 0.5D) * 0.7D;
		}
		double[] branchletAngle = new double[effectPlan.branches()];
		double[] branchletStart = new double[effectPlan.branches()];
		double[] branchletReach = new double[effectPlan.branches()];
		int[] branchletTick = new int[effectPlan.branches()];
		for(int i = 0; i < effectPlan.branches(); i++)
		{
			double side = Math.random() < 0.5D ? -1.0D : 1.0D;
			branchletAngle[i] = crackAngle[i] + (side * (0.55D + (Math.random() * 0.5D)));
			branchletStart[i] = 0.35D + (Math.random() * 0.3D);
			branchletReach[i] = 0.18D + (Math.random() * 0.22D);
			branchletTick[i] = 3 + (int) (Math.random() * 4.0D);
		}

		Particle.DustOptions crackDust = new Particle.DustOptions(Color.fromRGB(235, 245, 255), 0.75f);
		Particle.DustOptions branchletDust = new Particle.DustOptions(Color.fromRGB(210, 230, 255), 0.55f);
		BlockData shardData = Material.GLASS.createBlockData();

		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(displays.isClosing() || !active.getAsBoolean())
			{
				displays.remove(pane);
				return;
			}
			int t = tick[0]++;
			double[] point = new double[3];
			if(t >= CLOSE_CRACK_TICKS)
			{
				displays.remove(pane);
				for(int i = 0; i < effectPlan.shards(); i++)
				{
					double a = Math.random() * Math.PI * 2.0D;
					double radialA = Math.cos(a);
					double radialB = Math.sin(a);
					double rr = EffectManager.ellipseRadius(halfA, halfB, a) * (0.15D + (Math.random() * 0.85D));
					point[0] = cc[0];
					point[1] = cc[1];
					point[2] = cc[2];
					point[planeA] += rr * radialA;
					point[planeB] += rr * radialB;
					double[] velocity = EffectManager.outwardShardVelocity(normalAxis, planeA, planeB, radialA, radialB, (i & 1) == 0 ? 1.0D : -1.0D);
					world.spawnParticle(Particle.BLOCK, point[0], point[1], point[2], 0, velocity[0], velocity[1], velocity[2], 0.35D, shardData);
				}
				world.spawnParticle(Particle.REVERSE_PORTAL, center, effectPlan.shards(), 0.2, 0.3, 0.2, 0.65);
				world.spawnParticle(Particle.SCULK_SOUL, center, 6, 0.25, 0.35, 0.25, 0.03);
				world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(220, 235, 255));
				if(audible.getAsBoolean())
				{
					world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, Settings.portalSoundVolume(1.8f), 0.8f);
					world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, Settings.portalSoundVolume(1.1f), 1.05f);
					world.playSound(center, Sound.ENTITY_ITEM_BREAK, SoundCategory.BLOCKS, Settings.portalSoundVolume(1.2f), 0.8f);
				}
				return;
			}

			double frac = (double) (t + 1) / (double) CLOSE_CRACK_TICKS;
			for(int i = 0; i < effectPlan.branches(); i++)
			{
				for(int segment = 1; segment <= effectPlan.segments(); segment++)
				{
					double segmentFraction = (double) segment / (double) effectPlan.segments();
					double angle = crackAngle[i] + (crackBend[i] * frac * frac * segmentFraction);
					double len = EffectManager.ellipseRadius(halfA, halfB, angle) * crackReach[i] * frac * segmentFraction;
					point[0] = cc[0];
					point[1] = cc[1];
					point[2] = cc[2];
					point[planeA] += len * Math.cos(angle);
					point[planeB] += len * Math.sin(angle);
					world.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0, crackDust);
				}
			}
			for(int i = 0; i < effectPlan.branches(); i++)
			{
				if(t < branchletTick[i])
				{
					continue;
				}
				double baseFrac = (double) (branchletTick[i] + 1) / (double) CLOSE_CRACK_TICKS;
				double baseAngle = crackAngle[i] + (crackBend[i] * baseFrac * baseFrac * branchletStart[i]);
				double baseLen = EffectManager.ellipseRadius(halfA, halfB, baseAngle) * crackReach[i] * baseFrac * branchletStart[i];
				double growth = Math.min(1.0D, (double) (t - branchletTick[i] + 1) / 4.0D);
				for(int segment = 1; segment <= 2; segment++)
				{
					double offshoot = EffectManager.ellipseRadius(halfA, halfB, branchletAngle[i]) * branchletReach[i] * growth * ((double) segment / 2.0D);
					point[0] = cc[0];
					point[1] = cc[1];
					point[2] = cc[2];
					point[planeA] += (baseLen * Math.cos(baseAngle)) + (offshoot * Math.cos(branchletAngle[i]));
					point[planeB] += (baseLen * Math.sin(baseAngle)) + (offshoot * Math.sin(branchletAngle[i]));
					world.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0, branchletDust);
				}
			}
			if(t == 0 && audible.getAsBoolean())
			{
				world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, Settings.portalSoundVolume(0.4f), 1.7f);
			}
			if(t % 3 == 1 && audible.getAsBoolean())
			{
				world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.BLOCKS, Settings.portalSoundVolume(0.5f), 0.7f);
			}
			if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
			{
				displays.remove(pane);
			}
		};
		if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
		{
			displays.remove(pane);
		}
	}

	void playOpenPrelude(
			World world,
			Location center,
			double sx,
			double sy,
			double sz,
			BooleanSupplier active,
			BooleanSupplier audible)
	{
		double[] ext = new double[] { sx, sy, sz };
		final int normalAxis = (ext[0] <= ext[1] && ext[0] <= ext[2]) ? 0 : (ext[1] <= ext[2] ? 1 : 2);
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final double halfA = Math.max(0.6D, (ext[planeA] / 2.0D) + 0.35D);
		final double halfB = Math.max(0.6D, (ext[planeB] / 2.0D) + 0.35D);
		final double[] cc = new double[] { center.getX(), center.getY(), center.getZ() };
		final int streams = EffectManager.openingRingPoints(Settings.VISUAL_QUALITY_PROFILE);
		final double[] streamAngle = new double[streams];
		final double[] streamTurns = new double[streams];
		final double[] streamExponent = new double[streams];
		for(int i = 0; i < streams; i++)
		{
			streamAngle[i] = ((Math.PI * 2.0D * i) / streams) + ((Math.random() - 0.5D) * 0.8D);
			streamTurns[i] = 1.2D + (Math.random() * 1.0D);
			streamExponent[i] = 1.2D + (Math.random() * 0.5D);
		}
		final Particle.DustTransition streamDust = new Particle.DustTransition(Color.fromRGB(185, 105, 255), Color.fromRGB(20, 5, 35), 0.8f);
		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(displays.isClosing() || !active.getAsBoolean())
			{
				return;
			}
			int t = tick[0]++;
			if(t >= OPEN_PRELUDE_TICKS)
			{
				playKawoosh(world, center, sx, sy, sz, active, audible);
				return;
			}
			double frac = (double) (t + 1) / (double) OPEN_PRELUDE_TICKS;
			double[] point = new double[3];
			for(int i = 0; i < streams; i++)
			{
				double angle = streamAngle[i] + (frac * streamTurns[i] * Math.PI * 2.0D);
				double radius = EffectManager.ellipseRadius(halfA, halfB, angle) * Math.pow(1.0D - frac, streamExponent[i]);
				point[0] = cc[0];
				point[1] = cc[1];
				point[2] = cc[2];
				point[planeA] += radius * Math.cos(angle);
				point[planeB] += radius * Math.sin(angle);
				Particle stream = (i & 1) == 0 ? Particle.PORTAL : Particle.REVERSE_PORTAL;
				world.spawnParticle(stream, point[0], point[1], point[2], 1, 0.03, 0.03, 0.03, 0.02);
				if((t & 1) == 0)
				{
					world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0, streamDust);
				}
			}
			if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
			{
				Wormholes.v("[EffectManager] open prelude tick rejected at " + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ() + "; delivering kawoosh early");
				playKawoosh(world, center, sx, sy, sz, active, audible);
			}
		};
		if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
		{
			Wormholes.v("[EffectManager] open prelude rejected at " + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ() + "; delivering kawoosh early");
			playKawoosh(world, center, sx, sy, sz, active, audible);
		}
	}

	void playKawoosh(
			World world,
			Location center,
			double sx,
			double sy,
			double sz,
			BooleanSupplier active,
			BooleanSupplier audible)
	{
		if(displays.isClosing() || world == null || center == null || active == null || !active.getAsBoolean())
		{
			return;
		}
		double[] ext = new double[] { sx, sy, sz };
		final int normalAxis = (ext[0] <= ext[1] && ext[0] <= ext[2]) ? 0 : (ext[1] <= ext[2] ? 1 : 2);
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final double halfA = Math.max(0.6D, (ext[planeA] / 2.0D) + 0.35D);
		final double halfB = Math.max(0.6D, (ext[planeB] / 2.0D) + 0.35D);
		final double[] cc = new double[] { center.getX(), center.getY(), center.getZ() };
		final EffectManager.KawooshPlan plan = EffectManager.kawooshPlan(Settings.VISUAL_QUALITY_PROFILE);
		world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(190, 130, 255));
		world.spawnParticle(Particle.REVERSE_PORTAL, center, plan.impactReverse(), 0.25, 0.45, 0.25, 0.75);
		world.spawnParticle(Particle.END_ROD, center, plan.impactEndRod(), 0.15, 0.15, 0.15, 0.3);
		playKawooshSounds(world, center, active, audible);
		final Particle.DustTransition armDust = new Particle.DustTransition(Color.fromRGB(185, 105, 255), Color.fromRGB(20, 5, 35), 0.9f);
		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(displays.isClosing() || !active.getAsBoolean())
			{
				return;
			}
			int t = tick[0]++;
			if(t >= KAWOOSH_TICKS)
			{
				return;
			}
			double frac = (double) (t + 1) / (double) KAWOOSH_TICKS;
			int armPoints = Math.max(3, (int) Math.round(plan.armPoints() * frac));
			double[] point = new double[3];
			for(int arm = 0; arm < plan.arms(); arm++)
			{
				double armOffset = (Math.PI * 2.0D * arm) / plan.arms();
				for(int j = 1; j <= armPoints; j++)
				{
					double along = frac * ((double) j / (double) armPoints);
					double angle = armOffset + (along * KAWOOSH_TWIST_RADIANS);
					double radius = EffectManager.ellipseRadius(halfA, halfB, angle) * along;
					point[0] = cc[0];
					point[1] = cc[1];
					point[2] = cc[2];
					point[planeA] += radius * Math.cos(angle);
					point[planeB] += radius * Math.sin(angle);
					if(j == armPoints)
					{
						world.spawnParticle(Particle.END_ROD, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.02);
					}
					else
					{
						world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0, armDust);
					}
				}
			}
			if(t < KAWOOSH_SURGE_TICKS)
			{
				double surgeSpeed = 0.6D - (t * 0.07D);
				for(int i = 0; i < plan.surgeCount(); i++)
				{
					double[] direction = new double[3];
					direction[normalAxis] = (i & 1) == 0 ? 1.0D : -1.0D;
					direction[planeA] = (Math.random() - 0.5D) * 0.45D;
					direction[planeB] = (Math.random() - 0.5D) * 0.45D;
					world.spawnParticle(Particle.END_ROD, cc[0], cc[1], cc[2], 0, direction[0], direction[1], direction[2], surgeSpeed);
				}
			}
			else
			{
				double settleSpread = Math.max(0.15D, 0.9D * (1.0D - frac));
				world.spawnParticle(Particle.REVERSE_PORTAL, cc[0], cc[1], cc[2], 6, settleSpread, settleSpread, settleSpread, 0.2);
				world.spawnParticle(Particle.ENCHANT, cc[0], cc[1], cc[2], 3, settleSpread, settleSpread, settleSpread, 0.05);
			}
			if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
			{
				Wormholes.v("[EffectManager] kawoosh tick rejected at " + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ());
			}
		};
		if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
		{
			Wormholes.v("[EffectManager] kawoosh animation rejected at " + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ());
		}
	}

	void playKawooshSounds(World world, Location center, BooleanSupplier active, BooleanSupplier audible)
	{
		EffectManager.OpeningSoundPlan plan = EffectManager.openingSoundPlan();
		if(audible.getAsBoolean())
		{
			world.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, plan.portalImpactVolume(), 0.85f);
			world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, plan.beaconImpactVolume(), 0.55f);
		}
		Runnable boom = () ->
		{
			if(!displays.isClosing() && active.getAsBoolean() && audible.getAsBoolean())
			{
				world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.BLOCKS, plan.sonicBoomVolume(), 0.8f);
			}
		};
		if(!FoliaScheduler.runRegion(Wormholes.instance, center, boom, KAWOOSH_BOOM_DELAY_TICKS))
		{
			boom.run();
		}
	}

	private static Transformation centeredPaneTransform(int normalAxis, int planeA, int planeB, float thickness, float scaleA, float scaleB)
	{
		float[] scale = new float[3];
		scale[normalAxis] = thickness;
		scale[planeA] = scaleA;
		scale[planeB] = scaleB;
		return new Transformation(new Vector3f(-scale[0] / 2.0f, -scale[1] / 2.0f, -scale[2] / 2.0f), new Quaternionf(), new Vector3f(scale[0], scale[1], scale[2]), new Quaternionf());
	}
}
