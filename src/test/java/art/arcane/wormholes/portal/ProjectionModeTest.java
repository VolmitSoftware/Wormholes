package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.md_5.bungee.api.ChatColor;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import org.junit.jupiter.api.Test;

public final class ProjectionModeTest
{
	@Test
	public void cyclesStrictlyBetweenOffAndOn()
	{
		assertArrayEquals(new ProjectionMode[] {ProjectionMode.OFF, ProjectionMode.ON}, ProjectionMode.values());
		assertEquals(ProjectionMode.ON, ProjectionMode.OFF.next());
		assertEquals(ProjectionMode.OFF, ProjectionMode.ON.next());
	}

	@Test
	public void primaryProjectionStatesUseBlackAndGoldTheme()
	{
		assertTrue(Wormholes.text().legacyLines(WormholesMessages.PORTAL_MENU_PROJECTION_OFF).getFirst().startsWith(ChatColor.DARK_GRAY.toString()));
		assertTrue(Wormholes.text().legacyLines(WormholesMessages.PORTAL_MENU_PROJECTION_ON).getFirst().startsWith(ChatColor.GOLD.toString()));
		assertFalse(Wormholes.text().legacyLines(WormholesMessages.PORTAL_MENU_PROJECTION_OFF).getFirst().contains(ChatColor.DARK_PURPLE.toString()));
		assertFalse(Wormholes.text().legacyLines(WormholesMessages.PORTAL_MENU_PROJECTION_ON).getFirst().contains(ChatColor.LIGHT_PURPLE.toString()));
	}
}
