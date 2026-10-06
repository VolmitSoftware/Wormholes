package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.ToolPreviewGeometry;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Face;

public final class PortalToolPreviewRendererTest
{
	@Test
	public void geometryCacheUsesPortalIdentityRevisionAndNormalAxis()
	{
		PortalToolPreviewRenderer renderer = new PortalToolPreviewRenderer();
		MutableStructure structure = new MutableStructure(List.of(new Vector(0, 0, 0)));
		UUID portalId = UUID.randomUUID();

		ToolPreviewGeometry.Geometry first = renderer.geometryFor(portalId, structure, Axis.Z);
		ToolPreviewGeometry.Geometry repeated = renderer.geometryFor(portalId, structure, Axis.Z);
		assertSame(first, repeated);
		assertEquals(1, renderer.cachedPortalCount());

		structure.replace(List.of(new Vector(0, 0, 0), new Vector(1, 0, 0)));
		ToolPreviewGeometry.Geometry revised = renderer.geometryFor(portalId, structure, Axis.Z);
		assertNotSame(first, revised);
		assertEquals(2, revised.cells().size());

		ToolPreviewGeometry.Geometry reoriented = renderer.geometryFor(portalId, structure, Axis.Y);
		assertNotSame(revised, reoriented);

		renderer.invalidate(portalId);
		assertEquals(0, renderer.cachedPortalCount());
	}

	@Test
	public void renderingIsViewerOnlyRangeFilteredAndBudgeted()
	{
		boolean previousParticles = Settings.ENABLE_PARTICLES;
		Settings.ENABLE_PARTICLES = true;
		try
		{
			World viewerWorld = world(UUID.randomUUID());
			World otherWorld = world(UUID.randomUUID());
			AtomicInteger outlineParticles = new AtomicInteger();
			AtomicInteger fillParticles = new AtomicInteger();
			Player viewer = player(viewerWorld, new Location(viewerWorld, 0.5D, 64.5D, 4.0D), outlineParticles, fillParticles);
			PortalToolPreviewRenderer renderer = new PortalToolPreviewRenderer();

			renderer.render(viewer, List.of(portal(viewerWorld, structureGrid(100, 20, 0, 64, 0), Face.N)));
			assertEquals(ToolPreviewGeometry.MAX_OUTLINE_PARTICLES, outlineParticles.get());
			assertEquals(ToolPreviewGeometry.MAX_FILL_PARTICLES, fillParticles.get());

			outlineParticles.set(0);
			fillParticles.set(0);
			List<ILocalPortal> crowded = new ArrayList<>(150);
			for(int index = 0; index < 150; index++)
			{
				crowded.add(portal(viewerWorld, structureGrid(1, 1, 0, 64, 0), Face.N));
			}
			renderer.render(viewer, crowded);
			assertEquals(ToolPreviewGeometry.MAX_OUTLINE_PARTICLES, outlineParticles.get());
			assertEquals(ToolPreviewGeometry.MAX_FILL_PARTICLES, fillParticles.get());

			outlineParticles.set(0);
			fillParticles.set(0);
			renderer.render(viewer, List.of(portal(viewerWorld, structureGrid(1, 1, 100, 64, 0), Face.N)));
			assertEquals(0, outlineParticles.get());
			assertEquals(0, fillParticles.get());

			renderer.render(viewer, List.of(portal(otherWorld, structureGrid(1, 1, 0, 64, 0), Face.N)));
			assertEquals(0, outlineParticles.get());
			assertEquals(0, fillParticles.get());
		}
		finally
		{
			Settings.ENABLE_PARTICLES = previousParticles;
		}
	}

	private static MutableStructure structureGrid(int width, int height, int minX, int minY, int z)
	{
		ArrayList<Vector> blocks = new ArrayList<Vector>(width * height);
		for(int x = 0; x < width; x++)
		{
			for(int y = 0; y < height; y++)
			{
				blocks.add(new Vector(minX + x, minY + y, z));
			}
		}
		return new MutableStructure(blocks);
	}

	private static ILocalPortal portal(World world, PortalStructure structure, Face normal)
	{
		UUID portalId = UUID.randomUUID();
		Frame frame = Frame.canonical(normal);
		return (ILocalPortal) Proxy.newProxyInstance(ILocalPortal.class.getClassLoader(), new Class<?>[] {ILocalPortal.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getId" -> portalId;
				case "getWorld" -> world;
				case "getStructure" -> structure;
				case "getFrame" -> frame;
				case "isDestroyed" -> false;
				case "toString" -> "PortalToolPreviewRendererTestPortal";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new AssertionError("Unexpected portal method " + method.getName());
			});
	}

	private static Player player(World world, Location location, AtomicInteger outlineParticles, AtomicInteger fillParticles)
	{
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getWorld" -> world;
				case "getLocation" -> location.clone();
				case "spawnParticle" ->
				{
					Particle particle = (Particle) arguments[0];
					if(particle == Particle.DUST)
					{
						outlineParticles.incrementAndGet();
					}
					else if(particle == Particle.DUST_COLOR_TRANSITION)
					{
						fillParticles.incrementAndGet();
					}
					else
					{
						throw new AssertionError("Unexpected particle " + particle);
					}
					yield null;
				}
				case "toString" -> "PortalToolPreviewRendererTestPlayer";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new AssertionError("Unexpected player method " + method.getName());
			});
	}

	private static World world(UUID worldId)
	{
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getUID" -> worldId;
				case "toString" -> "PortalToolPreviewRendererTestWorld";
				case "hashCode" -> worldId.hashCode();
				case "equals" -> proxy == arguments[0];
				default -> throw new AssertionError("Unexpected world method " + method.getName());
			});
	}

	private static final class MutableStructure extends PortalStructure
	{
		private MutableStructure(List<Vector> positions)
		{
			replace(positions);
		}

		private void replace(List<Vector> replacement)
		{
			List<Vec3d> cells = new ArrayList<>(replacement.size());
			for(Vector position : replacement)
			{
				cells.add(new Vec3d(position.getX(), position.getY(), position.getZ()));
			}
			geometry().setBlocks(cells);
		}
	}
}
