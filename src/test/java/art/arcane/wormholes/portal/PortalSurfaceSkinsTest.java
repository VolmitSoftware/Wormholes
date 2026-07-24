package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

public final class PortalSurfaceSkinsTest
{
	@Test
	public void normalizeSkinTrimsLowercasesAndEmptiesBlanks()
	{
		assertEquals("", PortalSurfaceSkins.normalizeSkin(null));
		assertEquals("", PortalSurfaceSkins.normalizeSkin("   "));
		assertEquals("minecraft:glass", PortalSurfaceSkins.normalizeSkin("  Minecraft:Glass  "));
	}

	@Test
	public void isFluidRecognizesWaterAndLava()
	{
		assertTrue(PortalSurfaceSkins.isFluid("minecraft:water"));
		assertTrue(PortalSurfaceSkins.isFluid("minecraft:lava"));
		assertTrue(PortalSurfaceSkins.isFluid("minecraft:water[level=0]"));
		assertTrue(PortalSurfaceSkins.isFluid("water"));
		assertFalse(PortalSurfaceSkins.isFluid("minecraft:glass"));
		assertFalse(PortalSurfaceSkins.isFluid("minecraft:blue_ice"));
		assertFalse(PortalSurfaceSkins.isFluid(""));
		assertFalse(PortalSurfaceSkins.isFluid(null));
	}

	@Test
	public void transparencyIsNameBased()
	{
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:glass"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:blue_stained_glass"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:tinted_glass"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:ice"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:packed_ice"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:blue_ice"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:frosted_ice"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:slime_block"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:honey_block"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:barrier"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:water"));
		assertTrue(PortalSurfaceSkins.isTransparentSkin("minecraft:lava"));

		assertFalse(PortalSurfaceSkins.isTransparentSkin("minecraft:stone"));
		assertFalse(PortalSurfaceSkins.isTransparentSkin("minecraft:oak_planks"));
		assertFalse(PortalSurfaceSkins.isTransparentSkin("minecraft:obsidian"));
		assertFalse(PortalSurfaceSkins.isTransparentSkin(""));
	}

	@Test
	public void skinForHeldItemMapsBuckets()
	{
		assertEquals(Optional.of("minecraft:water"), PortalSurfaceSkins.skinForHeldItem("WATER_BUCKET"));
		assertEquals(Optional.of("minecraft:lava"), PortalSurfaceSkins.skinForHeldItem("LAVA_BUCKET"));
		assertEquals(Optional.of("minecraft:water"), PortalSurfaceSkins.skinForHeldItem(" water_bucket "));
		assertEquals(Optional.empty(), PortalSurfaceSkins.skinForHeldItem("GLASS"));
		assertEquals(Optional.empty(), PortalSurfaceSkins.skinForHeldItem("BUCKET"));
		assertEquals(Optional.empty(), PortalSurfaceSkins.skinForHeldItem(null));
	}
}
