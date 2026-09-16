package art.arcane.wormholes.render;

import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ProjectionEntityFilterTest {
    @Test
    void retainsHiddenItemsForViewerChecksButExcludesThemFromBroadcasts() {
        Item item = entity(Item.class);
        when(item.isVisibleByDefault()).thenReturn(false);

        assertTrue(ProjectionEntityFilter.canCapture(item));
        assertFalse(ProjectionEntityFilter.canBroadcast(item));

        when(item.isVisibleByDefault()).thenReturn(true);

        assertTrue(ProjectionEntityFilter.canBroadcast(item));
    }

    @Test
    void keepsReplacementDisplaysAndViewerManagedLabelsEligible() {
        assertTrue(ProjectionEntityFilter.canCapture(entity(BlockDisplay.class)));
        assertTrue(ProjectionEntityFilter.canCapture(entity(ItemDisplay.class)));
        assertTrue(ProjectionEntityFilter.canCapture(entity(TextDisplay.class)));
        assertFalse(ProjectionEntityFilter.canBroadcast(entity(BlockDisplay.class)));
        assertFalse(ProjectionEntityFilter.canBroadcast(entity(ItemDisplay.class)));
        assertFalse(ProjectionEntityFilter.canBroadcast(entity(TextDisplay.class)));
    }

    @Test
    void rejectsPortalEffectDisplays() {
        BlockDisplay display = entity(BlockDisplay.class);
        when(display.getScoreboardTags()).thenReturn(Set.of("wormholes_fx"));

        assertFalse(ProjectionEntityFilter.canCapture(display));
        assertFalse(ProjectionEntityFilter.canBroadcast(display));
    }

    @Test
    void rejectsMissingDeadAndInvalidEntities() {
        Entity entity = entity(Entity.class);

        assertFalse(ProjectionEntityFilter.canCapture(null));
        assertTrue(ProjectionEntityFilter.canCapture(entity));

        when(entity.isDead()).thenReturn(true);
        assertFalse(ProjectionEntityFilter.canCapture(entity));

        when(entity.isDead()).thenReturn(false);
        when(entity.isValid()).thenReturn(false);
        assertFalse(ProjectionEntityFilter.canCapture(entity));
    }

    private static <T extends Entity> T entity(Class<T> type) {
        T entity = mock(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);
        return entity;
    }
}
