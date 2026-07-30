package art.arcane.wormholes.portal;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.util.Direction;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortalFeedbackSettingsTest
{
	@AfterEach
	void restoreDefaults()
	{
		refresh(new MainConfig());
	}

	@Test
	void defaultsPreservePortalFeedback()
	{
		refresh(new MainConfig());
		Traversive traversive = traversive();

		assertEquals(1.0D, Settings.PORTAL_PUSHBACK_MULTIPLIER);
		assertEquals(1.0D, Settings.PORTAL_SOUND_VOLUME_MULTIPLIER);
		assertEquals(-3.0D, LocalPortalTraversal.sourceRejectionVelocity(traversive).getZ(), 1.0E-9D);
		assertEquals(1.75D, LocalPortalTraversal.sourceRejectionPoint(traversive).getZ(), 1.0E-9D);
		assertEquals(0.5F, Settings.portalSoundVolume(0.5F), 1.0E-6F);
	}

	@Test
	void customMultipliersScaleVelocityAndSoundButNotSafetyRelocation()
	{
		MainConfig main = new MainConfig();
		main.portalPushbackMultiplier = 0.5D;
		main.portalSoundVolumeMultiplier = 0.25D;
		refresh(main);
		Traversive traversive = traversive();

		assertEquals(-1.5D, LocalPortalTraversal.sourceRejectionVelocity(traversive).getZ(), 1.0E-9D);
		assertEquals(1.0D, Settings.portalPushback(2.0D), 1.0E-9D);
		assertEquals(1.75D, LocalPortalTraversal.sourceRejectionPoint(traversive).getZ(), 1.0E-9D);
		assertEquals(0.125F, Settings.portalSoundVolume(0.5F), 1.0E-6F);
	}

	@Test
	void refreshClampsOutOfRangeAndNonFiniteMultipliers()
	{
		MainConfig low = new MainConfig();
		low.portalPushbackMultiplier = -1.0D;
		low.portalSoundVolumeMultiplier = -1.0D;
		refresh(low);
		assertEquals(0.0D, Settings.PORTAL_PUSHBACK_MULTIPLIER);
		assertEquals(0.0D, Settings.PORTAL_SOUND_VOLUME_MULTIPLIER);

		MainConfig high = new MainConfig();
		high.portalPushbackMultiplier = 10.0D;
		high.portalSoundVolumeMultiplier = Double.POSITIVE_INFINITY;
		refresh(high);
		assertEquals(4.0D, Settings.PORTAL_PUSHBACK_MULTIPLIER);
		assertEquals(1.0D, Settings.PORTAL_SOUND_VOLUME_MULTIPLIER);

		MainConfig nonFinite = new MainConfig();
		nonFinite.portalPushbackMultiplier = Double.NaN;
		nonFinite.portalSoundVolumeMultiplier = 10.0D;
		refresh(nonFinite);
		assertEquals(1.0D, Settings.PORTAL_PUSHBACK_MULTIPLIER);
		assertEquals(4.0D, Settings.PORTAL_SOUND_VOLUME_MULTIPLIER);
	}

	private static void refresh(MainConfig main)
	{
		Settings.refresh(new WormholesSettings(main, new ProjectionConfig(), new RenderConfig(), new NetworkConfig()));
	}

	private static Traversive traversive()
	{
		PortalFrame frame = PortalFrame.canonical(Direction.N).view(true);
		return new Traversive(
				new Object(),
				TraversableType.ENTITY,
				frame,
				new Vector(2.0D, 65.0D, 3.0D),
				new Vector(2.0D, 65.0D, 3.0D),
				new Vector(0.0D, 0.0D, -0.2D),
				new Vector(0.0D, 0.0D, -1.0D),
				true);
	}
}
