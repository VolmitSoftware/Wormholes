package art.arcane.wormholes.clientgametest.menu;

import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuProtocol;
import art.arcane.wormholes.menu.protocol.MenuServerInfo;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.ClientScreens;
import art.arcane.wormholes.modded.client.menu.MenuEntryWidget;
import art.arcane.wormholes.modded.client.menu.MenuFeedbackLine;
import art.arcane.wormholes.modded.client.menu.MenuLayout;
import art.arcane.wormholes.modded.client.menu.MenuRow;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.CommonComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

public final class NativeMenuScreenScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int NEGOTIATION_TICKS = 100;
    private static final int TIMEOUT_TICKS = (int) (MenuFeedbackLine.LIFETIME_MILLIS / 50L) + 20;

    private final MenuScreenDriver driver;
    private final NativeMenuShots shots;
    private long revision = 1L;

    private NativeMenuScreenScenario(MenuScreenDriver driver, NativeMenuShots shots) {
        this.driver = driver;
        this.shots = shots;
    }

    public static void run(MenuScreenDriver driver, NativeMenuShots shots) {
        new NativeMenuScreenScenario(driver, shots).run();
    }

    private void run() {
        negotiate();
        NativeMenuSink.clear();
        controls();
        keyboard();
        list();
        confirmation();
        feedback();
        settings();
        picker();
        rename();
        lifecycle();
        LOGGER.info("WORMHOLES_NATIVE_MENU_PASS screenshots={}", shots.captured().size());
    }

    private void negotiate() {
        NativeMenuInput input = driver.input();
        for (int tick = 0; tick < NEGOTIATION_TICKS && version() < 1; tick++) {
            input.waitTicks(1);
        }
        if (version() < 1) {
            LOGGER.info("WORMHOLES_NATIVE_MENU_ACCEPT injected because the integrated server did not answer HELLO");
            driver.send(new MenuServerMessage.Accept(MenuProtocol.VERSION, new MenuServerInfo("test-version", "Test 1.0", true)));
            input.waitFor(minecraft -> ClientMenus.session().version() >= 1, 40);
        }
    }

    private void controls() {
        driver.open(NativeMenuFixtures.controls(revision, true, 0, 24.0D, "Garden Arch"));
        check(driver.read(screen -> !screen.isPauseScreen()), "native menu screens must not pause the game");
        shots.capture(NativeMenuFixtures.CONTROLS);

        driver.click("open-settings");
        expectAction("open-settings", MenuInteraction.PRIMARY, null);
        check(driver.pending(), "an action shows the pending line");
        check(!widget("open-settings").isActive(), "the pressed control is inactive while pending");
        int scale = driver.guiScale();
        driver.guiScale(scale == 3 ? 2 : 3);
        check(!widget("open-settings").isActive(), "a resize keeps the pending control inactive");
        driver.guiScale(scale);
        check(driver.pending() && !widget("open-settings").isActive(), "restoring the scale keeps the action pending");
        controlsUpdate(true, 0, 24.0D, "Garden Arch");
        check(!driver.pending(), "an update clears the pending line");
        check(widget("open-settings").isActive(), "the update re-enables the pressed control");

        driver.toggle("toggle-projection");
        expectAction("toggle-projection", MenuInteraction.PRIMARY, null);
        controlsUpdate(false, 0, 24.0D, "Garden Arch");
        check(driver.value("toggle-projection").equals(driver.input().computeOnClient(minecraft -> CommonComponents.OPTION_OFF.getString())),
            "the toggle shows its off word after the update");

        driver.click("cycle-mode");
        expectAction("cycle-mode", MenuInteraction.PRIMARY, null);
        controlsUpdate(false, 1, 24.0D, "Garden Arch");
        check(driver.value("cycle-mode").equals("Wormhole"), "the cycle shows the new option");
        driver.shiftClick("cycle-mode");
        expectAction("cycle-mode", MenuInteraction.SECONDARY, null);
        controlsUpdate(false, 0, 24.0D, "Garden Arch");

        driver.increment("number-range", false);
        expectAction("number-range", MenuInteraction.INCREMENT, null);
        controlsUpdate(false, 0, 25.0D, "Garden Arch");
        driver.increment("number-range", true);
        expectAction("number-range", MenuInteraction.BIG_INCREMENT, null);
        controlsUpdate(false, 0, 33.0D, "Garden Arch");
        driver.decrement("number-range", false);
        expectAction("number-range", MenuInteraction.DECREMENT, null);
        controlsUpdate(false, 0, 32.0D, "Garden Arch");
        driver.decrement("number-range", true);
        expectAction("number-range", MenuInteraction.BIG_DECREMENT, null);
        controlsUpdate(false, 0, 24.0D, "Garden Arch");
        double reached = driver.setSlider("number-range", 48.0D);
        check(reached == 48.0D, "the slider reaches 48 at its three-quarter mark, got " + reached);
        expectAction("number-range", MenuInteraction.SET_VALUE, MenuValue.number(reached));
        controlsUpdate(false, 0, 48.0D, "Garden Arch");
        check(driver.value("number-range").equals("48 blocks"), "the slider shows the server display text");

        driver.typeAndApply("text-name", "Harbor Gate");
        expectAction("text-name", MenuInteraction.SET_TEXT, MenuValue.text("Harbor Gate"));
        controlsUpdate(false, 0, 48.0D, "Harbor Gate");
        driver.typeAndSubmit("text-name", "North Gate");
        expectAction("text-name", MenuInteraction.SET_TEXT, MenuValue.text("North Gate"));
        controlsUpdate(false, 0, 48.0D, "North Gate");

        driver.select("entry-linked");
        expectAction("entry-linked", MenuInteraction.PRIMARY, null);
        controlsUpdate(false, 0, 48.0D, "North Gate");
        driver.clickSide("entry-linked", MenuInteraction.SECONDARY);
        expectAction("entry-linked", MenuInteraction.SECONDARY, null);
        controlsUpdate(false, 0, 48.0D, "North Gate");
        driver.clickSide("entry-linked", MenuInteraction.TERTIARY);
        expectAction("entry-linked", MenuInteraction.TERTIARY, null);
        controlsUpdate(false, 0, 48.0D, "North Gate");

        driver.click("capture-item");
        expectAction("capture-item", MenuInteraction.PRIMARY, null);
        controlsUpdate(false, 0, 48.0D, "North Gate");
        driver.click("capture-look");
        expectAction("capture-look", MenuInteraction.PRIMARY, null);
        controlsUpdate(false, 0, 48.0D, "North Gate");

        driver.click("button-disabled");
        driver.expectNothing(4);

        driver.primary();
        expectAction("primary-done", MenuInteraction.PRIMARY, null);
        controlsUpdate(false, 0, 48.0D, "North Gate");

        driver.type("text-name", "Unsent edit");
        controlsUpdate(false, 0, 48.0D, "North Gate");
        check(driver.value("text-name").equals("Unsent edit"), "an update keeps the unsent edit, got " + driver.value("text-name"));
        controlsUpdate(false, 0, 48.0D, "North Gate");

        driver.click("open-settings");
        expectAction("open-settings", MenuInteraction.PRIMARY, null);
        driver.input().waitTicks(TIMEOUT_TICKS);
        check(driver.feedback().filter("The server did not answer."::equals).isPresent(), "a silent server shows the timeout text");
        check(!driver.pending() && widget("open-settings").isActive(), "the timeout re-enables the pressed control");

        driver.back();
        driver.expect(new MenuClientMessage.Back(revision));
        check(driver.open(), "Back keeps the screen open until the server answers");
        controlsUpdate(false, 0, 48.0D, "North Gate");
        driver.escape();
        driver.expect(new MenuClientMessage.Back(revision));
        check(driver.open(), "ESC with a parent sends Back and keeps the screen");
        controlsUpdate(false, 0, 48.0D, "North Gate");
    }

    private void keyboard() {
        driver.open(NativeMenuFixtures.controls(next(), false, 0, 48.0D, "North Gate"));
        if (driver.focusedElement().filter("open-settings"::equals).isEmpty()) {
            driver.pressKey(InputConstants.KEY_TAB);
        }
        check(driver.focusedElement().filter("open-settings"::equals).isPresent(), "Tab focuses the first control, got "
            + driver.focusedElement());
        driver.pressKey(InputConstants.KEY_RETURN);
        expectAction("open-settings", MenuInteraction.PRIMARY, null);
        controlsUpdate(false, 0, 48.0D, "North Gate");
        check(driver.focusedElement().filter("open-settings"::equals).isPresent(), "an update keeps keyboard focus");
        driver.pressKey(InputConstants.KEY_DOWN);
        check(driver.focusedElement().filter("toggle-projection"::equals).isPresent(), "Down moves to the next row, got "
            + driver.focusedElement());
        driver.pressKey(InputConstants.KEY_SPACE);
        expectAction("toggle-projection", MenuInteraction.PRIMARY, null);
        controlsUpdate(true, 0, 48.0D, "North Gate");
    }

    private void list() {
        driver.open(NativeMenuFixtures.list(next(), 1));
        shots.capture(NativeMenuFixtures.LIST);
        check(driver.read(screen -> screen.pageLabel().getString()).equals("Page 2 / 3"), "the footer shows the page label");
        check(driver.read(screen -> screen.rowFor(NativeMenuFixtures.LONG_ENTRY).flatMap(MenuRow::main)
            .map(widget -> widget instanceof MenuEntryWidget entry && entry.truncated()).orElse(false)), "long entry labels are clipped");
        driver.next();
        driver.expect(new MenuClientMessage.Page(revision, 2));
        driver.update(NativeMenuFixtures.list(next(), 2));
        driver.previous();
        driver.expect(new MenuClientMessage.Page(revision, 1));
        driver.update(NativeMenuFixtures.list(next(), 1));
        driver.select("entry-13");
        expectAction("entry-13", MenuInteraction.PRIMARY, null);
        driver.update(NativeMenuFixtures.list(next(), 1));
        driver.click("entry-1");
        driver.expectNothing(3);
        driver.clickSide("entry-1", MenuInteraction.PRIMARY);
        expectAction("entry-1", MenuInteraction.PRIMARY, null);
        driver.update(NativeMenuFixtures.list(next(), 1));
    }

    private void confirmation() {
        driver.open(NativeMenuFixtures.confirm(next()));
        check(driver.read(screen -> screen.confirmation().isPresent()), "the confirmation overlay is shown");
        check(driver.read(screen -> screen.rowFor("confirm-delete").isEmpty()), "confirmation buttons are not list rows");
        driver.confirm();
        driver.expectNothing(2);
        driver.awaitConfirmationArmed();
        driver.input().waitTicks(1);
        check(driver.read(screen -> screen.getFocused() == screen.confirmation().orElseThrow().cancelButton()), "Cancel has initial focus");
        shots.capture(NativeMenuFixtures.CONFIRM);
        driver.pressKey(InputConstants.KEY_RETURN);
        driver.expect(new MenuClientMessage.Confirm(revision, false));
        check(driver.read(screen -> screen.confirmation().isEmpty()), "cancelling hides the overlay");
        driver.update(NativeMenuFixtures.confirm(next()));
        driver.awaitConfirmationArmed();
        driver.confirm();
        driver.expect(new MenuClientMessage.Confirm(revision, true));
        driver.update(NativeMenuFixtures.confirm(next()));
        driver.awaitConfirmationArmed();
        driver.escape();
        driver.expect(new MenuClientMessage.Confirm(revision, false));
    }

    private void feedback() {
        driver.open(NativeMenuFixtures.feedback(next(), MenuFeedback.success("Projection enabled.")));
        check(driver.feedback().filter("Projection enabled."::equals).isPresent(), "snapshot feedback is shown");
        shots.capture(NativeMenuFixtures.FEEDBACK);
        driver.send(new MenuServerMessage.Feedback(MenuFeedback.error("You cannot edit this portal.")));
        driver.input().waitTicks(2);
        check(driver.feedback().filter("You cannot edit this portal."::equals).isPresent(), "a feedback frame replaces the line");
        shots.capture(NativeMenuFixtures.FEEDBACK + "-error");
        driver.send(new MenuServerMessage.Feedback(new MenuFeedback(MenuFeedbackStyle.WARNING, "Linking is slow on this server.")));
        driver.input().waitTicks(2);
        shots.capture(NativeMenuFixtures.FEEDBACK + "-warning");
        driver.toggle("toggle-projection");
        expectAction("toggle-projection", MenuInteraction.PRIMARY, null);
        check(driver.pending(), "the toggle is pending");
        driver.send(new MenuServerMessage.Feedback(MenuFeedback.info("Nothing changed.")));
        driver.input().waitTicks(2);
        check(!driver.pending() && driver.feedback().filter("Nothing changed."::equals).isPresent(), "feedback settles a pending action");
    }

    private void settings() {
        driver.open(NativeMenuFixtures.settings(next(), null, null));
        int width = driver.read(screen -> screen.width);
        if (width >= MenuLayout.TWO_COLUMN_MIN_W + 36) {
            check(driver.read(screen -> screen.rowList().getRowWidth()) == 2 * MenuLayout.COLUMN_W + MenuLayout.COLUMN_GAP,
                "a 30 row settings screen uses two columns at width " + width);
        }
        shots.capture(NativeMenuFixtures.SETTINGS);
        driver.click("s-27");
        expectAction("s-27", MenuInteraction.PRIMARY, null);
        check(driver.focusedElement().filter("s-27"::equals).isPresent(), "clicking focuses the control");
        double scroll = driver.scrollAmount();
        check(scroll > 0.0D, "the long settings screen scrolled to reach s-27");
        driver.update(NativeMenuFixtures.settings(next(), null, "s-3"));
        check(driver.scrollAmount() == scroll, "an update keeps the scroll offset " + scroll + ", got " + driver.scrollAmount());
        check(driver.focusedElement().filter("s-27"::equals).isPresent(), "an update keeps focus by element id");
        check(driver.label("s-3").equals("Renamed setting 3"), "an update rebuilds labels in place");
        driver.update(NativeMenuFixtures.settings(next(), "s-27", null));
        Optional<String> neighbour = driver.focusedElement();
        check(neighbour.isPresent() && !neighbour.get().equals("s-27"), "focus moves to a neighbour when its element disappears, got " + neighbour);
    }

    private void picker() {
        driver.open(NativeMenuFixtures.picker(next(), 0));
        shots.capture(NativeMenuFixtures.PICKER);
        driver.click("cycle-color");
        check(driver.read(screen -> screen.picker().isPresent()), "a long cycle with SELECT opens the picker");
        driver.expectNothing(2);
        shots.capture(NativeMenuFixtures.PICKER + "-open");
        driver.pickOption(7);
        expectAction("cycle-color", MenuInteraction.SELECT, MenuValue.index(7));
        check(driver.read(screen -> screen.picker().isEmpty()), "picking closes the picker");
        driver.update(NativeMenuFixtures.picker(next(), 7));
        check(driver.value("cycle-color").equals("Gray"), "the cycle shows the picked option");
        driver.click("cycle-color");
        driver.escape();
        driver.expectNothing(2);
        check(driver.read(screen -> screen.picker().isEmpty()), "ESC closes the picker without a message");
        check(driver.focusedElement().filter("cycle-color"::equals).isPresent(), "closing the picker focuses its cycle, got "
            + driver.focusedElement());
        driver.click("cycle-color");
        check(driver.read(screen -> screen.picker().isPresent()), "the picker opens again");
        driver.cancelPicker();
        driver.expectNothing(2);
        check(driver.read(screen -> screen.picker().isEmpty()), "Cancel closes the picker without a message");
    }

    private void rename() {
        driver.open(NativeMenuFixtures.rename(next(), "Garden Arch"));
        check(driver.focusedElement().filter("set-name"::equals).isPresent(), "a single text screen focuses its field, got "
            + driver.focusedElement());
        shots.capture(NativeMenuFixtures.RENAME);
        driver.typeAndSubmit("set-name", "Harbor");
        expectAction("set-name", MenuInteraction.SET_TEXT, MenuValue.text("Harbor"));
        driver.update(NativeMenuFixtures.rename(next(), "Harbor"));
        driver.escape();
        driver.expect(new MenuClientMessage.Closed(revision));
        driver.awaitClosed();
    }

    private void lifecycle() {
        driver.open(NativeMenuFixtures.feedback(next(), null));
        long started = driver.input().computeOnClient(minecraft -> Objects.requireNonNull(minecraft.level).getGameTime());
        driver.input().waitTicks(20);
        long later = driver.input().computeOnClient(minecraft -> Objects.requireNonNull(minecraft.level).getGameTime());
        check(later > started && !driver.input().computeOnClient(minecraft -> minecraft.isPaused()),
            "the world keeps ticking under a native menu (" + started + " -> " + later + ")");

        NativeMenuSink.clear();
        driver.open(NativeMenuFixtures.controls(next(), true, 0, 24.0D, "Garden Arch"));
        check(NativeMenuSink.messages().stream().noneMatch(message -> message instanceof MenuClientMessage.Closed),
            "an OPEN over an open native menu does not send CLOSED");

        driver.send(new MenuServerMessage.Close());
        driver.awaitClosed();
        driver.expectNothing(3);

        driver.open(NativeMenuFixtures.feedback(next(), null));
        NativeMenuSink.clear();
        driver.input().runOnClient(minecraft -> ClientScreens.open(null));
        driver.expect(new MenuClientMessage.Closed(revision));
        driver.awaitClosed();
    }

    private void controlsUpdate(boolean projection, int mode, double range, String name) {
        driver.update(NativeMenuFixtures.controls(next(), projection, mode, range, name));
    }

    private void expectAction(String elementId, MenuInteraction interaction, MenuValue value) {
        driver.expect(new MenuClientMessage.Action(new MenuAction(revision, elementId, interaction, value)));
    }

    private AbstractWidget widget(String elementId) {
        return driver.read(screen -> screen.widgetFor(elementId).orElseThrow(() -> new AssertionError("no widget " + elementId)));
    }

    private long next() {
        revision++;
        return revision;
    }

    private int version() {
        return driver.input().computeOnClient(minecraft -> ClientMenus.session().version());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
