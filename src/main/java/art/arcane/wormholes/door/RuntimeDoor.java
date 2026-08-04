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
		// The cycle tracks portal-active, which may be the inverse of the raw open bit.
		cycle.observe(snapshot.portalLive());
	}

	void invalidate()
	{
		plane = null;
		cycle.observe(false);
	}
}
