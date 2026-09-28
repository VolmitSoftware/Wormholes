package art.arcane.wormholes.portal;


public enum PortalTravelMode
{
	BOTH(true, true),
	OUTBOUND(true, false),
	INBOUND(false, true),
	LOCKED(false, false);

	private static final PortalTravelMode[] CYCLE = values();
	private final boolean outgoing;
	private final boolean incoming;

	PortalTravelMode(boolean outgoing, boolean incoming)
	{
		this.outgoing = outgoing;
		this.incoming = incoming;
	}

	public boolean allowsOutgoing()
	{
		return outgoing;
	}

	public boolean allowsIncoming()
	{
		return incoming;
	}

	public PortalTravelMode next()
	{
		return CYCLE[(ordinal() + 1) % CYCLE.length];
	}

	public static PortalTravelMode from(boolean outgoing, boolean incoming)
	{
		if(outgoing)
		{
			return incoming ? BOTH : OUTBOUND;
		}
		return incoming ? INBOUND : LOCKED;
	}
}
