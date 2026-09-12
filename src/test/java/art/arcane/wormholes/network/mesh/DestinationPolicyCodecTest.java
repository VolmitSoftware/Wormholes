package art.arcane.wormholes.network.mesh;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestinationPolicyCodecTest {
    @Test
    void policyRoundTripsThroughItsJsonString() {
        UUID portalId = UUID.randomUUID();
        DestinationPolicy policy = new DestinationPolicy(List.of(
            new DestinationCandidate("beta", portalId, null, 2),
            new DestinationCandidate("gamma", null, "hub", 1)),
            SelectionStrategy.LEAST_LOADED, 2, 18.5D, false);

        String encoded = policy.encode();
        DestinationPolicy decoded = DestinationPolicy.decode(encoded);

        assertEquals(policy, decoded);
        assertEquals(portalId, decoded.candidates().get(0).portalId());
        assertEquals("hub", decoded.candidates().get(1).tag());
        assertEquals(2, decoded.candidates().get(0).weight());
        assertEquals(SelectionStrategy.LEAST_LOADED, decoded.strategy());
        assertEquals(2, decoded.minHeadroom());
        assertEquals(18.5D, decoded.minTps());
        assertTrue(!decoded.queue());
    }

    @Test
    void sixteenCandidatesStayUnderOneKibibyte() {
        List<DestinationCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            candidates.add(new DestinationCandidate("survival-" + (index < 10 ? "0" : "") + index, UUID.randomUUID(), null, 1 + index % 3));
        }
        DestinationPolicy policy = new DestinationPolicy(candidates, SelectionStrategy.ROUND_ROBIN, 1, 0.0D, true);
        String encoded = policy.encode();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length < 1024, "encoded policy is " + encoded.length() + " chars");
        assertEquals(policy, DestinationPolicy.decode(encoded));
    }

    @Test
    void blankOrMalformedInputDecodesToNull() {
        assertNull(DestinationPolicy.decode(""));
        assertNull(DestinationPolicy.decode(null));
        assertNull(DestinationPolicy.decode("{\"s\":\"NOPE\",\"c\":[]}"));
        assertNull(DestinationPolicy.decode("not json"));
        assertNull(DestinationPolicy.decode("{\"s\":\"FIRST_AVAILABLE\",\"c\":[\"beta|not-a-uuid|1\"]}"));
    }

    @Test
    void candidateTextFormIsServerTargetWeight() {
        UUID portalId = UUID.randomUUID();
        DestinationCandidate byId = DestinationCandidate.parse("beta:" + portalId + ":3");
        assertEquals("beta", byId.server());
        assertEquals(portalId, byId.portalId());
        assertEquals(3, byId.weight());
        DestinationCandidate byTag = DestinationCandidate.parse("gamma:hub");
        assertEquals("hub", byTag.tag());
        assertNull(byTag.portalId());
        assertEquals(1, byTag.weight());
        assertNull(DestinationCandidate.parse("gamma"));
        assertNull(DestinationCandidate.parse("gamma:hub:0"));
        assertEquals("gamma:hub:1", byTag.text());
    }
}
