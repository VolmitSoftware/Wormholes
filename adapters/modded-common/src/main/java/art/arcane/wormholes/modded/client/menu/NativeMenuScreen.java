package art.arcane.wormholes.modded.client.menu;

import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuConfirmation;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuStyle;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.TextElement;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NativeMenuScreen extends Screen {
    private static final MenuIcon PREVIOUS_ICON = MenuIcon.sprite("small/prev");
    private static final MenuIcon NEXT_ICON = MenuIcon.sprite("small/next");

    private final ClientMenuSession session;
    private final MenuFeedbackLine feedbackLine = new MenuFeedbackLine();
    private final MenuTextSubmission textSubmission = new MenuTextSubmission();
    private final List<MenuRow> rows = new ArrayList<>();
    private final Map<String, MenuRow> rowsById = new HashMap<>();
    private MenuScreen snapshot;
    private Component titleText;
    private MenuClippedText clippedTitle;
    private String contextLine = "";
    private int contextWidth = -1;
    private MenuRowList rowList;
    private MenuLayout.Header header = new MenuLayout.Header(MenuLayout.HEADER_H, false);
    private MenuLayout.Grid grid = new MenuLayout.Grid(1, MenuLayout.ROW_W_SETTINGS, MenuLayout.ROW_W_SETTINGS);
    private MenuLayout.Footer footer;
    private MenuActionButton backButton;
    private MenuSideButton previousButton;
    private MenuSideButton nextButton;
    private MenuActionButton primaryButton;
    private ButtonElement primaryElement;
    private MenuConfirmOverlay confirmOverlay;
    private MenuPickerOverlay pickerOverlay;
    private PendingControl pendingControl;
    private AbstractWidget pendingSource;
    private Component pageLabel = Component.empty();
    private boolean confirmationDismissed;
    private boolean feedbackShown;
    private boolean finished;

    public NativeMenuScreen(ClientMenuSession session, MenuScreen snapshot) {
        super(MenuComponents.component(Objects.requireNonNull(snapshot, "snapshot").title()));
        this.session = Objects.requireNonNull(session, "session");
        this.snapshot = snapshot;
        this.titleText = MenuComponents.component(snapshot.title());
        this.clippedTitle = new MenuClippedText(titleText);
        if (snapshot.feedback() != null) {
            feedbackLine.show(snapshot.feedback(), Util.getMillis());
        }
    }

    public MenuScreen snapshot() {
        return snapshot;
    }

    public void apply(MenuScreen next) {
        boolean sameScreen = snapshot.screenId().equals(next.screenId());
        ViewState state = sameScreen ? capture() : null;
        Optional<String> accepted = textSubmission.answered(sameScreen);
        if (state != null && accepted.isPresent()) {
            state.edits().remove(accepted.get());
        }
        MenuFeedback previousFeedback = snapshot.feedback();
        snapshot = next;
        titleText = MenuComponents.component(next.title());
        clippedTitle = new MenuClippedText(titleText);
        contextWidth = -1;
        boolean announced = feedbackLine.answer(next.feedback(), previousFeedback, Util.getMillis());
        release();
        confirmationDismissed = false;
        pickerOverlay = null;
        if (announced) {
            narrateFeedback();
        }
        rebuild(state);
        if (state == null) {
            setInitialFocus();
        }
    }

    public void feedback(MenuFeedback feedback) {
        textSubmission.feedback(feedback).ifPresent(this::restoreText);
        showFeedback(feedback);
        release();
        relayoutList();
    }

    public void closeFromServer() {
        finished = true;
        if (ClientScreens.current() == this) {
            ClientScreens.open(null);
        }
    }

    public void markReplaced() {
        finished = true;
    }

    public Optional<AbstractWidget> widgetFor(String elementId) {
        if (primaryElement != null && primaryElement.id().equals(elementId)) {
            return Optional.ofNullable(primaryButton);
        }
        MenuRow row = rowsById.get(elementId);
        return row == null ? Optional.empty() : row.widget(MenuRow.Part.MAIN, null);
    }

    public Optional<AbstractWidget> sideButton(String elementId, MenuInteraction interaction) {
        MenuRow row = rowsById.get(elementId);
        return row == null ? Optional.empty() : row.side(interaction).map(AbstractWidget.class::cast);
    }

    public Optional<MenuRow> rowFor(String elementId) {
        return Optional.ofNullable(rowsById.get(elementId));
    }

    public List<MenuRow> rows() {
        return Collections.unmodifiableList(rows);
    }

    public Optional<AbstractWidget> footerButton(FooterPart part) {
        AbstractWidget widget = switch (part) {
            case BACK -> backButton;
            case PREVIOUS -> previousButton;
            case NEXT -> nextButton;
            case PRIMARY -> primaryButton;
        };
        return Optional.ofNullable(widget);
    }

    public Optional<MenuConfirmOverlay> confirmation() {
        return Optional.ofNullable(confirmOverlay);
    }

    public Optional<MenuPickerOverlay> picker() {
        return Optional.ofNullable(pickerOverlay);
    }

    public MenuFeedbackLine feedbackLine() {
        return feedbackLine;
    }

    public MenuRowList rowList() {
        return rowList;
    }

    public MenuLayout.Header header() {
        return header;
    }

    public Component pageLabel() {
        return pageLabel;
    }

    @Override
    public Component getTitle() {
        return titleText;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        long now = Util.getMillis();
        if (feedbackLine.timedOut(now)) {
            showFeedback(MenuFeedback.error(snapshot.chrome().timeout()));
            release();
        }
        if (confirmOverlay != null && confirmOverlay.tick() && getFocused() == null) {
            setInitialFocus(confirmOverlay.cancelButton());
        }
        if (feedbackLine.live(now) != feedbackShown) {
            relayoutList();
        }
    }

    @Override
    public void onClose() {
        if (pickerOverlay != null) {
            closePicker();
            return;
        }
        if (confirmOverlay != null) {
            answerConfirmation(false);
            return;
        }
        back();
    }

    @Override
    public void removed() {
        if (finished) {
            return;
        }
        finished = true;
        session.closed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        boolean modal = confirmOverlay != null || pickerOverlay != null;
        super.extractRenderState(graphics, modal ? -1 : mouseX, modal ? -1 : mouseY, partialTick);
        if (pickerOverlay == null) {
            extractHeader(graphics);
        }
        feedbackLine.extract(graphics, font, width / 2 - grid.rowWidth() / 2, MenuLayout.feedbackY(header), grid.rowWidth(),
            Util.getMillis());
        if (footer != null && footer.label() != null) {
            MenuLayout.Rect label = footer.label();
            graphics.text(font, pageLabel, label.centerX() - font.width(pageLabel) / 2, label.y() + (label.height() - 8) / 2,
                MenuPalette.WHITE, true);
        }
        if (confirmOverlay != null) {
            graphics.nextStratum();
            confirmOverlay.extract(graphics, font, width, height, mouseX, mouseY, partialTick);
        }
        if (pickerOverlay != null) {
            graphics.nextStratum();
            pickerOverlay.extract(graphics, font, width, height, mouseX, mouseY, partialTick);
        }
    }

    @Override
    protected void init() {
        rebuild(null);
    }

    @Override
    protected void repositionElements() {
        rebuild(capture());
    }

    @Override
    protected void setInitialFocus() {
        if (confirmOverlay != null) {
            if (confirmOverlay.armed()) {
                setInitialFocus(confirmOverlay.cancelButton());
            }
            return;
        }
        if (pickerOverlay != null) {
            setInitialFocus(pickerOverlay.list());
            return;
        }
        Optional<MenuRow> single = singleText();
        if (single.isPresent() && single.get().textField().isPresent() && single.get().textField().get().isActive()) {
            MenuTextField field = single.get().textField().get();
            field.moveCursorToEnd(false);
            focus(single.get(), field);
            return;
        }
        super.setInitialFocus();
    }

    private void rebuild(ViewState state) {
        clearFocus();
        clearWidgets();
        rows.clear();
        rowsById.clear();
        MenuConfirmation confirmation = snapshot.confirmation();
        MenuConfirmation shownConfirmation = confirmationDismissed ? null : confirmation;
        primaryElement = primary();
        MenuRowContext context = new MenuRowContext(font, snapshot.chrome(), this::act, this::openPicker);
        boolean listRows = false;
        for (MenuElement element : snapshot.elements()) {
            if (!element.common().nativeVisible() || element == primaryElement || confirmationElement(element, confirmation)) {
                continue;
            }
            MenuRow row = MenuRows.create(element, context);
            rows.add(row);
            rowsById.put(element.id(), row);
            listRows |= element instanceof ListEntryElement;
        }
        grid = MenuLayout.grid(width, listRows, rows.size());
        header = MenuLayout.header(!snapshot.context().isEmpty(), height);
        feedbackShown = feedbackLine.live(Util.getMillis());
        int top = MenuLayout.listTop(header, feedbackShown);
        rowList = new MenuRowList(minecraft, width, Math.max(0, MenuLayout.listBottom(height) - top), top);
        rowList.band(top - header.height());
        rowList.rows(rows, grid);
        buildFooter();
        confirmOverlay = shownConfirmation == null ? null : confirmOverlay(shownConfirmation);
        boolean modal = confirmOverlay != null || pickerOverlay != null;
        register(rowList, modal);
        register(backButton, modal);
        register(previousButton, modal);
        register(nextButton, modal);
        register(primaryButton, modal);
        if (confirmOverlay != null) {
            confirmOverlay.layout(font, width, height);
            for (AbstractWidget widget : confirmOverlay.widgets()) {
                addWidget(widget);
            }
        }
        if (pickerOverlay != null) {
            pickerOverlay.layout(width, height);
            addWidget(pickerOverlay.list());
            addWidget(pickerOverlay.cancelButton());
            pickerOverlay.reveal(pickerOverlay.element().index());
        }
        restore(state);
        holdPending();
    }

    private void buildFooter() {
        MenuPaging paging = snapshot.paging();
        pageLabel = paging == null ? Component.empty() : MenuComponents.component(snapshot.chrome().pageLabel());
        footer = MenuLayout.footer(width, height, MenuLayout.footerSpan(width), paging != null, font.width(pageLabel), primaryElement != null);
        Component backLabel = snapshot.canGoBack()
            ? chrome(snapshot.chrome().back(), CommonComponents.GUI_BACK)
            : chrome(snapshot.chrome().close(), CommonComponents.GUI_DONE);
        backButton = new MenuActionButton(backLabel, backLabel, input -> back());
        place(backButton, footer.back());
        previousButton = null;
        nextButton = null;
        if (paging != null) {
            previousButton = new MenuSideButton(MenuComponents.component(snapshot.chrome().previousPage()), PREVIOUS_ICON,
                input -> page(-1, FooterPart.PREVIOUS));
            previousButton.active = paging.page() > 0;
            place(previousButton, footer.previous());
            nextButton = new MenuSideButton(MenuComponents.component(snapshot.chrome().nextPage()), NEXT_ICON,
                input -> page(1, FooterPart.NEXT));
            nextButton.active = paging.page() < paging.pageCount() - 1;
            place(nextButton, footer.next());
        }
        primaryButton = null;
        if (primaryElement != null) {
            Component label = MenuComponents.component(primaryElement.label());
            String elementId = primaryElement.id();
            primaryButton = new MenuActionButton(label, label,
                input -> act(new MenuRowAction(elementId, MenuInteraction.PRIMARY, null, MenuRow.Part.MAIN)));
            primaryButton.active = primaryElement.enabled() && MenuRows.press(primaryElement) != null;
            place(primaryButton, footer.primary());
        }
    }

    private MenuConfirmOverlay confirmOverlay(MenuConfirmation confirmation) {
        List<Component> message = new ArrayList<>(confirmation.messageLines().size());
        for (String line : confirmation.messageLines()) {
            message.add(MenuComponents.component(line));
        }
        Component confirmLabel = elementLabel(confirmation.confirmElementId(), snapshot.chrome().confirm());
        Component cancelLabel = elementLabel(confirmation.cancelElementId(), snapshot.chrome().cancel());
        if (confirmOverlay != null && confirmOverlay.message().equals(message)) {
            return confirmOverlay;
        }
        return new MenuConfirmOverlay(message, confirmLabel, cancelLabel, () -> answerConfirmation(true), () -> answerConfirmation(false));
    }

    private void answerConfirmation(boolean accepted) {
        MenuConfirmOverlay overlay = confirmOverlay;
        if (overlay == null) {
            return;
        }
        if (accepted) {
            pending(session.confirm(true), new PendingControl(null, null, true));
            return;
        }
        ViewState state = capture();
        if (!session.confirm(false)) {
            return;
        }
        release();
        feedbackLine.pending(Util.getMillis());
        confirmationDismissed = true;
        rebuild(state);
    }

    private void openPicker(CycleElement element) {
        ViewState state = capture();
        Component cancelLabel = chrome(snapshot.chrome().cancel(), CommonComponents.GUI_CANCEL);
        pickerOverlay = new MenuPickerOverlay(minecraft, element, cancelLabel, index -> pick(element, index), this::closePicker);
        rebuild(state);
        setInitialFocus(pickerOverlay.list());
    }

    private void pick(CycleElement element, int index) {
        closePicker();
        act(new MenuRowAction(element.id(), MenuInteraction.SELECT, MenuValue.index(index), MenuRow.Part.MAIN));
    }

    private void closePicker() {
        MenuPickerOverlay closing = pickerOverlay;
        if (closing == null) {
            return;
        }
        ViewState state = capture();
        pickerOverlay = null;
        rebuild(state);
        MenuRow row = rowsById.get(closing.element().id());
        if (row != null) {
            row.main().filter(AbstractWidget::isActive).ifPresent(widget -> focus(row, widget));
        }
    }

    private void act(MenuRowAction action) {
        pending(session.act(action.elementId(), action.interaction(), action.value()), new PendingControl(null, action, false));
    }

    private void back() {
        if (snapshot.canGoBack()) {
            pending(session.back(), new PendingControl(FooterPart.BACK, null, false));
            return;
        }
        finished = true;
        session.closed();
        ClientScreens.open(null);
    }

    private void page(int delta, FooterPart part) {
        MenuPaging paging = snapshot.paging();
        if (paging == null || !paging.contains(paging.page() + delta)) {
            return;
        }
        pending(session.page(paging.page() + delta), new PendingControl(part, null, false));
    }

    private void pending(boolean sent, PendingControl control) {
        if (!sent) {
            return;
        }
        textSubmission.sent(control.action());
        release();
        feedbackLine.pending(Util.getMillis());
        pendingControl = control;
        holdPending();
        relayoutList();
    }

    private void holdPending() {
        if (pendingControl == null) {
            return;
        }
        AbstractWidget widget = pendingWidget(pendingControl);
        if (widget == pendingSource) {
            return;
        }
        pendingSource = widget != null && widget.active ? widget : null;
        if (pendingSource != null) {
            pendingSource.active = false;
        }
    }

    private AbstractWidget pendingWidget(PendingControl control) {
        if (control.footer() != null) {
            return footerButton(control.footer()).orElse(null);
        }
        if (control.action() != null) {
            return source(control.action());
        }
        return control.confirm() && confirmOverlay != null ? confirmOverlay.confirmButton() : null;
    }

    private void release() {
        feedbackLine.settle();
        if (pendingSource != null) {
            pendingSource.active = true;
        }
        pendingSource = null;
        pendingControl = null;
    }

    private AbstractWidget source(MenuRowAction action) {
        if (primaryElement != null && primaryElement.id().equals(action.elementId())) {
            return primaryButton;
        }
        MenuRow row = rowsById.get(action.elementId());
        return row == null ? null : row.widget(action.part(), action.interaction()).orElse(null);
    }

    private void showFeedback(MenuFeedback feedback) {
        feedbackLine.show(feedback, Util.getMillis());
        narrateFeedback();
    }

    private void narrateFeedback() {
        feedbackLine.message().ifPresent(message -> minecraft.getNarrator().saySystemNow(message));
    }

    private void relayoutList() {
        if (rowList == null) {
            return;
        }
        feedbackShown = feedbackLine.live(Util.getMillis());
        int top = MenuLayout.listTop(header, feedbackShown);
        rowList.band(top - header.height());
        rowList.updateSizeAndPosition(width, Math.max(0, MenuLayout.listBottom(height) - top), 0, top);
    }

    private void extractHeader(GuiGraphicsExtractor graphics) {
        int available = Math.max(0, width - 2 * MenuLayout.MARGIN);
        int titleWidth = Math.min(font.width(titleText), available);
        graphics.text(font, clippedTitle.clip(font, available), width / 2 - titleWidth / 2, MenuLayout.TITLE_Y, MenuPalette.WHITE, true);
        if (!header.context()) {
            return;
        }
        if (contextWidth != available) {
            contextWidth = available;
            contextLine = MenuComponents.clipFront(MenuComponents.plain(snapshot.context()), available, font::width);
        }
        graphics.text(font, contextLine, width / 2 - font.width(contextLine) / 2, MenuLayout.CONTEXT_Y, MenuPalette.SECONDARY, true);
    }

    private ButtonElement primary() {
        for (MenuElement element : snapshot.elements()) {
            if (element instanceof ButtonElement button && element.style() == MenuStyle.PRIMARY && element.common().nativeVisible()
                && !confirmationElement(element, snapshot.confirmation())) {
                return button;
            }
        }
        return null;
    }

    private static boolean confirmationElement(MenuElement element, MenuConfirmation confirmation) {
        return confirmation != null && (confirmation.confirmElementId().equals(element.id())
            || confirmation.cancelElementId().equals(element.id()));
    }

    private Optional<MenuRow> singleText() {
        MenuRow found = null;
        for (MenuRow row : rows) {
            if (row.widgets().isEmpty()) {
                continue;
            }
            if (found != null || !(row.element() instanceof TextElement)) {
                return Optional.empty();
            }
            found = row;
        }
        return Optional.ofNullable(found);
    }

    private Component elementLabel(String elementId, String fallback) {
        Optional<MenuElement> element = snapshot.element(elementId);
        String label = element.map(MenuElement::label).filter(text -> !text.isEmpty()).orElse(fallback);
        return MenuComponents.component(label);
    }

    private void register(AbstractWidget widget, boolean modal) {
        if (widget == null) {
            return;
        }
        if (modal) {
            addRenderableOnly(widget);
        } else {
            addRenderableWidget(widget);
        }
    }

    private ViewState capture() {
        if (rowList == null) {
            return null;
        }
        Map<String, String> edits = new HashMap<>();
        for (MenuRow row : rows) {
            if (row.element() instanceof TextElement text && row.textField().isPresent()
                && !row.textField().get().getValue().equals(text.value())) {
                edits.put(row.id(), row.textField().get().getValue());
            }
        }
        return new ViewState(rowList.scrollAmount(), focusKey(), edits);
    }

    private void restoreText(MenuTextSubmission.Restore restore) {
        MenuRow row = rowsById.get(restore.elementId());
        if (row != null && row.textField().isPresent()) {
            row.textField().get().setValue(restore.text());
        }
    }

    private FocusKey focusKey() {
        GuiEventListener focused = getFocused();
        if (focused == null) {
            return null;
        }
        for (FooterPart part : FooterPart.values()) {
            if (footerButton(part).filter(widget -> widget == focused).isPresent()) {
                return new FocusKey(null, null, null, -1, part, null);
            }
        }
        if (confirmOverlay != null && (focused == confirmOverlay.confirmButton() || focused == confirmOverlay.cancelButton())) {
            return new FocusKey(null, null, null, -1, null, focused == confirmOverlay.confirmButton());
        }
        if (focused != rowList || rowList.getFocused() == null) {
            return null;
        }
        MenuRowList.Entry entry = rowList.getFocused();
        GuiEventListener leaf = entry.getFocused();
        for (MenuRow row : entry.cells()) {
            Optional<MenuRow.Part> part = row.part(leaf);
            if (part.isPresent()) {
                return new FocusKey(row.id(), part.get(), row.sideInteraction(leaf).orElse(null), rows.indexOf(row), null, null);
            }
        }
        return null;
    }

    private void restore(ViewState state) {
        if (state == null) {
            return;
        }
        for (Map.Entry<String, String> edit : state.edits().entrySet()) {
            MenuRow row = rowsById.get(edit.getKey());
            if (row != null && row.textField().isPresent()) {
                row.textField().get().setValue(edit.getValue());
            }
        }
        restoreFocus(state.focus());
        rowList.setScrollAmount(state.scroll());
    }

    private void restoreFocus(FocusKey key) {
        if (pickerOverlay != null) {
            setInitialFocus(pickerOverlay.list());
            return;
        }
        if (confirmOverlay != null) {
            if (key != null && key.confirm() != null) {
                AbstractWidget target = key.confirm() ? confirmOverlay.confirmButton() : confirmOverlay.cancelButton();
                if (target.isActive()) {
                    setInitialFocus(target);
                }
            }
            return;
        }
        if (key == null) {
            return;
        }
        if (key.footer() != null) {
            footerButton(key.footer()).filter(AbstractWidget::isActive).ifPresent(this::setInitialFocus);
            return;
        }
        MenuRow row = rowsById.get(key.elementId());
        if (row != null) {
            Optional<AbstractWidget> widget = row.widget(key.part(), key.side()).filter(AbstractWidget::isActive);
            if (widget.isPresent()) {
                focus(row, widget.get());
                return;
            }
        }
        neighbour(key.index()).ifPresent(found -> focus(found, firstActive(found).orElseThrow()));
    }

    private Optional<MenuRow> neighbour(int index) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        int start = Math.max(0, Math.min(index, rows.size() - 1));
        for (int offset = 0; offset < rows.size(); offset++) {
            int after = start + offset;
            if (after < rows.size() && firstActive(rows.get(after)).isPresent()) {
                return Optional.of(rows.get(after));
            }
            int before = start - offset - 1;
            if (before >= 0 && firstActive(rows.get(before)).isPresent()) {
                return Optional.of(rows.get(before));
            }
        }
        return Optional.empty();
    }

    private static Optional<AbstractWidget> firstActive(MenuRow row) {
        for (AbstractWidget widget : row.widgets()) {
            if (widget.isActive()) {
                return Optional.of(widget);
            }
        }
        return Optional.empty();
    }

    private void focus(MenuRow row, AbstractWidget widget) {
        rowList.entry(row).ifPresent(entry -> changeFocus(ComponentPath.path(this,
            ComponentPath.path(rowList, ComponentPath.path(entry, ComponentPath.leaf(widget))))));
    }

    private static Component chrome(String text, Component fallback) {
        return text.isEmpty() ? fallback : MenuComponents.component(text);
    }

    private static void place(AbstractWidget widget, MenuLayout.Rect rect) {
        widget.setRectangle(rect.width(), rect.height(), rect.x(), rect.y());
    }

    public enum FooterPart {
        BACK, PREVIOUS, NEXT, PRIMARY
    }

    private record FocusKey(String elementId, MenuRow.Part part, MenuInteraction side, int index, FooterPart footer, Boolean confirm) {
    }

    private record PendingControl(FooterPart footer, MenuRowAction action, boolean confirm) {
    }

    private record ViewState(double scroll, FocusKey focus, Map<String, String> edits) {
    }
}
