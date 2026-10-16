package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

public final class MenuDriver {
    public final FakeMenuHost host = new FakeMenuHost();
    public final FakeMenuViewer viewer;
    public final MenuService service;
    private boolean nativeScreens;

    public MenuDriver(UUID viewerId, String name, List<MenuController> controllers) {
        viewer = host.viewer(viewerId, name);
        service = new MenuService(host, new MenuRegistry(controllers), () -> 1_000L);
    }

    public void open(MenuRoute route) {
        service.open(viewer.id(), route);
    }

    public void hello() {
        nativeScreens = true;
        receive(new MenuClientMessage.Hello(1));
    }

    public void chest(String elementId) {
        chest(elementId, MenuInteraction.PRIMARY);
    }

    public void chest(String elementId, MenuInteraction interaction) {
        service.chestAction(viewer.id(), new MenuChestAction(elementId, interaction, null, false, null));
    }

    public void chestAction(String elementId, MenuInteraction interaction, MenuValue value, boolean preconfirmed) {
        service.chestAction(viewer.id(), new MenuChestAction(elementId, interaction, value, preconfirmed, null));
    }

    public void chestPreconfirmed(String elementId) {
        service.chestAction(viewer.id(), new MenuChestAction(elementId, MenuInteraction.PRIMARY, null, true, null));
    }

    public void chestText(String elementId, String text) {
        service.chestAction(viewer.id(), new MenuChestAction(elementId, MenuInteraction.SET_TEXT, MenuValue.text(text), false, null));
    }

    public void nativeAction(String elementId, MenuInteraction interaction, MenuValue value) {
        nativeActionAt(revision(), elementId, interaction, value);
    }

    public void nativeActionAt(long revision, String elementId, MenuInteraction interaction, MenuValue value) {
        receive(new MenuClientMessage.Action(new MenuAction(revision, elementId, interaction, value)));
    }

    public void nativeConfirm(boolean accept) {
        receive(new MenuClientMessage.Confirm(revision(), accept));
    }

    public void nativePage(int page) {
        receive(new MenuClientMessage.Page(revision(), page));
    }

    public void tick(int ticks) {
        for (int index = 0; index < ticks; index++) {
            service.tick();
        }
    }

    public long revision() {
        if (nativeScreens) {
            try {
                return host.screenRevision();
            } catch (MenuProtocolException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return host.chest.builds.isEmpty() ? -1L : host.chest.screen().revision();
    }

    public boolean sessionOpen() {
        if (nativeScreens) {
            return !host.frames.isEmpty() && !(lastScreenMessage() instanceof MenuServerMessage.Close)
                && !(lastScreenMessage() instanceof MenuServerMessage.Accept);
        }
        for (int index = host.chest.events.size() - 1; index >= 0; index--) {
            String event = host.chest.events.get(index);
            if (!event.startsWith("notice:")) {
                return !event.equals("close");
            }
        }
        return false;
    }

    public MenuBuild build() {
        return host.chest.last();
    }

    public MenuScreen screen() {
        return host.chest.screen();
    }

    public MenuElement element(String elementId) {
        return element(screen(), elementId);
    }

    public MenuServerMessage lastMessage() {
        try {
            return host.lastMessage();
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public MenuScreen nativeScreen() {
        List<MenuServerMessage> messages;
        try {
            messages = host.serverMessages();
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            MenuServerMessage message = messages.get(index);
            if (message instanceof MenuServerMessage.Open open) {
                return open.screen();
            }
            if (message instanceof MenuServerMessage.Update update) {
                return update.screen();
            }
        }
        throw new IllegalStateException("no native screen was sent");
    }

    public MenuServerMessage lastScreenMessage() {
        List<MenuServerMessage> messages;
        try {
            messages = host.serverMessages();
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            MenuServerMessage message = messages.get(index);
            if (!(message instanceof MenuServerMessage.Feedback)) {
                return message;
            }
        }
        throw new IllegalStateException("no native screen message was sent");
    }

    public List<String> noticeTexts() {
        List<String> texts = new ArrayList<>(host.chest.notices.size());
        for (MenuNotice notice : host.chest.notices) {
            texts.add(notice.feedback().text());
        }
        return texts;
    }

    public String lastNotice() {
        return host.chest.lastNotice();
    }

    public MenuNotice lastNoticeRecord() {
        return host.chest.notices.get(host.chest.notices.size() - 1);
    }

    public String lastEvent() {
        return host.chest.lastEvent();
    }

    private void receive(MenuClientMessage message) {
        try {
            service.receive(viewer.id(), MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static MenuElement element(MenuScreen screen, String elementId) {
        return screen.element(elementId).orElseThrow(() -> new AssertionError("no element " + elementId + " on " + screen.screenId()));
    }

    public static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return map;
    }

    public static List<String> chestLore(MenuElement element) {
        List<String> lore = new ArrayList<>(element.description());
        lore.addAll(element.common().chestHints());
        return lore;
    }

    public static List<String> ids(MenuScreen screen) {
        List<String> ids = new ArrayList<>(screen.elements().size());
        for (MenuElement element : screen.elements()) {
            ids.add(element.id());
        }
        return ids;
    }

    public static Map<String, String> layout(MenuScreen screen) {
        Map<String, String> layout = new LinkedHashMap<>();
        for (MenuElement element : screen.elements()) {
            layout.put(element.id(), describe(element));
        }
        return layout;
    }

    public static String describe(MenuElement element) {
        MenuSlot slot = element.chestSlot();
        MenuIcon.Item item = (MenuIcon.Item) element.icon();
        MenuIcon nativeIcon = element.common().nativeIcon();
        String sprite = nativeIcon instanceof MenuIcon.Sprite named ? named.name() : "-";
        String position = slot == null ? "-" : slot.position() + "," + slot.row();
        StringBuilder clicks = new StringBuilder("{");
        if (slot != null) {
            for (Map.Entry<ChestClick, ChestTarget> click : slot.clicks().entrySet()) {
                if (clicks.length() > 1) {
                    clicks.append(", ");
                }
                ChestTarget target = click.getValue();
                clicks.append(click.getKey()).append('=');
                if (target.elementId() != null) {
                    clicks.append(target.elementId()).append('.');
                }
                clicks.append(code(target.interaction())).append(target.preconfirmed() ? "!" : "");
            }
        }
        clicks.append('}');
        StringBuilder sides = new StringBuilder();
        for (MenuSideAction side : element.common().sideActions()) {
            sides.append(' ').append(code(side.interaction())).append(':').append(((MenuIcon.Sprite) side.icon()).name());
        }
        String kind = element.getClass().getSimpleName().replace("Element", "");
        return kind + " " + position + " " + item.itemId() + (item.glint() ? "*" : "") + (item.count() > 1 ? "x" + item.count() : "") + " "
            + sprite + " " + interactions(element) + " " + clicks + sides + (element.common().nativeHidden() ? " hidden" : "");
    }

    public static void assertBound(MenuBuild build) {
        for (MenuElement element : build.screen().elements()) {
            for (MenuInteraction interaction : element.interactions()) {
                assertTrue(build.bindings().find(element.id()).flatMap(binding -> binding.handler(interaction)).isPresent(),
                    element.id() + " " + interaction);
            }
        }
    }

    private static String interactions(MenuElement element) {
        StringBuilder codes = new StringBuilder("[");
        for (MenuInteraction interaction : MenuInteraction.values()) {
            if (element.interactions().contains(interaction)) {
                codes.append(code(interaction));
            }
        }
        return codes.append(']').toString();
    }

    private static String code(MenuInteraction interaction) {
        return switch (interaction) {
            case PRIMARY -> "P";
            case SECONDARY -> "S";
            case TERTIARY -> "T";
            case SET_TEXT -> "X";
            case SELECT -> "Sel";
            default -> interaction.name();
        };
    }
}
