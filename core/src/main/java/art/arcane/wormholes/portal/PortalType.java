package art.arcane.wormholes.portal;

public enum PortalType
{
	PORTAL("wormholes.portals.portal"),
	WORMHOLE("wormholes.portals.wormhole"),
	GATEWAY("wormholes.gateway"),
	RTP("wormholes.portals.portal");

	private final String permission;

	PortalType(String permission)
	{
		this.permission = permission;
	}

	public String permission()
	{
		return permission;
	}
}
