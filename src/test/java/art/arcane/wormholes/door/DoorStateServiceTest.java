package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorStateServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsAndIndexesCompletePersistedSnapshot() throws Exception {
        DoorPairIdentity pair = pair(1);
        PlacedDoorEndpoint endpointA = placed(id(10), "minecraft:overworld", 1, 64, 2, pair.endpoint(PairEndpoint.A));
        PocketAllocator allocator = new PocketAllocator();
        PocketSpace pocket = allocator.getOrAllocate(PocketBinding.personal(id(11)));
        ReturnTicket ticket = ticket(id(12), endpointA.identity().itemId());
        DoorAccessRecord access = DoorAccessRecord.unrestricted(endpointA.identity().itemId(), id(13))
            .withPlayerState(id(14), DoorAccessState.BLACKLIST);
        DoorStoreSnapshot persisted = new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA,
            allocator.nextSlot(),
            List.of(pair),
            List.of(endpointA),
            List.of(pocket),
            List.of(ticket),
            List.of(access)
        );
        DimensionalDoorRepository repository = repository();
        repository.save(persisted);

        DoorStateService service = DoorStateService.load(new DimensionalDoorRepository(repository.stateFile()));

        assertEquals(pair, service.findPair(pair.pairId()).orElseThrow());
        assertEquals(endpointA, service.findEndpoint(endpointA.position()).orElseThrow());
        assertEquals(endpointA, service.findEndpointByItem(endpointA.identity().itemId()).orElseThrow());
        assertEquals(pocket, service.findPocket(pocket.binding()).orElseThrow());
        assertEquals(ticket, service.getReturnTicket(ticket.playerId()).orElseThrow());
        assertEquals(access, service.accessRecord(access.itemId()).orElseThrow());
        assertTrue(service.accessRecord(id(15)).isEmpty());
        assertEquals(persisted, service.snapshot());
    }

    @Test
    void pairAndEndpointMutationsPersistAsOneCompleteState() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorPairIdentity pair = pair(20);
        PlacedDoorEndpoint endpointA = placed(id(30), "minecraft:overworld", 0, 64, 0, pair.endpoint(PairEndpoint.A));
        PlacedDoorEndpoint endpointB = placed(id(31), "minecraft:the_nether", 5, 70, 5, pair.endpoint(PairEndpoint.B));

        assertTrue(service.registerPair(pair));
        assertFalse(service.registerPair(pair));
        assertTrue(service.registerEndpoint(endpointA));
        assertTrue(service.registerEndpoint(endpointB));
        assertEquals(endpointB, service.findMate(endpointA.identity()).orElseThrow());
        assertThrows(IllegalStateException.class, () -> service.removePair(pair.pairId()));

        assertEquals(endpointB, service.removeEndpoint(endpointB.position()).orElseThrow());
        assertEquals(endpointA, service.removeEndpoint(endpointA.position()).orElseThrow());
        assertEquals(pair, service.removePair(pair.pairId()).orElseThrow());

        DoorStateService restarted = DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()));
        assertTrue(restarted.pairs().isEmpty());
        assertTrue(restarted.endpoints().isEmpty());
        assertEquals(service.snapshot(), restarted.snapshot());
    }

    @Test
    void ownedRegistrationMintsOneAccessRecordThatSurvivesRePlacement() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.publicDoor(id(200));
        PlacedDoorEndpoint placement = placed(id(201), "minecraft:overworld", 0, 64, 0, identity);
        UUID owner = id(202);

        assertTrue(service.registerEndpoint(placement, owner));
        assertEquals(
            DoorAccessRecord.unrestricted(identity.itemId(), owner),
            service.accessRecord(identity.itemId()).orElseThrow());
        assertTrue(service.addAccessPlayer(identity.itemId(), id(205)));
        assertTrue(service.setAccessState(identity.itemId(), id(205), DoorAccessState.WHITELIST));
        assertFalse(service.registerEndpoint(placement, id(203)));

        assertEquals(placement, service.removeEndpoint(placement.position()).orElseThrow());
        PlacedDoorEndpoint rePlacement = placed(id(201), "minecraft:overworld", 9, 70, 9, identity);
        assertTrue(service.registerEndpoint(rePlacement, id(204)));

        DoorAccessRecord retained = service.accessRecord(identity.itemId()).orElseThrow();
        assertEquals(owner, retained.ownerId());
        assertEquals(DoorAccessState.WHITELIST, retained.stateOf(id(205)));
        assertEquals(1, service.snapshot().accessRecords().size());
    }

    @Test
    void returnDoorsNeverReceiveAnAccessRecord() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.returnDoor(id(210), id(211));
        PlacedDoorEndpoint placement = placed(id(212), "wormholes:pockets", 8, 128, 11, identity);

        assertTrue(service.registerEndpoint(placement, id(213)));

        assertTrue(service.accessRecord(identity.itemId()).isEmpty());
        assertTrue(service.snapshot().accessRecords().isEmpty());
    }

    @Test
    void aReplayedRegistrationNeverMintsAnAccessRecord() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.publicDoor(id(240));
        PlacedDoorEndpoint placement = placed(id(241), "minecraft:overworld", 5, 64, 5, identity);

        assertTrue(service.registerEndpoint(placement));
        assertFalse(service.registerEndpoint(placement, id(242)));

        assertTrue(service.accessRecord(identity.itemId()).isEmpty());
        assertTrue(service.snapshot().accessRecords().isEmpty());
    }

    @Test
    void aRolledBackPlacementCanDropTheRecordItMinted() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.publicDoor(id(250));
        PlacedDoorEndpoint placement = placed(id(251), "minecraft:overworld", 7, 64, 7, identity);

        assertTrue(service.registerEndpoint(placement, id(252)));
        assertEquals(placement, service.removeEndpoint(placement.position()).orElseThrow());
        assertTrue(service.removeAccessRecord(identity.itemId()));

        assertFalse(service.removeAccessRecord(identity.itemId()));
        assertTrue(service.accessRecord(identity.itemId()).isEmpty());
        assertTrue(DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()))
            .accessRecord(identity.itemId()).isEmpty());
    }

    @Test
    void aRePlacedDoorTakesTheOwnerOfWhoeverPlacedItAfterARollback() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.publicDoor(id(260));
        PlacedDoorEndpoint firstAttempt = placed(id(261), "minecraft:overworld", 2, 64, 2, identity);
        UUID first = id(262);
        UUID second = id(263);

        assertTrue(service.registerEndpoint(firstAttempt, first));
        service.removeEndpoint(firstAttempt.position());
        assertTrue(service.removeAccessRecord(identity.itemId()));

        PlacedDoorEndpoint secondAttempt = placed(id(261), "minecraft:overworld", 12, 70, 12, identity);
        assertTrue(service.registerEndpoint(secondAttempt, second));

        assertEquals(second, service.accessRecord(identity.itemId()).orElseThrow().ownerId());
    }

    @Test
    void accessMutationsRejectNoOpsAndPersistAcrossRestart() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.personal(id(220));
        PlacedDoorEndpoint placement = placed(id(221), "minecraft:overworld", 3, 64, 3, identity);
        UUID owner = id(222);
        UUID guest = id(223);
        UUID unknownItem = id(224);
        service.registerEndpoint(placement, owner);

        assertTrue(service.addAccessPlayer(identity.itemId(), guest));
        assertFalse(service.addAccessPlayer(identity.itemId(), guest));
        assertTrue(service.setAccessState(identity.itemId(), guest, DoorAccessState.WHITELIST));
        assertFalse(service.setAccessState(identity.itemId(), guest, DoorAccessState.WHITELIST));
        assertFalse(service.setAccessState(identity.itemId(), id(225), DoorAccessState.WHITELIST));
        assertFalse(service.removeAccessPlayer(identity.itemId(), id(225)));
        assertFalse(service.setAccessState(unknownItem, guest, DoorAccessState.BLACKLIST));
        assertFalse(service.addAccessPlayer(unknownItem, guest));
        assertFalse(service.removeAccessPlayer(unknownItem, guest));

        DoorStateService restarted = DoorStateService.load(
            new DimensionalDoorRepository(service.repository().stateFile()));
        assertEquals(
            DoorAccessRecord.unrestricted(identity.itemId(), owner)
                .withPlayerState(guest, DoorAccessState.WHITELIST),
            restarted.accessRecord(identity.itemId()).orElseThrow());
        assertEquals(service.snapshot(), restarted.snapshot());

        assertTrue(restarted.removeAccessPlayer(identity.itemId(), guest));
        assertTrue(DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()))
            .accessRecord(identity.itemId()).orElseThrow().players().isEmpty());
    }

    @Test
    void listedPlayersSurviveTheSaveAndReloadRoundTrip() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.publicDoor(id(240));
        PlacedDoorEndpoint placement = placed(id(241), "minecraft:overworld", 6, 64, 6, identity);
        UUID owner = id(242);
        UUID neutral = id(243);
        UUID whitelisted = id(244);
        UUID blacklisted = id(245);
        service.registerEndpoint(placement, owner);

        assertTrue(service.addAccessPlayer(identity.itemId(), neutral));
        assertTrue(service.addAccessPlayer(identity.itemId(), whitelisted));
        assertTrue(service.addAccessPlayer(identity.itemId(), blacklisted));
        assertTrue(service.setAccessState(identity.itemId(), whitelisted, DoorAccessState.WHITELIST));
        assertTrue(service.setAccessState(identity.itemId(), blacklisted, DoorAccessState.BLACKLIST));

        DoorAccessRecord reloaded = DoorStateService
            .load(new DimensionalDoorRepository(service.repository().stateFile()))
            .accessRecord(identity.itemId())
            .orElseThrow();

        assertEquals(List.of(neutral, whitelisted, blacklisted), reloaded.listedPlayers());
        assertEquals(DoorAccessState.NEUTRAL, reloaded.stateOf(neutral));
        assertEquals(DoorAccessState.WHITELIST, reloaded.stateOf(whitelisted));
        assertEquals(DoorAccessState.BLACKLIST, reloaded.stateOf(blacklisted));
        assertEquals(owner, reloaded.ownerId());
    }

    @Test
    void accessRecordsOutliveEndpointRemoval() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.publicDoor(id(230));
        PlacedDoorEndpoint placement = placed(id(231), "minecraft:overworld", 1, 64, 1, identity);
        service.registerEndpoint(placement, id(232));
        service.addAccessPlayer(identity.itemId(), id(233));
        service.setAccessState(identity.itemId(), id(233), DoorAccessState.BLACKLIST);

        assertEquals(placement, service.removeEndpoint(placement.position()).orElseThrow());

        assertTrue(service.endpoints().isEmpty());
        DoorAccessRecord expected = DoorAccessRecord.unrestricted(identity.itemId(), id(232))
            .withPlayerState(id(233), DoorAccessState.BLACKLIST);
        assertEquals(expected, service.accessRecord(identity.itemId()).orElseThrow());
        assertEquals(expected, DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()))
            .accessRecord(identity.itemId()).orElseThrow());
    }

    @Test
    void pairedEndpointCannotBeRegisteredBeforeItsPairIdentity() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorPairIdentity pair = pair(40);
        PlacedDoorEndpoint endpoint = placed(id(45), "minecraft:overworld", 0, 64, 0, pair.endpoint(PairEndpoint.A));

        assertThrows(IllegalArgumentException.class, () -> service.registerEndpoint(endpoint));
        assertTrue(service.endpoints().isEmpty());
        assertEquals(DoorStoreSnapshot.empty(), service.snapshot());
    }

    @Test
    void personalAndPublicPocketsResolveAndSurviveRestart() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity personalA = DoorItemIdentity.personal(id(50));
        DoorItemIdentity personalB = DoorItemIdentity.personal(id(51));
        DoorItemIdentity publicDoor = DoorItemIdentity.publicDoor(id(52));
        UUID traveler = id(53);

        PocketSpace personal = service.getOrAllocatePocket(personalA, traveler);
        assertSame(personal, service.getOrAllocatePocket(personalB, traveler));
        PocketSpace publicPocket = service.getOrAllocatePocket(publicDoor, id(54));
        assertSame(publicPocket, service.getOrAllocatePocket(publicDoor, id(55)));
        assertEquals(0, personal.slot());
        assertEquals(1, publicPocket.slot());
        assertThrows(IllegalArgumentException.class,
            () -> service.getOrAllocatePocket(pair(56).endpoint(PairEndpoint.A), traveler));

        DoorStateService restarted = DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()));
        assertEquals(personal, restarted.getOrAllocatePocket(personalA, traveler));
        assertEquals(publicPocket, restarted.getOrAllocatePocket(publicDoor, id(57)));
        assertEquals(2, restarted.snapshot().nextPocketSlot());
    }

    @Test
    void allocatorRestoresMonotonicNextSlotAndNeverFillsOldGaps() throws Exception {
        PocketBinding existingBinding = PocketBinding.personal(id(60));
        PocketSpace existing = new PocketSpace(
            PocketAllocator.spaceIdFor(existingBinding),
            existingBinding,
            0,
            PocketAllocator.CHUNK_CENTER_OFFSET,
            PocketAllocator.DEFAULT_CENTER_Y,
            PocketAllocator.CHUNK_CENTER_OFFSET
        );
        DoorStoreSnapshot stateWithRetiredGap = new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA, 4, List.of(), List.of(), List.of(existing), List.of(), List.of()
        );
        DimensionalDoorRepository repository = repository();
        repository.save(stateWithRetiredGap);
        DoorStateService service = DoorStateService.load(new DimensionalDoorRepository(repository.stateFile()));

        PocketSpace allocated = service.getOrAllocatePocket(PocketBinding.publicDoor(id(61)));

        assertEquals(4, allocated.slot());
        assertEquals(5, service.snapshot().nextPocketSlot());
        assertEquals(5,
            DoorStateService.load(new DimensionalDoorRepository(repository.stateFile())).snapshot().nextPocketSlot());
    }

    @Test
    void ticketMutationsReplacePerPlayerAndPersistAcrossRestart() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        ReturnTicket first = ticket(id(70), id(71));
        ReturnTicket replacement = new ReturnTicket(
            first.playerId(), id(72), id(73), "minecraft:the_end", 9, 80, -9, 180, 0
        );

        service.putReturnTicket(first);
        service.putReturnTicket(replacement);
        assertEquals(List.of(replacement), service.returnTickets());
        assertEquals(replacement,
            DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()))
                .getReturnTicket(first.playerId()).orElseThrow());

        assertEquals(replacement, service.removeReturnTicket(first.playerId()).orElseThrow());
        assertTrue(service.removeReturnTicket(first.playerId()).isEmpty());
        assertTrue(DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()))
            .returnTickets().isEmpty());
    }

    @Test
    void publishedCollectionsAreReadOnlySnapshots() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        List<DoorPairIdentity> before = service.pairs();
        service.registerPair(pair(80));

        assertTrue(before.isEmpty(), "earlier collection must not become a live view");
        assertThrows(UnsupportedOperationException.class, () -> service.pairs().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.endpoints().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.spaces().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.returnTickets().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.accessRecords().clear());
    }

    @Test
    void persistenceFailureDoesNotPublishCandidateMutation() throws Exception {
        Path parentThatIsAFile = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(parentThatIsAFile, "occupied");
        DimensionalDoorRepository repository = new DimensionalDoorRepository(parentThatIsAFile.resolve("state.json"));
        DoorStateService service = DoorStateService.load(repository);
        DoorPairIdentity pair = pair(90);

        assertThrows(IOException.class, () -> service.registerPair(pair));
        assertTrue(service.pairs().isEmpty());
        assertEquals(DoorStoreSnapshot.empty(), service.snapshot());
    }

    @Test
    void duplicateEndpointFailureLeavesExistingStateIntact() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        PlacedDoorEndpoint first = placed(id(100), "minecraft:overworld", 0, 64, 0, DoorItemIdentity.publicDoor(id(101)));
        PlacedDoorEndpoint conflicting = placed(id(100), "minecraft:overworld", 0, 64, 0, DoorItemIdentity.publicDoor(id(102)));
        service.registerEndpoint(first);

        assertThrows(IllegalStateException.class, () -> service.registerEndpoint(conflicting));
        assertEquals(List.of(first), service.endpoints());
        assertEquals(List.of(first),
            DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile())).endpoints());
    }

    @Test
    void returnEndpointRelocationIsAtomicAndPersistsAcrossRestart() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.returnDoor(id(110), id(111));
        PlacedDoorEndpoint previous = placed(id(112), "wormholes:pockets", 8, 128, 11, identity);
        PlacedDoorEndpoint replacement = placed(id(112), "wormholes:pockets", 15, 128, 31, identity);
        service.registerEndpoint(previous);

        assertTrue(service.relocateEndpoint(previous, replacement));
        assertTrue(service.findEndpoint(previous.position()).isEmpty());
        assertEquals(replacement, service.findEndpoint(replacement.position()).orElseThrow());
        assertEquals(replacement, service.findEndpointByItem(identity.itemId()).orElseThrow());

        DoorStateService restarted = DoorStateService.load(
            new DimensionalDoorRepository(service.repository().stateFile()));
        assertEquals(List.of(replacement), restarted.endpoints());
        assertFalse(restarted.relocateEndpoint(replacement, replacement));
    }

    @Test
    void failedEndpointRelocationLeavesTheRegisteredEndpointUntouched() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.returnDoor(id(120), id(121));
        PlacedDoorEndpoint previous = placed(id(122), "wormholes:pockets", 8, 128, 11, identity);
        PlacedDoorEndpoint occupied = placed(
            id(122), "wormholes:pockets", 15, 128, 31, DoorItemIdentity.publicDoor(id(123)));
        PlacedDoorEndpoint replacement = placed(id(122), "wormholes:pockets", 15, 128, 31, identity);
        service.registerEndpoint(previous);
        service.registerEndpoint(occupied);

        assertThrows(IllegalStateException.class, () -> service.relocateEndpoint(previous, replacement));
        assertThrows(IllegalArgumentException.class, () -> service.relocateEndpoint(previous, occupied));
        assertEquals(List.of(previous, occupied), service.endpoints());
        assertEquals(List.of(previous, occupied),
            DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile())).endpoints());
    }

    @Test
    void endpointOpenStatesPersistForBothDoorFormsAndSurviveRestart() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        PlacedDoorEndpoint door = placed(
            id(601), "minecraft:overworld", 2, 64, 3, DoorItemIdentity.publicDoor(id(600)));
        PlacedDoorEndpoint trapdoor = placed(
            id(603), "minecraft:overworld", 4, 64, 3,
            DoorItemIdentity.publicDoor(id(604), DoorForm.TRAPDOOR));
        service.registerEndpoint(door, id(602));
        service.registerEndpoint(trapdoor, id(602));

        assertFalse(service.setEndpointOpenState(door.position(), DoorOpenState.OPEN));
        assertTrue(service.setEndpointOpenState(door.position(), DoorOpenState.CLOSED));
        assertTrue(service.setEndpointOpenState(trapdoor.position(), DoorOpenState.CLOSED));

        DoorStateService restarted = DoorStateService.load(
            new DimensionalDoorRepository(service.repository().stateFile()));
        assertEquals(DoorOpenState.CLOSED, restarted.findEndpoint(door.position()).orElseThrow().openState());
        assertEquals(DoorOpenState.CLOSED, restarted.findEndpoint(trapdoor.position()).orElseThrow().openState());
        assertTrue(restarted.setEndpointOpenState(door.position(), DoorOpenState.OPEN));
        assertEquals(DoorOpenState.OPEN,
            DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()))
                .findEndpoint(door.position()).orElseThrow().openState());
    }

    @Test
    void emptyPositionsAndNullStatesAreRejectedWithoutMutation() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity identity = DoorItemIdentity.publicDoor(id(610));
        PlacedDoorEndpoint placement = placed(id(611), "minecraft:overworld", 0, 64, 0, identity);
        service.registerEndpoint(placement, id(612));

        assertFalse(service.setEndpointOpenState(
            new DoorPosition(id(613), "minecraft:overworld", 9, 64, 9), DoorOpenState.CLOSED));
        assertThrows(NullPointerException.class,
            () -> service.setEndpointOpenState(placement.position(), null));
        assertEquals(DoorOpenState.OPEN, service.findEndpoint(placement.position()).orElseThrow().openState());
    }

    private DimensionalDoorRepository repository() {
        return new DimensionalDoorRepository(temporaryDirectory.resolve("state.json"));
    }

    private static DoorPairIdentity pair(long base) {
        return new DoorPairIdentity(id(base), id(base + 1), id(base + 2));
    }

    private static PlacedDoorEndpoint placed(
        UUID worldId,
        String worldName,
        int x,
        int y,
        int z,
        DoorItemIdentity identity
    ) {
        return new PlacedDoorEndpoint(new DoorPosition(worldId, worldName, x, y, z), identity);
    }

    private static ReturnTicket ticket(UUID playerId, UUID endpointId) {
        return new ReturnTicket(playerId, endpointId, id(999), "minecraft:overworld", 1.5, 65, -2.5, 90, 5);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
