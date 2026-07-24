package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

public final class ProjectionRenderModeTest
{
	@Test
	public void defaultsToPanOpticFirst()
	{
		assertSame(ProjectionRenderMode.PANOPTIC, ProjectionRenderMode.values()[0]);
	}

	@Test
	public void nextCyclesThroughBothModesExactlyOnce()
	{
		assertEquals(2, ProjectionRenderMode.values().length);
		assertSame(ProjectionRenderMode.VENTICULAR, ProjectionRenderMode.PANOPTIC.next());
		assertSame(ProjectionRenderMode.PANOPTIC, ProjectionRenderMode.VENTICULAR.next());
	}

	@Test
	public void fromNameParsesCaseInsensitivelyAndFallsBack()
	{
		assertSame(ProjectionRenderMode.VENTICULAR, ProjectionRenderMode.fromName("venticular", ProjectionRenderMode.PANOPTIC));
		assertSame(ProjectionRenderMode.PANOPTIC, ProjectionRenderMode.fromName(" PanOptic ", ProjectionRenderMode.VENTICULAR));
		assertSame(ProjectionRenderMode.VENTICULAR, ProjectionRenderMode.fromName("nope", ProjectionRenderMode.VENTICULAR));
		assertSame(ProjectionRenderMode.PANOPTIC, ProjectionRenderMode.fromName(null, ProjectionRenderMode.PANOPTIC));
	}

	@Test
	public void displayNamesUseCanonicalCapitalization()
	{
		assertEquals("PanOptic", ProjectionRenderMode.PANOPTIC.displayName());
		assertEquals("Venticular", ProjectionRenderMode.VENTICULAR.displayName());
	}

	@Test
	public void iconMaterialNamesAreDeclared()
	{
		assertEquals("SPYGLASS", ProjectionRenderMode.PANOPTIC.iconMaterialName());
		assertEquals("TINTED_GLASS", ProjectionRenderMode.VENTICULAR.iconMaterialName());
	}
}
