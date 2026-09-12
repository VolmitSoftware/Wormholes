package art.arcane.wormholes.nexus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialMenuModelTest {
    @Test
    void dialableMembersComeBackInAddressOrderWithoutThePortalItself() {
        UUID self = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        UUID alpha = UUID.randomUUID();
        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), "ring", UUID.randomUUID())
                .withMember(self, new NetworkMember(self, "MMMM", "", 0L, null))
                .withMember(beta, new NetworkMember(beta, "ZZZZ", "", 0L, null))
                .withMember(alpha, new NetworkMember(alpha, "AAAA", "", 0L, null));

        List<NetworkMember> dialable = DialMenuModel.dialable(network, self);

        assertEquals(List.of("AAAA", "ZZZZ"), dialable.stream().map(NetworkMember::address).toList());
    }

    @Test
    void anEmptyOrUnknownNetworkDialsNothing() {
        assertTrue(DialMenuModel.dialable(null, UUID.randomUUID()).isEmpty());
        assertTrue(DialMenuModel.dialable(PortalNetwork.create(UUID.randomUUID(), "empty", null), UUID.randomUUID())
                .isEmpty());
    }

    @Test
    void pagingSplitsMembersIntoFortyFiveSlotPagesAndClampsOutOfRangePages() {
        assertEquals(1, DialMenuModel.pageCount(0));
        assertEquals(1, DialMenuModel.pageCount(45));
        assertEquals(2, DialMenuModel.pageCount(46));
        assertEquals(3, DialMenuModel.pageCount(91));

        assertEquals(0, DialMenuModel.clampPage(-4, 3));
        assertEquals(2, DialMenuModel.clampPage(9, 3));
        assertEquals(1, DialMenuModel.clampPage(1, 3));

        assertEquals(45, DialMenuModel.pageStart(1));
        assertEquals(50, DialMenuModel.pageEnd(50, 1));
        assertEquals(90, DialMenuModel.pageEnd(120, 1));
    }

    @Test
    void theCurrentAddressIsHighlightedAndItsPageIsTheOneTheMenuOpensOn() {
        List<NetworkMember> members = members(60);

        assertEquals(3, DialMenuModel.indexOf(members, "A003"));
        assertEquals(0, DialMenuModel.pageOf(DialMenuModel.indexOf(members, "A003")));
        assertEquals(1, DialMenuModel.pageOf(DialMenuModel.indexOf(members, "A050")));

        assertTrue(DialMenuModel.isCurrent(members.get(3), "a003"));
        assertFalse(DialMenuModel.isCurrent(members.get(3), "A004"));
        assertFalse(DialMenuModel.isCurrent(members.get(3), ""));
    }

    @Test
    void anAddressThatIsNotOnTheNetworkHasNoIndexAndOpensTheFirstPage() {
        List<NetworkMember> members = members(10);

        assertEquals(-1, DialMenuModel.indexOf(members, "ZZZZ"));
        assertEquals(-1, DialMenuModel.indexOf(members, ""));
        assertEquals(0, DialMenuModel.pageOf(-1));
    }

    @Test
    void scrollingTheHotbarStepsOneAddressInTheDirectionTheWheelTurned() {
        assertEquals(1, DialGestures.scrollDirection(0, 1));
        assertEquals(-1, DialGestures.scrollDirection(1, 0));
        assertEquals(1, DialGestures.scrollDirection(8, 0), "scrolling past the end still steps forward");
        assertEquals(-1, DialGestures.scrollDirection(0, 8), "scrolling past the start still steps back");
    }

    private static List<NetworkMember> members(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new NetworkMember(UUID.randomUUID(), String.format("A%03d", index), "", 0L, null))
                .toList();
    }
}
