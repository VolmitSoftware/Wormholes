package art.arcane.wormholes.door;

/**
 * Thread-safe lifecycle for one placed dimensional door.
 *
 * <p>A successful transit consumes the current open cycle. If redstone holds
 * the block open, the cycle remains consumed and no second traveler can trigger
 * it. The state arms again only after the real door is observed inactive and
 * then active.</p>
 *
 * <p>Every observation is the door's <em>portal-active</em> state rather than
 * the raw {@code Openable.isOpen()} bit. A closed-state portal inverts that bit;
 * feeding the raw value would leave its contact surface permanently disarmed.</p>
 */
public final class DoorOpenCycle
{
	private Phase phase;
	private boolean portalActive;

	public DoorOpenCycle()
	{
		phase = Phase.CLOSED;
		portalActive = false;
	}

	/**
	 * Records the latest portal-active state read from the world.
	 */
	public synchronized Phase observe(boolean active)
	{
		boolean wasActive = portalActive;
		portalActive = active;

		if(phase == Phase.IN_TRANSIT)
		{
			return phase;
		}

		if(!active)
		{
			phase = Phase.CLOSED;
		}
		else if(!wasActive && phase == Phase.CLOSED)
		{
			phase = Phase.ARMED;
		}

		return phase;
	}

	/**
	 * Atomically claims the current open cycle for one traveler transit.
	 */
	public synchronized boolean tryBegin(boolean active)
	{
		observe(active);
		if(!portalActive || phase != Phase.ARMED)
		{
			return false;
		}

		phase = Phase.IN_TRANSIT;
		return true;
	}

	/**
	 * Completes the claimed transit using a freshly observed portal-active
	 * state. A failed teleport may be retried during the same open cycle; a
	 * successful one may not.
	 */
	public synchronized Phase complete(boolean success, boolean active)
	{
		if(phase != Phase.IN_TRANSIT)
		{
			throw new IllegalStateException("No dimensional-door transit is in progress");
		}

		portalActive = active;
		if(success)
		{
			phase = active ? Phase.CONSUMED : Phase.CLOSED;
		}
		else
		{
			phase = active ? Phase.ARMED : Phase.CLOSED;
		}
		return phase;
	}

	public synchronized Phase phase()
	{
		return phase;
	}

	public synchronized boolean portalActive()
	{
		return portalActive;
	}

	public enum Phase
	{
		CLOSED,
		ARMED,
		IN_TRANSIT,
		CONSUMED
	}
}
