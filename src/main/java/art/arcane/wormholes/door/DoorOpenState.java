package art.arcane.wormholes.door;

public enum DoorOpenState
{
	OPEN,
	CLOSED;

	public boolean matches(boolean open)
	{
		return this == (open ? OPEN : CLOSED);
	}

	public DoorOpenState flipped()
	{
		return this == OPEN ? CLOSED : OPEN;
	}

	public static DoorOpenState fromLegacy(boolean activeWhenOpen)
	{
		return activeWhenOpen ? OPEN : CLOSED;
	}
}
