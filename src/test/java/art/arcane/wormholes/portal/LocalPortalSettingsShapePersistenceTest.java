package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.util.Cuboid;

public final class LocalPortalSettingsShapePersistenceTest
{
	@Test
	public void theApertureShapeRoundTripsWhileTheBuiltCellsStayPersisted() throws Exception
	{
		World world = LocalPortalTestSupport.world("shape-round-trip");
		LocalPortal portal = portal(world);
		assertTrue(portal.setApertureShape(ShapeDescriptor.parse("circle")));
		JSONObject json = portal.toJSON();
		assertEquals(ShapeDescriptor.parse("circle").format(), json.getString("apertureShape"));
		assertEquals("circle(radius=1)", json.getString("apertureShape"));
		assertEquals(49, json.getJSONObject("structure").getJSONArray("blocks").length());

		LocalPortal reloaded = load(json, world);
		assertEquals(ShapeDescriptor.parse("circle"), reloaded.getApertureShape());
		assertEquals(portal.getStructure().getBlockPositions().size(), reloaded.getStructure().getBlockPositions().size());
		assertTrue(reloaded.getStructure().getBlockPositions().size() < 49);
	}

	@Test
	public void aMissingKeyLoadsAFullAperture() throws Exception
	{
		World world = LocalPortalTestSupport.world("shape-missing");
		JSONObject json = portal(world).toJSON();
		assertFalse(json.has("apertureShape"));
		LocalPortal reloaded = load(json, world);
		assertEquals(ShapeDescriptor.FULL, reloaded.getApertureShape());
		assertEquals(49, reloaded.getStructure().getBlockPositions().size());
	}

	@Test
	public void anUnreadableShapeFallsBackToFullWithOneWarningNamingThePortal() throws Exception
	{
		World world = LocalPortalTestSupport.world("shape-invalid");
		LocalPortal portal = portal(world);
		JSONObject json = portal.toJSON();
		json.put("apertureShape", "circle(radius=");
		List<LogRecord> warnings = new ArrayList<LogRecord>();
		Handler handler = new Handler()
		{
			@Override
			public void publish(LogRecord record)
			{
				if(record.getLevel() == Level.WARNING && record.getMessage().contains(portal.getId().toString()))
				{
					warnings.add(record);
				}
			}

			@Override
			public void flush()
			{
			}

			@Override
			public void close()
			{
			}
		};
		Logger logger = Logger.getLogger("Wormholes");
		logger.addHandler(handler);
		try
		{
			LocalPortal reloaded = load(json, world);
			assertEquals(ShapeDescriptor.FULL, reloaded.getApertureShape());
			assertEquals(49, reloaded.getStructure().getBlockPositions().size());
		}
		finally
		{
			logger.removeHandler(handler);
		}
		assertEquals(1, warnings.size());
	}

	@Test
	public void aShapeTooSmallForTheFrameIsRefusedAndKeepsTheCurrentShape()
	{
		LocalPortal portal = portal(LocalPortalTestSupport.world("shape-small"));
		assertTrue(portal.setApertureShape(ShapeDescriptor.parse("star")));
		assertFalse(portal.setApertureShape(ShapeDescriptor.parse("circle(radius=0.05)")));
		assertEquals(ShapeDescriptor.parse("star"), portal.getApertureShape());
		assertTrue(portal.setApertureShape(ShapeDescriptor.FULL));
		assertFalse(portal.toJSON().has("apertureShape"));
	}

	private static LocalPortal portal(World world)
	{
		PortalStructure structure = new PortalStructure();
		structure.setWorld(world);
		structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 6.0D, 70.0D, 0.0D)));
		LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.WORMHOLE, structure);
		portal.setAmbientAttended(false);
		return portal;
	}

	private static LocalPortal load(JSONObject stored, World world) throws Exception
	{
		LocalPortal portal = portal(world);
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
			case "getName" -> "ApertureShapeTestServer";
			case "toString" -> "ApertureShapeTestServer";
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
}
