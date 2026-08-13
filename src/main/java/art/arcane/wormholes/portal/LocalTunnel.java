package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;

public class LocalTunnel extends Tunnel
{
	public LocalTunnel(ILocalPortal portal)
	{
		super(portal, TunnelType.LOCAL);
	}

	@Override
	public IPortal getDestination()
	{
		return resolveDestination();
	}

	@Override
	public boolean isValid()
	{
		return resolveDestination() != null;
	}

	boolean hasRtpDestination()
	{
		ILocalPortal destination = findDestination();
		return destination != null && destination.getType() == PortalType.RTP;
	}

	private ILocalPortal resolveDestination()
	{
		ILocalPortal resolved = findDestination();
		if(resolved == null || resolved.getType() == PortalType.RTP)
		{
			return null;
		}
		portal = resolved;
		return resolved;
	}

	private ILocalPortal findDestination()
	{
		if(portal instanceof ILocalPortal localPortal)
		{
			return localPortal;
		}
		if(pendingDestinationId == null || Wormholes.portalManager == null)
		{
			return null;
		}
		return Wormholes.portalManager.getLocalPortal(pendingDestinationId);
	}
}
