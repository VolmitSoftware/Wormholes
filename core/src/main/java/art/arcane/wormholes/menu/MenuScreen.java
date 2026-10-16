package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record MenuScreen(
    String screenId,
    String title,
    String context,
    long revision,
    List<MenuElement> elements,
    MenuPaging paging,
    boolean canGoBack,
    MenuFeedback feedback,
    MenuConfirmation confirmation,
    MenuChrome chrome
) {
    public MenuScreen {
        screenId = MenuText.id(screenId, "screen id");
        title = MenuText.text(title, "title");
        context = MenuText.text(context, "context");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision " + revision + " must not be negative");
        }
        elements = copyElements(elements);
        if (confirmation != null) {
            requireElement(elements, confirmation.confirmElementId());
            requireElement(elements, confirmation.cancelElementId());
        }
        Objects.requireNonNull(chrome, "chrome");
    }

    public static Builder builder(String screenId) {
        return new Builder(screenId);
    }

    public Builder toBuilder() {
        return new Builder(screenId)
            .title(title)
            .context(context)
            .revision(revision)
            .elements(elements)
            .paging(paging)
            .canGoBack(canGoBack)
            .feedback(feedback)
            .confirmation(confirmation)
            .chrome(chrome);
    }

    public MenuScreen withRevision(long nextRevision) {
        return new MenuScreen(screenId, title, context, nextRevision, elements, paging, canGoBack, feedback, confirmation, chrome);
    }

    public MenuScreen withFeedback(MenuFeedback nextFeedback) {
        return new MenuScreen(screenId, title, context, revision, elements, paging, canGoBack, nextFeedback, confirmation, chrome);
    }

    public boolean sameContent(MenuScreen other) {
        return other != null && withRevision(0L).withFeedback(null).equals(other.withRevision(0L).withFeedback(null));
    }

    public Optional<MenuElement> element(String elementId) {
        for (MenuElement element : elements) {
            if (element.id().equals(elementId)) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    private static List<MenuElement> copyElements(List<MenuElement> elements) {
        List<MenuElement> required = Objects.requireNonNull(elements, "elements");
        if (required.size() > MenuLimits.MAX_ELEMENTS) {
            throw new IllegalArgumentException("screen has " + required.size() + " elements, more than " + MenuLimits.MAX_ELEMENTS);
        }
        List<MenuElement> copy = new ArrayList<>(required.size());
        Set<String> ids = new HashSet<>(required.size() * 2);
        Set<Integer> slots = new HashSet<>(required.size() * 2);
        for (MenuElement element : required) {
            MenuElement present = Objects.requireNonNull(element, "element");
            if (!ids.add(present.id())) {
                throw new IllegalArgumentException("duplicate element id " + present.id());
            }
            MenuSlot slot = present.chestSlot();
            if (slot != null && !slots.add(slot.slot())) {
                throw new IllegalArgumentException("element " + present.id() + " reuses chest slot " + slot.slot());
            }
            copy.add(present);
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireElement(List<MenuElement> elements, String elementId) {
        for (MenuElement element : elements) {
            if (element.id().equals(elementId)) {
                return;
            }
        }
        throw new IllegalArgumentException("confirmation references missing element " + elementId);
    }

    public static final class Builder {
        private final String screenId;
        private String title = "";
        private String context = "";
        private long revision;
        private List<MenuElement> elements = List.of();
        private MenuPaging paging;
        private boolean canGoBack;
        private MenuFeedback feedback;
        private MenuConfirmation confirmation;
        private MenuChrome chrome = MenuChrome.EMPTY;

        private Builder(String screenId) {
            this.screenId = screenId;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder context(String context) {
            this.context = context;
            return this;
        }

        public Builder revision(long revision) {
            this.revision = revision;
            return this;
        }

        public Builder elements(List<MenuElement> elements) {
            this.elements = elements;
            return this;
        }

        public Builder paging(MenuPaging paging) {
            this.paging = paging;
            return this;
        }

        public Builder canGoBack(boolean canGoBack) {
            this.canGoBack = canGoBack;
            return this;
        }

        public Builder feedback(MenuFeedback feedback) {
            this.feedback = feedback;
            return this;
        }

        public Builder confirmation(MenuConfirmation confirmation) {
            this.confirmation = confirmation;
            return this;
        }

        public Builder chrome(MenuChrome chrome) {
            this.chrome = chrome;
            return this;
        }

        public MenuScreen build() {
            return new MenuScreen(screenId, title, context, revision, elements, paging, canGoBack, feedback, confirmation, chrome);
        }
    }
}
