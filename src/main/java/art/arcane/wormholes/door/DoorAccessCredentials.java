package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

record DoorAccessCredentials(UUID playerId, boolean bypass)
{
	static DoorAccessCredentials ungated()
	{
		return new DoorAccessCredentials(null, false);
	}

	static DoorAccessCredentials of(UUID playerId, boolean bypass)
	{
		return new DoorAccessCredentials(Objects.requireNonNull(playerId, "playerId"), bypass);
	}

	boolean canUse(DoorAccessRecord record)
	{
		return playerId == null || DoorAccessPolicy.canUse(record, playerId, bypass);
	}
}
