package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Set;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

public final class PortalTypeAccessTest
{
	@Test
	public void portalAndRtpRequireTheResolvedPortalLeaf()
	{
		assertTrue(PortalTypeAccess.allows(player(false, Set.of(PortalTypeAccess.PORTAL)), PortalType.PORTAL));
		assertTrue(PortalTypeAccess.allows(player(false, Set.of(PortalTypeAccess.PORTAL)), PortalType.RTP));
		assertFalse(PortalTypeAccess.allows(player(false, Set.of()), PortalType.PORTAL));
		assertFalse(PortalTypeAccess.allows(player(false, Set.of("wormholes.portals")), PortalType.PORTAL));
	}

	@Test
	public void wormholeAndGatewayUseTheirOwnManagementNodes()
	{
		assertTrue(PortalTypeAccess.allows(player(false, Set.of(PortalTypeAccess.WORMHOLE)), PortalType.WORMHOLE));
		assertTrue(PortalTypeAccess.allows(player(false, Set.of(PortalTypeAccess.GATEWAY)), PortalType.GATEWAY));
		assertFalse(PortalTypeAccess.allows(player(false, Set.of(PortalTypeAccess.PORTAL)), PortalType.WORMHOLE));
		assertFalse(PortalTypeAccess.allows(player(false, Set.of("wormholes.portals")), PortalType.GATEWAY));
	}

	@Test
	public void administratorsAndOperatorsAlwaysPass()
	{
		assertTrue(PortalTypeAccess.allows(player(true, Set.of()), PortalType.GATEWAY));
		assertTrue(PortalTypeAccess.allows(player(false, Set.of(PortalTypeAccess.ADMIN)), PortalType.GATEWAY));
		assertFalse(PortalTypeAccess.allows(null, PortalType.PORTAL));
		assertFalse(PortalTypeAccess.allows(player(false, Set.of()), null));
	}

	private static Player player(boolean operator, Set<String> permissions)
	{
		return (Player) Proxy.newProxyInstance(
				PortalTypeAccessTest.class.getClassLoader(),
				new Class<?>[] {Player.class},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "isOp" -> operator;
					case "hasPermission" -> permissions.contains(String.valueOf(arguments[0]));
					case "equals" -> proxy == arguments[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> "PortalTypeAccessTestPlayer";
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
