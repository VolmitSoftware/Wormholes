package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class MenuInitialPageTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final MenuRoute LIST = MenuRoute.of("paged", "paged.list");
    private static final MenuRoute OTHER = MenuRoute.of("paged", "paged.other");
    private static final Logger MENU_LOGGER = Logger.getLogger(MenuService.class.getName());

    private PagedController controller;
    private MenuDriver driver;
    private RecordingHandler logs;

    @BeforeEach
    void setUp() {
        controller = new PagedController();
        driver = new MenuDriver(VIEWER, "Viewer", List.of(controller));
        logs = new RecordingHandler();
        MENU_LOGGER.addHandler(logs);
    }

    @AfterEach
    void tearDown() {
        MENU_LOGGER.removeHandler(logs);
    }

    @Test
    void openingStartsOnTheControllersPage() {
        controller.anchor = 3;

        driver.open(LIST);

        assertEquals(new MenuPaging(3, 5), driver.screen().paging());
        assertEquals(List.of(3), controller.builtPages);
    }

    @Test
    void explicitPagingWinsOverTheAnchor() {
        controller.anchor = 1;
        driver.open(LIST);

        driver.chest("previous");
        driver.chest("previous");

        assertEquals(new MenuPaging(0, 5), driver.screen().paging());
        assertEquals(List.of(1, 0, 0), controller.builtPages);
    }

    @Test
    void pushedRoutesAnchorEachTimeTheyAreEntered() {
        driver.open(OTHER);
        controller.anchor = 2;

        driver.chest("open-list");
        assertEquals(new MenuPaging(2, 5), driver.screen().paging());
        driver.chest("previous");
        assertEquals(new MenuPaging(1, 5), driver.screen().paging());
        controller.anchor = 4;
        driver.chest("back");
        driver.chest("open-list");

        assertEquals(new MenuPaging(4, 5), driver.screen().paging());
        assertEquals(List.of(0, 2, 1, 0, 4), controller.builtPages);
    }

    @Test
    void explicitNavigatePagesSkipTheAnchor() {
        driver.open(OTHER);
        controller.anchor = 3;
        controller.failing = true;

        driver.chest("open-list-page");

        assertEquals(new MenuPaging(1, 5), driver.screen().paging());
        assertTrue(logs.records.isEmpty());
    }

    @Test
    void negativeAnchorsOpenOnTheFirstPage() {
        controller.anchor = -2;

        driver.open(LIST);

        assertEquals(new MenuPaging(0, 5), driver.screen().paging());
    }

    @Test
    void failingAnchorsAreLoggedAndOpenOnTheFirstPage() {
        controller.anchor = 3;
        controller.failing = true;

        driver.open(LIST);

        assertEquals(new MenuPaging(0, 5), driver.screen().paging());
        assertTrue(driver.sessionOpen());
        assertEquals(1, logs.records.size());
        assertEquals(Level.SEVERE, logs.records.get(0).getLevel());
        assertInstanceOf(IllegalStateException.class, logs.records.get(0).getThrown());
    }

    @Test
    void failingAnchorsOnPushedRoutesOpenOnTheFirstPage() {
        driver.open(OTHER);
        controller.anchor = 2;
        controller.failing = true;

        driver.chest("open-list");

        assertEquals(new MenuPaging(0, 5), driver.screen().paging());
        assertEquals(1, logs.records.size());
        assertEquals(Level.SEVERE, logs.records.get(0).getLevel());
        assertInstanceOf(IllegalStateException.class, logs.records.get(0).getThrown());
    }

    private static final class PagedController implements MenuController {
        private final List<Integer> builtPages = new ArrayList<>();
        private int anchor;
        private boolean failing;

        @Override
        public String id() {
            return "paged";
        }

        @Override
        public MenuDecision routeGuard(MenuContext context) {
            return MenuDecision.allow();
        }

        @Override
        public int initialPage(MenuContext context) {
            if (failing && context.route().equals(LIST)) {
                throw new IllegalStateException("anchor lookup failed");
            }
            return context.route().equals(LIST) ? anchor : 0;
        }

        @Override
        public MenuBuild build(MenuContext context) {
            builtPages.add(context.page());
            int page = Math.min(context.page(), 4);
            MenuBindings.Builder bindings = MenuBindings.builder();
            List<MenuElement> elements = new ArrayList<>(2);
            if (context.route().equals(LIST)) {
                elements.add(button("previous", 0));
                bindings.guard("previous", MenuGuards.none()).on("previous", MenuInteraction.PRIMARY,
                    (ctx, action) -> MenuResult.page(Math.max(0, page - 1)));
                elements.add(button("back", 1));
                bindings.guard("back", MenuGuards.none()).on("back", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.back());
            } else {
                elements.add(button("open-list", 0));
                bindings.guard("open-list", MenuGuards.none()).on("open-list", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(LIST));
                elements.add(button("open-list-page", 1));
                bindings.guard("open-list-page", MenuGuards.none()).on("open-list-page", MenuInteraction.PRIMARY,
                    (ctx, action) -> MenuResult.navigate(LIST, NavigateMode.PUSH, 1, null));
            }
            MenuScreen screen = MenuScreen.builder(context.route().screen())
                .elements(elements)
                .paging(context.route().equals(LIST) ? new MenuPaging(page, 5) : null)
                .build();
            return new MenuBuild(screen, new ChestLayout("Paged", 1, ChestPane.GRAY, null), bindings.build());
        }

        private static ButtonElement button(String id, int position) {
            return new ButtonElement(MenuElementCommon.builder(id).interactions(Set.of(MenuInteraction.PRIMARY))
                .chestSlot(new MenuSlot(position, 0)).build());
        }
    }

    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
