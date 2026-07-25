package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

public final class LocalPortalEffectLocationTest
{
	@Test
	public void pushAndRejectEffectsSurviveAMissingLocation()
	{
		LocalPortal portal = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("effects-missing-location"), PortalType.PORTAL);

		assertDoesNotThrow(() -> portal.playEffect(PortalEffect.PUSH));
		assertDoesNotThrow(() -> portal.playEffect(PortalEffect.REJECT));
	}

	@Test
	public void pushAndRejectEffectsSurviveAnUnloadedWorld()
	{
		World world = LocalPortalTestSupport.world("effects-unloaded-world");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		Location orphaned = new Location(null, 0.5D, 65.0D, 1.0D);

		assertDoesNotThrow(() -> portal.playEffect(PortalEffect.PUSH, orphaned));
		assertDoesNotThrow(() -> portal.playEffect(PortalEffect.REJECT, orphaned));
	}
}
