package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuValue;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class MenuTextSubmissionTest {
    private static final String FIELD = "access-add";
    private static final MenuRowAction SUBMIT = new MenuRowAction(FIELD, MenuInteraction.SET_TEXT, MenuValue.text("Astra"), MenuRow.Part.MAIN);

    @Test
    public void anAcceptedSubmissionDropsItsTypedText() {
        MenuTextSubmission submission = new MenuTextSubmission();
        submission.sent(SUBMIT);

        assertEquals(Optional.of(FIELD), submission.answered(true));
        assertEquals(Optional.empty(), submission.feedback(MenuFeedback.success("Added Astra.")));
        assertEquals(Optional.empty(), submission.answered(true));
    }

    @Test
    public void aSubmissionAcceptedWithoutANoticeStaysCleared() {
        MenuTextSubmission submission = new MenuTextSubmission();
        submission.sent(SUBMIT);

        assertEquals(Optional.of(FIELD), submission.answered(true));
        assertEquals(Optional.empty(), submission.answered(true));
    }

    @Test
    public void aRefusedSubmissionGetsItsTextBack() {
        MenuTextSubmission submission = new MenuTextSubmission();
        submission.sent(SUBMIT);

        assertEquals(Optional.of(FIELD), submission.answered(true));
        assertEquals(Optional.of(new MenuTextSubmission.Restore(FIELD, "Astra")),
            submission.feedback(MenuFeedback.error("No player named Astra.")));
        assertEquals(Optional.empty(), submission.feedback(MenuFeedback.error("Again.")));
    }

    @Test
    public void aRefusalBeforeAnyScreenUpdateLeavesTheFieldAlone() {
        MenuTextSubmission submission = new MenuTextSubmission();
        submission.sent(SUBMIT);

        assertEquals(Optional.empty(), submission.feedback(MenuFeedback.error("Not allowed.")));
        assertEquals(Optional.empty(), submission.answered(true));
    }

    @Test
    public void aWaitingNoticeKeepsTheSubmissionOpen() {
        MenuTextSubmission submission = new MenuTextSubmission();
        submission.sent(SUBMIT);

        assertEquals(Optional.empty(), submission.feedback(MenuFeedback.info("Looking up Astra...")));
        assertEquals(Optional.of(FIELD), submission.answered(true));
        assertEquals(Optional.of(new MenuTextSubmission.Restore(FIELD, "Astra")),
            submission.feedback(MenuFeedback.error("No player named Astra.")));
    }

    @Test
    public void otherActionsAndNewScreensForgetTheSubmission() {
        MenuTextSubmission submission = new MenuTextSubmission();
        submission.sent(SUBMIT);
        submission.sent(new MenuRowAction("access-listed", MenuInteraction.PRIMARY, null, MenuRow.Part.MAIN));
        assertEquals(Optional.empty(), submission.answered(true));

        submission.sent(SUBMIT);
        submission.sent(null);
        assertEquals(Optional.empty(), submission.answered(true));

        submission.sent(SUBMIT);
        assertEquals(Optional.empty(), submission.answered(false));
        assertEquals(Optional.empty(), submission.feedback(MenuFeedback.error("Refused.")));
    }
}
