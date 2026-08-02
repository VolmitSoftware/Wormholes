package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorAccessRecordTest {
    @Test
    void requiredValuesAreRejectedWhenMissing() {
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(null, DoorAccessMode.WHITELIST, id(2), List.of()));
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(id(1), null, id(2), List.of()));
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(id(1), DoorAccessMode.WHITELIST, null, List.of()));
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(id(1), DoorAccessMode.WHITELIST, id(2), null));
        assertThrows(NullPointerException.class,
            () -> new DoorAccessRecord(id(1), DoorAccessMode.WHITELIST, id(2), Arrays.asList(id(3), null)));
    }

    @Test
    void duplicatePlayerEntriesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new DoorAccessRecord(id(1), DoorAccessMode.WHITELIST, id(2), List.of(id(3), id(3))));
    }

    @Test
    void playerListIsDefensivelyCopiedAndReadOnly() {
        ArrayList<UUID> mutablePlayers = new ArrayList<>();
        mutablePlayers.add(id(3));
        DoorAccessRecord record = new DoorAccessRecord(id(1), DoorAccessMode.WHITELIST, id(2), mutablePlayers);
        mutablePlayers.add(id(4));

        assertEquals(List.of(id(3)), record.players());
        assertThrows(UnsupportedOperationException.class, () -> record.players().add(id(5)));
    }

    @Test
    void withModeReplacesOnlyTheModeAndSkipsNoOps() {
        DoorAccessRecord record = new DoorAccessRecord(id(1), DoorAccessMode.UNRESTRICTED, id(2), List.of(id(3)));

        DoorAccessRecord blacklisted = record.withMode(DoorAccessMode.BLACKLIST);

        assertEquals(new DoorAccessRecord(id(1), DoorAccessMode.BLACKLIST, id(2), List.of(id(3))), blacklisted);
        assertEquals(DoorAccessMode.UNRESTRICTED, record.mode());
        assertSame(record, record.withMode(DoorAccessMode.UNRESTRICTED));
        assertThrows(NullPointerException.class, () -> record.withMode(null));
    }

    @Test
    void withPlayerAddedAppendsOnceAndPreservesOrder() {
        DoorAccessRecord record = new DoorAccessRecord(id(1), DoorAccessMode.WHITELIST, id(2), List.of(id(3)));

        DoorAccessRecord grown = record.withPlayerAdded(id(4));

        assertEquals(List.of(id(3), id(4)), grown.players());
        assertEquals(List.of(id(3)), record.players());
        assertSame(grown, grown.withPlayerAdded(id(4)));
        assertThrows(NullPointerException.class, () -> record.withPlayerAdded(null));
    }

    @Test
    void withPlayerRemovedDropsOnlyTheNamedPlayer() {
        DoorAccessRecord record = new DoorAccessRecord(
            id(1), DoorAccessMode.BLACKLIST, id(2), List.of(id(3), id(4), id(5)));

        DoorAccessRecord shrunk = record.withPlayerRemoved(id(4));

        assertEquals(List.of(id(3), id(5)), shrunk.players());
        assertEquals(DoorAccessMode.BLACKLIST, shrunk.mode());
        assertEquals(id(2), shrunk.ownerId());
        assertSame(shrunk, shrunk.withPlayerRemoved(id(4)));
        assertTrue(shrunk.withPlayerRemoved(id(3)).withPlayerRemoved(id(5)).players().isEmpty());
        assertThrows(NullPointerException.class, () -> record.withPlayerRemoved(null));
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
