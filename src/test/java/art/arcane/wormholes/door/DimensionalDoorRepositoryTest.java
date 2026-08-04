package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionalDoorRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingRepositoryLoadsEmptyWithoutCreatingStateFile() throws Exception {
        DimensionalDoorRepository repository = DimensionalDoorRepository.under(temporaryDirectory);

        assertEquals(DoorStoreSnapshot.empty(), repository.load());
        assertFalse(Files.exists(repository.stateFile()));
    }

    @Test
    void completeStateRoundTripsAcrossFreshRepository() throws Exception {
        DoorPairIdentity pair = new DoorPairIdentity(id(1), id(2), id(3));
        PlacedDoorEndpoint endpointA = new PlacedDoorEndpoint(
            new DoorPosition(id(4), "minecraft:overworld", 1, 64, 2),
            pair.endpoint(PairEndpoint.A)
        );
        PlacedDoorEndpoint personal = new PlacedDoorEndpoint(
            new DoorPosition(id(5), "minecraft:the_end", -3, 70, 8),
            DoorItemIdentity.personal(id(6))
        );
        PocketAllocator allocator = new PocketAllocator();
        allocator.getOrAllocate(PocketBinding.personal(id(7)));
        allocator.getOrAllocate(PocketBinding.publicDoor(id(8)));
        ReturnTicket ticket = ticket(id(9), id(6));
        DoorAccessRecord pairedAccess = DoorAccessRecord.unrestricted(pair.endpointAItemId(), id(10))
            .withPlayerState(id(11), DoorAccessState.WHITELIST)
            .withPlayerState(id(12), DoorAccessState.BLACKLIST);
        DoorAccessRecord personalAccess = DoorAccessRecord.unrestricted(id(6), id(13));
        DoorStoreSnapshot expected = new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA,
            allocator.nextSlot(),
            List.of(pair),
            List.of(endpointA, personal),
            allocator.spaces(),
            List.of(ticket),
            List.of(pairedAccess, personalAccess)
        );
        Path stateFile = temporaryDirectory.resolve("custom-state.json");

        new DimensionalDoorRepository(stateFile).save(expected);
        DoorStoreSnapshot actual = new DimensionalDoorRepository(stateFile).load();

        assertEquals(expected, actual);
        assertEquals(List.of(pairedAccess, personalAccess), actual.accessRecords());
        assertTrue(Files.readString(stateFile).contains("\"nextPocketSlot\": 2"));
        assertTrue(Files.readString(stateFile).contains("\"returnTickets\": []"));
        assertTrue(Files.readString(stateFile).contains("\"state\": \"WHITELIST\""));
        assertTrue(Files.readString(stateFile).contains("\"state\": \"BLACKLIST\""));
        Path ticketDirectory = stateFile.resolveSibling("custom-state.json.tickets");
        assertTrue(Files.isRegularFile(ticketDirectory.resolve(ticket.playerId() + ".json")));
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(
                List.of(stateFile, ticketDirectory),
                files.sorted().toList(),
                "atomic temp files must be cleaned up"
            );
        }
    }

    @Test
    void legacyDoorKindsMigrateWithoutChangingIdentityOrPocketAllocation() throws Exception {
        Path stateFile = temporaryDirectory.resolve("legacy-state.json");
        UUID pairId = id(110);
        UUID pairA = id(111);
        UUID pairB = id(112);
        UUID publicItemId = id(113);
        UUID publicSpaceId = id(114);
        Files.writeString(stateFile, """
            {
              "schema": 2,
              "nextPocketSlot": 1,
              "pairs": [{
                "pairId": "%s",
                "endpointAItemId": "%s",
                "endpointBItemId": "%s"
              }],
              "endpoints": [{
                "worldId": "%s",
                "worldKey": "minecraft:overworld",
                "x": 1,
                "y": 64,
                "z": 2,
                "item": {
                  "itemId": "%s",
                  "kind": "PAIRED",
                  "pairId": "%s",
                  "pairEndpoint": "A"
                }
              }, {
                "worldId": "%s",
                "worldKey": "minecraft:the_nether",
                "x": 3,
                "y": 70,
                "z": 4,
                "item": {
                  "itemId": "%s",
                  "kind": "IRON"
                }
              }],
              "spaces": [{
                "spaceId": "%s",
                "bindingKind": "IRON",
                "bindingId": "%s",
                "slot": 0,
                "centerX": 8,
                "centerY": 128,
                "centerZ": 8
              }],
              "returnTickets": []
            }
            """.formatted(
                pairId,
                pairA,
                pairB,
                id(115),
                pairA,
                pairId,
                id(116),
                publicItemId,
                publicSpaceId,
                publicItemId));

        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);
        DoorStoreSnapshot migrated = repository.load();

        assertEquals(DoorStoreSnapshot.CURRENT_SCHEMA, migrated.schema());
        assertEquals(DoorKind.PAIR, migrated.endpoints().get(0).identity().kind());
        assertEquals(pairA, migrated.endpoints().get(0).identity().itemId());
        assertEquals(pairId, migrated.endpoints().get(0).identity().pairId());
        assertEquals(DoorKind.PUBLIC, migrated.endpoints().get(1).identity().kind());
        assertEquals(publicItemId, migrated.endpoints().get(1).identity().itemId());
        assertEquals(publicSpaceId, migrated.spaces().get(0).spaceId());
        assertEquals(PocketBinding.publicDoor(publicItemId), migrated.spaces().get(0).binding());

        repository.save(migrated);
        String canonical = Files.readString(stateFile);
        assertTrue(canonical.contains("\"schema\": 6"));
        assertTrue(canonical.contains("\"kind\": \"PAIR\""));
        assertTrue(canonical.contains("\"kind\": \"PUBLIC\""));
        assertFalse(canonical.contains("\"kind\": \"PAIRED\""));
        assertFalse(canonical.contains("\"kind\": \"IRON\""));
        assertTrue(canonical.contains("\"bindingKind\": \"IRON\""));
    }

    @Test
    void schemaThreeStateWithoutAccessKeyLoadsWithNoAccessRecords() throws Exception {
        Path stateFile = temporaryDirectory.resolve("no-access-state.json");
        Files.writeString(stateFile, """
            {
              "schema": 3,
              "nextPocketSlot": 0,
              "pairs": [],
              "endpoints": [{
                "worldId": "%s",
                "worldKey": "minecraft:overworld",
                "x": 1,
                "y": 64,
                "z": 2,
                "item": {
                  "itemId": "%s",
                  "kind": "PUBLIC"
                }
              }],
              "spaces": [],
              "returnTickets": []
            }
            """.formatted(id(130), id(131)));

        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);
        DoorStoreSnapshot loaded = repository.load();

        assertTrue(loaded.accessRecords().isEmpty());
        assertEquals(1, loaded.endpoints().size());

        repository.save(loaded);
        assertTrue(Files.readString(stateFile).contains("\"access\": []"));
    }

    @Test
    void schemaThreeDoorModesMigrateToPerPlayerStates() throws Exception {
        Path stateFile = temporaryDirectory.resolve("mode-access-state.json");
        Files.writeString(stateFile, """
            {
              "schema": 3,
              "nextPocketSlot": 0,
              "pairs": [],
              "endpoints": [],
              "spaces": [],
              "returnTickets": [],
              "access": [
                {"itemId": "%s", "mode": "WHITELIST", "ownerId": "%s", "players": ["%s", "%s"]},
                {"itemId": "%s", "mode": "BLACKLIST", "ownerId": "%s", "players": ["%s"]},
                {"itemId": "%s", "mode": "UNRESTRICTED", "ownerId": "%s", "players": ["%s"]},
                {"itemId": "%s", "mode": "WHITELIST", "ownerId": "%s", "players": []}
              ]
            }
            """.formatted(
                id(300), id(301), id(302), id(303),
                id(310), id(311), id(312),
                id(320), id(321), id(322),
                id(330), id(331)));

        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);
        DoorStoreSnapshot migrated = repository.load();

        assertEquals(DoorStoreSnapshot.CURRENT_SCHEMA, migrated.schema());
        assertEquals(
            List.of(
                DoorAccessRecord.unrestricted(id(300), id(301))
                    .withPlayerState(id(302), DoorAccessState.WHITELIST)
                    .withPlayerState(id(303), DoorAccessState.WHITELIST),
                DoorAccessRecord.unrestricted(id(310), id(311))
                    .withPlayerState(id(312), DoorAccessState.BLACKLIST),
                DoorAccessRecord.unrestricted(id(320), id(321))
                    .withPlayerState(id(322), DoorAccessState.NEUTRAL),
                DoorAccessRecord.unrestricted(id(330), id(331))),
            migrated.accessRecords());

        String upgraded = Files.readString(stateFile);
        assertTrue(upgraded.contains("\"schema\": 6"));
        assertFalse(upgraded.contains("\"mode\""));
        assertEquals(migrated, new DimensionalDoorRepository(stateFile).load());
    }

    @Test
    void legacyBlacklistContainingTheOwnerNeverLocksTheOwnerOut() throws Exception {
        Path stateFile = temporaryDirectory.resolve("owner-blacklist-state.json");
        Files.writeString(stateFile, """
            {
              "schema": 3,
              "nextPocketSlot": 0,
              "pairs": [],
              "endpoints": [],
              "spaces": [],
              "returnTickets": [],
              "access": [
                {"itemId": "%s", "mode": "BLACKLIST", "ownerId": "%s", "players": ["%s", "%s"]}
              ]
            }
            """.formatted(id(340), id(341), id(341), id(342)));

        DoorStoreSnapshot migrated = new DimensionalDoorRepository(stateFile).load();

        DoorAccessRecord record = migrated.accessRecords().get(0);
        assertEquals(DoorAccessState.BLACKLIST, record.stateOf(id(341)));
        assertTrue(DoorAccessPolicy.canUse(record, id(341), false));
        assertFalse(DoorAccessPolicy.canUse(record, id(342), false));
    }

    @Test
    void doorFormsAndOpenStatesRoundTripThroughTheStateFile() throws Exception {
        Path stateFile = temporaryDirectory.resolve("trapdoor-state.json");
        PlacedDoorEndpoint closedTrapdoor = new PlacedDoorEndpoint(
            new DoorPosition(id(400), "minecraft:overworld", 1, 64, 2),
            DoorItemIdentity.publicDoor(id(401), DoorForm.TRAPDOOR),
            DoorOpenState.CLOSED
        );
        PlacedDoorEndpoint closedDoor = new PlacedDoorEndpoint(
            new DoorPosition(id(400), "minecraft:overworld", 4, 64, 2),
            DoorItemIdentity.personal(id(402)),
            DoorOpenState.CLOSED
        );
        DoorStoreSnapshot expected = new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA, 0, List.of(), List.of(closedTrapdoor, closedDoor),
            List.of(), List.of(), List.of()
        );

        new DimensionalDoorRepository(stateFile).save(expected);
        DoorStoreSnapshot actual = new DimensionalDoorRepository(stateFile).load();

        assertEquals(expected, actual);
        assertEquals(DoorForm.TRAPDOOR, actual.endpoints().get(0).identity().form());
        assertEquals(DoorOpenState.CLOSED, actual.endpoints().get(0).openState());
        assertEquals(DoorForm.DOOR, actual.endpoints().get(1).identity().form());
        assertEquals(DoorOpenState.CLOSED, actual.endpoints().get(1).openState());
        String encoded = Files.readString(stateFile);
        assertTrue(encoded.contains("\"form\": \"TRAPDOOR\""));
        assertTrue(encoded.contains("\"form\": \"DOOR\""));
        assertTrue(encoded.contains("\"openState\": \"CLOSED\""));
        assertFalse(encoded.contains("activeWhenOpen"));
    }

    @Test
    void preTrapdoorEndpointsMigrateToDoorFormAndOpenStateExactlyOnce() throws Exception {
        Path stateFile = temporaryDirectory.resolve("pre-trapdoor-state.json");
        Files.writeString(stateFile, """
            {
              "schema": 4,
              "nextPocketSlot": 0,
              "pairs": [],
              "endpoints": [{
                "worldId": "%s",
                "worldKey": "minecraft:overworld",
                "x": 1,
                "y": 64,
                "z": 2,
                "item": {
                  "itemId": "%s",
                  "kind": "PUBLIC"
                }
              }],
              "spaces": [],
              "returnTickets": [],
              "access": []
            }
            """.formatted(id(410), id(411)));

        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);
        DoorStoreSnapshot migrated = repository.load();

        assertEquals(DoorStoreSnapshot.CURRENT_SCHEMA, migrated.schema());
        assertEquals(DoorForm.DOOR, migrated.endpoints().get(0).identity().form());
        assertEquals(DoorOpenState.OPEN, migrated.endpoints().get(0).openState());

        String upgraded = Files.readString(stateFile);
        assertTrue(upgraded.contains("\"schema\": 6"));
        assertTrue(upgraded.contains("\"form\": \"DOOR\""));
        assertTrue(upgraded.contains("\"openState\": \"OPEN\""));

        assertEquals(migrated, new DimensionalDoorRepository(stateFile).load());
        assertEquals(upgraded, Files.readString(stateFile), "an already migrated file must not be rewritten");
    }

    @Test
    void schemaFivePolarityMigratesToOpenState() throws Exception {
        Path stateFile = temporaryDirectory.resolve("legacy-polarity-state.json");
        Files.writeString(stateFile, endpointState(
            5,
            "\"form\": \"TRAPDOOR\",",
            "\"activeWhenOpen\": false,"));

        DoorStoreSnapshot migrated = new DimensionalDoorRepository(stateFile).load();

        assertEquals(DoorOpenState.CLOSED, migrated.endpoints().get(0).openState());
        String upgraded = Files.readString(stateFile);
        assertTrue(upgraded.contains("\"schema\": 6"));
        assertTrue(upgraded.contains("\"openState\": \"CLOSED\""));
        assertFalse(upgraded.contains("activeWhenOpen"));
    }

    @Test
    void corruptedFormAndOpenStateValuesFailTheLoadInsteadOfDefaultingSilently() throws Exception {
        Path unknownForm = temporaryDirectory.resolve("unknown-form-state.json");
        Files.writeString(unknownForm, endpointState(6, "\"form\": \"HATCH\",", "\"openState\": \"OPEN\","));
        assertThrows(IOException.class, () -> new DimensionalDoorRepository(unknownForm).load());

        Path missingForm = temporaryDirectory.resolve("missing-form-state.json");
        Files.writeString(missingForm, endpointState(6, "", "\"openState\": \"OPEN\","));
        assertThrows(IOException.class, () -> new DimensionalDoorRepository(missingForm).load());

        Path missingOpenState = temporaryDirectory.resolve("missing-open-state.json");
        Files.writeString(missingOpenState, endpointState(6, "\"form\": \"TRAPDOOR\",", ""));
        assertThrows(IOException.class, () -> new DimensionalDoorRepository(missingOpenState).load());

        Path corruptOpenState = temporaryDirectory.resolve("corrupt-open-state.json");
        Files.writeString(corruptOpenState, endpointState(
            6,
            "\"form\": \"TRAPDOOR\",",
            "\"openState\": \"SIDEWAYS\","));
        assertThrows(IOException.class, () -> new DimensionalDoorRepository(corruptOpenState).load());
    }

    @Test
    void corruptLegacyPolarityStillFailsMigration() throws Exception {
        Path corrupt = temporaryDirectory.resolve("corrupt-legacy-polarity-state.json");
        Files.writeString(corrupt, endpointState(
            5,
            "\"form\": \"DOOR\",",
            "\"activeWhenOpen\": \"maybe\","));

        assertThrows(IOException.class, () -> new DimensionalDoorRepository(corrupt).load());
    }

    private static String endpointState(int schema, String formEntry, String openStateEntry) {
        return """
            {
              "schema": %d,
              "nextPocketSlot": 0,
              "pairs": [],
              "endpoints": [{
                "worldId": "%s",
                "worldKey": "minecraft:overworld",
                "x": 1,
                "y": 64,
                "z": 2,
                %s
                "item": {
                  "itemId": "%s",
                  %s
                  "kind": "PUBLIC"
                }
              }],
              "spaces": [],
              "returnTickets": [],
              "access": []
            }
            """.formatted(schema, id(420), openStateEntry, id(421), formEntry);
    }

    @Test
    void corruptedAccessStatesFailTheLoadInsteadOfSilentlyUnlockingTheDoor() throws Exception {
        Path stateFile = temporaryDirectory.resolve("corrupt-access-state.json");
        Files.writeString(stateFile, """
            {
              "schema": 4,
              "nextPocketSlot": 0,
              "pairs": [],
              "endpoints": [],
              "spaces": [],
              "returnTickets": [],
              "access": [
                {"itemId": "%s", "ownerId": "%s", "players": [
                  {"id": "%s", "state": "WHITLIST"}
                ]}
              ]
            }
            """.formatted(id(350), id(351), id(352)));

        assertThrows(IOException.class, () -> new DimensionalDoorRepository(stateFile).load());
    }

    @Test
    void snapshotRejectsDuplicateAccessRecordsForOneDoorItem() {
        DoorAccessRecord first = DoorAccessRecord.unrestricted(id(140), id(141));
        DoorAccessRecord second = DoorAccessRecord.unrestricted(id(140), id(142));

        assertThrows(IllegalArgumentException.class, () -> new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA, 0, List.of(), List.of(), List.of(), List.of(), List.of(first, second)
        ));
    }

    @Test
    void returnTicketApisReplacePersistAndRemovePerPlayer() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);
        ReturnTicket first = ticket(id(20), id(21));
        ReturnTicket replacement = new ReturnTicket(
            first.playerId(), id(22), id(23), "minecraft:the_nether", 9.5, 75, -4.5, 180, -10
        );

        repository.putReturnTicket(first);
        assertFalse(Files.exists(stateFile), "ticket writes must not rewrite the complete door-state file");
        assertEquals(first, repository.getReturnTicket(first.playerId()).orElseThrow());
        repository.putReturnTicket(replacement);
        assertFalse(Files.exists(stateFile), "ticket replacements must remain isolated per player");
        assertEquals(replacement, repository.getReturnTicket(first.playerId()).orElseThrow());
        assertEquals(1, repository.load().returnTickets().size());
        assertEquals(
            replacement,
            new DimensionalDoorRepository(stateFile).getReturnTicket(first.playerId()).orElseThrow()
        );

        assertEquals(replacement, repository.removeReturnTicket(first.playerId()).orElseThrow());
        assertTrue(repository.getReturnTicket(first.playerId()).isEmpty());
        assertTrue(new DimensionalDoorRepository(stateFile).load().returnTickets().isEmpty());
        assertTrue(repository.removeReturnTicket(first.playerId()).isEmpty());
    }

    @Test
    void embeddedReturnTicketsMigrateToIsolatedFilesOnLoad() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        ReturnTicket ticket = ticket(id(24), id(25));
        Files.writeString(stateFile, """
            {
              "schema": 3,
              "nextPocketSlot": 0,
              "pairs": [],
              "endpoints": [],
              "spaces": [],
              "returnTickets": [{
                "playerId": "%s",
                "sourceEndpointId": "%s",
                "sourceWorldId": "%s",
                "sourceWorldKey": "%s",
                "x": %s,
                "y": %s,
                "z": %s,
                "yaw": %s,
                "pitch": %s
              }]
            }
            """.formatted(
                ticket.playerId(),
                ticket.sourceEndpointId(),
                ticket.sourceWorldId(),
                ticket.sourceWorldKey(),
                ticket.x(),
                ticket.y(),
                ticket.z(),
                ticket.yaw(),
                ticket.pitch()
            ));

        DoorStoreSnapshot migrated = new DimensionalDoorRepository(stateFile).load();

        assertEquals(List.of(ticket), migrated.returnTickets());
        assertTrue(Files.readString(stateFile).contains("\"returnTickets\": []"));
        assertTrue(Files.isRegularFile(
            stateFile.resolveSibling("state.json.tickets").resolve(ticket.playerId() + ".json")
        ));
    }

    @Test
    void successiveAtomicSavesReplaceWholeSnapshot() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);
        repository.save(DoorStoreSnapshot.empty().withReturnTicket(ticket(id(30), id(31))));
        repository.save(DoorStoreSnapshot.empty());

        assertEquals(DoorStoreSnapshot.empty(), new DimensionalDoorRepository(stateFile).load());
    }

    @Test
    void malformedAndUnsupportedFilesFailLoudly() throws Exception {
        Path malformed = temporaryDirectory.resolve("malformed.json");
        Files.writeString(malformed, "{ definitely not json");
        assertThrows(IOException.class, () -> new DimensionalDoorRepository(malformed).load());

        Path unsupported = temporaryDirectory.resolve("unsupported.json");
        Files.writeString(unsupported, """
            {"schema": 999, "nextPocketSlot": 0, "pairs": [], "endpoints": [], "spaces": [], "returnTickets": []}
            """);
        assertThrows(IOException.class, () -> new DimensionalDoorRepository(unsupported).load());
    }

    @Test
    void malformedStateCanRecoverItsRetiredPocketSlot() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        Files.writeString(stateFile, "{\"nextPocketSlot\": 37, broken");

        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);

        assertThrows(IOException.class, repository::load);
        assertEquals(37L, repository.recoverNextPocketSlot());
    }

    @Test
    void recoveryNeverRegressesBelowTheHighestAllocatedSlot() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        Files.writeString(
            stateFile,
            "{\"nextPocketSlot\":1,\"spaces\":[{\"slot\":4},{\"slot\":9}], broken");

        assertEquals(10L, new DimensionalDoorRepository(stateFile).recoverNextPocketSlot());
    }

    @Test
    void unrecoverableStateFailsWithoutDiscardingSlotHistory() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        Files.writeString(stateFile, "not dimensional door state");

        assertThrows(
            IOException.class,
            () -> new DimensionalDoorRepository(stateFile).recoverNextPocketSlot());
    }

    @Test
    void snapshotRejectsCrossRecordIdentityAndAllocationCorruption() {
        DoorPairIdentity pair = new DoorPairIdentity(id(40), id(41), id(42));
        PlacedDoorEndpoint forged = new PlacedDoorEndpoint(
            new DoorPosition(id(43), "minecraft:overworld", 0, 64, 0),
            DoorItemIdentity.paired(id(44), pair.pairId(), PairEndpoint.A)
        );
        assertThrows(IllegalArgumentException.class, () -> new DoorStoreSnapshot(
            1, 0, List.of(pair), List.of(forged), List.of(), List.of(), List.of()
        ));

        PocketBinding binding = PocketBinding.personal(id(45));
        PocketSpace space = new PocketSpace(id(46), binding, 0, 0, 128, 0);
        assertThrows(IllegalArgumentException.class, () -> new DoorStoreSnapshot(
            1, 0, List.of(), List.of(), List.of(space), List.of(), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new DoorStoreSnapshot(
            1, 1, List.of(), List.of(), List.of(space), List.of(ticket(id(47), id(48)), ticket(id(47), id(49))), List.of()
        ));
    }

    @Test
    void snapshotDefensivelyCopiesListsAndTicketsValidateCoordinates() {
        ArrayList<ReturnTicket> mutableTickets = new ArrayList<>();
        ArrayList<DoorAccessRecord> mutableAccess = new ArrayList<>();
        DoorStoreSnapshot snapshot = new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA, 0, List.of(), List.of(), List.of(), mutableTickets, mutableAccess);
        mutableTickets.add(ticket(id(50), id(51)));
        mutableAccess.add(DoorAccessRecord.unrestricted(id(57), id(58)));

        assertTrue(snapshot.returnTickets().isEmpty());
        assertTrue(snapshot.accessRecords().isEmpty());
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.returnTickets().add(ticket(id(52), id(53))));
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.accessRecords().add(DoorAccessRecord.unrestricted(id(59), id(60))));
        assertThrows(IllegalArgumentException.class,
            () -> new ReturnTicket(id(54), id(55), id(56), "minecraft:overworld", Double.NaN, 0, 0, 0, 0));
    }

    private static ReturnTicket ticket(UUID playerId, UUID sourceEndpointId) {
        return new ReturnTicket(playerId, sourceEndpointId, id(100), "minecraft:overworld", 1.25, 65, -2.75, 90, 5);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
