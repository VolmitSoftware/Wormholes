package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class IrisBiomeProbeTest
{
	@BeforeEach
	public void resetFakes()
	{
		FakeBiomeToolbelt.generator = new FakeBiomeGenerator();
		FakeBiomeToolbelt.generator.engine = new FakeBiomeEngine();
		FakeBiomeToolbelt.generator.engine.surfaceBiome = new FakeIrisBiome("tropical_beach", "Tropical Beach", "minecraft:beach");
		FakeBiomeToolbelt.generator.engine.caveBiome = new FakeIrisBiome("caves/lush", "Lush Caves", "minecraft:lush_caves");
	}

	@Test
	public void surfaceColumnReportsLoadKeyAndDerivativeKey()
	{
		List<String> keys = fakeProbe().biomeKeysAt(null, 32, -64, null);

		assertEquals(List.of("tropical_beach", "minecraft:beach"), keys);
		assertEquals(32, FakeBiomeToolbelt.generator.engine.lastX);
		assertEquals(-64, FakeBiomeToolbelt.generator.engine.lastZ);
	}

	@Test
	public void probeYUsesThreeDimensionalBiomeLookup()
	{
		List<String> keys = fakeProbe().biomeKeysAt(null, 8, 12, Integer.valueOf(-40));

		assertEquals(List.of("caves/lush", "minecraft:lush_caves"), keys);
		assertEquals(-40, FakeBiomeToolbelt.generator.engine.lastY);
	}

	@Test
	public void allBiomesListsReachablePackBiomes()
	{
		List<IrisBiomeProbe.BiomeInfo> biomes = fakeProbe().allBiomes(null);

		assertEquals(2, biomes.size());
		assertEquals("tropical_beach", biomes.get(0).loadKey());
		assertEquals("Tropical Beach", biomes.get(0).displayName());
		assertEquals("minecraft:beach", biomes.get(0).derivativeKey());
	}

	@Test
	public void missingToolbeltClassIsUnavailable()
	{
		IrisBiomeProbe probe = new IrisBiomeProbe(
				"art.arcane.wormholes.portal.rtp.DoesNotExist",
				() -> IrisBiomeProbeTest.class.getClassLoader());

		assertNull(probe.biomeKeysAt(null, 0, 0, null));
		assertNull(probe.allBiomes(null));
	}

	@Test
	public void missingGeneratorOrClosedEngineIsUnavailable()
	{
		FakeBiomeToolbelt.generator.engine.closed = true;
		assertNull(fakeProbe().biomeKeysAt(null, 0, 0, null));

		FakeBiomeToolbelt.generator = null;
		assertNull(fakeProbe().biomeKeysAt(null, 0, 0, null));
	}

	private IrisBiomeProbe fakeProbe()
	{
		return new IrisBiomeProbe(FakeBiomeToolbelt.class.getName(), () -> FakeBiomeToolbelt.class.getClassLoader());
	}

	public static final class FakeBiomeToolbelt
	{
		static FakeBiomeGenerator generator;

		public static FakeBiomeGenerator access(World world)
		{
			return generator;
		}
	}

	public static final class FakeBiomeGenerator
	{
		FakeBiomeEngine engine;

		public FakeBiomeEngine getEngine()
		{
			return engine;
		}
	}

	public static final class FakeBiomeEngine
	{
		boolean closed;
		FakeIrisBiome surfaceBiome;
		FakeIrisBiome caveBiome;
		int lastX;
		int lastY;
		int lastZ;

		public boolean isClosed()
		{
			return closed;
		}

		public FakeIrisBiome getSurfaceBiome(int x, int z)
		{
			lastX = x;
			lastZ = z;
			return surfaceBiome;
		}

		public FakeIrisBiome getBiome(int x, int y, int z)
		{
			lastX = x;
			lastY = y;
			lastZ = z;
			return caveBiome;
		}

		public List<FakeIrisBiome> getAllBiomes()
		{
			List<FakeIrisBiome> biomes = new ArrayList<FakeIrisBiome>();
			biomes.add(surfaceBiome);
			biomes.add(caveBiome);
			return biomes;
		}
	}

	public static final class FakeIrisBiome
	{
		private final String loadKey;
		private final String name;
		private final FakeDerivative derivative;

		FakeIrisBiome(String loadKey, String name, String derivativeKey)
		{
			this.loadKey = loadKey;
			this.name = name;
			derivative = derivativeKey == null ? null : new FakeDerivative(derivativeKey);
		}

		public String getLoadKey()
		{
			return loadKey;
		}

		public String getName()
		{
			return name;
		}

		public FakeDerivative getDerivative()
		{
			return derivative;
		}
	}

	public static final class FakeDerivative
	{
		private final String key;

		FakeDerivative(String key)
		{
			this.key = key;
		}

		public FakeKey getKey()
		{
			return new FakeKey(key);
		}
	}

	public static final class FakeKey
	{
		private final String key;

		FakeKey(String key)
		{
			this.key = key;
		}

		@Override
		public String toString()
		{
			return key;
		}
	}
}
