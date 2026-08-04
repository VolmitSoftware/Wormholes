package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.junit.jupiter.api.Test;

final class RuntimeDoorTest
{
	private static final UUID WORLD_ID = new UUID(0, 800);

	@Test
	void aNormalDoorArmsWhenItIsObservedOpen()
	{
		RuntimeDoor runtime = new RuntimeDoor(endpoint(DoorForm.DOOR, DoorOpenState.OPEN));

		runtime.update(snapshot(plane(DoorForm.DOOR, DoorOpenState.OPEN), false));
		assertEquals(DoorOpenCycle.Phase.CLOSED, runtime.cycle().phase());
		assertFalse(runtime.cycle().portalActive());

		runtime.update(snapshot(plane(DoorForm.DOOR, DoorOpenState.OPEN), true));
		assertEquals(DoorOpenCycle.Phase.ARMED, runtime.cycle().phase());
		assertTrue(runtime.cycle().portalActive());
	}

	@Test
	void aContactPadArmsWhenItIsObservedClosed()
	{
		RuntimeDoor runtime = new RuntimeDoor(endpoint(DoorForm.TRAPDOOR, DoorOpenState.CLOSED));
		DoorwayPlane pad = plane(DoorForm.TRAPDOOR, DoorOpenState.CLOSED);

		// the plate swung aside is the pad's dormant state, not its live one
		runtime.update(snapshot(pad, true));
		assertEquals(DoorOpenCycle.Phase.CLOSED, runtime.cycle().phase());
		assertFalse(runtime.cycle().portalActive());

		runtime.update(snapshot(pad, false));
		assertEquals(DoorOpenCycle.Phase.ARMED, runtime.cycle().phase());
		assertTrue(runtime.cycle().portalActive(), "a shut pad is a live portal");
	}

	@Test
	void aSwingingTrapdoorStillFollowsTheRawOpenBit()
	{
		RuntimeDoor runtime = new RuntimeDoor(endpoint(DoorForm.TRAPDOOR, DoorOpenState.OPEN));
		DoorwayPlane swing = plane(DoorForm.TRAPDOOR, DoorOpenState.OPEN);

		runtime.update(snapshot(swing, false));
		assertFalse(runtime.cycle().portalActive());

		runtime.update(snapshot(swing, true));
		assertTrue(runtime.cycle().portalActive());
		assertEquals(swing, runtime.plane());
	}

	@Test
	void invalidatingDropsThePlaneAndTheLivePortal()
	{
		RuntimeDoor runtime = new RuntimeDoor(endpoint(DoorForm.TRAPDOOR, DoorOpenState.CLOSED));
		runtime.update(snapshot(plane(DoorForm.TRAPDOOR, DoorOpenState.CLOSED), false));

		runtime.invalidate();

		assertNull(runtime.plane());
		assertFalse(runtime.cycle().portalActive());
		assertEquals(DoorOpenCycle.Phase.CLOSED, runtime.cycle().phase());
	}

	private static PlacedDoorEndpoint endpoint(DoorForm form, DoorOpenState openState)
	{
		return new PlacedDoorEndpoint(
			new DoorPosition(WORLD_ID, "minecraft:overworld", 1, 64, 2),
			DoorItemIdentity.publicDoor(new UUID(0, 801), form),
			openState);
	}

	private static DoorwayPlane plane(DoorForm form, DoorOpenState openState)
	{
		return form == DoorForm.TRAPDOOR
			? DoorwayPlane.trapdoor(1, 64, 2, BlockFace.NORTH, Bisected.Half.BOTTOM, openState)
			: new DoorwayPlane(1, 64, 2, BlockFace.NORTH, form, Bisected.Half.BOTTOM, openState);
	}

	private static VanillaDoorSnapshot snapshot(DoorwayPlane plane, boolean open)
	{
		return new VanillaDoorSnapshot(WORLD_ID, plane, Door.Hinge.LEFT, open, false);
	}
}
