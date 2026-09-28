package art.arcane.wormholes.door;

/**
 * How a traveler is carried through a dimensional door.
 *
 * <p>{@code LIVING} covers players, mobs, and vehicles: they claim the door's
 * single armed open cycle, close the door behind them, need a safe standing
 * spot on arrival, and land at rest. {@code OBJECT} covers projectiles, dropped
 * items, and experience orbs: a whole volley passes while the door is
 * physically open, the door is left alone, arrival needs only a passable
 * volume, and momentum is carried across.</p>
 */
public enum DoorTravelerClass
{
	LIVING,
	OBJECT
}
