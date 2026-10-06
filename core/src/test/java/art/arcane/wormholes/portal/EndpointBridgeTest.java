package art.arcane.wormholes.portal;

import art.arcane.optics.aperture.Endpoint;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EndpointBridgeTest {
    @Test
    void portalIdentityIsTheEndpointIdentity() {
        UUID id = UUID.randomUUID();
        Endpoint endpoint = new FixedPortal(id);
        assertEquals(id, endpoint.id());
    }

    @Test
    void endpointIdentityFollowsThePortal() {
        FixedPortal portal = new FixedPortal(UUID.randomUUID());
        UUID replaced = UUID.randomUUID();
        portal.id = replaced;
        assertEquals(replaced, portal.id());
    }

    private static final class FixedPortal implements IPortal {
        private UUID id;

        private FixedPortal(UUID id) {
            this.id = id;
        }

        @Override
        public Direction getDirection() {
            return Direction.N;
        }

        @Override
        public PortalFrame getFrame() {
            return PortalFrame.canonical(Direction.N);
        }

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public String getName() {
            return "fixed";
        }

        @Override
        public void setName(String name) {
        }

        @Override
        public boolean isRemote() {
            return false;
        }

        @Override
        public GeometryVector getOrigin() {
            return new GeometryVector(0, 64, 0);
        }
    }
}
