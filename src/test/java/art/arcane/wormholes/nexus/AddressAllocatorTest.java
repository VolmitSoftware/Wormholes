package art.arcane.wormholes.nexus;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressAllocatorTest {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Test
    void allocatedAddressUsesOnlyAlphabetCharactersAtTheRequestedLength() {
        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), "hub", UUID.randomUUID());
        String address = AddressAllocator.next(network, ALPHABET, 4, new Random(7L));

        assertEquals(4, address.length());
        for (int index = 0; index < address.length(); index++) {
            assertTrue(ALPHABET.indexOf(address.charAt(index)) >= 0, "unexpected character " + address.charAt(index));
        }
    }

    @Test
    void allocatorNeverReturnsAnAddressTheNetworkAlreadyUses() {
        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), "hub", UUID.randomUUID());
        Set<String> issued = new HashSet<>();
        Random random = new Random(11L);
        for (int index = 0; index < 200; index++) {
            String address = AddressAllocator.next(network, ALPHABET, 4, random);
            assertTrue(issued.add(address), "reissued " + address);
            network = network.withMember(UUID.randomUUID(), new NetworkMember(UUID.randomUUID(), address, "", 0L, null));
        }
        assertEquals(200, issued.size());
    }

    @Test
    void allocatorGrowsTheAddressWhenTheAlphabetSpaceAtThatLengthIsFull() {
        Map<UUID, NetworkMember> members = new LinkedHashMap<>();
        for (String taken : new String[] {"A", "B"}) {
            UUID portalId = UUID.randomUUID();
            members.put(portalId, new NetworkMember(portalId, taken, "", 0L, null));
        }
        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), "tiny", UUID.randomUUID())
                .withMembers(members);

        String address = AddressAllocator.next(network, "AB", 1, new Random(3L));

        assertTrue(address.length() > 1, "allocator returned a colliding length");
        assertFalse(network.usesAddress(address));
        for (int index = 0; index < address.length(); index++) {
            assertTrue("AB".indexOf(address.charAt(index)) >= 0);
        }
    }

    @Test
    void addressesAreNormalizedToUpperCaseAndCompareCaseInsensitively() {
        UUID portalId = UUID.randomUUID();
        PortalNetwork network = PortalNetwork.create(UUID.randomUUID(), "hub", UUID.randomUUID())
                .withMember(portalId, new NetworkMember(portalId, "ab12", "", 0L, null));

        assertEquals("AB12", network.member(portalId).address());
        assertTrue(network.usesAddress("Ab12"));
        assertEquals(portalId, network.portalIdAt("aB12"));
    }
}
