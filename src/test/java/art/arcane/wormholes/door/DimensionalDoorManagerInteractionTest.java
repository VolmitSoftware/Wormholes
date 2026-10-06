package art.arcane.wormholes.door;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.crossing.PlaneCrossing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DimensionalDoorManagerInteractionTest
{
	@Test
	void bukkitDefaultCancelledAirInteractionCanUnpackPairKit()
	{
		assertTrue(DimensionalDoorManager.shouldUnpackPairKit(
			Action.RIGHT_CLICK_AIR,
			Event.Result.DENY,
			Event.Result.DEFAULT));
	}

	@Test
	void deniedItemUseCannotUnpackPairKit()
	{
		assertFalse(DimensionalDoorManager.shouldUnpackPairKit(
			Action.RIGHT_CLICK_AIR,
			Event.Result.DEFAULT,
			Event.Result.DENY));
	}

	@Test
	void deniedProtectedBlockUseCannotUnpackPairKit()
	{
		assertFalse(DimensionalDoorManager.shouldUnpackPairKit(
			Action.RIGHT_CLICK_BLOCK,
			Event.Result.DENY,
			Event.Result.DEFAULT));
	}

	@Test
	void defaultRightClickBlockCanUnpackPairKit()
	{
		assertTrue(DimensionalDoorManager.shouldUnpackPairKit(
			Action.RIGHT_CLICK_BLOCK,
			Event.Result.DEFAULT,
			Event.Result.DEFAULT));
	}

	@Test
	void leftClickActionsCannotUnpackPairKit()
	{
		assertFalse(DimensionalDoorManager.shouldUnpackPairKit(
			Action.LEFT_CLICK_AIR,
			Event.Result.DEFAULT,
			Event.Result.DEFAULT));
		assertFalse(DimensionalDoorManager.shouldUnpackPairKit(
			Action.LEFT_CLICK_BLOCK,
			Event.Result.DEFAULT,
			Event.Result.DEFAULT));
	}

	@Test
	void cancelledAirInteractionStillReachesPairKitHandler() throws NoSuchMethodException
	{
		Method method = DimensionalDoorManager.class.getMethod("onPairKitUse", PlayerInteractEvent.class);
		EventHandler handler = method.getAnnotation(EventHandler.class);

		assertNotNull(handler);
		assertFalse(handler.ignoreCancelled());
	}

	@Test
	void crafterCraftCancelsIdentityProductsTheSameWayAsSkinRecipes() throws NoSuchMethodException
	{
		Method method = DimensionalDoorManager.class.getMethod("onCrafterCraft", CrafterCraftEvent.class);
		EventHandler handler = method.getAnnotation(EventHandler.class);

		assertNotNull(handler);
		assertEquals(EventPriority.HIGHEST, handler.priority());
		assertTrue(handler.ignoreCancelled());
	}

	@Test
	void accessMenuOpensOnlyForSneakingEmptyHandedManagers()
	{
		assertTrue(DimensionalDoorManager.shouldOpenAccessMenu(true, true, true, true));
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(true, false, true, true));
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(true, true, false, true));
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(true, true, true, false));
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(true, false, false, false));
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(true, false, false, true));
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(true, false, true, false));
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(true, true, false, false));
	}

	@Test
	void doorsWithoutAnAccessRecordFallThroughToTheVanillaToggle()
	{
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(false, true, true, true));
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(false, true, true, false));
		assertFalse(DimensionalDoorManager.shouldOpenAccessMenu(false, false, true, true));
	}

	@Test
	void returnDoorsAreNeverAccessGated()
	{
		assertFalse(DimensionalDoorManager.isAccessGated(DoorKind.RETURN));
	}

	@Test
	void everyPlaceableDoorKindIsAccessGated()
	{
		assertTrue(DimensionalDoorManager.isAccessGated(DoorKind.PAIR));
		assertTrue(DimensionalDoorManager.isAccessGated(DoorKind.PERSONAL));
		assertTrue(DimensionalDoorManager.isAccessGated(DoorKind.PUBLIC));
	}

	@Test
	void accessGatingRejectsNullKind()
	{
		assertThrows(NullPointerException.class, () -> DimensionalDoorManager.isAccessGated(null));
	}

	@Test
	void accessInteractRunsBeforeTheVanillaDoorToggleAndSkipsCancelledClicks() throws NoSuchMethodException
	{
		Method method = DimensionalDoorManager.class.getMethod("onDoorAccessInteract", PlayerInteractEvent.class);
		EventHandler handler = method.getAnnotation(EventHandler.class);

		assertNotNull(handler);
		assertEquals(EventPriority.HIGH, handler.priority());
		assertTrue(handler.ignoreCancelled());
	}

	@Test
	void aTrapdoorArrivalPutsFeetOnThePlateGoingUpAndTheWholeBodyUnderItGoingDown()
	{
		DoorwayPlane plane = DoorwayPlane.trapdoor(
			5, 70, -3, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.EAST), BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.BOTTOM),
			DoorOpenState.OPEN);
		DoorTransit transit = new DoorTransit(
			plane, true, 0.0F, 0.0F, 0.3D, 1.8D);

		Vec3d up = DoorArrivals.arrivalPoint(plane, transit, 1);
		Vec3d down = DoorArrivals.arrivalPoint(plane, transit, -1);

		assertEquals(5.5D, up.x(), 1.0E-9D);
		assertEquals(-2.5D, up.z(), 1.0E-9D);
		assertEquals(plane.planeY(), up.y(), 1.0E-9D);
		assertEquals(plane.planeY() - 1.8D, down.y(), 1.0E-9D);
	}

	@Test
	void aHingedArrivalStillStepsAStrideClearOfTheDoorway()
	{
		DoorwayPlane plane = new DoorwayPlane(5, 70, -3, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.EAST));
		DoorTransit transit = new DoorTransit(
			plane, true, 0.0F, 0.0F, 0.3D, 1.8D);

		Vec3d arrival = DoorArrivals.arrivalPoint(plane, transit, 1);

		assertEquals(70.0D, arrival.y(), 1.0E-9D);
		assertTrue(arrival.x() > 5.5D, "pushed out along the facing");
	}

	@Test
	void theVerticalSearchLadderFollowsTheExitDirection()
	{
		DoorwayPlane trapdoor = DoorwayPlane.trapdoor(
			0, 64, 0, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.NORTH), BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.TOP),
			DoorOpenState.OPEN);
		DoorwayPlane door = new DoorwayPlane(0, 64, 0, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.NORTH));

		assertEquals(1, DoorPlanePairing.arrivalYOffsets(trapdoor, 1)[1], "an upward exit searches upward first");
		assertEquals(-1, DoorPlanePairing.arrivalYOffsets(trapdoor, -1)[1], "a downward exit searches downward first");
		assertArrayEquals(DoorPlanePairing.DOOR_ARRIVAL_Y_OFFSETS, DoorPlanePairing.arrivalYOffsets(door, 1));
		assertArrayEquals(DoorPlanePairing.DOOR_ARRIVAL_Y_OFFSETS, DoorPlanePairing.arrivalYOffsets(door, -1));

		for(int offset : DoorPlanePairing.arrivalYOffsets(trapdoor, -1))
		{
			assertTrue(offset <= 0, "a downward exit must never fall back above the plate");
		}
		for(int offset : DoorPlanePairing.arrivalYOffsets(trapdoor, 1))
		{
			assertTrue(offset >= 0, "an upward exit must never fall back below the plate");
		}
	}

	@Test
	void droppingInThroughOneTrapdoorLeavesUnderTheFarPlate()
	{
		DoorwayPlane source = DoorwayPlane.trapdoor(
			0, 64, 0, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.NORTH), BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.TOP),
			DoorOpenState.OPEN);
		DoorwayPlane destination = DoorwayPlane.trapdoor(
			40, 20, 40, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.EAST), BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.BOTTOM),
			DoorOpenState.OPEN);

		// falling in from above the source plate is a FRONT_TO_BACK crossing of an upward normal
		PlaneCrossing crossing = source.crossing(
			new Vec3d(0.5D, source.planeY() + 0.5D, 0.5D),
			new Vec3d(0.5D, source.planeY() - 0.5D, 0.5D)).orElseThrow();
		assertTrue(crossing.frontSide());

		int sideSign = DoorPlanePairing.arrivalSideSign(source, destination, (crossing.frontSide() ? 1 : -1));
		assertEquals(-1, sideSign, "entering the top has to leave under the far plate");

		DoorTransit transit = new DoorTransit(
			source, crossing.frontSide(), 0.0F, 0.0F, 0.3D, 1.8D);
		Vec3d arrival = DoorArrivals.arrivalPoint(destination, transit, sideSign);
		assertTrue(
			arrival.y() + transit.height() <= destination.planeY() + 1.0E-9D,
			"the whole traveler clears the destination plate downward");
	}

	@Test
	void climbingUpThroughOneTrapdoorLeavesAboveTheFarPlate()
	{
		DoorwayPlane source = DoorwayPlane.trapdoor(
			0, 64, 0, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.NORTH), BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.BOTTOM),
			DoorOpenState.OPEN);
		DoorwayPlane destination = DoorwayPlane.trapdoor(
			40, 20, 40, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.WEST), BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.TOP),
			DoorOpenState.OPEN);

		PlaneCrossing crossing = source.crossing(
			new Vec3d(0.5D, source.planeY() - 0.5D, 0.5D),
			new Vec3d(0.5D, source.planeY() + 0.5D, 0.5D)).orElseThrow();
		assertFalse(crossing.frontSide());

		int sideSign = DoorPlanePairing.arrivalSideSign(source, destination, (crossing.frontSide() ? 1 : -1));
		assertEquals(1, sideSign, "entering the bottom has to leave above the far plate");

		DoorTransit transit = new DoorTransit(
			source, crossing.frontSide(), 0.0F, 0.0F, 0.3D, 1.8D);
		Vec3d arrival = DoorArrivals.arrivalPoint(destination, transit, sideSign);
		assertTrue(
			arrival.y() >= destination.planeY() - 1.0E-9D,
			"the traveler stands on the destination plate rather than under it");
	}

	@Test
	void aTrapdoorPlaneFiresWhereItsVeilIsDrawn()
	{
		for(org.bukkit.block.data.Bisected.Half half : org.bukkit.block.data.Bisected.Half.values())
		{
			DoorwayPlane plane = DoorwayPlane.trapdoor(
				2, 64, 2, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.NORTH), BukkitDoorGeometry.half(half), DoorOpenState.OPEN);
			PortalPlaneGeometry veil =
				DoorPortalGeometry.planeGeometry(plane, DoorHinge.LEFT);
			double veilCentre = plane.blockY() + veil.translationY() + (veil.scaleY() / 2.0D);

			// the veil is built from floats, so match to well under a visible fraction of a block
			assertEquals(plane.planeY(), veilCentre, 1.0E-4D);
			assertTrue(plane.planeY() > plane.blockY(), "the plane stays inside its own block");
			assertTrue(plane.planeY() < plane.blockY() + 1.0D, "the plane stays inside its own block");
		}
	}

	@Test
	void aContactPadAlwaysDeliversOntoItsExposedFace()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.NORTH));
		DoorwayPlane pad = DoorwayPlane.trapdoor(
			9, 64, 9, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.SOUTH), BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.BOTTOM),
			DoorOpenState.CLOSED);

		for (boolean frontSide : new boolean[] {true, false})
		{
			assertEquals(1, DoorPlanePairing.arrivalSideSign(source, pad, (frontSide ? 1 : -1)));
		}
	}

	@Test
	void closedTrapdoorArrivalsClearBothPlateFacesForEveryHalfAndTravelerClass()
	{
		for(org.bukkit.block.data.Bisected.Half half : org.bukkit.block.data.Bisected.Half.values())
		{
			DoorwayPlane plane = DoorwayPlane.trapdoor(
				5, 70, -3, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.EAST), BukkitDoorGeometry.half(half), DoorOpenState.CLOSED);
			DoorTransit living = new DoorTransit(
				plane, true, 0.0F, 0.0F, 0.3D, 1.8D);
			DoorTransit object = new DoorTransit(
				plane,
				true,
				0.0F,
				0.0F,
				0.125D,
				0.25D,
				DoorTravelerClass.OBJECT,
				new Vec3d(0.0D, 0.1D, 0.0D));
			double expectedUpper = half == org.bukkit.block.data.Bisected.Half.TOP
				? 71.0D
				: 70.0D + DoorwayPlane.TRAPDOOR_PLATE_THICKNESS;
			double expectedLower = half == org.bukkit.block.data.Bisected.Half.TOP
				? 71.0D - DoorwayPlane.TRAPDOOR_PLATE_THICKNESS
				: 70.0D;

			assertEquals(expectedUpper, plane.exposedSurfaceY(1), 1.0E-9D);
			assertEquals(expectedLower, plane.exposedSurfaceY(-1), 1.0E-9D);
			for(DoorTransit transit : new DoorTransit[] {living, object})
			{
				for(int sideSign : new int[] {-1, 1})
				{
					Vec3d arrival = DoorArrivals.arrivalPoint(plane, transit, sideSign);
					double expectedY = sideSign > 0
						? expectedUpper
						: expectedLower - transit.height();

					assertEquals(expectedY, arrival.y(), 1.0E-9D);
					assertTrue(DoorArrivalResolver.isNonOverlappingContactSurfaceBlock(
						plane,
						5,
						70,
						-3,
						arrival.y(),
						arrival.y() + transit.height()));
				}
			}
			assertFalse(DoorArrivalResolver.isNonOverlappingContactSurfaceBlock(
				plane, 5, 70, -3, plane.planeY(), plane.planeY() + living.height()));
			assertFalse(DoorArrivalResolver.isNonOverlappingContactSurfaceBlock(
				plane, 6, 70, -3, expectedUpper, expectedUpper + living.height()));
			assertThrows(IllegalArgumentException.class, () -> plane.exposedSurfaceY(0));
		}
	}

	@Test
	void aClosedHingedDestinationStillPreservesTheEnteredFace()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.NORTH));
		DoorwayPlane destination = new DoorwayPlane(
			9,
			64,
			9,
			BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.SOUTH),
			DoorForm.DOOR,
			BukkitDoorGeometry.half(org.bukkit.block.data.Bisected.Half.BOTTOM),
			DoorOpenState.CLOSED);

		for (boolean frontSide : new boolean[] {true, false})
		{
			assertEquals(
				(frontSide ? 1 : -1),
				DoorPlanePairing.arrivalSideSign(source, destination, (frontSide ? 1 : -1)));
		}
	}

	@Test
	void objectArrivalsPreserveExactUpperLowerAndOffCenterCrossings()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.NORTH));
		DoorwayPlane destination = new DoorwayPlane(40, 20, 40, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.EAST));
		double[][] offsets = {
			{-0.3D, 0.25D},
			{0.3D, 1.75D},
			{0.4D, 1.1D}
		};

		for(double[] offset : offsets)
		{
			PlaneCrossing crossing = doorCrossing(source, offset[0], offset[1]);
			DoorTransit transit = new DoorTransit(
				source,
				crossing,
				0.0F,
				0.0F,
				0.125D,
				0.25D,
				DoorTravelerClass.OBJECT,
				new Vec3d(0.0D, 0.0D, -1.0D));
			Vec3d aperturePoint = DoorPlanePairing.mapAperturePoint(source, destination, crossing);
			int sideSign = DoorPlanePairing.arrivalSideSign(source, destination, (crossing.frontSide() ? 1 : -1));
			Vec3d arrival = DoorArrivals.arrivalPoint(destination, transit, sideSign);

			assertEquals(aperturePoint.x() + (destination.normalX() * sideSign), arrival.x(), 1.0E-9D);
			assertEquals(aperturePoint.y(), arrival.y(), 1.0E-9D);
			assertEquals(aperturePoint.z() + (destination.normalZ() * sideSign), arrival.z(), 1.0E-9D);
		}
	}

	@Test
	void livingArrivalsRemainCenteredDespiteAnExactOffCenterCrossing()
	{
		DoorwayPlane source = new DoorwayPlane(0, 64, 0, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.NORTH));
		DoorwayPlane destination = new DoorwayPlane(40, 20, 40, BukkitDoorGeometry.direction(org.bukkit.block.BlockFace.EAST));
		PlaneCrossing crossing = doorCrossing(source, 0.4D, 1.75D);
		DoorTransit exact = new DoorTransit(
			source,
			crossing,
			0.0F,
			0.0F,
			0.3D,
			1.8D,
			DoorTravelerClass.LIVING,
			null);
		DoorTransit centered = new DoorTransit(
			source, crossing.frontSide(), 0.0F, 0.0F, 0.3D, 1.8D);

		assertEquals(
			DoorArrivals.arrivalPoint(destination, centered),
			DoorArrivals.arrivalPoint(destination, exact));
	}

	@Test
	void creativePlacementConsumesDoorItem()
	{
		assertTrue(DimensionalDoorManager.consumesPlacedDoorItem(GameMode.CREATIVE));
	}

	@Test
	void survivalPlacementLeavesVanillaConsumptionInPlace()
	{
		assertFalse(DimensionalDoorManager.consumesPlacedDoorItem(GameMode.SURVIVAL));
	}

	@Test
	void consumingMainHandClearsOnlyMainHand()
	{
		InventoryCalls calls = new InventoryCalls();
		DimensionalDoorManager.consumeHeldItem(player(inventory(calls)), EquipmentSlot.HAND);

		assertEquals(1, calls.mainHandWrites);
		assertEquals(0, calls.offHandWrites);
		assertNull(calls.mainHandValue);
	}

	@Test
	void consumingOffHandClearsOnlyOffHand()
	{
		InventoryCalls calls = new InventoryCalls();
		DimensionalDoorManager.consumeHeldItem(player(inventory(calls)), EquipmentSlot.OFF_HAND);

		assertEquals(0, calls.mainHandWrites);
		assertEquals(1, calls.offHandWrites);
		assertNull(calls.offHandValue);
	}

	private static PlaneCrossing doorCrossing(
		DoorwayPlane plane,
		double lateralOffset,
		double verticalOffset)
	{
		Vec3d center = plane.center();
		Vec3d point = new Vec3d(
			center.x() + (lateralOffset * -plane.facing().z()),
			plane.blockY() + verticalOffset,
			center.z() + (lateralOffset * plane.facing().x()));
		Vec3d from = new Vec3d(
			point.x() + plane.normalX(),
			point.y() + plane.normalY(),
			point.z() + plane.normalZ());
		Vec3d to = new Vec3d(
			point.x() - plane.normalX(),
			point.y() - plane.normalY(),
			point.z() - plane.normalZ());
		return plane.crossing(from, to).orElseThrow();
	}

	private static Player player(PlayerInventory inventory)
	{
		return (Player) Proxy.newProxyInstance(
			Player.class.getClassLoader(),
			new Class<?>[]{Player.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getInventory" -> inventory;
				case "toString" -> "player";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> defaultValue(method.getReturnType());
			});
	}

	private static PlayerInventory inventory(InventoryCalls calls)
	{
		return (PlayerInventory) Proxy.newProxyInstance(
			PlayerInventory.class.getClassLoader(),
			new Class<?>[]{PlayerInventory.class},
			(proxy, method, arguments) ->
			{
				switch(method.getName())
				{
					case "setItemInMainHand" ->
					{
						calls.mainHandWrites++;
						calls.mainHandValue = arguments[0];
						return null;
					}
					case "setItemInOffHand" ->
					{
						calls.offHandWrites++;
						calls.offHandValue = arguments[0];
						return null;
					}
					case "toString" ->
					{
						return "inventory";
					}
					case "hashCode" ->
					{
						return System.identityHashCode(proxy);
					}
					case "equals" ->
					{
						return proxy == arguments[0];
					}
					default ->
					{
						return defaultValue(method.getReturnType());
					}
				}
			});
	}

	private static Object defaultValue(Class<?> type)
	{
		if(!type.isPrimitive() || type == void.class)
		{
			return null;
		}
		if(type == boolean.class)
		{
			return false;
		}
		if(type == char.class)
		{
			return '\0';
		}
		if(type == byte.class)
		{
			return (byte) 0;
		}
		if(type == short.class)
		{
			return (short) 0;
		}
		if(type == int.class)
		{
			return 0;
		}
		if(type == long.class)
		{
			return 0L;
		}
		if(type == float.class)
		{
			return 0.0F;
		}
		return 0.0D;
	}

	private static final class InventoryCalls
	{
		private int mainHandWrites;
		private int offHandWrites;
		private Object mainHandValue = new Object();
		private Object offHandValue = new Object();
	}
}
