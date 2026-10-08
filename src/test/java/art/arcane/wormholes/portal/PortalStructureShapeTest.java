package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeRaster;
import art.arcane.optics.shape.Shapes;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.util.Cuboid;

public final class PortalStructureShapeTest
{
	private static final ShapeDescriptor CIRCLE = ShapeDescriptor.parse("circle");
	private static final Frame WALL = Frame.canonical(Face.S);

	@Test
	public void effectiveCellsAreTheBuiltCellsInsideTheRaster()
	{
		PortalStructure structure = wall(LocalPortalTestSupport.world("shape-effective"));
		assertTrue(structure.setApertureShape(CIRCLE, WALL));
		int expected = ShapeRaster.of(PlaneShape.fit(Shapes.circle(1.0D), FitMode.CONTAIN, 7, 7), 4, 0.5D).insideCount();
		assertEquals(expected, structure.getBlockPositions().size());
		assertEquals(CIRCLE, structure.getApertureShape());
		assertNotNull(structure.shapeOutline());
		assertFalse(structure.isFullCuboid());
		assertFalse(structure.containsBlock(0, 64, 0));
		assertTrue(structure.containsBlock(3, 67, 0));
		for(Vector cell : structure.getBlockPositions())
		{
			assertTrue(cell.getBlockX() >= 0 && cell.getBlockX() <= 6 && cell.getBlockY() >= 64 && cell.getBlockY() <= 70);
		}
	}

	@Test
	public void aShapeThatCoversNoCellIsRefusedAndChangesNothing()
	{
		PortalStructure structure = wall(LocalPortalTestSupport.world("shape-refused"));
		assertTrue(structure.setApertureShape(CIRCLE, WALL));
		long revision = structure.getRevision();
		int cells = structure.getBlockPositions().size();
		assertFalse(structure.setApertureShape(ShapeDescriptor.parse("circle(radius=0.05)"), WALL));
		assertEquals(CIRCLE, structure.getApertureShape());
		assertEquals(revision, structure.getRevision());
		assertEquals(cells, structure.getBlockPositions().size());
	}

	@Test
	public void containmentIsExactInsideTheShape()
	{
		World world = LocalPortalTestSupport.world("shape-contains");
		PortalStructure structure = wall(world);
		assertTrue(structure.contains(new Location(world, 0.2D, 70.8D, 0.5D)));
		assertTrue(structure.setApertureShape(CIRCLE, WALL));
		assertTrue(structure.contains(new Location(world, 3.5D, 67.5D, 0.5D)));
		assertFalse(structure.contains(new Location(world, 0.2D, 70.8D, 0.5D)));
		int analyticMisses = 0;
		for(Vector cell : structure.getBlockPositions())
		{
			for(int sample = 0; sample < 16; sample++)
			{
				double x = cell.getBlockX() + ((sample & 3) + 0.5D) / 4.0D;
				double y = cell.getBlockY() + ((sample >> 2) + 0.5D) / 4.0D;
				double u = (x - 3.5D) / 3.5D;
				double v = (y - 67.5D) / 3.5D;
				boolean inside = u * u + v * v <= 1.0D;
				analyticMisses += inside ? 0 : 1;
				assertEquals(inside, structure.contains(new Location(world, x, y, 0.5D)));
			}
		}
		assertTrue(analyticMisses > 0);
	}

	@Test
	public void wallCrossingsAreJudgedAtTheTravellersEye()
	{
		World world = LocalPortalTestSupport.world("shape-admits");
		PortalStructure structure = wall(world);
		Entity player = traveller(LivingEntity.class, 1.62D, 1.8D);
		Entity item = traveller(Entity.class, 0.0D, 0.25D);
		assertTrue(structure.admits(new Location(world, 0.3D, 64.0D, 0.5D), player));
		assertTrue(structure.setApertureShape(CIRCLE, WALL));
		assertTrue(structure.admits(new Location(world, 3.3D, 64.0D, 0.5D), player));
		assertFalse(structure.admits(new Location(world, 0.3D, 64.0D, 0.5D), player));
		assertFalse(structure.admits(new Location(world, 2.4D, 64.0D, 0.5D), item));
		assertTrue(structure.admits(new Location(world, 2.4D, 64.0D, 0.5D), player));
		assertTrue(structure.admits(new Location(world, 2.4D, 66.0D, 0.5D), item));
	}

	@Test
	public void shapeChangesBumpTheRevisionAndFullRestoresTheBuiltCells()
	{
		PortalStructure structure = wall(LocalPortalTestSupport.world("shape-revision"));
		long full = structure.getRevision();
		assertTrue(structure.setApertureShape(CIRCLE, WALL));
		long circle = structure.getRevision();
		assertNotEquals(full, circle);
		assertTrue(structure.setApertureShape(ShapeDescriptor.FULL, WALL));
		assertNotEquals(circle, structure.getRevision());
		assertEquals(49, structure.getBlockPositions().size());
		assertNull(structure.shapeOutline());
		assertTrue(structure.isFullCuboid());
	}

	@Test
	public void builtCellsPersistUnchangedAndTheCenterStaysOnTheBuiltFrame()
	{
		World world = LocalPortalTestSupport.world("shape-persist");
		PortalStructure structure = wall(world);
		Vec3d center = structure.getApertureCenter();
		assertTrue(structure.setApertureShape(ShapeDescriptor.parse("heart"), WALL));
		assertEquals(center, structure.getApertureCenter());
		assertEquals(3.5D, structure.getCenter().getX(), 1.0E-9D);
		JSONObject json = structure.toJSON();
		assertEquals(49, json.getJSONArray("blocks").length());
		assertTrue(structure.getBlockPositions().size() < 49);
	}

	@Test
	public void reorientingAShapedApertureFollowsTheNewFrame()
	{
		PortalStructure structure = wall(LocalPortalTestSupport.world("shape-orient"));
		assertTrue(structure.setApertureShape(ShapeDescriptor.parse("heart"), WALL));
		int top = rowCount(structure, 69);
		int bottom = rowCount(structure, 65);
		assertTrue(top > bottom);
		structure.orient(WALL.rotateClockwise().rotateClockwise());
		assertEquals(bottom, rowCount(structure, 69));
		assertEquals(top, rowCount(structure, 65));
	}

	private static int rowCount(PortalStructure structure, int y)
	{
		int count = 0;
		for(Vector cell : structure.getBlockPositions())
		{
			count += cell.getBlockY() == y ? 1 : 0;
		}
		return count;
	}

	private static Entity traveller(Class<? extends Entity> type, double eyeHeight, double height)
	{
		return (Entity) Proxy.newProxyInstance(PortalStructureShapeTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getEyeHeight" -> Double.valueOf(eyeHeight);
			case "getHeight" -> Double.valueOf(height);
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static PortalStructure wall(World world)
	{
		PortalStructure structure = new PortalStructure();
		structure.setWorld(world);
		structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 6.0D, 70.0D, 0.0D)));
		return structure;
	}
}
