package art.arcane.wormholes.door;

final class DoorTravelerPolicy
{
	private static final double MAX_WIDTH = 1.0D;
	private static final double MAX_HEIGHT = 2.0D;

	private DoorTravelerPolicy()
	{
	}

	static boolean canEnter(
		DoorKind kind,
		boolean player,
		boolean mobileEntity,
		boolean objectEntity,
		boolean boss,
		boolean complex,
		boolean constrained,
		double width,
		double height)
	{
		if(constrained)
		{
			return false;
		}
		if(player)
		{
			return true;
		}
		if(!fitsAperture(width, height))
		{
			return false;
		}
		if(objectEntity && !mobileEntity)
		{
			// A personal pocket is keyed to one player and a return ticket to one
			// traveler; an unowned object has neither, so only shared destinations
			// accept it.
			return kind == DoorKind.PAIR || kind == DoorKind.PUBLIC;
		}
		return kind == DoorKind.PAIR
			&& mobileEntity
			&& !boss
			&& !complex;
	}

	private static boolean fitsAperture(double width, double height)
	{
		return Double.isFinite(width)
			&& width > 0.0D
			&& width <= MAX_WIDTH
			&& Double.isFinite(height)
			&& height > 0.0D
			&& height <= MAX_HEIGHT;
	}
}
