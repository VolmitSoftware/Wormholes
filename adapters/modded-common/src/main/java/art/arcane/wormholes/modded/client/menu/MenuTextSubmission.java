package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuValue;

import java.util.Optional;

final class MenuTextSubmission {
    private String elementId;
    private String text;
    private boolean answered;

    void sent(MenuRowAction action) {
        if (action != null && action.interaction() == MenuInteraction.SET_TEXT && action.value() instanceof MenuValue.Text typed) {
            elementId = action.elementId();
            text = typed.text();
            answered = false;
            return;
        }
        clear();
    }

    Optional<String> answered(boolean sameScreen) {
        if (elementId == null || answered) {
            return Optional.empty();
        }
        if (!sameScreen) {
            clear();
            return Optional.empty();
        }
        answered = true;
        return Optional.of(elementId);
    }

    Optional<Restore> feedback(MenuFeedback feedback) {
        if (elementId == null) {
            return Optional.empty();
        }
        boolean refused = feedback.style() == MenuFeedbackStyle.ERROR;
        if (!refused && !answered) {
            return Optional.empty();
        }
        Optional<Restore> restore = refused && answered ? Optional.of(new Restore(elementId, text)) : Optional.empty();
        clear();
        return restore;
    }

    private void clear() {
        elementId = null;
        text = null;
        answered = false;
    }

    record Restore(String elementId, String text) {
    }
}
