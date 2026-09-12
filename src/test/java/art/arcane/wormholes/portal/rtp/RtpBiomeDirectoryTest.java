package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.rtp.IrisBiomeProbe.BiomeInfo;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.BiomeOption;

public final class RtpBiomeDirectoryTest
{
	@Test
	public void unavailableWorldDoesNotReadEitherCatalog()
	{
		assertTrue(RtpBiomeDirectory.optionsFor(null,
				world -> fail("An unavailable world cannot supply Iris biomes"),
				() -> fail("An unavailable world cannot supply registry biomes")).isEmpty());
	}

	@Test
	public void selectedWorldControlsTheIrisCatalog()
	{
		World overworld = world("overworld");
		World underworld = world("underworld");
		World vanilla = world("vanilla");
		Map<World, List<BiomeInfo>> catalogs = Map.of(
				overworld, List.of(new BiomeInfo("tropical/highlands", "Tropical Highlands")),
				underworld, List.of(new BiomeInfo("volcanic/highlands", "Volcanic Highlands")));
		Function<World, List<BiomeInfo>> irisLookup = catalogs::get;

		assertEquals(List.of(new BiomeOption("tropical/highlands", "Tropical Highlands", true)),
				RtpBiomeDirectory.optionsFor(overworld, irisLookup, () -> fail("Iris worlds use their own catalogs")));
		assertEquals(List.of(new BiomeOption("volcanic/highlands", "Volcanic Highlands", true)),
				RtpBiomeDirectory.optionsFor(underworld, irisLookup, () -> fail("Iris worlds use their own catalogs")));
		assertEquals(List.of(new BiomeOption("minecraft:plains", "Plains", false)),
				RtpBiomeDirectory.optionsFor(vanilla, irisLookup,
						() -> List.of("minecraft:plains", "iris:11111111-1111-1111-1111-111111111111")));
	}

	@Test
	public void anEmptyAvailableIrisCatalogStaysEmpty()
	{
		assertTrue(RtpBiomeDirectory.optionsFor(world("iris"), selected -> List.of(),
				() -> fail("An empty Iris catalog must not expose the global registry")).isEmpty());
	}

	@Test
	public void irisBiomesSortByNameThenCanonicalKeyAndDeduplicate()
	{
		List<BiomeOption> options = RtpBiomeDirectory.optionsFor(world("iris"), selected -> List.of(
				new BiomeInfo("mountain/peaks", "Zenith Peaks"),
				new BiomeInfo("temperate/birch", "Birch Forest"),
				new BiomeInfo(" tropical/birch ", "birch forest"),
				new BiomeInfo("TROPICAL/BIRCH", "Duplicate"),
				new BiomeInfo("swamp/ancient", "Ancient Marsh"),
				new BiomeInfo(" ", "Invalid")), () -> fail("Iris catalogs do not read registry biomes"));

		assertEquals(List.of(
				new BiomeOption("swamp/ancient", "Ancient Marsh", true),
				new BiomeOption("temperate/birch", "Birch Forest", true),
				new BiomeOption("tropical/birch", "birch forest", true),
				new BiomeOption("mountain/peaks", "Zenith Peaks", true)), options);
		assertThrows(UnsupportedOperationException.class, () -> options.add(new BiomeOption("extra", "Extra", true)));
	}

	@Test
	public void registryBiomesExcludeOnlyTheIrisNamespaceAndKeepDatapacks()
	{
		List<BiomeOption> options = RtpBiomeDirectory.optionsFor(world("vanilla"), selected -> null, () -> List.of(
				"minecraft:swamp", "IRIS:11111111-1111-1111-1111-111111111111", "other:iris_grove",
				"custom:lavender_fields", "minecraft:plains", "custom:plains", " MINECRAFT:PLAINS ", " "));

		assertEquals(List.of(
				new BiomeOption("other:iris_grove", "Iris Grove", false),
				new BiomeOption("custom:lavender_fields", "Lavender Fields", false),
				new BiomeOption("custom:plains", "Plains", false),
				new BiomeOption("minecraft:plains", "Plains", false),
				new BiomeOption("minecraft:swamp", "Swamp", false)), options);
		assertFalse(options.stream().anyMatch(BiomeOption::irisBiome));
	}

	@Test
	public void unavailableRegistryProducesNoOptions()
	{
		World vanilla = world("vanilla");
		assertTrue(RtpBiomeDirectory.optionsFor(vanilla, selected -> null, () ->
		{
			throw new IllegalStateException("Registry is not available");
		}).isEmpty());
		assertTrue(RtpBiomeDirectory.optionsFor(vanilla, selected -> null, () ->
		{
			throw new NoClassDefFoundError("Registry is not available");
		}).isEmpty());
	}

	private static World world(String name)
	{
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class },
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "getName", "toString" -> name;
					case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
					case "equals" -> Boolean.valueOf(proxy == arguments[0]);
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
