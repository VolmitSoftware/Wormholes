package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorIdentityTest {
    @Test
    void pairedKitMintsDistinctMatchingEndpoints() {
        DoorPairIdentity pair = new DoorPairIdentity(id(1), id(2), id(3));

        DoorItemIdentity endpointA = pair.endpoint(PairEndpoint.A);
        DoorItemIdentity endpointB = pair.endpoint(PairEndpoint.B);

        assertEquals(DoorKind.PAIR, endpointA.kind());
        assertEquals(pair.pairId(), endpointA.pairId());
        assertEquals(PairEndpoint.A, endpointA.pairEndpoint());
        assertEquals(PairEndpoint.B, endpointA.pairEndpoint().other());
        assertNotEquals(endpointA.itemId(), endpointB.itemId());
        assertTrue(pair.contains(endpointA.itemId()));
        assertTrue(pair.contains(endpointB.itemId()));
    }

    @Test
    void pairedDestinationIsAlwaysTheOtherEndpoint() {
        DoorPairIdentity pair = new DoorPairIdentity(id(10), id(11), id(12));

        assertEquals(
            new PairedDoorDestination(pair.pairId(), PairEndpoint.B),
            DoorDestinationResolver.resolve(pair.endpoint(PairEndpoint.A), id(13))
        );
        assertEquals(
            new PairedDoorDestination(pair.pairId(), PairEndpoint.A),
            DoorDestinationResolver.resolve(pair.endpoint(PairEndpoint.B), id(13))
        );
    }

    @Test
    void personalDestinationDependsOnTravelerNotDoorItem() {
        UUID traveler = id(20);
        DoorDestination firstDoor = DoorDestinationResolver.resolve(DoorItemIdentity.personal(id(21)), traveler);
        DoorDestination secondDoor = DoorDestinationResolver.resolve(DoorItemIdentity.personal(id(22)), traveler);
        DoorDestination anotherTraveler = DoorDestinationResolver.resolve(DoorItemIdentity.personal(id(21)), id(23));

        assertEquals(new PocketDoorDestination(PocketBinding.personal(traveler)), firstDoor);
        assertEquals(firstDoor, secondDoor);
        assertNotEquals(firstDoor, anotherTraveler);
    }

    @Test
    void publicDestinationDependsOnImmutableItemIdentity() {
        DoorItemIdentity publicDoor = DoorItemIdentity.publicDoor(id(30));

        DoorDestination beforeMoving = DoorDestinationResolver.resolve(publicDoor, id(31));
        DoorDestination afterMoving = DoorDestinationResolver.resolve(publicDoor, id(32));
        DoorDestination separatelyCrafted = DoorDestinationResolver.resolve(DoorItemIdentity.publicDoor(id(33)), id(31));

        assertEquals(new PocketDoorDestination(PocketBinding.publicDoor(publicDoor.itemId())), beforeMoving);
        assertEquals(beforeMoving, afterMoving, "traveler must not affect a public door's pocket");
        assertNotEquals(beforeMoving, separatelyCrafted);
    }

    @Test
    void returnDestinationCarriesSpaceAndTraveler() {
        DoorItemIdentity exit = DoorItemIdentity.returnDoor(id(40), id(41));
        assertEquals(
            new ReturnDoorDestination(id(41), id(42)),
            DoorDestinationResolver.resolve(exit, id(42))
        );
    }

    @Test
    void malformedIdentityCombinationsAreRejected() {
        assertThrows(NullPointerException.class,
            () -> DoorItemIdentity.paired(id(50), null, PairEndpoint.A));
        assertThrows(NullPointerException.class,
            () -> DoorItemIdentity.paired(id(50), id(51), null));
        assertThrows(IllegalArgumentException.class,
            () -> new DoorItemIdentity(id(50), DoorKind.PUBLIC, id(51), null, null));
        assertThrows(NullPointerException.class,
            () -> new DoorItemIdentity(id(50), DoorKind.RETURN, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new DoorPairIdentity(id(50), id(51), id(51)));
    }

    @Test
    void everyIdentityDefaultsToTheDoorForm() {
        assertEquals(DoorForm.DOOR, DoorItemIdentity.personal(id(60)).form());
        assertEquals(DoorForm.DOOR, DoorItemIdentity.publicDoor(id(61)).form());
        assertEquals(DoorForm.DOOR, DoorItemIdentity.paired(id(62), id(63), PairEndpoint.A).form());
        assertEquals(DoorForm.DOOR, DoorItemIdentity.returnDoor(id(64), id(65)).form());
        assertEquals(DoorForm.DOOR, new DoorItemIdentity(id(66), DoorKind.PUBLIC, null, null, null).form());
        assertFalse(DoorItemIdentity.publicDoor(id(67)).isTrapdoor());
    }

    @Test
    void trapdoorFormIsCarriedByEveryPlaceableKind() {
        assertEquals(DoorForm.TRAPDOOR, DoorItemIdentity.personal(id(70), DoorForm.TRAPDOOR).form());
        assertEquals(DoorForm.TRAPDOOR, DoorItemIdentity.publicDoor(id(71), DoorForm.TRAPDOOR).form());
        assertEquals(
            DoorForm.TRAPDOOR,
            DoorItemIdentity.paired(id(72), id(73), PairEndpoint.B, DoorForm.TRAPDOOR).form());
        assertTrue(DoorItemIdentity.newPersonal(DoorForm.TRAPDOOR).isTrapdoor());
        assertTrue(DoorItemIdentity.newPublic(DoorForm.TRAPDOOR).isTrapdoor());
    }

    @Test
    void returnDoorsCanNeverBeTrapdoors() {
        assertEquals(DoorForm.DOOR, DoorItemIdentity.newReturn(id(80)).form());
        assertThrows(IllegalArgumentException.class,
            () -> new DoorItemIdentity(id(81), DoorKind.RETURN, DoorForm.TRAPDOOR, null, null, id(82)));
        assertThrows(NullPointerException.class,
            () -> new DoorItemIdentity(id(83), DoorKind.PUBLIC, null, null, null, null));
    }

    @Test
    void formIsPartOfIdentityEquality() {
        assertNotEquals(
            DoorItemIdentity.personal(id(90)),
            DoorItemIdentity.personal(id(90), DoorForm.TRAPDOOR));
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
