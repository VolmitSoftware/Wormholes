package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Cuboid;
import art.arcane.volmlib.util.json.JSONObject;

public final class PortalCosmeticSettingsPersistenceTest
{
	@Test
	public void saveJsonWritesCosmeticKeys() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(world);
		portal.setAmbientStyle(AmbientParticleStyle.OUTLINE);
		portal.setAmbientColor(0x123456);
		portal.setSurfaceSkin("minecraft:glass");
		portal.setRenderMode(ProjectionRenderMode.VENTICULAR);

		JSONObject json = portal.toJSON();

		assertEquals("OUTLINE", json.getString("ambientStyle"));
		assertEquals(0x123456, json.getInt("ambientColor"));
		assertEquals("minecraft:glass", json.getString("surfaceSkin"));
		assertFalse(json.has("surfaceThickness"));
		assertEquals("VENTICULAR", json.getString("renderMode"));

		LocalPortal reloaded = loadPortal(json, world);
		assertEquals(AmbientParticleStyle.OUTLINE, reloaded.getAmbientStyle());
		assertEquals(0x123456, reloaded.getAmbientColor());
		assertEquals("minecraft:glass", reloaded.getSurfaceSkin());
		assertTrue(reloaded.hasSurfaceSkin());
		assertEquals(ProjectionRenderMode.VENTICULAR, reloaded.getRenderMode());
	}

	@Test
	public void legacySurfaceThicknessKeyLoadsAndIsDroppedOnResave() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(world);
		portal.setSurfaceSkin("minecraft:glass");
		JSONObject json = portal.toJSON();
		json.put("surfaceThickness", 45);

		LocalPortal reloaded = loadPortal(json, world);

		assertEquals("minecraft:glass", reloaded.getSurfaceSkin());
		assertFalse(reloaded.toJSON().has("surfaceThickness"));
	}

	@Test
	public void loadJsonDefaultsMissingKeys() throws Exception
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(world);
		JSONObject json = portal.toJSON();
		json.remove("ambientStyle");
		json.remove("ambientColor");
		json.remove("surfaceSkin");
		json.remove("renderMode");

		LocalPortal reloaded = loadPortal(json, world);

		assertEquals(AmbientParticleStyle.SPARKS, reloaded.getAmbientStyle());
		assertEquals(0xB969FF, reloaded.getAmbientColor());
		assertEquals("", reloaded.getSurfaceSkin());
		assertFalse(reloaded.hasSurfaceSkin());
		assertEquals(ProjectionRenderMode.VENTICULAR, reloaded.getRenderMode());
	}

	@Test
	public void settersNormalizeColorAndSkin()
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(world);

		portal.setAmbientColor(-5);
		assertEquals(0, portal.getAmbientColor());

		portal.setAmbientColor(0x1000000);
		assertEquals(0xFFFFFF, portal.getAmbientColor());

		portal.setAmbientColor(0x00FF00);
		assertEquals(0x00FF00, portal.getAmbientColor());

		portal.setSurfaceSkin("  Minecraft:Glass  ");
		assertEquals("minecraft:glass", portal.getSurfaceSkin());
		assertTrue(portal.hasSurfaceSkin());

		portal.setSurfaceSkin("   ");
		assertEquals("", portal.getSurfaceSkin());
		assertFalse(portal.hasSurfaceSkin());
	}

	@Test
	public void onlyOpaqueSurfaceSkinsBlockProjection()
	{
		LocalPortal portal = portal(world("overworld", -64, 320, 63));

		portal.setSurfaceSkin("minecraft:glass");
		assertFalse(portal.blocksProjection());

		portal.setSurfaceSkin("minecraft:water");
		assertFalse(portal.blocksProjection());

		portal.setSurfaceSkin("minecraft:lava");
		assertTrue(portal.blocksProjection());

		portal.setSurfaceSkin("");
		assertFalse(portal.blocksProjection());
	}

	@Test
	public void ambientStyleSetterIgnoresNull()
	{
		World world = world("overworld", -64, 320, 63);
		LocalPortal portal = portal(world);
		portal.setAmbientStyle(AmbientParticleStyle.CORNERS);

		portal.setAmbientStyle(null);

		assertEquals(AmbientParticleStyle.CORNERS, portal.getAmbientStyle());
	}

	private static LocalPortal portal(World world)
	{
		PortalStructure structure = new PortalStructure();
		structure.setArea(cuboid());
		structure.setWorld(world);
		return new LocalPortal(UUID.randomUUID(), PortalType.WORMHOLE, structure);
	}

	private static LocalPortal loadPortal(JSONObject stored, World world) throws Exception
	{
		PortalStructure structure = new PortalStructure();
		structure.setArea(cuboid());
		structure.setWorld(world);
		LocalPortal portal = new LocalPortal(UUID.fromString(stored.getString("id")), PortalType.valueOf(stored.getString("type")), structure);
		return withBukkitWorld(world, () ->
		{
			portal.loadJSON(stored);
			return portal;
		});
	}

	private static <T> T withBukkitWorld(World world, Supplier<T> action) throws Exception
	{
		synchronized(Bukkit.class)
		{
			Field serverField = Bukkit.class.getDeclaredField("server");
			serverField.setAccessible(true);
			Object previous = serverField.get(null);
			serverField.set(null, server(world));
			try
			{
				return action.get();
			}
			finally
			{
				serverField.set(null, previous);
			}
		}
	}

	private static Server server(World world)
	{
		return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] { Server.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getWorlds" -> List.of(world);
			case "createBlockData" -> blockData((String) arguments[0]);
			case "getName" -> "PortalCosmeticSettingsTestServer";
			case "toString" -> "PortalCosmeticSettingsTestServer";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static BlockData blockData(String state)
	{
		return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getAsString" -> state;
			case "toString" -> state;
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static Cuboid cuboid()
	{
		Map<String, Object> values = new HashMap<String, Object>();
		values.put("worldKey", "minecraft:overworld");
		values.put("x1", Integer.valueOf(0));
		values.put("y1", Integer.valueOf(64));
		values.put("z1", Integer.valueOf(0));
		values.put("x2", Integer.valueOf(0));
		values.put("y2", Integer.valueOf(66));
		values.put("z2", Integer.valueOf(2));
		return new Cuboid(values);
	}

	private static World world(String key, int minimumHeight, int maximumHeight, int seaLevel)
	{
		NamespacedKey namespacedKey = NamespacedKey.minecraft(key);
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> key;
			case "getKey" -> namespacedKey;
			case "getUID" -> UUID.nameUUIDFromBytes(namespacedKey.toString().getBytes());
			case "getMinHeight" -> Integer.valueOf(minimumHeight);
			case "getMaxHeight" -> Integer.valueOf(maximumHeight);
			case "getSeaLevel" -> Integer.valueOf(seaLevel);
			case "toString" -> "PortalCosmeticSettingsTestWorld[" + namespacedKey + "]";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}
}
