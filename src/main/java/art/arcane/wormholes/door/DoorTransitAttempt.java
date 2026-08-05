package art.arcane.wormholes.door;

import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.Objects;
import java.util.UUID;

record DoorTransitAttempt(
	Entity traveler,
	UUID travelerId,
	World sourceWorld,
	RuntimeDoor runtime,
	VanillaDoorSnapshot crossingSnapshot,
	DoorTransit transit)
{
	DoorTransitAttempt
	{
		Objects.requireNonNull(traveler, "traveler");
		Objects.requireNonNull(travelerId, "travelerId");
		Objects.requireNonNull(sourceWorld, "sourceWorld");
		Objects.requireNonNull(runtime, "runtime");
		Objects.requireNonNull(crossingSnapshot, "crossingSnapshot");
		Objects.requireNonNull(transit, "transit");
		if(!crossingSnapshot.plane().equals(transit.sourcePlane()))
		{
			throw new IllegalArgumentException("Transit source plane must match the captured crossing plane");
		}
		if(!runtime.endpoint().position().worldId().equals(crossingSnapshot.worldId()))
		{
			throw new IllegalArgumentException("Transit source snapshot must belong to the endpoint world");
		}
	}
}
