package art.arcane.wormholes.door;

import java.util.Objects;

final class RuntimeDoor
{
	private final PlacedDoorEndpoint endpoint;
	private final DoorOpenCycle cycle;

	private volatile DoorwayPlane plane;

	RuntimeDoor(PlacedDoorEndpoint endpoint)
	{
		this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
		cycle = new DoorOpenCycle();
	}

	PlacedDoorEndpoint endpoint()
	{
		return endpoint;
	}

	DoorOpenCycle cycle()
	{
		return cycle;
	}

	DoorwayPlane plane()
	{
		return plane;
	}

	void update(VanillaDoorSnapshot snapshot)
	{
		plane = snapshot.plane();
		cycle.observe(snapshot.open());
	}

	void invalidate()
	{
		plane = null;
		cycle.observe(false);
	}
}
