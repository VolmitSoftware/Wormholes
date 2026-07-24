package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

public final class AmbientParticleStyleTest
{
	@Test
	public void nextCyclesThroughEveryStyleExactlyOnce()
	{
		assertEquals(4, AmbientParticleStyle.values().length);

		for(AmbientParticleStyle start : AmbientParticleStyle.values())
		{
			Set<AmbientParticleStyle> visited = EnumSet.noneOf(AmbientParticleStyle.class);
			AmbientParticleStyle current = start;

			for(int i = 0; i < AmbientParticleStyle.values().length; i++)
			{
				assertEquals(true, visited.add(current));
				current = current.next();
			}

			assertEquals(AmbientParticleStyle.values().length, visited.size());
			assertSame(start, current);
		}
	}

	@Test
	public void fromNameParsesCaseInsensitivelyAndFallsBack()
	{
		assertSame(AmbientParticleStyle.OUTLINE, AmbientParticleStyle.fromName("outline", AmbientParticleStyle.SPARKS));
		assertSame(AmbientParticleStyle.CORNERS, AmbientParticleStyle.fromName(" Corners ", AmbientParticleStyle.SPARKS));
		assertSame(AmbientParticleStyle.SPARKS, AmbientParticleStyle.fromName("nope", AmbientParticleStyle.SPARKS));
		assertSame(AmbientParticleStyle.OFF, AmbientParticleStyle.fromName(null, AmbientParticleStyle.OFF));
	}

	@Test
	public void displayNameIsHumanReadable()
	{
		assertEquals("Sparks", AmbientParticleStyle.SPARKS.displayName());
		assertEquals("Outline", AmbientParticleStyle.OUTLINE.displayName());
		assertEquals("Corners", AmbientParticleStyle.CORNERS.displayName());
		assertEquals("Off", AmbientParticleStyle.OFF.displayName());
	}

	@Test
	public void iconMaterialNamesAreDeclared()
	{
		assertEquals("FIREWORK_STAR", AmbientParticleStyle.SPARKS.iconMaterialName());
		assertEquals("BLAZE_ROD", AmbientParticleStyle.OUTLINE.iconMaterialName());
		assertEquals("END_ROD", AmbientParticleStyle.CORNERS.iconMaterialName());
		assertEquals("GLASS", AmbientParticleStyle.OFF.iconMaterialName());
	}
}
