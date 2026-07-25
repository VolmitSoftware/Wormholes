package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraversalServiceDestinationAdmissionTest {
    @Test
    void directTransferRequiresDestinationSupport() {
        TraversalAdmissionPolicy.DestinationPlayerState state = state(true, false, false, false, false, false, 0, 20);

        assertEquals("destination does not accept direct transfers", TraversalAdmissionPolicy.destinationPlayerDenialReason(state));
    }

    @Test
    void proxyTransferDoesNotRequireNativeTransferSupport() {
        TraversalAdmissionPolicy.DestinationPlayerState state = state(false, false, false, false, false, false, 0, 20);

        assertNull(TraversalAdmissionPolicy.destinationPlayerDenialReason(state));
    }

    @Test
    void bannedProfileIsDeniedBeforeWhitelistAndCapacity() {
        TraversalAdmissionPolicy.DestinationPlayerState state = state(true, true, true, true, false, false, 20, 20);

        assertEquals("player is banned", TraversalAdmissionPolicy.destinationPlayerDenialReason(state));
    }

    @Test
    void whitelistRequiresMembershipForNonOperator() {
        TraversalAdmissionPolicy.DestinationPlayerState state = state(true, true, false, true, false, false, 0, 20);

        assertEquals("player is not whitelisted", TraversalAdmissionPolicy.destinationPlayerDenialReason(state));
    }

    @Test
    void reservationsCountTowardExactCapacityBoundary() {
        TraversalAdmissionPolicy.DestinationPlayerState state = state(true, true, false, false, false, false, 20, 20);

        assertEquals("destination server is full", TraversalAdmissionPolicy.destinationPlayerDenialReason(state));
    }

    @Test
    void operatorBypassesWhitelistWhenCapacityRemains() {
        TraversalAdmissionPolicy.DestinationPlayerState state = state(true, true, false, true, false, true, 19, 20);

        assertNull(TraversalAdmissionPolicy.destinationPlayerDenialReason(state));
    }

    @Test
    void operatorIsDeniedWhenPlayerLimitBypassCannotBeVerified() {
        TraversalAdmissionPolicy.DestinationPlayerState state = state(true, true, false, true, false, true, 20, 20);

        assertEquals("destination server is full", TraversalAdmissionPolicy.destinationPlayerDenialReason(state));
    }

    private static TraversalAdmissionPolicy.DestinationPlayerState state(
        boolean directTransfer,
        boolean transferSupported,
        boolean banned,
        boolean whitelistEnabled,
        boolean whitelisted,
        boolean operator,
        int admittedPlayers,
        int maxPlayers
    ) {
        return new TraversalAdmissionPolicy.DestinationPlayerState(
            directTransfer,
            transferSupported,
            banned,
            whitelistEnabled,
            whitelisted,
            operator,
            admittedPlayers,
            maxPlayers
        );
    }
}
