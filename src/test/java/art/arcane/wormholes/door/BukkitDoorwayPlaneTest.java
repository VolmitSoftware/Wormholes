package art.arcane.wormholes.door;

import art.arcane.wormholes.util.Direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.junit.jupiter.api.Test;

public final class BukkitDoorwayPlaneTest
{

	@Test
	public void vanillaTopHalfNormalizesToLowerDoorAndOpenableStateIsAuthoritative()
	{
		UUID worldId = UUID.randomUUID();
		Door door = doorData(BlockFace.WEST, Bisected.Half.TOP, Door.Hinge.RIGHT, true, false);

		VanillaDoorSnapshot snapshot = VanillaDoorSnapshot.fromBlockData(worldId, 3, 81, 9, door);

		assertEquals(worldId, snapshot.worldId());
		assertEquals(new DoorwayPlane(3, 80, 9, Direction.W), snapshot.plane());
		assertEquals(Door.Hinge.RIGHT, snapshot.hinge());
		assertTrue(snapshot.open());
		assertFalse(snapshot.powered());
	}

	private static double physicalNormalOffset(DoorwayPlane plane, DoorVec3 point)
	{
		return ((point.x() - (plane.blockX() + 0.5D)) * plane.facing().x())
			+ ((point.z() - (plane.blockZ() + 0.5D)) * plane.facing().z());
	}

	@Test
	public void enteredClosedDoorFaceMapsToMatchingDestinationFaceAcrossFacingsAndHinges()
	{
		for(BlockFace sourceFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			for(Door.Hinge sourceHinge : new Door.Hinge[]{Door.Hinge.LEFT, Door.Hinge.RIGHT})
			{
				DoorwayPlane source = BukkitDoorGeometry.plane(
					0, 64, 0, doorData(sourceFacing, Bisected.Half.BOTTOM, sourceHinge, true, false));
				for(BlockFace destinationFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
				{
					for(Door.Hinge destinationHinge : new Door.Hinge[]{Door.Hinge.LEFT, Door.Hinge.RIGHT})
					{
						DoorwayPlane destination = BukkitDoorGeometry.plane(
							100,
							70,
							100,
							doorData(destinationFacing, Bisected.Half.BOTTOM, destinationHinge, true, false));
						for(DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values())
						{
							float sourceYaw = vectorYaw(
								sourceFacing.getModX() * direction.exitSideSign(),
								sourceFacing.getModZ() * direction.exitSideSign());
							float expectedYaw = vectorYaw(
								destinationFacing.getModX() * direction.entrySideSign(),
								destinationFacing.getModZ() * direction.entrySideSign());
							DoorTransit transit = new DoorTransit(source, direction, sourceYaw, 0.0F);
							DoorVec3 arrival = DoorArrivals.arrivalPoint(destination, transit);
							String scenario = sourceFacing + " " + sourceHinge + " -> "
								+ destinationFacing + " " + destinationHinge + " " + direction;

							assertEquals(destination.entrySidePoint(direction, 1.0D), arrival, scenario);
							assertEquals(direction.entrySideSign(), physicalNormalOffset(destination, arrival), 1.0E-9D, scenario);
							assertTrue(destination.signedDistance(arrival) * direction.entrySideSign() > 0.0D, scenario);
							assertEquals(expectedYaw, DoorArrivals.arrivalFacing(destination, transit, DoorPlanePairing.arrivalSideSign(source, destination, direction)).yaw(), 1.0E-6F, scenario);
						}
					}
				}
			}
		}
	}

	@Test
	public void destinationArrivalCanStepDownWithoutChangingDoorSide()
	{
		DoorwayPlane plane = new DoorwayPlane(-132, 68, 56, Direction.E);
		DoorVec3 nominal = plane.entrySidePoint(DoorwayCrossing.Direction.BACK_TO_FRONT, 1.0D);
		DoorVec3 selected = DoorArrivals.findSafeVerticalDoorStanding(
			nominal,
			candidate -> candidate.y() == 67.0D).orElseThrow();

		assertEquals(nominal.x(), selected.x(), 0.0D);
		assertEquals(67.0D, selected.y(), 0.0D);
		assertEquals(nominal.z(), selected.z(), 0.0D);
		assertEquals(plane.signedDistance(nominal), plane.signedDistance(selected), 0.0D);
	}

	@Test
	public void destinationArrivalPrefersTheDoorBaseHeight()
	{
		DoorVec3 nominal = new DoorVec3(4.5D, 70.0D, -2.5D);

		DoorVec3 selected = DoorArrivals.findSafeVerticalDoorStanding(
			nominal,
			candidate -> candidate.y() == 70.0D || candidate.y() == 69.0D).orElseThrow();

		assertEquals(nominal, selected);
	}

	private static float vectorYaw(int x, int z)
	{
		float yaw = (float) Math.toDegrees(Math.atan2(-x, z));
		return yaw >= 180.0F ? yaw - 360.0F : yaw;
	}

	// ---- trapdoor planes -------------------------------------------------

	@Test
	public void aTrapdoorIsCapturedWhereItStandsWithNoHalfNormalization()
	{
		for(Bisected.Half half : Bisected.Half.values())
		{
			DoorwayPlane plane = BukkitDoorGeometry.plane(
				6, 91, 2, trapDoorData(BlockFace.WEST, half, false), DoorOpenState.CLOSED);

			assertEquals(6, plane.blockX());
			assertEquals(91, plane.blockY());
			assertEquals(2, plane.blockZ());
			assertEquals(BukkitDoorGeometry.half(half), plane.half());
			assertEquals(Direction.W, plane.facing());
			assertEquals(DoorForm.TRAPDOOR, plane.form());
			assertTrue(plane.contactSurface());
		}
	}

	// ---- contact pads ----------------------------------------------------

	private static org.bukkit.block.data.type.TrapDoor trapDoorData(
		BlockFace facing,
		Bisected.Half half,
		boolean open)
	{
		return (org.bukkit.block.data.type.TrapDoor) Proxy.newProxyInstance(
			org.bukkit.block.data.type.TrapDoor.class.getClassLoader(),
			new Class<?>[]{org.bukkit.block.data.type.TrapDoor.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getFacing" -> facing;
				case "getHalf" -> half;
				case "isOpen" -> open;
				case "isPowered" -> false;
				case "toString" -> "TestTrapDoorData";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}

	private static Door doorData(
		BlockFace facing,
		Bisected.Half half,
		Door.Hinge hinge,
		boolean open,
		boolean powered)
	{
		return (Door) Proxy.newProxyInstance(
			Door.class.getClassLoader(),
			new Class<?>[]{Door.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getFacing" -> facing;
				case "getHalf" -> half;
				case "getHinge" -> hinge;
				case "isOpen" -> open;
				case "isPowered" -> powered;
				case "toString" -> "TestDoorData";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}
}
