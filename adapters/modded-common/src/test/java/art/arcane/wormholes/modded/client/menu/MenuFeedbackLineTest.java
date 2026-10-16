package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuFeedback;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuFeedbackLineTest {
    private static final MenuFeedback SAVED = MenuFeedback.success("Saved.");

    @Test
    public void repeatedFeedbackIsShownAgainWhenItAnswersAnAction() {
        MenuFeedbackLine line = new MenuFeedbackLine();
        line.show(SAVED, 0L);
        line.pending(10_000L);
        assertTrue(line.answer(SAVED, SAVED, 10_100L));
        assertFalse(line.pending());
        assertTrue(line.live(10_200L));
        assertEquals("Saved.", line.message().orElseThrow().getString());
    }

    @Test
    public void repeatedFeedbackWithoutAnActionStaysQuiet() {
        MenuFeedbackLine line = new MenuFeedbackLine();
        line.show(SAVED, 0L);
        assertFalse(line.answer(SAVED, SAVED, 10_000L));
        assertFalse(line.live(10_000L));
    }

    @Test
    public void changedFeedbackIsAlwaysShown() {
        MenuFeedbackLine line = new MenuFeedbackLine();
        line.show(SAVED, 0L);
        assertTrue(line.answer(MenuFeedback.error("Denied."), SAVED, 100L));
        assertEquals("Denied.", line.message().orElseThrow().getString());
    }

    @Test
    public void anAnswerWithoutFeedbackSettlesThePendingLine() {
        MenuFeedbackLine line = new MenuFeedbackLine();
        line.pending(0L);
        assertFalse(line.answer(null, SAVED, 100L));
        assertFalse(line.pending());
        assertFalse(line.live(100L));
    }
}
