package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

public final class WorldPairingTest
{
	@Test
	public void builtInDimensionsPairByCanonicalKeys()
	{
		assertEquals(NamespacedKey.minecraft("the_nether"),
			WorldPairing.pairedNetherKey(NamespacedKey.minecraft("overworld")));
		assertEquals(NamespacedKey.minecraft("the_end"),
			WorldPairing.pairedEndKey(NamespacedKey.minecraft("overworld")));
		assertEquals(NamespacedKey.minecraft("overworld"),
			WorldPairing.pairedOverworldKey(NamespacedKey.minecraft("the_nether")));
	}

	@Test
	public void customDimensionsKeepTheirNamespaceWhenPaired()
	{
		NamespacedKey overworld = new NamespacedKey("iris", "floating_islands");
		assertEquals(new NamespacedKey("iris", "floating_islands_nether"),
			WorldPairing.pairedNetherKey(overworld));
		assertEquals(overworld,
			WorldPairing.pairedOverworldKey(new NamespacedKey("iris", "floating_islands_the_end")));
	}

	@Test
	public void customOverworldFallsBackToTheCanonicalNether()
	{
		NamespacedKey overworld = new NamespacedKey("iris", "generated_world");
		assertEquals(List.of(new NamespacedKey("iris", "generated_world_nether"), NamespacedKey.minecraft("the_nether")),
			WorldPairing.pairedNetherKeys(overworld));
		assertEquals(List.of(NamespacedKey.minecraft("the_nether")),
			WorldPairing.pairedNetherKeys(NamespacedKey.minecraft("overworld")));
	}
}
