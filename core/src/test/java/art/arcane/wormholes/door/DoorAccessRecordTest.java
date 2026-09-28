package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorAccessRecordTest {
    @Test
    void requiredValuesAreRejectedWhenMissing() {
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(null, id(2), Map.of()));
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(id(1), null, Map.of()));
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(id(1), id(2), null));
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(id(1), id(2), Collections.singletonMap(id(3), null)));
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(id(1), id(2), Collections.singletonMap(null, DoorAccessState.NEUTRAL)));
    }

    @Test
    void playerStatesAreDefensivelyCopiedAndReadOnly() {
        LinkedHashMap<UUID, DoorAccessState> mutable = new LinkedHashMap<>();
        mutable.put(id(3), DoorAccessState.WHITELIST);
        DoorAccessRecord record = new DoorAccessRecord(id(1), id(2), mutable);
        mutable.put(id(4), DoorAccessState.BLACKLIST);

        assertEquals(Map.of(id(3), DoorAccessState.WHITELIST), record.players());
        assertThrows(UnsupportedOperationException.class,
            () -> record.players().put(id(5), DoorAccessState.NEUTRAL));
    }

    @Test
    void listedPlayersKeepInsertionOrder() {
        DoorAccessRecord record = DoorAccessRecord.unrestricted(id(1), id(2))
            .withPlayerState(id(5), DoorAccessState.NEUTRAL)
            .withPlayerState(id(3), DoorAccessState.BLACKLIST)
            .withPlayerState(id(4), DoorAccessState.WHITELIST);

        assertEquals(List.of(id(5), id(3), id(4)), record.listedPlayers());
        assertEquals(List.of(id(5), id(3), id(4)),
            record.withPlayerState(id(5), DoorAccessState.WHITELIST).listedPlayers());
    }

    @Test
    void withPlayerStateAddsUpdatesAndSkipsNoOps() {
        DoorAccessRecord record = DoorAccessRecord.unrestricted(id(1), id(2))
            .withPlayerState(id(3), DoorAccessState.NEUTRAL);

        DoorAccessRecord whitelisted = record.withPlayerState(id(3), DoorAccessState.WHITELIST);

        assertEquals(DoorAccessState.WHITELIST, whitelisted.stateOf(id(3)));
        assertEquals(DoorAccessState.NEUTRAL, record.stateOf(id(3)));
        assertSame(whitelisted, whitelisted.withPlayerState(id(3), DoorAccessState.WHITELIST));
        assertThrows(NullPointerException.class, () -> record.withPlayerState(null, DoorAccessState.NEUTRAL));
        assertThrows(NullPointerException.class, () -> record.withPlayerState(id(3), null));
    }

    @Test
    void withPlayerRemovedDropsOnlyTheNamedPlayer() {
        DoorAccessRecord record = DoorAccessRecord.unrestricted(id(1), id(2))
            .withPlayerState(id(3), DoorAccessState.WHITELIST)
            .withPlayerState(id(4), DoorAccessState.BLACKLIST)
            .withPlayerState(id(5), DoorAccessState.NEUTRAL);

        DoorAccessRecord shrunk = record.withPlayerRemoved(id(4));

        assertEquals(List.of(id(3), id(5)), shrunk.listedPlayers());
        assertEquals(id(2), shrunk.ownerId());
        assertSame(shrunk, shrunk.withPlayerRemoved(id(4)));
        assertTrue(shrunk.withPlayerRemoved(id(3)).withPlayerRemoved(id(5)).players().isEmpty());
        assertThrows(NullPointerException.class, () -> record.withPlayerRemoved(null));
    }

    @Test
    void listingHelpersReportMembershipAndWhitelistPresence() {
        DoorAccessRecord neutral = DoorAccessRecord.unrestricted(id(1), id(2))
            .withPlayerState(id(3), DoorAccessState.NEUTRAL);

        assertTrue(neutral.isListed(id(3)));
        assertFalse(neutral.isListed(id(4)));
        assertFalse(neutral.isListed(null));
        assertNull(neutral.stateOf(null));
        assertFalse(neutral.hasWhitelist());
        assertFalse(neutral.withPlayerState(id(4), DoorAccessState.BLACKLIST).hasWhitelist());
        assertTrue(neutral.withPlayerState(id(4), DoorAccessState.WHITELIST).hasWhitelist());
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
