package art.arcane.wormholes.door;

import art.arcane.wormholes.util.Direction;

import art.arcane.wormholes.util.Axis;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import art.arcane.wormholes.util.Direction;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class DoorPortalVisualServiceTest
{
	private static final float EPSILON = 0.00001F;
	private static final float PIXEL = 0.0625F;

	@Test
	void portalKeepsTheHingeInsetAndExtendsFlushToEveryLatchEdge()
	{
		assertLateralBounds(Direction.N, DoorHinge.LEFT, PIXEL, 1.0F);
		assertLateralBounds(Direction.N, DoorHinge.RIGHT, 0.0F, 1.0F - PIXEL);
		assertLateralBounds(Direction.S, DoorHinge.LEFT, 0.0F, 1.0F - PIXEL);
		assertLateralBounds(Direction.S, DoorHinge.RIGHT, PIXEL, 1.0F);
		assertLateralBounds(Direction.E, DoorHinge.LEFT, PIXEL, 1.0F);
		assertLateralBounds(Direction.E, DoorHinge.RIGHT, 0.0F, 1.0F - PIXEL);
		assertLateralBounds(Direction.W, DoorHinge.LEFT, 0.0F, 1.0F - PIXEL);
		assertLateralBounds(Direction.W, DoorHinge.RIGHT, PIXEL, 1.0F);
	}

	@Test
	void everyCardinalPlaneIsInsetFromTheDoorFrame()
	{
		for(Direction facing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W})
		{
			for(DoorHinge hinge : DoorHinge.values())
			{
				PortalPlaneGeometry geometry =
					DoorPortalGeometry.geometry(facing, hinge);
				assertEquals(PIXEL, geometry.translationY(), EPSILON);
				assertEquals(1.9375F, geometry.translationY() + geometry.scaleY(), EPSILON);
				assertEquals(1.875F, geometry.scaleY(), EPSILON);
				assertEquals(0.9375F, Math.max(geometry.scaleX(), geometry.scaleZ()), EPSILON);
			}
		}
	}

	@Test
	void movementThresholdMatchesTheVisiblePortalSurface()
	{
		for(Direction facing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W})
		{
			for(DoorHinge hinge : DoorHinge.values())
			{
				PortalPlaneGeometry geometry =
					DoorPortalGeometry.geometry(facing, hinge);
				double localCenterX = geometry.translationX() + (geometry.scaleX() / 2.0D);
				double localCenterZ = geometry.translationZ() + (geometry.scaleZ() / 2.0D);
				double visibleOffset = (localCenterX * facing.x()) + (localCenterZ * facing.z());
				DoorwayPlane plane = new DoorwayPlane(0, 64, 0, facing);

				assertEquals(DoorwayPlane.PORTAL_THRESHOLD_OFFSET, visibleOffset, EPSILON);
				assertEquals(DoorwayPlane.PORTAL_THRESHOLD_OFFSET,
					((plane.center().x() - 0.5D) * facing.x())
						+ ((plane.center().z() - 0.5D) * facing.z()),
					EPSILON);
			}
		}
	}

	@Test
	void portalUsesAnOpaqueBackingAndAnimatedOverlay()
	{
		assertEquals(Material.CRYING_OBSIDIAN, DoorPortalVisualService.PORTAL_MATERIAL);
		assertEquals(Material.NETHER_PORTAL, DoorPortalVisualService.PORTAL_OVERLAY_MATERIAL);
		assertEquals(Axis.X, DoorPortalGeometry.overlayAxis(Direction.N));
		assertEquals(Axis.X, DoorPortalGeometry.overlayAxis(Direction.S));
		assertEquals(Axis.Z, DoorPortalGeometry.overlayAxis(Direction.E));
		assertEquals(Axis.Z, DoorPortalGeometry.overlayAxis(Direction.W));
	}

	@Test
	void animatedOverlayStraddlesBothBackingFacesWithoutZFighting()
	{
		for(Direction facing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W})
		{
			for(DoorHinge hinge : DoorHinge.values())
			{
				PortalPlaneGeometry backing =
					DoorPortalGeometry.geometry(facing, hinge);
				PortalPlaneGeometry overlay =
					DoorPortalGeometry.overlayGeometry(backing, facing);
				float backingStart = normalTranslation(backing, facing);
				float backingEnd = backingStart + normalScale(backing, facing);
				float overlayStart = normalTranslation(overlay, facing);
				float overlayScale = normalScale(overlay, facing);

				assertEquals(backingStart - 0.00125F, overlayStart + (overlayScale * 0.375F), EPSILON);
				assertEquals(backingEnd + 0.00125F, overlayStart + (overlayScale * 0.625F), EPSILON);
				assertEquals(backing.translationY(), overlay.translationY(), EPSILON);
				assertEquals(backing.scaleY(), overlay.scaleY(), EPSILON);
			}
		}
	}

	@Test
	void nonCardinalFacingsAreRejected()
	{
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalGeometry.geometry(Direction.U, DoorHinge.LEFT));
		assertThrows(NullPointerException.class,
			() -> DoorPortalGeometry.geometry(null, DoorHinge.LEFT));
		assertThrows(NullPointerException.class,
			() -> DoorPortalGeometry.geometry(Direction.N, null));
	}

	@Test
	void trapdoorVeilIsAFlatUnitPanelLyingInThePlatePlane()
	{
		for(Direction facing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W})
		{
			for(org.bukkit.block.data.Bisected.Half half : org.bukkit.block.data.Bisected.Half.values())
			{
					DoorwayPlane plane = DoorwayPlane.trapdoor(
						4, 70, -9, facing, BukkitDoorGeometry.half(half), DoorOpenState.OPEN);
				assertEquals(Direction.U, DoorPortalGeometry.panelFace(plane));
				PortalPlaneGeometry geometry =
					DoorPortalGeometry.planeGeometry(plane, DoorHinge.LEFT);
				assertEquals(geometry.scaleX(), geometry.scaleZ(), EPSILON);
				assertTrue(geometry.scaleY() < geometry.scaleX());
				// the panel is centred on the block and straddles the crossing plane, so what
				// a traveler sees is the surface that fires
				assertEquals(-geometry.scaleX() / 2.0F, geometry.translationX(), EPSILON);
				assertEquals(-geometry.scaleZ() / 2.0F, geometry.translationZ(), EPSILON);
				assertEquals(
					(float) (plane.planeY() - plane.blockY()),
					geometry.translationY() + (geometry.scaleY() / 2.0F),
					EPSILON);
				// and it stays inside its own block instead of floating into the one above
				assertTrue(geometry.translationY() >= 0.0F);
				assertTrue(geometry.translationY() + geometry.scaleY() <= 1.0F);

				PortalPlaneGeometry overlay =
					DoorPortalGeometry.overlayGeometry(geometry, Direction.U);
				assertEquals(geometry.scaleX(), overlay.scaleX(), EPSILON);
				assertEquals(geometry.scaleZ(), overlay.scaleZ(), EPSILON);
				// the animated overlay straddles both faces of the flat backing, as it does on a hinged door
				assertTrue(overlay.scaleY() > geometry.scaleY());
				assertEquals(
					geometry.translationY() + (geometry.scaleY() / 2.0F),
					overlay.translationY() + (overlay.scaleY() / 2.0F),
					EPSILON);
				assertEquals(Axis.X, DoorPortalGeometry.overlayAxis(Direction.U));
			}
		}
	}

	@Test
	void hingeIsIgnoredForATrapdoorVeil()
	{
		DoorwayPlane plane = DoorwayPlane.trapdoor(
			0,
			64,
			0,
			Direction.N,
			BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.TOP),
			DoorOpenState.CLOSED);
		assertEquals(
			DoorPortalGeometry.planeGeometry(plane, DoorHinge.LEFT),
			DoorPortalGeometry.planeGeometry(plane, DoorHinge.RIGHT));
	}

	@Test
	void hingedPlaneGeometryStillDelegatesToTheCardinalTable()
	{
		for(Direction facing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W})
		{
			for(DoorHinge hinge : DoorHinge.values())
			{
				assertEquals(
					DoorPortalGeometry.geometry(facing, hinge),
					DoorPortalGeometry.planeGeometry(new DoorwayPlane(1, 2, 3, facing), hinge));
			}
		}
	}

	@Test
	void closedSurfaceGeometryCoversThePhysicalLeafAndKeepsItsCenter()
	{
		float contactThickness = (float) DoorwayPlane.TRAPDOOR_PLATE_THICKNESS + 0.02F;
		for(org.bukkit.block.data.Bisected.Half half : org.bukkit.block.data.Bisected.Half.values())
		{
			DoorwayPlane plane = DoorwayPlane.trapdoor(
				0, 64, 0, Direction.N, BukkitDoorGeometry.half(half), DoorOpenState.CLOSED);
			PortalPlaneGeometry geometry =
				DoorPortalGeometry.planeGeometry(plane, DoorHinge.LEFT);
			float plateMinimum = half == org.bukkit.block.data.Bisected.Half.BOTTOM
				? 0.0F
				: 1.0F - (float) DoorwayPlane.TRAPDOOR_PLATE_THICKNESS;

			assertEquals(contactThickness, geometry.scaleY(), EPSILON);
			assertEquals(plateMinimum - 0.01F, geometry.translationY(), EPSILON);
			assertEquals(
				plateMinimum + (float) DoorwayPlane.TRAPDOOR_PLATE_THICKNESS + 0.01F,
				geometry.translationY() + geometry.scaleY(),
				EPSILON);
			assertEquals(
				(float) (plane.planeY() - plane.blockY()),
				geometry.translationY() + (geometry.scaleY() / 2.0F),
				EPSILON);
		}

		for(Direction facing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W})
		{
			for(DoorHinge hinge : DoorHinge.values())
			{
				PortalPlaneGeometry open = DoorPortalGeometry.geometry(facing, hinge);
				DoorwayPlane plane = new DoorwayPlane(
					0,
					64,
					0,
					facing,
					DoorForm.DOOR,
					BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.BOTTOM),
					DoorOpenState.CLOSED);
				PortalPlaneGeometry closed =
					DoorPortalGeometry.planeGeometry(plane, hinge);

				assertEquals(contactThickness, normalScale(closed, facing), EPSILON);
				assertEquals(
					normalTranslation(open, facing) + (normalScale(open, facing) / 2.0F),
					normalTranslation(closed, facing) + (normalScale(closed, facing) / 2.0F),
					EPSILON);
				assertEquals(open.translationY(), closed.translationY(), EPSILON);
				assertEquals(open.scaleY(), closed.scaleY(), EPSILON);
			}
		}
	}

	@Test
	void closeIsIdempotentAndPreventsLaterWorldAccess()
	{
		Plugin plugin = (Plugin) Proxy.newProxyInstance(
			Plugin.class.getClassLoader(),
			new Class<?>[] {Plugin.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("namespace"))
				{
					return "test";
				}
				throw new AssertionError("Closed service accessed plugin method " + method.getName());
			});
		UUID worldId = UUID.randomUUID();
		DoorPortalVisualService service = new DoorPortalVisualService(plugin);
		PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
			new DoorPosition(worldId, "minecraft:overworld", 1, 2, 3),
			DoorItemIdentity.personal(UUID.randomUUID()));
		VanillaDoorSnapshot snapshot = new VanillaDoorSnapshot(
			worldId,
			new DoorwayPlane(1, 2, 3, Direction.N),
			org.bukkit.block.data.type.Door.Hinge.LEFT,
			true,
			false);

		assertDoesNotThrow(service::close);
		assertDoesNotThrow(service::close);
		assertDoesNotThrow(() -> service.show(endpoint, snapshot));
	}

	@Test
	void viewerAttendanceUsesTrackedCoordinatesWithoutPollingWorldPlayers()
	{
		UUID worldId = UUID.randomUUID();
		AtomicReference<UUID> queriedWorld = new AtomicReference<>();
		AtomicReference<double[]> query = new AtomicReference<>();
		Plugin plugin = (Plugin) Proxy.newProxyInstance(
			Plugin.class.getClassLoader(),
			new Class<?>[]{Plugin.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("namespace"))
				{
					return "test";
				}
				throw new AssertionError("Unexpected plugin method " + method.getName());
			});
		DoorPortalVisualService service = new DoorPortalVisualService(
			plugin,
			(queriedId, x, y, z, rangeSquared) ->
			{
				queriedWorld.set(queriedId);
				query.set(new double[]{x, y, z, rangeSquared});
				return true;
			});
		World world = (World) Proxy.newProxyInstance(
			World.class.getClassLoader(),
			new Class<?>[]{World.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("getUID"))
				{
					return worldId;
				}
				throw new AssertionError("Attendance polled World." + method.getName());
			});

		assertTrue(service.hasNearbyViewer(world, new org.bukkit.Location(world, 1.25D, 64.5D, -3.75D)));

		assertEquals(worldId, queriedWorld.get());
		assertEquals(1.25D, query.get()[0]);
		assertEquals(64.5D, query.get()[1]);
		assertEquals(-3.75D, query.get()[2]);
		assertEquals(DoorPortalAnimation.ATTENDANCE_RANGE_SQUARED, query.get()[3]);
	}

	@Test
	void hidingPortalRemovesBackingAndAnimatedOverlay()
	{
		UUID worldId = UUID.randomUUID();
		DoorItemIdentity identity = DoorItemIdentity.personal(UUID.randomUUID());
		AtomicBoolean backingRemoved = new AtomicBoolean();
		AtomicBoolean overlayRemoved = new AtomicBoolean();
		AtomicInteger spawnCalls = new AtomicInteger();
		BlockDisplay backing = display(backingRemoved);
		BlockDisplay overlay = display(overlayRemoved);
		World world = (World) Proxy.newProxyInstance(
			World.class.getClassLoader(),
			new Class<?>[] {World.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("spawn"))
				{
					return spawnCalls.getAndIncrement() == 0 ? backing : overlay;
				}
				throw new AssertionError("Unexpected world method " + method.getName());
			});
		Server server = (Server) Proxy.newProxyInstance(
			Server.class.getClassLoader(),
			new Class<?>[] {Server.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("getWorld"))
				{
					return world;
				}
				throw new AssertionError("Unexpected server method " + method.getName());
			});
		Plugin plugin = (Plugin) Proxy.newProxyInstance(
			Plugin.class.getClassLoader(),
			new Class<?>[] {Plugin.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "namespace" -> "test";
				case "getServer" -> server;
				case "isEnabled" -> false;
				default -> throw new AssertionError("Unexpected plugin method " + method.getName());
			});
		DoorPortalVisualService service = new DoorPortalVisualService(plugin);
		PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
			new DoorPosition(worldId, "minecraft:overworld", 1, 2, 3),
			identity);
		VanillaDoorSnapshot snapshot = new VanillaDoorSnapshot(
			worldId,
			new DoorwayPlane(1, 2, 3, Direction.N),
			org.bukkit.block.data.type.Door.Hinge.LEFT,
			true,
			false);

		service.show(endpoint, snapshot);
		service.hide(identity.itemId());

		assertEquals(2, spawnCalls.get());
		assertTrue(backingRemoved.get());
		assertTrue(overlayRemoved.get());
	}

	@Test
	void failedOverlaySpawnRemovesUntrackedBacking()
	{
		UUID worldId = UUID.randomUUID();
		AtomicBoolean backingRemoved = new AtomicBoolean();
		AtomicInteger spawnCalls = new AtomicInteger();
		BlockDisplay backing = display(backingRemoved);
		World world = (World) Proxy.newProxyInstance(
			World.class.getClassLoader(),
			new Class<?>[] {World.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("spawn"))
				{
					if(spawnCalls.getAndIncrement() == 0)
					{
						return backing;
					}
					throw new IllegalStateException("overlay spawn failed");
				}
				throw new AssertionError("Unexpected world method " + method.getName());
			});
		Server server = (Server) Proxy.newProxyInstance(
			Server.class.getClassLoader(),
			new Class<?>[] {Server.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("getWorld"))
				{
					return world;
				}
				throw new AssertionError("Unexpected server method " + method.getName());
			});
		Plugin plugin = (Plugin) Proxy.newProxyInstance(
			Plugin.class.getClassLoader(),
			new Class<?>[] {Plugin.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "namespace" -> "test";
				case "getServer" -> server;
				case "isEnabled" -> false;
				default -> throw new AssertionError("Unexpected plugin method " + method.getName());
			});
		DoorPortalVisualService service = new DoorPortalVisualService(plugin);
		PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
			new DoorPosition(worldId, "minecraft:overworld", 1, 2, 3),
			DoorItemIdentity.personal(UUID.randomUUID()));
		VanillaDoorSnapshot snapshot = new VanillaDoorSnapshot(
			worldId,
			new DoorwayPlane(1, 2, 3, Direction.N),
			org.bukkit.block.data.type.Door.Hinge.LEFT,
			true,
			false);

		assertThrows(IllegalStateException.class, () -> service.show(endpoint, snapshot));

		assertEquals(2, spawnCalls.get());
		assertTrue(backingRemoved.get());
	}

	@Test
	void displaySpawnedWhileCloseWinsIsRemovedAndNeverRetained()
	{
		UUID worldId = UUID.randomUUID();
		AtomicBoolean removed = new AtomicBoolean();
		AtomicInteger spawnCalls = new AtomicInteger();
		AtomicReference<DoorPortalVisualService> serviceReference = new AtomicReference<>();
		BlockDisplay display = (BlockDisplay) Proxy.newProxyInstance(
			BlockDisplay.class.getClassLoader(),
			new Class<?>[] {BlockDisplay.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "isValid" -> !removed.get();
				case "remove" ->
				{
					removed.set(true);
					yield null;
				}
				default -> throw new AssertionError("Unexpected display method " + method.getName());
			});
		World world = (World) Proxy.newProxyInstance(
			World.class.getClassLoader(),
			new Class<?>[] {World.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("spawn"))
				{
					spawnCalls.incrementAndGet();
					serviceReference.get().close();
					return display;
				}
				throw new AssertionError("Unexpected world method " + method.getName());
			});
		Server server = (Server) Proxy.newProxyInstance(
			Server.class.getClassLoader(),
			new Class<?>[] {Server.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("getWorld"))
				{
					return world;
				}
				throw new AssertionError("Unexpected server method " + method.getName());
			});
		Plugin plugin = (Plugin) Proxy.newProxyInstance(
			Plugin.class.getClassLoader(),
			new Class<?>[] {Plugin.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "namespace" -> "test";
				case "getServer" -> server;
				case "isEnabled" -> false;
				default -> throw new AssertionError("Unexpected plugin method " + method.getName());
			});
		DoorPortalVisualService service = new DoorPortalVisualService(plugin);
		serviceReference.set(service);
		PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(
			new DoorPosition(worldId, "minecraft:overworld", 1, 2, 3),
			DoorItemIdentity.personal(UUID.randomUUID()));
		VanillaDoorSnapshot snapshot = new VanillaDoorSnapshot(
			worldId,
			new DoorwayPlane(1, 2, 3, Direction.N),
			org.bukkit.block.data.type.Door.Hinge.LEFT,
			true,
			false);

		service.show(endpoint, snapshot);
		service.show(endpoint, snapshot);

		assertTrue(removed.get());
		assertEquals(1, spawnCalls.get());
	}

	@Test
	void animateFrameDrivesInterpolatedTransformAndSurfaceParticles()
	{
		boolean particlesEnabled = art.arcane.wormholes.Settings.ENABLE_PARTICLES;
		art.arcane.wormholes.Settings.ENABLE_PARTICLES = true;
		try
		{
			AtomicInteger particleSpawns = new AtomicInteger();
			AtomicInteger interpolationDelay = new AtomicInteger(-1);
			AtomicInteger interpolationDuration = new AtomicInteger(-1);
			AtomicReference<org.bukkit.util.Transformation> applied = new AtomicReference<>();
			BlockDisplay overlay = (BlockDisplay) Proxy.newProxyInstance(
				BlockDisplay.class.getClassLoader(),
				new Class<?>[] {BlockDisplay.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "setInterpolationDelay" ->
					{
						interpolationDelay.set((Integer) arguments[0]);
						yield null;
					}
					case "setInterpolationDuration" ->
					{
						interpolationDuration.set((Integer) arguments[0]);
						yield null;
					}
					case "setTransformation" ->
					{
						applied.set((org.bukkit.util.Transformation) arguments[0]);
						yield null;
					}
					default -> throw new AssertionError("Unexpected overlay method " + method.getName());
				});
			BlockDisplay backing = display(new AtomicBoolean());
			World world = (World) Proxy.newProxyInstance(
				World.class.getClassLoader(),
				new Class<?>[] {World.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "spawnParticle" ->
					{
						particleSpawns.incrementAndGet();
						yield null;
					}
					case "playSound" -> throw new AssertionError("Portal surface animation must remain silent");
					default -> throw new AssertionError("Unexpected world method " + method.getName());
				});
			Plugin plugin = (Plugin) Proxy.newProxyInstance(
				Plugin.class.getClassLoader(),
				new Class<?>[] {Plugin.class},
				(proxy, method, arguments) ->
				{
					if(method.getName().equals("namespace"))
					{
						return "test";
					}
					throw new AssertionError("Unexpected plugin method " + method.getName());
				});
			DoorPortalVisualService service = new DoorPortalVisualService(plugin);
			DoorPortalVisualService.Visual visual = new DoorPortalVisualService.Visual(
				new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 1, 2, 3),
				backing,
				overlay);
			PortalPlaneGeometry base =
				DoorPortalGeometry.overlayGeometry(
					DoorPortalGeometry.geometry(Direction.N, DoorHinge.LEFT), Direction.N);
			org.bukkit.Location anchor = new org.bukkit.Location(world, 1.5D, 2.0D, 3.5D);

			ThreadLocalRandom random = mock(ThreadLocalRandom.class);
			try(MockedStatic<ThreadLocalRandom> randomSource = mockStatic(ThreadLocalRandom.class))
			{
				randomSource.when(ThreadLocalRandom::current).thenReturn(random);
				service.animateFrame(visual, world, anchor, Direction.N, base, 2);
			}

			assertEquals(0, interpolationDelay.get());
			assertEquals(DoorPortalAnimation.FRAME_PERIOD_TICKS, interpolationDuration.get());
			assertTrue(applied.get() != null);
			assertEquals(2, particleSpawns.get());

			particleSpawns.set(0);
			service.animateFrame(visual, world, anchor, Direction.N, base, 0);
			assertEquals(3, particleSpawns.get());
		}
		finally
		{
			art.arcane.wormholes.Settings.ENABLE_PARTICLES = particlesEnabled;
		}
	}

	@Test
	void animateFrameSkipsParticlesWhenDisabled()
	{
		boolean particlesEnabled = art.arcane.wormholes.Settings.ENABLE_PARTICLES;
		art.arcane.wormholes.Settings.ENABLE_PARTICLES = false;
		try
		{
			AtomicInteger overlayCalls = new AtomicInteger();
			BlockDisplay overlay = (BlockDisplay) Proxy.newProxyInstance(
				BlockDisplay.class.getClassLoader(),
				new Class<?>[] {BlockDisplay.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "setInterpolationDelay", "setInterpolationDuration", "setTransformation" ->
					{
						overlayCalls.incrementAndGet();
						yield null;
					}
					default -> throw new AssertionError("Unexpected overlay method " + method.getName());
				});
			World world = (World) Proxy.newProxyInstance(
				World.class.getClassLoader(),
				new Class<?>[] {World.class},
				(proxy, method, arguments) ->
				{
					throw new AssertionError("Unexpected world method " + method.getName());
				});
			Plugin plugin = (Plugin) Proxy.newProxyInstance(
				Plugin.class.getClassLoader(),
				new Class<?>[] {Plugin.class},
				(proxy, method, arguments) ->
				{
					if(method.getName().equals("namespace"))
					{
						return "test";
					}
					throw new AssertionError("Unexpected plugin method " + method.getName());
				});
			DoorPortalVisualService service = new DoorPortalVisualService(plugin);
			DoorPortalVisualService.Visual visual = new DoorPortalVisualService.Visual(
				new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 1, 2, 3),
				display(new AtomicBoolean()),
				overlay);
			PortalPlaneGeometry base =
				DoorPortalGeometry.overlayGeometry(
					DoorPortalGeometry.geometry(Direction.E, DoorHinge.RIGHT), Direction.E);

			service.animateFrame(
				visual, world, new org.bukkit.Location(world, 1.5D, 2.0D, 3.5D), Direction.E, base, 4);

			assertEquals(3, overlayCalls.get());
		}
		finally
		{
			art.arcane.wormholes.Settings.ENABLE_PARTICLES = particlesEnabled;
		}
	}

	@Test
	void animationHaltsForUntrackedOrClosedVisuals()
	{
		Plugin plugin = (Plugin) Proxy.newProxyInstance(
			Plugin.class.getClassLoader(),
			new Class<?>[] {Plugin.class},
			(proxy, method, arguments) ->
			{
				if(method.getName().equals("namespace"))
				{
					return "test";
				}
				throw new AssertionError("Unexpected plugin method " + method.getName());
			});
		DoorPortalVisualService service = new DoorPortalVisualService(plugin);
		DoorPortalVisualService.Visual visual = new DoorPortalVisualService.Visual(
			new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 1, 2, 3),
			display(new AtomicBoolean()),
			display(new AtomicBoolean()));

		assertTrue(!service.shouldContinueAnimating(UUID.randomUUID(), visual));

		service.close();
		assertTrue(!service.shouldContinueAnimating(UUID.randomUUID(), visual));
	}

	private static void assertLateralBounds(
		Direction facing,
		DoorHinge hinge,
		float expectedMin,
		float expectedMax)
	{
		PortalPlaneGeometry geometry =
			DoorPortalGeometry.geometry(facing, hinge);
		float translation = facing == Direction.N || facing == Direction.S
			? geometry.translationX()
			: geometry.translationZ();
		float scale = facing == Direction.N || facing == Direction.S
			? geometry.scaleX()
			: geometry.scaleZ();

		assertEquals(expectedMin, translation + 0.5F, EPSILON, facing + " " + hinge + " min");
		assertEquals(expectedMax, translation + 0.5F + scale, EPSILON, facing + " " + hinge + " max");
		assertEquals(0.9375F, scale, EPSILON, facing + " " + hinge + " width");
	}

	private static float normalTranslation(
		PortalPlaneGeometry geometry,
		Direction facing)
	{
		return facing == Direction.N || facing == Direction.S
			? geometry.translationZ()
			: geometry.translationX();
	}

	private static float normalScale(
		PortalPlaneGeometry geometry,
		Direction facing)
	{
		return facing == Direction.N || facing == Direction.S
			? geometry.scaleZ()
			: geometry.scaleX();
	}

	private static BlockDisplay display(AtomicBoolean removed)
	{
		return (BlockDisplay) Proxy.newProxyInstance(
			BlockDisplay.class.getClassLoader(),
			new Class<?>[] {BlockDisplay.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "isValid" -> !removed.get();
				case "remove" ->
				{
					removed.set(true);
					yield null;
				}
				default -> throw new AssertionError("Unexpected display method " + method.getName());
			});
	}
}
