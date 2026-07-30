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
	public void nextCyclesThroughAllModesExactlyOnce()
	{
		assertEquals(3, ProjectionRenderMode.values().length);
		assertSame(ProjectionRenderMode.VENTICULAR, ProjectionRenderMode.PANOPTIC.next());
		assertSame(ProjectionRenderMode.PLANNAR_OPTIC, ProjectionRenderMode.VENTICULAR.next());
		assertSame(ProjectionRenderMode.PANOPTIC, ProjectionRenderMode.PLANNAR_OPTIC.next());
	}

	@Test
	public void fromNameParsesCaseInsensitivelyAndFallsBack()
	{
		assertSame(ProjectionRenderMode.VENTICULAR, ProjectionRenderMode.fromName("venticular", ProjectionRenderMode.PANOPTIC));
		assertSame(ProjectionRenderMode.PANOPTIC, ProjectionRenderMode.fromName(" PanOptic ", ProjectionRenderMode.VENTICULAR));
		assertSame(ProjectionRenderMode.PLANNAR_OPTIC, ProjectionRenderMode.fromName("plannar_optic", ProjectionRenderMode.PANOPTIC));
		assertSame(ProjectionRenderMode.VENTICULAR, ProjectionRenderMode.fromName("nope", ProjectionRenderMode.VENTICULAR));
		assertSame(ProjectionRenderMode.PANOPTIC, ProjectionRenderMode.fromName(null, ProjectionRenderMode.PANOPTIC));
	}

	@Test
	public void displayNamesUseCanonicalCapitalization()
	{
		assertEquals("PanOptic", ProjectionRenderMode.PANOPTIC.displayName());
		assertEquals("Venticular", ProjectionRenderMode.VENTICULAR.displayName());
		assertEquals("PlannarOptic", ProjectionRenderMode.PLANNAR_OPTIC.displayName());
	}

	@Test
	public void iconMaterialNamesAreDeclared()
	{
		assertEquals("SPYGLASS", ProjectionRenderMode.PANOPTIC.iconMaterialName());
		assertEquals("TINTED_GLASS", ProjectionRenderMode.VENTICULAR.iconMaterialName());
		assertEquals("ENDER_EYE", ProjectionRenderMode.PLANNAR_OPTIC.iconMaterialName());
	}

	@Test
	public void optimizedModesUseBuriedCellCulling()
	{
		assertEquals(false, ProjectionRenderMode.PANOPTIC.usesBuriedCellCulling());
		assertEquals(true, ProjectionRenderMode.VENTICULAR.usesBuriedCellCulling());
		assertEquals(true, ProjectionRenderMode.PLANNAR_OPTIC.usesBuriedCellCulling());
	}
}
