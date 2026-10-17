package art.arcane.wormholes.menu.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.ItemCaptureElement;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.LookCaptureElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuChrome;
import art.arcane.wormholes.menu.MenuConfirmation;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedback;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuStyle;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;

public final class MenuCodec {
    public static final int S2C_ACCEPT = 1;
    public static final int S2C_OPEN = 2;
    public static final int S2C_UPDATE = 3;
    public static final int S2C_CLOSE = 4;
    public static final int S2C_FEEDBACK = 5;
    public static final int C2S_HELLO = 32;
    public static final int C2S_ACTION = 33;
    public static final int C2S_BACK = 34;
    public static final int C2S_PAGE = 35;
    public static final int C2S_CONFIRM = 36;
    public static final int C2S_CLOSED = 37;
    public static final int VALUE_NONE = 0;
    public static final int VALUE_INDEX = 1;
    public static final int VALUE_NUMBER = 2;
    public static final int VALUE_TEXT = 3;
    private static final int ICON_ITEM = 1;
    private static final int ICON_SPRITE = 2;
    private static final int SCREEN_CAN_GO_BACK = 1;
    private static final int SCREEN_PAGING = 2;
    private static final int SCREEN_FEEDBACK = 4;
    private static final int SCREEN_CONFIRMATION = 8;
    private static final int SCREEN_FLAGS = SCREEN_CAN_GO_BACK | SCREEN_PAGING | SCREEN_FEEDBACK | SCREEN_CONFIRMATION;
    private static final int KIND_BUTTON = 0;
    private static final int KIND_TOGGLE = 1;
    private static final int KIND_CYCLE = 2;
    private static final int KIND_NUMBER = 3;
    private static final int KIND_TEXT = 4;
    private static final int KIND_LIST_ENTRY = 5;
    private static final int KIND_LABEL = 6;
    private static final int KIND_ITEM_CAPTURE = 7;
    private static final int KIND_LOOK_CAPTURE = 8;
    private static final int MIN_ELEMENT_BYTES = 12;
    private static final int MIN_STRING_BYTES = 1;
    private static final int MIN_SIDE_ACTION_BYTES = 5;

    private MenuCodec() {
    }

    public static byte[] encodeClient(MenuClientMessage message) throws MenuProtocolException {
        ViewStreamWriter out = new ViewStreamWriter(64);
        try {
            switch (message) {
                case MenuClientMessage.Hello hello -> {
                    out.u8(C2S_HELLO);
                    out.u8(hello.version());
                }
                case MenuClientMessage.Action action -> {
                    out.u8(C2S_ACTION);
                    writeAction(out, action.action());
                }
                case MenuClientMessage.Back back -> {
                    out.u8(C2S_BACK);
                    out.i64(back.revision());
                }
                case MenuClientMessage.Page page -> {
                    out.u8(C2S_PAGE);
                    out.i64(page.revision());
                    out.i32(page.page());
                }
                case MenuClientMessage.Confirm confirm -> {
                    out.u8(C2S_CONFIRM);
                    out.i64(confirm.revision());
                    out.u8(confirm.accepted() ? 1 : 0);
                }
                case MenuClientMessage.Closed closed -> {
                    out.u8(C2S_CLOSED);
                    out.i64(closed.revision());
                }
            }
        } catch (ViewStreamProtocolException exception) {
            throw new MenuProtocolException("cannot encode client message: " + exception.getMessage(), exception);
        }
        if (out.size() > MenuLimits.MAX_C2S_BYTES) {
            throw new MenuProtocolException("client frame of " + out.size() + " bytes exceeds " + MenuLimits.MAX_C2S_BYTES);
        }
        return out.toByteArray();
    }

    public static MenuClientMessage decodeClient(byte[] frame) throws MenuProtocolException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length > MenuLimits.MAX_C2S_BYTES) {
            throw new MenuProtocolException("client frame of " + frame.length + " bytes exceeds " + MenuLimits.MAX_C2S_BYTES);
        }
        ViewStreamReader in = new ViewStreamReader(frame);
        try {
            int id = in.u8();
            MenuClientMessage message = switch (id) {
                case C2S_HELLO -> new MenuClientMessage.Hello(in.u8());
                case C2S_ACTION -> new MenuClientMessage.Action(readAction(in));
                case C2S_BACK -> new MenuClientMessage.Back(in.i64());
                case C2S_PAGE -> new MenuClientMessage.Page(in.i64(), in.i32());
                case C2S_CONFIRM -> new MenuClientMessage.Confirm(in.i64(), readBoolean(in));
                case C2S_CLOSED -> new MenuClientMessage.Closed(in.i64());
                default -> throw new ViewStreamProtocolException("unknown client message id " + id);
            };
            in.expectEnd();
            return message;
        } catch (ViewStreamProtocolException exception) {
            throw new MenuProtocolException("malformed client frame: " + exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new MenuProtocolException("invalid client frame: " + exception.getMessage(), exception);
        }
    }

    public static byte[] encodeServer(MenuServerMessage message) throws MenuProtocolException {
        ViewStreamWriter out = new ViewStreamWriter(256);
        try {
            switch (message) {
                case MenuServerMessage.Accept accept -> {
                    out.u8(S2C_ACCEPT);
                    out.u8(accept.version());
                    writeString(out, accept.server().wormholesVersion());
                    writeString(out, accept.server().platform());
                    out.u8(accept.server().nativeScreens() ? 1 : 0);
                }
                case MenuServerMessage.Open open -> {
                    out.u8(S2C_OPEN);
                    writeScreen(out, wireScreen(open.screen()));
                }
                case MenuServerMessage.Update update -> {
                    out.u8(S2C_UPDATE);
                    writeScreen(out, wireScreen(update.screen()));
                }
                case MenuServerMessage.Close _ -> out.u8(S2C_CLOSE);
                case MenuServerMessage.Feedback feedback -> {
                    out.u8(S2C_FEEDBACK);
                    writeFeedback(out, feedback.feedback());
                }
            }
        } catch (ViewStreamProtocolException exception) {
            throw new MenuProtocolException("cannot encode server message: " + exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new MenuProtocolException("server message has no valid wire form: " + exception.getMessage(), exception);
        }
        if (out.size() > MenuLimits.MAX_S2C_BYTES) {
            throw new MenuProtocolException("server frame of " + out.size() + " bytes exceeds " + MenuLimits.MAX_S2C_BYTES);
        }
        return out.toByteArray();
    }

    public static MenuServerMessage decodeServer(byte[] frame) throws MenuProtocolException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length > MenuLimits.MAX_S2C_BYTES) {
            throw new MenuProtocolException("server frame of " + frame.length + " bytes exceeds " + MenuLimits.MAX_S2C_BYTES);
        }
        ViewStreamReader in = new ViewStreamReader(frame);
        try {
            int id = in.u8();
            MenuServerMessage message = switch (id) {
                case S2C_ACCEPT -> new MenuServerMessage.Accept(in.u8(), new MenuServerInfo(readString(in), readString(in), readBoolean(in)));
                case S2C_OPEN -> new MenuServerMessage.Open(readScreen(in));
                case S2C_UPDATE -> new MenuServerMessage.Update(readScreen(in));
                case S2C_CLOSE -> new MenuServerMessage.Close();
                case S2C_FEEDBACK -> new MenuServerMessage.Feedback(readFeedback(in));
                default -> throw new ViewStreamProtocolException("unknown server message id " + id);
            };
            in.expectEnd();
            return message;
        } catch (ViewStreamProtocolException exception) {
            throw new MenuProtocolException("malformed server frame: " + exception.getMessage(), exception);
        } catch (IllegalArgumentException exception) {
            throw new MenuProtocolException("invalid server frame: " + exception.getMessage(), exception);
        }
    }

    public static MenuScreen wireScreen(MenuScreen screen) {
        List<MenuElement> visible = new ArrayList<>(screen.elements().size());
        for (MenuElement element : screen.elements()) {
            if (element.common().nativeVisible()) {
                visible.add(wireElement(element));
            }
        }
        return screen.toBuilder().elements(visible).build();
    }

    private static MenuElement wireElement(MenuElement element) {
        MenuElementCommon common = element.common().toBuilder()
            .label(element.common().wireLabel())
            .nativeLabel(null)
            .description(element.common().wireDescription())
            .nativeDescription(null)
            .chestHints(List.of())
            .icon(element.common().wireIcon())
            .nativeIcon(null)
            .chestSlot(null)
            .nativeHidden(false)
            .build();
        return switch (element) {
            case ButtonElement _ -> new ButtonElement(common);
            case ToggleElement toggle -> new ToggleElement(common, toggle.value());
            case CycleElement cycle -> new CycleElement(common, cycle.options(), cycle.index());
            case NumberElement number -> new NumberElement(common, number.value(), number.min(), number.max(), number.step(),
                number.bigStep(), number.display());
            case TextElement text -> new TextElement(common, text.value(), text.maxLength(), text.placeholder(), List.of(), ChestReopen.ALWAYS,
                null);
            case ListEntryElement entry -> new ListEntryElement(common, entry.selected());
            case LabelElement _ -> new LabelElement(common);
            case ItemCaptureElement _ -> new ItemCaptureElement(common, List.of());
            case LookCaptureElement _ -> new LookCaptureElement(common);
        };
    }

    private static void writeAction(ViewStreamWriter out, MenuAction action) throws ViewStreamProtocolException {
        out.i64(action.revision());
        writeString(out, action.elementId());
        out.u8(action.interaction().wireId());
        switch (action.value()) {
            case null -> out.u8(VALUE_NONE);
            case MenuValue.Index index -> {
                out.u8(VALUE_INDEX);
                out.i32(index.index());
            }
            case MenuValue.Number number -> {
                out.u8(VALUE_NUMBER);
                out.f64(number.number());
            }
            case MenuValue.Text text -> {
                out.u8(VALUE_TEXT);
                writeString(out, text.text());
            }
        }
    }

    private static MenuAction readAction(ViewStreamReader in) throws ViewStreamProtocolException {
        long revision = in.i64();
        String elementId = readString(in);
        MenuInteraction interaction = readInteraction(in);
        int tag = in.u8();
        MenuValue value = switch (tag) {
            case VALUE_NONE -> null;
            case VALUE_INDEX -> MenuValue.index(in.i32());
            case VALUE_NUMBER -> MenuValue.number(in.f64());
            case VALUE_TEXT -> MenuValue.text(readString(in));
            default -> throw new ViewStreamProtocolException("unknown value kind " + tag);
        };
        return new MenuAction(revision, elementId, interaction, value);
    }

    private static void writeScreen(ViewStreamWriter out, MenuScreen screen) throws ViewStreamProtocolException {
        writeString(out, screen.screenId());
        writeString(out, screen.title());
        writeString(out, screen.context());
        out.i64(screen.revision());
        int flags = (screen.canGoBack() ? SCREEN_CAN_GO_BACK : 0)
            | (screen.paging() != null ? SCREEN_PAGING : 0)
            | (screen.feedback() != null ? SCREEN_FEEDBACK : 0)
            | (screen.confirmation() != null ? SCREEN_CONFIRMATION : 0);
        out.u8(flags);
        if (screen.paging() != null) {
            out.i32(screen.paging().page());
            out.i32(screen.paging().pageCount());
        }
        if (screen.feedback() != null) {
            writeFeedback(out, screen.feedback());
        }
        if (screen.confirmation() != null) {
            writeLines(out, screen.confirmation().messageLines());
            writeString(out, screen.confirmation().confirmElementId());
            writeString(out, screen.confirmation().cancelElementId());
        }
        writeChrome(out, screen.chrome());
        out.u16(screen.elements().size());
        for (MenuElement element : screen.elements()) {
            writeElement(out, element);
        }
    }

    private static MenuScreen readScreen(ViewStreamReader in) throws ViewStreamProtocolException {
        String screenId = readString(in);
        String title = readString(in);
        String context = readString(in);
        long revision = in.i64();
        int flags = in.u8();
        if ((flags & ~SCREEN_FLAGS) != 0) {
            throw new ViewStreamProtocolException("unknown screen flags " + flags);
        }
        MenuPaging paging = (flags & SCREEN_PAGING) == 0 ? null : new MenuPaging(in.i32(), in.i32());
        MenuFeedback feedback = (flags & SCREEN_FEEDBACK) == 0 ? null : readFeedback(in);
        MenuConfirmation confirmation = (flags & SCREEN_CONFIRMATION) == 0
            ? null
            : new MenuConfirmation(readLines(in, MenuLimits.MAX_CONFIRMATION_LINES), readString(in), readString(in));
        MenuChrome chrome = readChrome(in);
        int count = in.checkedCount(in.u16(), MenuLimits.MAX_ELEMENTS, MIN_ELEMENT_BYTES);
        List<MenuElement> elements = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            elements.add(readElement(in));
        }
        return new MenuScreen(screenId, title, context, revision, elements, paging, (flags & SCREEN_CAN_GO_BACK) != 0, feedback,
            confirmation, chrome);
    }

    private static void writeChrome(ViewStreamWriter out, MenuChrome chrome) throws ViewStreamProtocolException {
        writeString(out, chrome.back());
        writeString(out, chrome.close());
        writeString(out, chrome.apply());
        writeString(out, chrome.confirm());
        writeString(out, chrome.cancel());
        writeString(out, chrome.previousPage());
        writeString(out, chrome.nextPage());
        writeString(out, chrome.pageLabel());
        writeString(out, chrome.useHeldItem());
        writeString(out, chrome.aim());
        writeString(out, chrome.timeout());
    }

    private static MenuChrome readChrome(ViewStreamReader in) throws ViewStreamProtocolException {
        return new MenuChrome(readString(in), readString(in), readString(in), readString(in), readString(in), readString(in),
            readString(in), readString(in), readString(in), readString(in), readString(in));
    }

    private static void writeElement(ViewStreamWriter out, MenuElement element) throws ViewStreamProtocolException {
        out.u8(kind(element));
        writeCommon(out, element.common());
        switch (element) {
            case ToggleElement toggle -> out.u8(toggle.value() ? 1 : 0);
            case CycleElement cycle -> {
                writeLines(out, cycle.options());
                out.u8(cycle.index());
            }
            case NumberElement number -> {
                out.f64(number.value());
                out.f64(number.min());
                out.f64(number.max());
                out.f64(number.step());
                out.f64(number.bigStep());
                writeString(out, number.display());
            }
            case TextElement text -> {
                writeString(out, text.value());
                out.u16(text.maxLength());
                writeString(out, text.placeholder());
            }
            case ListEntryElement entry -> out.u8(entry.selected() ? 1 : 0);
            case ButtonElement _ -> {
            }
            case LabelElement _ -> {
            }
            case ItemCaptureElement _ -> {
            }
            case LookCaptureElement _ -> {
            }
        }
    }

    private static MenuElement readElement(ViewStreamReader in) throws ViewStreamProtocolException {
        int kind = in.u8();
        if (kind > KIND_LOOK_CAPTURE) {
            throw new ViewStreamProtocolException("unknown element kind " + kind);
        }
        MenuElementCommon common = readCommon(in);
        return switch (kind) {
            case KIND_BUTTON -> new ButtonElement(common);
            case KIND_TOGGLE -> new ToggleElement(common, readBoolean(in));
            case KIND_CYCLE -> new CycleElement(common, readLines(in, MenuLimits.MAX_CYCLE_OPTIONS), in.u8());
            case KIND_NUMBER -> new NumberElement(common, in.f64(), in.f64(), in.f64(), in.f64(), in.f64(), readString(in));
            case KIND_TEXT -> new TextElement(common, readString(in), in.u16(), readString(in), List.of(), ChestReopen.ALWAYS, null);
            case KIND_LIST_ENTRY -> new ListEntryElement(common, readBoolean(in));
            case KIND_LABEL -> new LabelElement(common);
            case KIND_ITEM_CAPTURE -> new ItemCaptureElement(common, List.of());
            default -> new LookCaptureElement(common);
        };
    }

    private static int kind(MenuElement element) {
        return switch (element) {
            case ButtonElement _ -> KIND_BUTTON;
            case ToggleElement _ -> KIND_TOGGLE;
            case CycleElement _ -> KIND_CYCLE;
            case NumberElement _ -> KIND_NUMBER;
            case TextElement _ -> KIND_TEXT;
            case ListEntryElement _ -> KIND_LIST_ENTRY;
            case LabelElement _ -> KIND_LABEL;
            case ItemCaptureElement _ -> KIND_ITEM_CAPTURE;
            case LookCaptureElement _ -> KIND_LOOK_CAPTURE;
        };
    }

    private static void writeCommon(ViewStreamWriter out, MenuElementCommon common) throws ViewStreamProtocolException {
        writeString(out, common.id());
        writeString(out, common.label());
        writeLines(out, common.description());
        writeIcon(out, common.icon());
        out.u8(common.enabled() ? 1 : 0);
        out.u8(common.style().wireId());
        out.u16(MenuInteraction.mask(common.interactions()));
        out.u8(common.sideActions().size());
        for (MenuSideAction action : common.sideActions()) {
            out.u8(action.interaction().wireId());
            writeString(out, action.label());
            writeIcon(out, action.icon());
        }
    }

    private static MenuElementCommon readCommon(ViewStreamReader in) throws ViewStreamProtocolException {
        String id = readString(in);
        String label = readString(in);
        List<String> description = readLines(in, MenuLimits.MAX_DESCRIPTION_LINES);
        MenuIcon icon = readIcon(in);
        boolean enabled = readBoolean(in);
        int styleId = in.u8();
        MenuStyle style = MenuStyle.byWireId(styleId);
        if (style == null) {
            throw new ViewStreamProtocolException("unknown element style " + styleId);
        }
        int interactions = in.u16();
        int sideActionCount = in.checkedCount(in.u8(), MenuLimits.MAX_SIDE_ACTIONS, MIN_SIDE_ACTION_BYTES);
        List<MenuSideAction> sideActions = new ArrayList<>(sideActionCount);
        for (int index = 0; index < sideActionCount; index++) {
            sideActions.add(new MenuSideAction(readInteraction(in), readString(in), readIcon(in)));
        }
        return MenuElementCommon.builder(id)
            .label(label)
            .description(description)
            .icon(icon)
            .enabled(enabled)
            .style(style)
            .interactions(MenuInteraction.fromMask(interactions))
            .sideActions(sideActions)
            .build();
    }

    private static void writeIcon(ViewStreamWriter out, MenuIcon icon) throws ViewStreamProtocolException {
        switch (icon) {
            case MenuIcon.Item item -> {
                out.u8(ICON_ITEM);
                writeString(out, item.itemId());
                out.u8(item.count());
                out.u8(item.glint() ? 1 : 0);
            }
            case MenuIcon.Sprite sprite -> {
                out.u8(ICON_SPRITE);
                writeString(out, sprite.name());
            }
        }
    }

    private static MenuIcon readIcon(ViewStreamReader in) throws ViewStreamProtocolException {
        int tag = in.u8();
        return switch (tag) {
            case ICON_ITEM -> MenuIcon.item(readString(in), in.u8(), readBoolean(in));
            case ICON_SPRITE -> MenuIcon.sprite(readString(in));
            default -> throw new ViewStreamProtocolException("unknown icon kind " + tag);
        };
    }

    private static void writeFeedback(ViewStreamWriter out, MenuFeedback feedback) throws ViewStreamProtocolException {
        out.u8(feedback.style().wireId());
        writeString(out, feedback.text());
    }

    private static MenuFeedback readFeedback(ViewStreamReader in) throws ViewStreamProtocolException {
        int styleId = in.u8();
        MenuFeedbackStyle style = MenuFeedbackStyle.byWireId(styleId);
        if (style == null) {
            throw new ViewStreamProtocolException("unknown feedback style " + styleId);
        }
        return new MenuFeedback(style, readString(in));
    }

    private static MenuInteraction readInteraction(ViewStreamReader in) throws ViewStreamProtocolException {
        int interactionId = in.u8();
        MenuInteraction interaction = MenuInteraction.byWireId(interactionId);
        if (interaction == null) {
            throw new ViewStreamProtocolException("unknown interaction " + interactionId);
        }
        return interaction;
    }

    private static boolean readBoolean(ViewStreamReader in) throws ViewStreamProtocolException {
        int value = in.u8();
        if (value > 1) {
            throw new ViewStreamProtocolException("boolean byte " + value + " is not 0 or 1");
        }
        return value == 1;
    }

    private static void writeLines(ViewStreamWriter out, List<String> lines) throws ViewStreamProtocolException {
        out.u8(lines.size());
        for (String line : lines) {
            writeString(out, line);
        }
    }

    private static List<String> readLines(ViewStreamReader in, int maxLines) throws ViewStreamProtocolException {
        int count = in.checkedCount(in.u8(), maxLines, MIN_STRING_BYTES);
        List<String> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            lines.add(readString(in));
        }
        return lines;
    }

    private static void writeString(ViewStreamWriter out, String value) throws ViewStreamProtocolException {
        byte[] bytes;
        try {
            bytes = MenuText.encode(value);
        } catch (IllegalArgumentException exception) {
            throw new ViewStreamProtocolException("string is not well-formed", exception);
        }
        if (bytes.length > MenuLimits.MAX_STRING_BYTES) {
            throw new ViewStreamProtocolException("string of " + bytes.length + " bytes exceeds " + MenuLimits.MAX_STRING_BYTES);
        }
        out.varint(bytes.length);
        out.bytes(bytes);
    }

    private static String readString(ViewStreamReader in) throws ViewStreamProtocolException {
        int length = in.varint(MenuLimits.MAX_STRING_BYTES);
        byte[] bytes = in.bytes(length);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new ViewStreamProtocolException("string is not valid UTF-8", exception);
        }
    }
}
