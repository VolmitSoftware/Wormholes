package art.arcane.wormholes.clientgametest.menu;

import art.arcane.wormholes.clientgametest.mixin.NativeMenuMouseAccess;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.ClientScreens;
import art.arcane.wormholes.modded.client.menu.MenuLayout;
import art.arcane.wormholes.modded.client.menu.MenuNumbers;
import art.arcane.wormholes.modded.client.menu.MenuRow;
import art.arcane.wormholes.modded.client.menu.MenuRowList;
import art.arcane.wormholes.modded.client.menu.MenuSlider;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.modded.client.menu.NativeMenuScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class MenuScreenDriver {
    private static final int TIMEOUT_TICKS = 200;
    private static final int SCROLL_LIMIT = 80;
    private static final int DUPLICATE_TICKS = 2;

    private final NativeMenuInput input;

    public MenuScreenDriver(NativeMenuInput input) {
        this.input = input;
    }

    public NativeMenuInput input() {
        return input;
    }

    public void send(MenuServerMessage message) {
        byte[] frame;
        try {
            frame = MenuCodec.encodeServer(message);
        } catch (MenuProtocolException exception) {
            throw new AssertionError("native menu fixture could not be encoded: " + exception.getMessage(), exception);
        }
        input.runOnClient(minecraft -> ClientMenus.receive(frame));
    }

    public void open(MenuScreen screen) {
        send(new MenuServerMessage.Open(screen));
        awaitRevision(screen.screenId(), screen.revision());
    }

    public void update(MenuScreen screen) {
        send(new MenuServerMessage.Update(screen));
        awaitRevision(screen.screenId(), screen.revision());
    }

    public void awaitRevision(String screenId, long revision) {
        input.waitFor(minecraft -> NativeMenuListener.current()
            .filter(screen -> screen.snapshot().screenId().equals(screenId) && screen.snapshot().revision() == revision)
            .isPresent(), TIMEOUT_TICKS);
        input.waitTicks(1);
    }

    public void awaitClosed() {
        input.waitFor(minecraft -> NativeMenuListener.current().isEmpty(), TIMEOUT_TICKS);
    }

    public boolean open() {
        return input.computeOnClient(minecraft -> NativeMenuListener.current().isPresent());
    }

    public <T> T read(Function<NativeMenuScreen, T> reader) {
        return input.computeOnClient(minecraft -> reader.apply(screen()));
    }

    public void click(String elementId) {
        clickWidget(elementId, screen -> screen.widgetFor(elementId), false);
    }

    public void shiftClick(String elementId) {
        clickWidget(elementId, screen -> screen.widgetFor(elementId), true);
    }

    public void clickSide(String elementId, MenuInteraction interaction) {
        clickWidget(elementId, screen -> screen.sideButton(elementId, interaction), false);
    }

    public void toggle(String elementId) {
        click(elementId);
    }

    public void select(String elementId) {
        click(elementId);
    }

    public void increment(String elementId, boolean big) {
        clickWidget(elementId, screen -> screen.rowFor(elementId).flatMap(MenuRow::increment), big);
    }

    public void decrement(String elementId, boolean big) {
        clickWidget(elementId, screen -> screen.rowFor(elementId).flatMap(MenuRow::decrement), big);
    }

    public double setSlider(String elementId, double value) {
        scrollTo(elementId);
        double[] target = input.computeOnClient(minecraft -> {
            MenuSlider slider = (MenuSlider) screen().widgetFor(elementId).orElseThrow(() -> missing(elementId));
            NumberElement element = (NumberElement) screen().rowFor(elementId).orElseThrow().element();
            double fraction = MenuNumbers.fraction(value, element.min(), element.max());
            int x = (int) Math.round(slider.getX() + 4 + fraction * (slider.getWidth() - 8));
            double reached = MenuNumbers.valueAt((x - (slider.getX() + 4.0D)) / (slider.getWidth() - 8.0D), element.min(), element.max(),
                element.step());
            return new double[]{x, slider.getY() + slider.getHeight() / 2.0D, reached};
        });
        clickAt(target[0], target[1], false);
        return target[2];
    }

    public void typeAndApply(String elementId, String text) {
        type(elementId, text);
        clickWidget(elementId, screen -> screen.rowFor(elementId).flatMap(MenuRow::apply).map(AbstractWidget.class::cast), false);
    }

    public void typeAndSubmit(String elementId, String text) {
        type(elementId, text);
        pressKey(InputConstants.KEY_RETURN);
    }

    public void type(String elementId, String text) {
        click(elementId);
        int length = input.computeOnClient(minecraft -> screen().rowFor(elementId).flatMap(MenuRow::textField).orElseThrow().getValue().length());
        input.pressKey(InputConstants.KEY_END);
        for (int index = 0; index < length; index++) {
            input.pressKey(InputConstants.KEY_BACKSPACE);
        }
        input.typeChars(text);
        input.waitTicks(1);
    }

    public void back() {
        clickFooter(NativeMenuScreen.FooterPart.BACK);
    }

    public void next() {
        clickFooter(NativeMenuScreen.FooterPart.NEXT);
    }

    public void previous() {
        clickFooter(NativeMenuScreen.FooterPart.PREVIOUS);
    }

    public void primary() {
        clickFooter(NativeMenuScreen.FooterPart.PRIMARY);
    }

    public void awaitConfirmationArmed() {
        input.waitFor(minecraft -> NativeMenuListener.current().flatMap(NativeMenuScreen::confirmation)
            .filter(overlay -> overlay.armed()).isPresent(), TIMEOUT_TICKS);
    }

    public void confirm() {
        clickRect(input.computeOnClient(minecraft -> rect(screen().confirmation().orElseThrow().confirmButton())));
    }

    public void cancel() {
        clickRect(input.computeOnClient(minecraft -> rect(screen().confirmation().orElseThrow().cancelButton())));
    }

    public void cancelPicker() {
        clickRect(input.computeOnClient(minecraft -> rect(screen().picker().orElseThrow().cancelButton())));
    }

    public void pickOption(int index) {
        input.runOnClient(minecraft -> screen().picker().orElseThrow().reveal(index));
        input.waitTicks(1);
        clickRect(input.computeOnClient(minecraft -> rect(screen().picker().orElseThrow().option(index).orElseThrow())));
    }

    public void escape() {
        pressKey(InputConstants.KEY_ESCAPE);
    }

    public void pressKey(int keyCode) {
        NativeMenuSink.clear();
        input.pressKey(keyCode);
        input.waitTicks(1);
    }

    public void scrollTo(String elementId) {
        for (int attempt = 0; attempt < SCROLL_LIMIT; attempt++) {
            int direction = input.computeOnClient(minecraft -> visibility(elementId));
            if (direction == 0) {
                return;
            }
            MenuLayout.Rect viewport = input.computeOnClient(minecraft -> screen().rowList().viewport());
            moveTo(viewport.centerX(), viewport.centerY());
            input.scroll(direction);
            input.waitTicks(1);
        }
        throw new AssertionError("element " + elementId + " never scrolled into view");
    }

    public String label(String elementId) {
        return input.computeOnClient(minecraft -> screen().rowFor(elementId).orElseThrow(() -> missing(elementId)).label().getString());
    }

    public String value(String elementId) {
        return input.computeOnClient(minecraft -> screen().rowFor(elementId).orElseThrow(() -> missing(elementId)).valueText());
    }

    public Optional<String> feedback() {
        return input.computeOnClient(minecraft -> screen().feedbackLine().message().map(message -> message.getString()));
    }

    public boolean pending() {
        return input.computeOnClient(minecraft -> screen().feedbackLine().pending());
    }

    public Optional<String> focusedElement() {
        return input.computeOnClient(minecraft -> {
            NativeMenuScreen screen = screen();
            GuiEventListener focused = screen.getFocused();
            if (focused != screen.rowList() || screen.rowList().getFocused() == null) {
                return Optional.empty();
            }
            MenuRowList.Entry entry = screen.rowList().getFocused();
            for (MenuRow row : entry.cells()) {
                if (row.part(entry.getFocused()).isPresent()) {
                    return Optional.of(row.id());
                }
            }
            return Optional.empty();
        });
    }

    public double scrollAmount() {
        return input.computeOnClient(minecraft -> screen().rowList().scrollAmount());
    }

    public int guiScale() {
        return input.computeOnClient(minecraft -> minecraft.options.guiScale().get());
    }

    public void guiScale(int scale) {
        input.runOnClient(minecraft -> {
            minecraft.options.guiScale().set(scale);
            minecraft.resizeGui();
        });
        input.waitTicks(1);
    }

    public void expect(MenuClientMessage expected) {
        input.waitFor(minecraft -> NativeMenuSink.messages().contains(expected), TIMEOUT_TICKS);
        input.waitTicks(DUPLICATE_TICKS);
        List<MenuClientMessage> messages = NativeMenuSink.messages();
        int matches = 0;
        for (MenuClientMessage message : messages) {
            if (message.equals(expected)) {
                matches++;
            }
        }
        if (matches != 1) {
            throw new AssertionError("expected exactly one " + expected + " but saw " + messages);
        }
        NativeMenuSink.clear();
    }

    public void expectNothing(int ticks) {
        input.waitTicks(ticks);
        List<MenuClientMessage> messages = NativeMenuSink.messages();
        if (!messages.isEmpty()) {
            throw new AssertionError("expected no native menu message but saw " + messages);
        }
    }

    public void assertLayout() {
        List<String> problems = input.computeOnClient(minecraft -> layoutProblems(minecraft));
        if (!problems.isEmpty()) {
            throw new AssertionError("native menu layout problems: " + String.join("; ", problems));
        }
    }

    private List<String> layoutProblems(Minecraft minecraft) {
        NativeMenuScreen screen = screen();
        MenuLayout.Rect bounds = new MenuLayout.Rect(0, 0, screen.width, screen.height);
        MenuLayout.Rect viewport = screen.rowList().viewport();
        List<String> problems = new ArrayList<>();
        List<AbstractWidget> visible = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (MenuRow row : screen.rows()) {
            if (row.geometry() != null && row.geometry().labelRight() > screen.width) {
                problems.add(row.id() + " label runs off screen");
            }
            for (AbstractWidget widget : row.widgets()) {
                MenuLayout.Rect rect = rect(widget);
                if (rect.y() >= viewport.y() && rect.bottom() <= viewport.bottom()) {
                    visible.add(widget);
                    names.add(row.id());
                }
            }
        }
        for (NativeMenuScreen.FooterPart part : NativeMenuScreen.FooterPart.values()) {
            screen.footerButton(part).ifPresent(widget -> {
                visible.add(widget);
                names.add(part.name());
            });
        }
        for (int index = 0; index < visible.size(); index++) {
            MenuLayout.Rect rect = rect(visible.get(index));
            if (rect.x() < bounds.x() || rect.y() < bounds.y() || rect.right() > bounds.right() || rect.bottom() > bounds.bottom()) {
                problems.add(names.get(index) + " widget " + rect + " leaves the screen " + bounds);
            }
            if (rect.width() < MenuLayout.BTN_H || rect.height() < MenuLayout.BTN_H) {
                problems.add(names.get(index) + " widget " + rect + " is smaller than a 20 px target");
            }
            for (int other = index + 1; other < visible.size(); other++) {
                if (rect.intersects(rect(visible.get(other)))) {
                    problems.add(names.get(index) + " overlaps " + names.get(other));
                }
            }
        }
        if (viewport.y() < screen.header().height()) {
            problems.add("list starts inside the header");
        }
        return problems;
    }

    private void clickFooter(NativeMenuScreen.FooterPart part) {
        clickRect(input.computeOnClient(minecraft -> rect(screen().footerButton(part).orElseThrow(() -> new AssertionError("no footer " + part)))));
    }

    private void clickWidget(String elementId, Function<NativeMenuScreen, Optional<? extends AbstractWidget>> lookup, boolean shift) {
        scrollTo(elementId);
        MenuLayout.Rect rect = input.computeOnClient(minecraft -> rect(lookup.apply(screen()).orElseThrow(() -> missing(elementId))));
        clickAt(rect.x() + rect.width() / 2.0D, rect.y() + rect.height() / 2.0D, shift);
    }

    private void clickRect(MenuLayout.Rect rect) {
        clickAt(rect.x() + rect.width() / 2.0D, rect.y() + rect.height() / 2.0D, false);
    }

    private void clickAt(double guiX, double guiY, boolean shift) {
        moveTo(guiX, guiY);
        NativeMenuSink.clear();
        if (shift) {
            input.holdShift();
            input.runOnClient(MenuScreenDriver::pressShifted);
            input.releaseShift();
        } else {
            input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        }
        input.waitTicks(1);
    }

    private static void pressShifted(Minecraft minecraft) {
        NativeMenuMouseAccess mouse = (NativeMenuMouseAccess) minecraft.mouseHandler;
        MouseButtonInfo button = new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOD_SHIFT);
        long handle = minecraft.getWindow().handle();
        mouse.nativeMenu$onButton(handle, button, InputConstants.PRESS);
        mouse.nativeMenu$onButton(handle, button, InputConstants.RELEASE);
    }

    private void moveTo(double guiX, double guiY) {
        double[] window = input.computeOnClient(minecraft -> {
            Window frame = minecraft.getWindow();
            double scaleX = (double) frame.getScreenWidth() / frame.getGuiScaledWidth();
            double scaleY = (double) frame.getScreenHeight() / frame.getGuiScaledHeight();
            return new double[]{guiX * scaleX, guiY * scaleY};
        });
        input.setCursorPos(window[0], window[1]);
        input.waitTicks(1);
    }

    private int visibility(String elementId) {
        NativeMenuScreen screen = screen();
        MenuRow row = screen.rowFor(elementId).orElse(null);
        if (row == null || row.geometry() == null) {
            return 0;
        }
        MenuLayout.Rect viewport = screen.rowList().viewport();
        MenuLayout.Rect entry = row.geometry().entry();
        if (entry.y() < viewport.y() + 2) {
            return 1;
        }
        if (entry.bottom() > viewport.bottom() - 2) {
            return -1;
        }
        return 0;
    }

    private static NativeMenuScreen screen() {
        return NativeMenuListener.current().orElseThrow(() -> new AssertionError("no native menu screen is open: " + ClientScreens.current()));
    }

    private static MenuLayout.Rect rect(AbstractWidget widget) {
        return new MenuLayout.Rect(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
    }

    private static AssertionError missing(String elementId) {
        return new AssertionError("no native menu widget for " + elementId);
    }
}
