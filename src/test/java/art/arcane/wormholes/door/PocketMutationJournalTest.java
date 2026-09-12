package art.arcane.wormholes.door;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.util.VIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketMutationJournalTest {
    private static final PocketShell SOURCE = PocketShell.defaults();
    private static final PocketShell TARGET = PocketShell.defaults().withSize(32);

    @TempDir
    Path temporaryDirectory;

    @Test
    void aJournalFileWrittenBeforeIntentKindsExistedLoadsAsAResize() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        UUID spaceId = new UUID(0, 900);
        UUID operationId = new UUID(0, 901);
        Files.createDirectories(directory);
        VIO.writeAll(directory.resolve(spaceId + ".json").toFile(), new JSONObject()
            .put("schema", 1)
            .put("operationId", operationId.toString())
            .put("spaceId", spaceId.toString())
            .put("source", shell(SOURCE))
            .put("target", shell(TARGET))
            .toString(2));

        List<PocketMutationIntent> pending = new PocketMutationJournal(directory).load();

        assertEquals(1, pending.size());
        PocketMutationIntent intent = pending.getFirst();
        assertEquals(PocketMutationIntent.Kind.RESIZE, intent.kind());
        assertEquals(operationId, intent.operationId());
        assertEquals(spaceId, intent.spaceId());
        assertEquals(SOURCE, intent.source());
        assertEquals(TARGET, intent.target());
        assertEquals("", intent.templateName());
    }

    @Test
    void aPasteIntentRoundTripsItsKindAndTemplateAcrossRestart() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        PocketMutationJournal journal = loaded(directory);
        PocketSpace space = space(0L);

        PocketMutationIntent begun = journal.beginPaste(space, "dungeon");

        assertEquals(PocketMutationIntent.Kind.PASTE, begun.kind());
        assertEquals("dungeon", begun.templateName());
        assertEquals(SOURCE, begun.source());
        assertEquals(SOURCE, begun.target(), "a paste never reshapes the room");

        PocketMutationIntent restored = loaded(directory).pending().getFirst();
        assertEquals(begun, restored);

        PocketMutationJournal restarted = loaded(directory);
        restarted.complete(restarted.pending().getFirst());
        assertEquals(List.of(), loaded(directory).pending());
    }

    @Test
    void aResetIntentIsJournalledSoAnInterruptedWipeIsFinishedOnRestart() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        PocketMutationJournal journal = loaded(directory);

        PocketMutationIntent intent = journal.beginReset(space(0L), "arena");

        assertEquals(PocketMutationIntent.Kind.RESET, intent.kind());
        assertEquals("arena", intent.templateName());
        assertEquals(intent, loaded(directory).pending().getFirst());
    }

    @Test
    void aResizeStillRefusesASecondPendingOperationOnTheSamePocket() throws Exception {
        PocketMutationJournal journal = loaded(temporaryDirectory.resolve("journal"));
        PocketSpace space = space(0L);
        journal.beginResize(space, TARGET);

        assertThrows(IllegalStateException.class, () -> journal.beginResize(space, TARGET));
        assertThrows(IllegalStateException.class, () -> journal.beginPaste(space, "dungeon"));
        assertEquals(1, journal.pending().size());
    }

    @Test
    void anUnknownIntentKindFailsTheLoadInsteadOfSilentlyResizing() throws Exception {
        Path directory = temporaryDirectory.resolve("journal");
        UUID spaceId = new UUID(0, 910);
        Files.createDirectories(directory);
        VIO.writeAll(directory.resolve(spaceId + ".json").toFile(), new JSONObject()
            .put("schema", 1)
            .put("operationId", new UUID(0, 911).toString())
            .put("spaceId", spaceId.toString())
            .put("kind", "DEMOLISH")
            .put("source", shell(SOURCE))
            .put("target", shell(TARGET))
            .toString(2));

        IOException failure = assertThrows(IOException.class, () -> new PocketMutationJournal(directory).load());
        assertTrue(failure.getMessage().contains("pocket-mutation journal"), failure.getMessage());
    }

    private static PocketMutationJournal loaded(Path directory) throws IOException {
        PocketMutationJournal journal = new PocketMutationJournal(directory);
        journal.load();
        return journal;
    }

    private static PocketSpace space(long slot) {
        PocketBinding binding = PocketBinding.personal(new UUID(0, 920 + slot));
        return new PocketSpace(
            PocketAllocator.spaceIdFor(binding), binding, slot,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketAllocator.DEFAULT_CENTER_Y,
            PocketAllocator.CHUNK_CENTER_OFFSET, SOURCE);
    }

    private static JSONObject shell(PocketShell shell) {
        return new JSONObject()
            .put("size", shell.size())
            .put("shellMaterial", shell.shellMaterial())
            .put("returnDoorMaterial", shell.returnDoorMaterial());
    }
}
