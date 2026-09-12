package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import art.arcane.wormholes.network.convoy.ConvoyLedger;

final class ConvoyJournalTest {
    @TempDir
    Path tempDir;

    @Test
    void inFlightGroupsRoundTripAndStaleOnesAreSelectedByAge() throws Exception {
        ConvoyJournal journal = new ConvoyJournal(tempDir.resolve("convoy"));
        UUID groupId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID boat = UUID.randomUUID();
        ConvoyLedger.Group fresh = new ConvoyLedger.Group(groupId, playerId, "beta", List.of(boat, playerId), 50_000L, ConvoyLedger.Phase.DISPATCHED, "");
        ConvoyLedger.Group old = new ConvoyLedger.Group(UUID.randomUUID(), null, "gamma", List.of(UUID.randomUUID()), 1_000L, ConvoyLedger.Phase.OPEN, "");

        journal.record(List.of(fresh, old));

        assertTrue(Files.isRegularFile(journal.file()));
        assertTrue(Files.readString(journal.file(), StandardCharsets.UTF_8).contains(groupId.toString()));
        List<ConvoyLedger.Group> loaded = journal.load();
        assertEquals(2, loaded.size());
        assertEquals(groupId, loaded.get(0).groupId());
        assertEquals(playerId, loaded.get(0).playerId());
        assertEquals("beta", loaded.get(0).peerName());
        assertEquals(List.of(boat, playerId), loaded.get(0).members());
        assertEquals(50_000L, loaded.get(0).openedAtMillis());
        assertEquals(ConvoyLedger.Phase.DISPATCHED, loaded.get(0).phase());
        assertEquals(null, loaded.get(1).playerId());

        List<ConvoyLedger.Group> stale = ConvoyJournal.stale(loaded, 60_000L, 20_000L);
        assertEquals(1, stale.size());
        assertEquals(old.groupId(), stale.getFirst().groupId());

        journal.clear();
        assertFalse(Files.exists(journal.file()));
        assertTrue(journal.load().isEmpty());
    }

    @Test
    void aCorruptJournalReadsAsEmpty() throws Exception {
        Path folder = tempDir.resolve("convoy");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(ConvoyJournal.FILE_NAME), "{ not json", StandardCharsets.UTF_8);
        assertTrue(new ConvoyJournal(folder).load().isEmpty());
    }
}
