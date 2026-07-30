package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.json.JSONObject;

public final class LocalPortalRtpPersistenceTest
{
	@Test
	public void unloadedSourceWorldPreservesStoredRtpSettingsInsteadOfRewritingThemAway()
	{
		LocalPortal portal = orphanedRtpPortal();
		JSONObject json = storedRtpJson(777, 4096);

		portal.rtp().load(json);

		assertNull(portal.getRtpSettings());
		assertFalse(portal.rtp().requiresPersistenceNormalization(json),
				"a portal that could not read its RTP block must not be rewritten without one");
		JSONObject rewritten = new JSONObject();
		portal.rtp().save(rewritten);
		assertTrue(rewritten.has("rtp"), "the operator's stored RTP block must survive a save while the world is unloaded");
		assertEquals(777, rewritten.getJSONObject("rtp").getInt("minimumRadius"));
		assertEquals(4096, rewritten.getJSONObject("rtp").getInt("maximumRadius"));
	}

	@Test
	public void resolvableSourceWorldStillReadsStoredRtpSettings()
	{
		World world = LocalPortalTestSupport.world("rtp-persistence-resolvable");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.RTP);

		portal.rtp().load(storedRtpJson(48, 512));

		assertNotNull(portal.getRtpSettings());
		assertEquals(48, portal.getRtpSettings().getMinimumRadius());
		assertEquals(512, portal.getRtpSettings().getMaximumRadius());
	}

	@Test
	public void applyingSettingsRetiresThePreservedBlock()
	{
		LocalPortal portal = orphanedRtpPortal();
		portal.rtp().load(storedRtpJson(777, 4096));
		portal.getStructure().setWorld(LocalPortalTestSupport.world("rtp-persistence-restored"));
		portal.rtp().applyRtpSettings(portal.rtp().defaultRtpSettings());

		JSONObject rewritten = new JSONObject();
		portal.rtp().save(rewritten);

		assertNotNull(portal.getRtpSettings());
		assertEquals(portal.getRtpSettings().getMinimumRadius(), rewritten.getJSONObject("rtp").getInt("minimumRadius"));
	}

	@Test
	public void normalizingAnRtpPortalClearsMirrorModeInsteadOfRetypingThePortal()
	{
		World world = LocalPortalTestSupport.world("rtp-normalize-mirror");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.RTP);
		portal.settings().assignMirrorMode(true);

		assertTrue(portal.rtp().normalizeState());

		assertEquals(PortalType.RTP, portal.getType(), "normalizing RTP state must not silently retype the portal");
		assertFalse(portal.isMirrorMode(), "an RTP portal cannot stay in mirror mode");
	}

	private static LocalPortal orphanedRtpPortal()
	{
		World world = LocalPortalTestSupport.world("rtp-persistence-orphan");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.RTP);
		portal.getStructure().setWorld(null);
		return portal;
	}

	private static JSONObject storedRtpJson(int minimumRadius, int maximumRadius)
	{
		JSONObject stored = new JSONObject();
		stored.put("minimumRadius", minimumRadius);
		stored.put("maximumRadius", maximumRadius);
		stored.put("rotationMode", "STATIC");
		JSONObject json = new JSONObject();
		json.put("rtp", stored);
		return json;
	}
}
