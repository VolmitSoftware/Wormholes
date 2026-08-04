package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.TrapDoor;
import org.junit.jupiter.api.Test;

final class VanillaDoorSnapshotTest
{
	private static final UUID WORLD_ID = new UUID(0, 950);

	@Test
	void aHingedDoorIsAlwaysAnchoredToItsLowerHalf()
	{
		VanillaDoorSnapshot fromUpper = VanillaDoorSnapshot.fromBlockData(
			WORLD_ID, 7, 65, -2, doorData(BlockFace.SOUTH, Bisected.Half.TOP, true));
		VanillaDoorSnapshot fromLower = VanillaDoorSnapshot.fromBlockData(
			WORLD_ID, 7, 64, -2, doorData(BlockFace.SOUTH, Bisected.Half.BOTTOM, true));

		assertEquals(64, fromUpper.plane().blockY());
		assertEquals(fromLower.plane(), fromUpper.plane());
		assertEquals(DoorForm.DOOR, fromUpper.plane().form());
		assertEquals(DoorOpenState.OPEN, fromUpper.plane().openState());
	}

	@Test
	void aTrapdoorIsCapturedOnItsOwnSingleBlock()
	{
		for(Bisected.Half half : Bisected.Half.values())
		{
			VanillaDoorSnapshot snapshot = VanillaDoorSnapshot.fromBlockData(
				WORLD_ID, 7, 65, -2, trapDoorData(BlockFace.SOUTH, half, false), DoorOpenState.OPEN);

			assertEquals(65, snapshot.plane().blockY(), "a trapdoor never normalizes to a lower half");
			assertEquals(half, snapshot.plane().half());
			assertEquals(DoorForm.TRAPDOOR, snapshot.plane().form());
		}
	}

	@Test
	void portalLivenessFollowsTheConfiguredOpenStateForBothForms()
	{
		DoorwayPlane door = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);
		DoorwayPlane trapdoor = DoorwayPlane.trapdoor(
			0, 64, 0, BlockFace.NORTH, Bisected.Half.BOTTOM, DoorOpenState.OPEN);

		for(DoorwayPlane plane : new DoorwayPlane[] {door, trapdoor})
		{
			assertTrue(snapshot(plane, DoorOpenState.OPEN, true).portalLive());
			assertFalse(snapshot(plane, DoorOpenState.OPEN, false).portalLive());
			assertFalse(snapshot(plane, DoorOpenState.CLOSED, true).portalLive());
			assertTrue(snapshot(plane, DoorOpenState.CLOSED, false).portalLive());
		}
	}

	@Test
	void blockDataSnapshotsCarryTheConfiguredOpenState()
	{
		TrapDoor plate = trapDoorData(BlockFace.WEST, Bisected.Half.TOP, true);
		Door door = doorData(BlockFace.EAST, Bisected.Half.BOTTOM, false);

		VanillaDoorSnapshot openTrapdoor = VanillaDoorSnapshot.fromBlockData(
			WORLD_ID, 1, 2, 3, plate, DoorOpenState.OPEN);
		VanillaDoorSnapshot closedTrapdoor = VanillaDoorSnapshot.fromBlockData(
			WORLD_ID, 1, 2, 3, plate, DoorOpenState.CLOSED);
		VanillaDoorSnapshot closedDoor = VanillaDoorSnapshot.fromBlockData(
			WORLD_ID, 1, 2, 3, door, DoorOpenState.CLOSED);

		assertTrue(openTrapdoor.portalLive());
		assertFalse(closedTrapdoor.portalLive());
		assertEquals(DoorOpenState.CLOSED, closedDoor.plane().openState());
		assertTrue(closedDoor.portalLive());
	}

	private static VanillaDoorSnapshot snapshot(DoorwayPlane plane, DoorOpenState openState, boolean open)
	{
		DoorwayPlane configured = new DoorwayPlane(
			plane.blockX(),
			plane.blockY(),
			plane.blockZ(),
			plane.facing(),
			plane.form(),
			plane.half(),
			openState);
		return new VanillaDoorSnapshot(WORLD_ID, configured, Door.Hinge.LEFT, open, false);
	}

	private static Door doorData(BlockFace facing, Bisected.Half half, boolean open)
	{
		return (Door) Proxy.newProxyInstance(
			Door.class.getClassLoader(),
			new Class<?>[]{Door.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getFacing" -> facing;
				case "getHalf" -> half;
				case "getHinge" -> Door.Hinge.LEFT;
				case "isOpen" -> open;
				case "isPowered" -> false;
				case "toString" -> "TestDoorData";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}

	private static TrapDoor trapDoorData(BlockFace facing, Bisected.Half half, boolean open)
	{
		return (TrapDoor) Proxy.newProxyInstance(
			TrapDoor.class.getClassLoader(),
			new Class<?>[]{TrapDoor.class},
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
}
