package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

public final class RtpBiomeMatcherTest
{
	@Test
	public void normalizeLowercasesTrimsAndDropsBlankKeys()
	{
		assertEquals("minecraft:swamp", RtpBiomeMatcher.normalize(" Minecraft:Swamp "));
		assertEquals("caves/lush", RtpBiomeMatcher.normalize("Caves/Lush"));
		assertNull(RtpBiomeMatcher.normalize(null));
		assertNull(RtpBiomeMatcher.normalize("   "));
	}

	@Test
	public void exactKeysMatchCaseInsensitively()
	{
		assertTrue(RtpBiomeMatcher.matches("minecraft:swamp", List.of("Minecraft:Swamp")));
		assertTrue(RtpBiomeMatcher.matches("caves/lush", List.of("caves/lush")));
		assertFalse(RtpBiomeMatcher.matches("minecraft:swamp", List.of("minecraft:desert")));
	}

	@Test
	public void namespacelessTargetMatchesTheKeyPath()
	{
		assertTrue(RtpBiomeMatcher.matches("swamp", List.of("minecraft:swamp")));
		assertFalse(RtpBiomeMatcher.matches("swamp", List.of("minecraft:mangrove_swamp")));
	}

	@Test
	public void namespacedTargetMatchesNamespacelessCandidatePath()
	{
		assertTrue(RtpBiomeMatcher.matches("minecraft:swamp", List.of("swamp")));
		assertFalse(RtpBiomeMatcher.matches("minecraft:swamp", List.of("desert")));
	}

	@Test
	public void nullAndBlankCandidatesAreIgnored()
	{
		assertTrue(RtpBiomeMatcher.matches("swamp", Arrays.asList(null, " ", "minecraft:swamp")));
		assertFalse(RtpBiomeMatcher.matches("swamp", Arrays.asList(null, " ")));
	}
}
