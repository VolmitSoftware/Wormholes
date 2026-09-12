package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.hook.PortalExtension;
import art.arcane.wormholes.hook.PortalExtensionFactory;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;

final class PortalExtensionsTest
{
	@AfterEach
	void clearHooks()
	{
		WormholesHooks.clear();
	}

	@Test
	void registeredExtensionPersistsInsidePortalJsonAndSyncsOnlyItsOwnPrefix()
	{
		WormholesHooks.install(new WormholesRegistrar().portalExtension(new CounterFactory()));
		World world = LocalPortalTestSupport.world("extensions");
		LocalPortal source = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		CounterExtension counter = source.extension(CounterExtension.class);
		assertNotNull(counter);
		counter.value = 7;

		JSONObject encoded = source.toJSON();
		assertEquals(7, encoded.getInt("counter.value"));

		LocalPortal target = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		target.extensions().load(encoded);
		assertEquals(7, target.extension(CounterExtension.class).value);

		Map<String, String> settings = new LinkedHashMap<String, String>();
		source.extensions().collectSync(settings);
		assertEquals("7", settings.get("counter.value"));

		Map<String, String> inbound = new LinkedHashMap<String, String>();
		inbound.put("other.value", "99");
		inbound.put("counter.value", "3");
		target.extensions().applySync(inbound);
		assertEquals(3, target.extension(CounterExtension.class).value);
		assertEquals(Map.of("counter.value", "3"), target.extension(CounterExtension.class).lastApplied);
	}

	@Test
	void unregisteredExtensionTypeResolvesToNull()
	{
		World world = LocalPortalTestSupport.world("no-extensions");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		assertNull(portal.extension(CounterExtension.class));
		JSONObject encoded = portal.toJSON();
		assertTrue(!encoded.has("counter.value"));
	}

	private static final class CounterFactory implements PortalExtensionFactory
	{
		@Override
		public Class<? extends PortalExtension> type()
		{
			return CounterExtension.class;
		}

		@Override
		public PortalExtension create(LocalPortal portal)
		{
			return new CounterExtension();
		}
	}

	private static final class CounterExtension implements PortalExtension
	{
		int value;
		Map<String, String> lastApplied = Map.of();

		@Override
		public String key()
		{
			return "counter";
		}

		@Override
		public void save(JSONObject portalJson)
		{
			portalJson.put("counter.value", value);
		}

		@Override
		public void load(JSONObject portalJson)
		{
			value = portalJson.optInt("counter.value", 0);
		}

		@Override
		public void collectSync(Map<String, String> settings)
		{
			settings.put("counter.value", Integer.toString(value));
		}

		@Override
		public void applySync(Map<String, String> settings)
		{
			lastApplied = Map.copyOf(settings);
			value = Integer.parseInt(settings.get("counter.value"));
		}
	}
}
