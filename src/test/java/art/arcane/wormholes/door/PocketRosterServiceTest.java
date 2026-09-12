package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketRosterServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aPersonalPocketIsOwnedByTheTravelerItIsBoundTo() {
        UUID traveler = new UUID(0, 960);
        PocketSpace space = space(PocketBinding.personal(traveler));

        assertEquals(Optional.of(traveler), PocketRosterService.ownerOf(space, itemId -> Optional.empty()));
    }

    @Test
    void aPublicPocketIsOwnedByWhoeverOwnsItsDoor() {
        UUID doorItemId = new UUID(0, 961);
        UUID doorOwner = new UUID(0, 962);
        PocketSpace space = space(PocketBinding.publicDoor(doorItemId));

        assertEquals(Optional.of(doorOwner), PocketRosterService.ownerOf(space, itemId ->
            itemId.equals(doorItemId)
                ? Optional.of(DoorAccessRecord.unrestricted(doorItemId, doorOwner))
                : Optional.empty()));
        assertEquals(Optional.empty(), PocketRosterService.ownerOf(space, itemId -> Optional.empty()),
            "a door whose access record is gone has no owner to grant");
    }

    @Test
    void theOwnerOutranksWhateverTheRosterSaysAndStrangersAreVisitors() {
        UUID owner = new UUID(0, 970);
        UUID builder = new UUID(0, 971);
        UUID stranger = new UUID(0, 972);
        PocketSpace space = space(PocketBinding.personal(owner))
            .withRoster(PocketRoster.empty()
                .with(builder, PocketRole.BUILDER)
                .with(owner, PocketRole.VISITOR));

        assertEquals(PocketRole.OWNER, PocketRosterService.roleOf(space, owner, owner));
        assertEquals(PocketRole.BUILDER, PocketRosterService.roleOf(space, owner, builder));
        assertEquals(PocketRole.VISITOR, PocketRosterService.roleOf(space, owner, stranger));
        assertEquals(PocketRole.VISITOR, PocketRosterService.roleOf(space, null, stranger));
    }

    @Test
    void rosterChangesPersistAndSurviveRestart() throws IOException {
        DoorStateService state = DoorStateService.under(temporaryDirectory);
        UUID owner = new UUID(0, 980);
        UUID builder = new UUID(0, 981);
        PocketSpace allocated = state.getOrAllocatePocket(PocketBinding.personal(owner));
        PocketRosterService roster = new PocketRosterService(() -> state, itemId -> Optional.empty());

        assertTrue(roster.assign(allocated.spaceId(), builder, PocketRole.BUILDER));
        assertFalse(roster.assign(allocated.spaceId(), builder, PocketRole.BUILDER), "an unchanged role is not a write");

        DoorStateService restarted = DoorStateService.load(
            new DimensionalDoorRepository(state.repository().stateFile()));
        PocketSpace reloaded = restarted.findPocketById(allocated.spaceId()).orElseThrow();
        assertEquals(PocketRole.BUILDER, reloaded.roster().role(builder));

        PocketRosterService restartedRoster = new PocketRosterService(() -> restarted, itemId -> Optional.empty());
        assertTrue(restartedRoster.remove(allocated.spaceId(), builder));
        assertFalse(restartedRoster.remove(allocated.spaceId(), builder));
        assertEquals(PocketRole.VISITOR,
            DoorStateService.load(new DimensionalDoorRepository(state.repository().stateFile()))
                .findPocketById(allocated.spaceId()).orElseThrow().roster().role(builder));
    }

    @Test
    void anUnknownPocketIsNeverWrittenTo() throws IOException {
        DoorStateService state = DoorStateService.under(temporaryDirectory);
        PocketRosterService roster = new PocketRosterService(() -> state, itemId -> Optional.empty());

        assertFalse(roster.assign(new UUID(0, 990), new UUID(0, 991), PocketRole.BUILDER));
        assertFalse(roster.remove(new UUID(0, 990), new UUID(0, 991)));
    }

    private static PocketSpace space(PocketBinding binding) {
        return new PocketSpace(
            PocketAllocator.spaceIdFor(binding), binding, 0L,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketAllocator.DEFAULT_CENTER_Y,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketShell.defaults());
    }
}
