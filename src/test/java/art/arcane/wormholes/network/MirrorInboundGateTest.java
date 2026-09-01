package art.arcane.wormholes.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.RemoteWorld;

public final class MirrorInboundGateTest {
    @Test
    public void networkPreflightRejectsMirrorBeforeTransfer() {
        assertFalse(TraversalAdmissionPolicy.acceptsInbound(portal(true, true)));
        assertFalse(TraversalAdmissionPolicy.acceptsInbound(portal(false, false)));
        assertTrue(TraversalAdmissionPolicy.acceptsInbound(portal(false, true)));
        assertFalse(TraversalAdmissionPolicy.acceptsInbound(null));
    }

    @Test
    public void remotePreflightRejectsDestinationsThatWouldDenyHandoff() {
        RemotePortal remote = remotePortal(true);
        assertTrue(remote.acceptsInboundTraversal());

		remote.setMirroredProjectionMode(ProjectionMode.OFF);
		assertTrue(remote.acceptsInboundTraversal());

        remote.setMirroredMirrorMode(true);
        assertFalse(remote.acceptsInboundTraversal());

        remote.setMirroredMirrorMode(false);
        remote.setMirroredIncomingTraversalsEnabled(false);
        assertFalse(remote.acceptsInboundTraversal());
        assertFalse(remotePortal(false).acceptsInboundTraversal());
    }

	@Test
	public void remotePreflightUsesMirroredPermissionPolicyBeforeOptimisticTransfer() {
		RemotePortal remote = remotePortal(true);
		remote.setName("Beta Gate");
		Player allowed = player(false, true);
		Player denied = player(false, false);

		remote.setMirroredPermissionMode(PortalPermissionMode.WHITELIST);
		assertTrue(remote.acceptsInboundTraversal(allowed));
		assertFalse(remote.acceptsInboundTraversal(denied));

		remote.setMirroredPermissionMode(PortalPermissionMode.BLACKLIST);
		assertFalse(remote.acceptsInboundTraversal(allowed));
		assertTrue(remote.acceptsInboundTraversal(denied));
	}

	@Test
	public void operatorsBypassRemoteIncomingDirectionButNotPortalState() {
		Player operator = player(true, false);
		RemotePortal remote = remotePortal(true);
		remote.setMirroredIncomingTraversalsEnabled(false);

		assertTrue(remote.acceptsInboundTraversal(operator));
		assertTrue(TraversalAdmissionPolicy.acceptsInbound(portal(false, false), true));
		assertFalse(TraversalAdmissionPolicy.acceptsInbound(portal(false, false), false));

		remote.setMirroredMirrorMode(true);
		assertFalse(remote.acceptsInboundTraversal(operator));
		assertFalse(TraversalAdmissionPolicy.acceptsInbound(portal(true, false), true));
		assertFalse(remotePortal(false).acceptsInboundTraversal(operator));
	}

    private static ILocalPortal portal(boolean mirror, boolean incoming) {
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(),
            new Class<?>[] { ILocalPortal.class },
            (proxy, method, args) -> switch(method.getName()) {
                case "isMirrorMode" -> Boolean.valueOf(mirror);
                case "isIncomingTraversalsEnabled" -> Boolean.valueOf(incoming);
                case "toString" -> "MirrorInboundGatePortal";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static RemotePortal remotePortal(boolean open) {
        return new RemotePortal(
            UUID.randomUUID(),
            new RemoteWorld("beta", "world"),
            new Vector(0.0D, 64.0D, 0.0D),
            PortalType.GATEWAY,
            open,
            new AxisAlignedBB(0.0D, 1.0D, 64.0D, 67.0D, 0.0D, 1.0D)
        );
    }

	private static Player player(boolean operator, boolean hasPermission) {
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class }, (proxy, method, args) -> switch(method.getName()) {
			case "isOp" -> Boolean.valueOf(operator);
			case "hasPermission" -> Boolean.valueOf(hasPermission);
			case "toString" -> "MirrorInboundGatePlayer";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == args[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}
}
