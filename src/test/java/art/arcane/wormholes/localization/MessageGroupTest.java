package art.arcane.wormholes.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;

final class MessageGroupTest {
    @Test
    void groupRejectsIdsOutsideItsPrefixAndRecordsAcceptedKeys() {
        MessageGroup group = new MessageGroup("demo.");
        TextKey accepted = group.text("demo.hello", "Hello");
        assertThrows(IllegalArgumentException.class, () -> group.text("other.hello", "Hello"));
        assertThrows(IllegalArgumentException.class, () -> new MessageGroup("noDot"));
        assertEquals(1, group.keys().size());
        assertEquals(accepted, group.keys().get(0));
    }

    @Test
    void laneGroupsMergeIntoTheCatalogWithoutDuplicateIds() {
        Set<String> ids = new HashSet<String>();
        for (MessageKey key : WormholesMessages.catalog().keys()) {
            assertTrue(ids.add(key.id()), "duplicate id " + key.id());
        }
        for (MessageKey key : WormholesMessageGroups.keys()) {
            assertTrue(ids.contains(key.id()), "lane key missing from catalog: " + key.id());
        }
    }
}
