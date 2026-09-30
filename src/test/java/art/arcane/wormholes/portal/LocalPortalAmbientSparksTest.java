package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.util.Cuboid;

public final class LocalPortalAmbientSparksTest
{
	@AfterEach
	public void restoreCadence()
	{
		Settings.AMBIENT_PARTICLE_INTERVAL_TICKS = 1;
	}

	@Test
	public void openSparksSendOneParticlePacketCarryingTheWholeBurstAtAnApertureCellCentre()
	{
		World world = world();
		LocalPortalEffects effects = new LocalPortalEffects(sparkPortal(world));

		for(int tick = 0; tick < 200; tick++)
		{
			effects.playEffect(PortalEffect.AMBIENT_OPEN, null);
		}

		ArgumentCaptor<Location> centre = ArgumentCaptor.forClass(Location.class);
		verify(world, times(200)).spawnParticle(eq(Particle.MYCELIUM), centre.capture(), eq(4),
				near(AmbientSparkCadence.CELL_SPREAD), near(AmbientSparkCadence.CELL_SPREAD), near(AmbientSparkCadence.CELL_SPREAD), eq(0.0D));
		Set<String> cells = new HashSet<String>();
		for(Location location : centre.getAllValues())
		{
			assertEquals(0.5D, location.getX() - Math.floor(location.getX()), 1.0E-9D);
			assertEquals(0.5D, location.getY() - Math.floor(location.getY()), 1.0E-9D);
			assertEquals(0.5D, location.getZ(), 1.0E-9D);
			assertTrue(location.getX() > 0.0D && location.getX() < 3.0D, "x=" + location.getX());
			assertTrue(location.getY() > 64.0D && location.getY() < 67.0D, "y=" + location.getY());
			cells.add(location.getBlockX() + "," + location.getBlockY());
		}
		assertEquals(9, cells.size(), "bursts cover every aperture cell over time");
	}

	@Test
	public void closedSparksSendASingleParticle()
	{
		World world = world();
		LocalPortalEffects effects = new LocalPortalEffects(sparkPortal(world));

		effects.playEffect(PortalEffect.AMBIENT_CLOSED, null);

		verify(world, times(1)).spawnParticle(eq(Particle.MYCELIUM), any(Location.class), eq(1),
				anyDouble(), anyDouble(), anyDouble(), eq(0.0D));
	}

	@Test
	public void slowerCadenceBatchesSeveralTicksIntoOnePacket()
	{
		Settings.AMBIENT_PARTICLE_INTERVAL_TICKS = 4;
		World world = world();
		LocalPortalEffects effects = new LocalPortalEffects(sparkPortal(world));

		for(int tick = 0; tick < 8; tick++)
		{
			effects.playEffect(PortalEffect.AMBIENT_OPEN, null);
		}

		verify(world, times(2)).spawnParticle(eq(Particle.MYCELIUM), any(Location.class), eq(16),
				anyDouble(), anyDouble(), anyDouble(), eq(0.0D));
		verify(world, never()).spawnParticle(eq(Particle.MYCELIUM), any(Location.class), eq(4),
				anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(world, times(2)).spawnParticle(any(Particle.class), any(Location.class), anyInt(),
				anyDouble(), anyDouble(), anyDouble(), anyDouble());
	}

	private static World world()
	{
		World world = mock(World.class);
		when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
		when(world.getUID()).thenReturn(UUID.nameUUIDFromBytes("ambient-sparks".getBytes(StandardCharsets.UTF_8)));
		return world;
	}

	private static double near(double expected)
	{
		return doubleThat(value -> Math.abs(value.doubleValue() - expected) < 1.0E-6D);
	}

	private static LocalPortal sparkPortal(World world)
	{
		PortalStructure structure = new PortalStructure();
		structure.setWorld(world);
		structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 2.0D, 66.0D, 0.0D)));
		LocalPortal portal = mock(LocalPortal.class);
		when(portal.getStructure()).thenReturn(structure);
		when(portal.getAmbientStyle()).thenReturn(AmbientParticleStyle.SPARKS);
		return portal;
	}
}
