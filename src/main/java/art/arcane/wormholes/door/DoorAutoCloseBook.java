package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bookkeeping for destination doors the server itself swung open so an arriving
 * traveler had something to step out of.
 *
 * <p>Only doors recorded here are ever auto-closed. Each arrival arms a fresh
 * token, so a later arrival silently supersedes the close that an earlier one
 * scheduled instead of stacking timers.</p>
 */
final class DoorAutoCloseBook
{
	static final int MAX_DEFERRALS = 20;

	private final ConcurrentHashMap<UUID, Long> armed;
	private final AtomicLong sequence;

	DoorAutoCloseBook()
	{
		armed = new ConcurrentHashMap<>();
		sequence = new AtomicLong();
	}

	/**
	 * Records a server-opened door and returns the token the matching scheduled
	 * close must present.
	 */
	long arm(UUID doorId)
	{
		Long token = Long.valueOf(sequence.incrementAndGet());
		armed.put(Objects.requireNonNull(doorId, "doorId"), token);
		return token.longValue();
	}

	boolean isArmed(UUID doorId)
	{
		return armed.containsKey(Objects.requireNonNull(doorId, "doorId"));
	}

	/**
	 * What an arrival should do with the destination door it is about to spill a
	 * traveler out of.
	 */
	Arrival decideArrival(UUID doorId, boolean portalLive)
	{
		if(!portalLive)
		{
			return Arrival.OPEN;
		}
		return isArmed(doorId) ? Arrival.EXTEND : Arrival.LEAVE;
	}

	/**
	 * Records a physical reading of a tracked door. A door seen shut stops being
	 * the server's to close: without this, a player who shuts one and swings it
	 * open again inside the window has their own door slammed by the timer the
	 * server armed for a traveler who has long since walked away.
	 */
	void observe(UUID doorId, boolean portalLive)
	{
		Objects.requireNonNull(doorId, "doorId");
		if(!portalLive)
		{
			forget(doorId);
		}
	}

	void forget(UUID doorId)
	{
		armed.remove(Objects.requireNonNull(doorId, "doorId"));
	}

	void clear()
	{
		armed.clear();
	}

	int size()
	{
		return armed.size();
	}

	Decision decide(UUID doorId, long token, boolean stillOpen, boolean transitInFlight, int deferrals)
	{
		Long current = armed.get(Objects.requireNonNull(doorId, "doorId"));
		if(current == null || current.longValue() != token)
		{
			return Decision.SUPERSEDED;
		}
		if(!stillOpen)
		{
			armed.remove(doorId, current);
			return Decision.ALREADY_CLOSED;
		}
		if(transitInFlight)
		{
			if(deferrals < MAX_DEFERRALS)
			{
				return Decision.DEFER;
			}
			// A transit stuck for the whole deferral budget is broken; hand the door
			// back to normal player operation instead of slamming it on a traveler.
			armed.remove(doorId, current);
			return Decision.ABANDONED;
		}
		armed.remove(doorId, current);
		return Decision.CLOSE;
	}

	enum Decision
	{
		SUPERSEDED,
		ALREADY_CLOSED,
		DEFER,
		ABANDONED,
		CLOSE
	}

	enum Arrival
	{
		/** The door is dormant: swing it live and arm the matching close. */
		OPEN,
		/** The server is already holding this door open, so push its close back. */
		EXTEND,
		/** A player opened this door; it is not the server's to time or to shut. */
		LEAVE
	}
}
