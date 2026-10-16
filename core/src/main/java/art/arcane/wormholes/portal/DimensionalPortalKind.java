package art.arcane.wormholes.portal;

import java.util.Locale;

public enum DimensionalPortalKind
{
	NONE,
	NETHER,
	SHAPED_NETHER,
	END_SOURCE,
	END_ARRIVAL,
	END_EXIT;

	public boolean isNetherPortal()
	{
		return this == NETHER || this == SHAPED_NETHER;
	}

	public boolean isManagedEndPortal()
	{
		return this == END_SOURCE || this == END_ARRIVAL || this == END_EXIT;
	}

	public boolean isManagedPortal()
	{
		return this != NONE;
	}

	public boolean isReceiverOnly(boolean twoWayEnd)
	{
		return this == END_ARRIVAL && !twoWayEnd;
	}

	public PortalTravelMode managedTravel(boolean twoWayEnd)
	{
		return switch(this)
		{
			case NONE, NETHER, SHAPED_NETHER -> PortalTravelMode.BOTH;
			case END_SOURCE -> twoWayEnd ? PortalTravelMode.BOTH : PortalTravelMode.OUTBOUND;
			case END_ARRIVAL -> twoWayEnd ? PortalTravelMode.BOTH : PortalTravelMode.INBOUND;
			case END_EXIT -> PortalTravelMode.LOCKED;
		};
	}

	public ProjectionMode managedProjection(boolean twoWayEnd)
	{
		return switch(this)
		{
			case END_SOURCE, END_EXIT -> ProjectionMode.ON;
			case END_ARRIVAL -> twoWayEnd ? ProjectionMode.ON : ProjectionMode.OFF;
			case NONE, NETHER, SHAPED_NETHER -> null;
		};
	}

	public boolean isGenericDestination()
	{
		return !isManagedPortal();
	}

	public static DimensionalPortalKind fromName(String name)
	{
		if(name == null || name.isBlank())
		{
			return NONE;
		}
		try
		{
			return valueOf(name.trim().toUpperCase(Locale.ROOT));
		}
		catch(IllegalArgumentException ignored)
		{
			return NONE;
		}
	}
}
