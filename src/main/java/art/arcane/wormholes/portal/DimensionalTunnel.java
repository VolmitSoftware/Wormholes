package art.arcane.wormholes.portal;

import java.util.UUID;

import art.arcane.wormholes.Wormholes;

public class DimensionalTunnel extends Tunnel
{
	public DimensionalTunnel(ILocalPortal portal)
	{
		super(portal, TunnelType.DIMENSIONAL);
	}

	static DimensionalTunnel pending(UUID destinationId)
	{
		DimensionalTunnel tunnel = new DimensionalTunnel(null);
		tunnel.pendingDestinationId = destinationId;
		return tunnel;
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
		ILocalPortal destination = portal instanceof ILocalPortal localPortal && !localPortal.isDestroyed()
				? localPortal : registeredDestination();
		return destination == null || destination.isDestroyed() ? null : destination;
	}

	private ILocalPortal registeredDestination()
	{
		if(pendingDestinationId == null || Wormholes.portalManager == null)
		{
			return null;
		}
		return Wormholes.portalManager.getLocalPortal(pendingDestinationId);
	}
}
