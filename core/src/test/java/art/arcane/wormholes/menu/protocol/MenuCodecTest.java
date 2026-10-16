package art.arcane.wormholes.menu.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
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
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStyle;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;

final class MenuCodecTest {
    private static final String GOLDEN = "/menu/golden-v1.hex";

    @Test
    void everyClientMessageRoundTrips() throws MenuProtocolException {
        List<MenuClientMessage> messages = List.of(
            new MenuClientMessage.Hello(1),
            new MenuClientMessage.Hello(0),
            new MenuClientMessage.Hello(255),
            new MenuClientMessage.Action(MenuAction.of(42L, "set-name", MenuInteraction.PRIMARY)),
            new MenuClientMessage.Action(new MenuAction(7L, "cycle", MenuInteraction.SELECT, MenuValue.index(3))),
            new MenuClientMessage.Action(new MenuAction(7L, "number", MenuInteraction.SET_VALUE, MenuValue.number(-12.5D))),
            new MenuClientMessage.Action(new MenuAction(7L, "name", MenuInteraction.SET_TEXT, MenuValue.text("Garden Arch é"))),
            new MenuClientMessage.Back(9L),
            new MenuClientMessage.Page(9L, 4),
            new MenuClientMessage.Confirm(9L, true),
            new MenuClientMessage.Confirm(9L, false),
            new MenuClientMessage.Closed(9L));
        for (MenuClientMessage message : messages) {
            assertEquals(message, MenuCodec.decodeClient(MenuCodec.encodeClient(message)));
        }
    }

    @Test
    void everyServerMessageAndElementKindRoundTrips() throws MenuProtocolException {
        MenuScreen screen = goldenScreen();
        List<MenuServerMessage> messages = List.of(
            new MenuServerMessage.Accept(1),
            new MenuServerMessage.Open(screen),
            new MenuServerMessage.Update(screen.withRevision(5L)),
            new MenuServerMessage.Close(),
            new MenuServerMessage.Feedback(MenuFeedback.error("No permission")));
        for (MenuServerMessage message : messages) {
            MenuServerMessage decoded = MenuCodec.decodeServer(MenuCodec.encodeServer(message));
            MenuServerMessage expected = switch (message) {
                case MenuServerMessage.Open open -> new MenuServerMessage.Open(MenuCodec.wireScreen(open.screen()));
                case MenuServerMessage.Update update -> new MenuServerMessage.Update(MenuCodec.wireScreen(update.screen()));
                default -> message;
            };
            assertEquals(expected, decoded);
        }
        Set<Class<?>> kinds = new HashSet<>();
        for (MenuElement element : MenuCodec.wireScreen(screen).elements()) {
            kinds.add(element.getClass());
        }
        assertEquals(Set.of(ButtonElement.class, ToggleElement.class, CycleElement.class, NumberElement.class, TextElement.class,
            ListEntryElement.class, LabelElement.class, ItemCaptureElement.class, LookCaptureElement.class), kinds);
    }

    @Test
    void serverOnlyFieldsNeverReachTheWire() throws MenuProtocolException {
        MenuScreen decoded = ((MenuServerMessage.Open) MenuCodec.decodeServer(MenuCodec.encodeServer(new MenuServerMessage.Open(goldenScreen())))).screen();

        assertTrue(decoded.element("back").isEmpty());
        MenuElement cost = decoded.element("cost").orElseThrow();
        assertNull(cost.chestSlot());
        assertTrue(cost.common().chestHints().isEmpty());
        assertEquals(MenuIcon.sprite("settings"), cost.icon());
        assertNull(cost.common().nativeIcon());
        ItemCaptureElement template = (ItemCaptureElement) decoded.element("template").orElseThrow();
        assertNull(((MenuIcon.Item) template.icon()).templateRef());
        assertTrue(template.chestPrompt().isEmpty());
        TextElement name = (TextElement) decoded.element("name").orElseThrow();
        assertTrue(name.chestPrompt().isEmpty());
        assertEquals(ChestReopen.ALWAYS, name.chestReopen());
        assertNull(name.chestCancelRoute());
        assertEquals("The server did not answer.", decoded.chrome().timeout());
    }

    @Test
    void nativeDescriptionsReplaceTheChestDescriptionOnTheWire() throws MenuProtocolException {
        MenuScreen screen = MenuScreen.builder("s").title("t").elements(List.of(
            new ButtonElement(MenuElementCommon.builder("swapped").label("Swapped").description(List.of("chest only"))
                .nativeDescription(List.of("native only")).interactions(Set.of(MenuInteraction.PRIMARY)).build()),
            new ButtonElement(MenuElementCommon.builder("plain").label("Plain").description(List.of("shared"))
                .interactions(Set.of(MenuInteraction.PRIMARY)).build()))).build();

        MenuScreen decoded = ((MenuServerMessage.Open) MenuCodec.decodeServer(MenuCodec.encodeServer(new MenuServerMessage.Open(screen)))).screen();

        MenuElement swapped = decoded.element("swapped").orElseThrow();
        assertEquals(List.of("native only"), swapped.description());
        assertNull(swapped.common().nativeDescription());
        MenuElement plain = decoded.element("plain").orElseThrow();
        assertEquals(List.of("shared"), plain.description());
        assertNull(plain.common().nativeDescription());
        assertEquals(decoded, MenuCodec.wireScreen(screen));
    }

    @Test
    void goldenVectorsAreStable() throws MenuProtocolException, IOException {
        Map<String, byte[]> frames = new LinkedHashMap<>();
        frames.put("hello", MenuCodec.encodeClient(new MenuClientMessage.Hello(1)));
        frames.put("action", MenuCodec.encodeClient(new MenuClientMessage.Action(new MenuAction(3L, "name", MenuInteraction.SET_TEXT,
            MenuValue.text("Arch")))));
        frames.put("accept", MenuCodec.encodeServer(new MenuServerMessage.Accept(1)));
        frames.put("open", MenuCodec.encodeServer(new MenuServerMessage.Open(goldenScreen())));
        StringBuilder actual = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : frames.entrySet()) {
            actual.append(entry.getKey()).append(' ').append(HexFormat.of().formatHex(entry.getValue())).append('\n');
        }
        String expected;
        try (InputStream in = MenuCodecTest.class.getResourceAsStream(GOLDEN)) {
            assertNotNull(in, "missing " + GOLDEN + "; current vectors:\n" + actual);
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(expected, actual.toString());
        assertArrayEquals(new byte[] {(byte) MenuCodec.C2S_HELLO, 1}, frames.get("hello"));
        assertArrayEquals(new byte[] {(byte) MenuCodec.S2C_ACCEPT, 1}, frames.get("accept"));
    }

    @Test
    void everyTruncatedPrefixIsRejected() throws MenuProtocolException {
        byte[] screen = MenuCodec.encodeServer(new MenuServerMessage.Open(goldenScreen()));
        for (int length = 0; length < screen.length; length++) {
            byte[] truncated = Arrays.copyOf(screen, length);
            assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(truncated), "length " + length);
        }
        byte[] action = MenuCodec.encodeClient(new MenuClientMessage.Action(new MenuAction(3L, "name", MenuInteraction.SET_TEXT, MenuValue.text("abc"))));
        for (int length = 0; length < action.length; length++) {
            byte[] truncated = Arrays.copyOf(action, length);
            assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(truncated), "length " + length);
        }
    }

    @Test
    void trailingBytesAreRejected() throws MenuProtocolException {
        byte[] back = MenuCodec.encodeClient(new MenuClientMessage.Back(1L));
        byte[] accept = MenuCodec.encodeServer(new MenuServerMessage.Accept(1));

        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(Arrays.copyOf(back, back.length + 1)));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(Arrays.copyOf(accept, accept.length + 3)));
    }

    @Test
    void oversizedFramesAreRejected() {
        byte[] client = new byte[MenuLimits.MAX_C2S_BYTES + 1];
        client[0] = (byte) MenuCodec.C2S_BACK;
        byte[] server = new byte[MenuLimits.MAX_S2C_BYTES + 1];
        server[0] = (byte) MenuCodec.S2C_CLOSE;

        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(client));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(server));
    }

    @Test
    void unknownAndWrongDirectionIdsAreRejected() throws MenuProtocolException {
        byte[] hello = MenuCodec.encodeClient(new MenuClientMessage.Hello(1));
        byte[] accept = MenuCodec.encodeServer(new MenuServerMessage.Accept(1));

        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(new byte[] {0}));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(new byte[] {(byte) 200, 1}));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(accept));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(hello));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(new byte[0]));
    }

    @Test
    void badBooleansInteractionsAndValueKindsAreRejected() throws MenuProtocolException {
        byte[] primary = MenuCodec.encodeClient(new MenuClientMessage.Action(MenuAction.of(1L, "a", MenuInteraction.PRIMARY)));
        byte[] badInteraction = primary.clone();
        badInteraction[primary.length - 2] = (byte) 10;
        byte[] badValueKind = primary.clone();
        badValueKind[primary.length - 1] = (byte) 4;
        byte[] confirm = MenuCodec.encodeClient(new MenuClientMessage.Confirm(1L, true));
        byte[] badBoolean = confirm.clone();
        badBoolean[confirm.length - 1] = (byte) 2;
        byte[] feedback = MenuCodec.encodeServer(new MenuServerMessage.Feedback(MenuFeedback.info("hi")));
        byte[] badStyle = feedback.clone();
        badStyle[1] = (byte) 4;

        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(badInteraction));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(badValueKind));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(badBoolean));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(badStyle));
    }

    @Test
    void unknownFlagsInteractionBitsAndKindsAreRejected() throws MenuProtocolException {
        MenuScreen screen = MenuScreen.builder("s").title("t").elements(List.of(new ButtonElement(
            MenuElementCommon.builder("b").label("b").icon(MenuIcon.sprite("portal")).interactions(Set.of(MenuInteraction.PRIMARY)).build()))).build();
        byte[] frame = MenuCodec.encodeServer(new MenuServerMessage.Open(screen));
        int flagsOffset = 1 + 2 + 2 + 1 + 8;
        int chromeEnd = flagsOffset + 1 + 11;
        int kindOffset = chromeEnd + 2;
        int interactionsOffset = kindOffset + 1 + 2 + 2 + 1 + 1 + 7 + 1 + 1;
        byte[] badFlags = frame.clone();
        badFlags[flagsOffset] = (byte) 0x10;
        byte[] badKind = frame.clone();
        badKind[kindOffset] = (byte) 9;
        byte[] badBits = frame.clone();
        badBits[interactionsOffset + 1] = (byte) 0x04;
        byte[] kindMismatch = frame.clone();
        kindMismatch[interactionsOffset] = 0;
        kindMismatch[interactionsOffset + 1] = (byte) (1 << (MenuInteraction.SET_TEXT.wireId() - 8));

        assertEquals(1, frame[chromeEnd]);
        assertEquals(1, frame[interactionsOffset]);
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(badFlags));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(badKind));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(badBits));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(kindMismatch));
    }

    @Test
    void countsOverTheirLimitsAreRejected() {
        ByteArrayOutputStream manyElements = new ByteArrayOutputStream();
        manyElements.write(MenuCodec.S2C_OPEN);
        writeString(manyElements, "s");
        writeString(manyElements, "");
        writeString(manyElements, "");
        writeLong(manyElements, 0L);
        manyElements.write(0);
        for (int index = 0; index < 11; index++) {
            writeString(manyElements, "");
        }
        manyElements.write((MenuLimits.MAX_ELEMENTS + 1) & 0xFF);
        manyElements.write((MenuLimits.MAX_ELEMENTS + 1) >>> 8);
        ByteArrayOutputStream longString = new ByteArrayOutputStream();
        longString.write(MenuCodec.C2S_ACTION);
        writeLong(longString, 1L);
        writeVarint(longString, MenuLimits.MAX_STRING_BYTES + 1);
        longString.writeBytes("a".repeat(MenuLimits.MAX_STRING_BYTES + 1).getBytes(StandardCharsets.UTF_8));
        longString.write(MenuInteraction.PRIMARY.wireId());
        longString.write(0);

        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(manyElements.toByteArray()));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(longString.toByteArray()));
    }

    @Test
    void nonFiniteDoublesAndMalformedUtf8AreRejected() {
        ByteArrayOutputStream nonFinite = new ByteArrayOutputStream();
        nonFinite.write(MenuCodec.C2S_ACTION);
        writeLong(nonFinite, 1L);
        writeString(nonFinite, "n");
        nonFinite.write(MenuInteraction.SET_VALUE.wireId());
        nonFinite.write(MenuCodec.VALUE_NUMBER);
        writeLong(nonFinite, Double.doubleToRawLongBits(Double.POSITIVE_INFINITY));
        ByteArrayOutputStream malformed = new ByteArrayOutputStream();
        malformed.write(MenuCodec.C2S_ACTION);
        writeLong(malformed, 1L);
        malformed.write(2);
        malformed.write(0xC3);
        malformed.write(0x28);
        malformed.write(MenuInteraction.PRIMARY.wireId());
        malformed.write(0);

        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(nonFinite.toByteArray()));
        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeClient(malformed.toByteArray()));
    }

    @Test
    void invalidRecordsSurfaceAsProtocolErrors() throws MenuProtocolException {
        CycleElement cycle = new CycleElement(MenuElementCommon.builder("c").build(), List.of("a", "b"), 1);
        byte[] frame = MenuCodec.encodeServer(new MenuServerMessage.Open(MenuScreen.builder("s").elements(List.of(cycle)).build()));
        byte[] outOfRange = frame.clone();
        outOfRange[frame.length - 1] = (byte) 7;

        assertThrows(MenuProtocolException.class, () -> MenuCodec.decodeServer(outOfRange));
        assertThrows(IllegalArgumentException.class, () -> new MenuClientMessage.Hello(256));
        assertThrows(IllegalArgumentException.class, () -> new MenuServerMessage.Accept(0));
    }

    @Test
    void hiddenElementsReferencedByAConfirmationCannotBeEncoded() {
        ButtonElement hidden = new ButtonElement(MenuElementCommon.builder("yes").interactions(Set.of(MenuInteraction.PRIMARY))
            .chestSlot(new MenuSlot(0, 0)).nativeHidden(true).build());
        ButtonElement no = new ButtonElement(MenuElementCommon.builder("no").interactions(Set.of(MenuInteraction.PRIMARY)).build());
        MenuScreen screen = MenuScreen.builder("s").elements(List.of(hidden, no))
            .confirmation(new MenuConfirmation(List.of("Sure?"), "yes", "no")).build();

        assertThrows(MenuProtocolException.class, () -> MenuCodec.encodeServer(new MenuServerMessage.Open(screen)));
        assertFalse(screen.elements().isEmpty());
    }

    @Test
    void decodedScreensAreImmutable() throws MenuProtocolException {
        MenuServerMessage decoded = MenuCodec.decodeServer(MenuCodec.encodeServer(new MenuServerMessage.Open(goldenScreen())));
        MenuScreen screen = assertInstanceOf(MenuServerMessage.Open.class, decoded).screen();

        assertThrows(UnsupportedOperationException.class, () -> screen.elements().clear());
    }

    static MenuScreen goldenScreen() {
        MenuIcon rotate = MenuIcon.sprite("small/rotate");
        List<MenuElement> elements = List.of(
            new LabelElement(MenuElementCommon.builder("placard").label("§6§lAlpha§r").description(List.of("§7Type: Portal"))
                .icon(MenuIcon.item("minecraft:book")).nativeIcon(MenuIcon.sprite("portal")).chestSlot(new MenuSlot(0, 0)).build()),
            new ToggleElement(MenuElementCommon.builder("projection").label("Projection").icon(MenuIcon.item("minecraft:redstone_torch", 1, true))
                .interactions(Set.of(MenuInteraction.PRIMARY)).chestSlot(new MenuSlot(0, 1)).build(), true),
            new CycleElement(MenuElementCommon.builder("quality").label("Quality").icon(MenuIcon.sprite("settings"))
                .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.SELECT))
                .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, "Advanced", MenuIcon.sprite("small/settings")))).build(),
                List.of("Standard", "Custom"), 1),
            new NumberElement(MenuElementCommon.builder("range").label("Range").style(MenuStyle.PRIMARY)
                .interactions(Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT, MenuInteraction.SET_VALUE)).build(),
                16.0D, 0.0D, 256.0D, 8.0D, 32.0D, "16"),
            new TextElement(MenuElementCommon.builder("name").label("Name").interactions(Set.of(MenuInteraction.SET_TEXT)).build(),
                "Alpha", 256, "Portal name", List.of("Type the new name in chat"), ChestReopen.ON_CANCEL, MenuRoute.of("rules", "rules.profile")),
            new ListEntryElement(MenuElementCommon.builder("mirror").label("Mirror").enabled(false)
                .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY))
                .sideActions(List.of(new MenuSideAction(MenuInteraction.SECONDARY, "Rotate clockwise", rotate),
                    new MenuSideAction(MenuInteraction.TERTIARY, "Rotate counterclockwise", rotate))).build(), true),
            new ItemCaptureElement(MenuElementCommon.builder("template").label("Item").icon(MenuIcon.template("minecraft:diamond", 3, false, "cost-1"))
                .interactions(Set.of(MenuInteraction.PRIMARY)).build(), List.of("Hold the cost item, then press Drop")),
            new LookCaptureElement(MenuElementCommon.builder("direction").label("Direction").interactions(Set.of(MenuInteraction.PRIMARY)).build()),
            new ButtonElement(MenuElementCommon.builder("cost").label("Cost").chestHints(List.of("Click to open")).icon(MenuIcon.item("minecraft:hopper"))
                .nativeIcon(MenuIcon.sprite("settings")).interactions(Set.of(MenuInteraction.PRIMARY))
                .chestSlot(new MenuSlot(2, 1).withClick(ChestClick.SHIFT_LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY))).build()),
            new ButtonElement(MenuElementCommon.builder("back").label("Back").icon(MenuIcon.item("minecraft:arrow"))
                .interactions(Set.of(MenuInteraction.PRIMARY)).chestSlot(new MenuSlot(0, 2)).nativeHidden(true).build()),
            new ButtonElement(MenuElementCommon.builder("yes").label("Delete").style(MenuStyle.DESTRUCTIVE).interactions(Set.of(MenuInteraction.PRIMARY)).build()),
            new ButtonElement(MenuElementCommon.builder("no").label("Cancel").interactions(Set.of(MenuInteraction.PRIMARY)).build()));
        return MenuScreen.builder("portal.home")
            .title("Portal")
            .context("Alpha - Portal")
            .revision(3L)
            .elements(elements)
            .paging(new MenuPaging(1, 3))
            .canGoBack(true)
            .feedback(MenuFeedback.success("Saved"))
            .confirmation(new MenuConfirmation(List.of("Delete Alpha?"), "yes", "no"))
            .chrome(new MenuChrome("Back", "Close", "Apply", "Confirm", "Cancel", "Previous page", "Next page", "Page 2 / 3", "Use held item", "Aim",
                "The server did not answer."))
            .build();
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int shift = 0; shift < 64; shift += 8) {
            out.write((int) (value >>> shift) & 0xFF);
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }
}
