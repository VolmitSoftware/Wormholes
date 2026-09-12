package art.arcane.wormholes.door;

import java.util.Objects;

/** Live view of one placed door: its last read plane and its open cycle. */
public final class RuntimeDoor
{
	private final PlacedDoorEndpoint endpoint;
	private final DoorOpenCycle cycle;

	private volatile DoorwayPlane plane;

	public RuntimeDoor(PlacedDoorEndpoint endpoint)
	{
		this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
		cycle = new DoorOpenCycle();
	}

	public PlacedDoorEndpoint endpoint()
	{
		return endpoint;
	}

	public DoorOpenCycle cycle()
	{
		return cycle;
	}

	public DoorwayPlane plane()
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
