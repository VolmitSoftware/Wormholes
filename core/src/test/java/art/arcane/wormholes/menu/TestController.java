package art.arcane.wormholes.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

class TestController implements MenuController {
    static final String ID = "test";
    static final String MAIN = "test.main";
    static final String CHILD = "test.child";
    static final UUID TOUCHED = UUID.fromString("00000000-0000-0000-0000-00000000cafe");

    final List<String> handled = new ArrayList<>();
    final List<String> entered = new ArrayList<>();
    int counter;
    String name = "";
    int cycle;
    int refreshInterval;
    boolean accessAllowed = true;
    boolean routeGuardThrows;
    boolean childRedirects;
    boolean guardAllows = true;
    boolean deleted;
    boolean buildThrows;
    boolean refreshIntervalThrows;
    boolean childTouchesThrow;
    boolean enterThrows;
    CapturedItem captured;
    CompletableFuture<MenuResult> slow = new CompletableFuture<>();
    Supplier<MenuNotice> handoff = () -> null;

    static MenuRoute main() {
        return MenuRoute.of(ID, MAIN);
    }

    static MenuRoute child() {
        return MenuRoute.of(ID, CHILD);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public MenuDecision routeGuard(MenuContext context) {
        if (routeGuardThrows) {
            throw new IllegalStateException("route guard boom");
        }
        if (childRedirects && context.route().screen().equals(CHILD)) {
            return MenuDecision.redirect(main(), MenuNotices.text("moved", MenuFeedbackStyle.WARNING, NoticeChannel.CHAT));
        }
        if (accessAllowed) {
            return MenuDecision.allow();
        }
        return MenuDecision.denyAndClose(MenuNotices.text("no access", MenuFeedbackStyle.ERROR, NoticeChannel.HUD));
    }

    @Override
    public void entered(MenuContext context) {
        if (enterThrows) {
            throw new IllegalStateException("enter boom");
        }
        entered.add(context.route().screen() + "@" + context.page());
    }

    @Override
    public int refreshIntervalTicks(MenuRoute route) {
        if (refreshIntervalThrows) {
            throw new IllegalStateException("interval boom");
        }
        return refreshInterval;
    }

    @Override
    public boolean touches(MenuRoute route, MenuScope scope) {
        if (childTouchesThrow && route.screen().equals(CHILD)) {
            throw new IllegalStateException("touches boom");
        }
        return scope instanceof MenuScope.Portal portal && portal.portalId().equals(TOUCHED);
    }

    @Override
    public MenuBuild build(MenuContext context) {
        if (buildThrows) {
            throw new IllegalStateException("build boom");
        }
        MenuBindings.Builder bindings = MenuBindings.builder();
        List<MenuElement> elements = new ArrayList<>();
        MenuGuard allow = MenuGuards.none();
        elements.add(new ButtonElement(press("increment", 0, 0)));
        bindings.guard("increment", allow).on("increment", MenuInteraction.PRIMARY, (ctx, action) -> {
            handled.add("increment");
            counter++;
            return MenuResult.stay();
        });
        elements.add(new NumberElement(common("number", 1, 0, Set.of(MenuInteraction.INCREMENT, MenuInteraction.SET_VALUE)), Math.min(counter, 10),
            0.0D, 10.0D, 1.0D, 1.0D, Integer.toString(counter)));
        bindings.guard("number", allow)
            .on("number", MenuInteraction.SET_VALUE, (ctx, action) -> {
                handled.add("number");
                counter = (int) ((MenuValue.Number) action.value()).number();
                return MenuResult.stay();
            })
            .on("number", MenuInteraction.INCREMENT, (ctx, action) -> {
                handled.add("number+");
                counter++;
                return MenuResult.stay();
            });
        elements.add(new TextElement(common("name", 2, 0, Set.of(MenuInteraction.SET_TEXT)), name, 8, "name", List.of("Type a name"),
            ChestReopen.ALWAYS, null));
        bindings.guard("name", allow).on("name", MenuInteraction.SET_TEXT, (ctx, action) -> {
            handled.add("name");
            name = ((MenuValue.Text) action.value()).text();
            return MenuResult.stay();
        });
        elements.add(new CycleElement(common("cycle", 3, 0, Set.of(MenuInteraction.SELECT)), List.of("a", "b", "c"), cycle));
        bindings.guard("cycle", allow).on("cycle", MenuInteraction.SELECT, (ctx, action) -> {
            handled.add("cycle");
            cycle = ((MenuValue.Index) action.value()).index();
            return MenuResult.stay();
        });
        elements.add(new ButtonElement(press("guarded", 4, 0)));
        bindings.guard("guarded", (ctx, action) -> guardAllows
                ? MenuDecision.allow()
                : MenuDecision.deny(MenuNotices.text("denied", MenuFeedbackStyle.ERROR, NoticeChannel.HUD)))
            .on("guarded", MenuInteraction.PRIMARY, (ctx, action) -> {
                handled.add("guarded");
                return MenuResult.stay();
            });
        elements.add(new ButtonElement(press("child", -1, 1)));
        bindings.guard("child", allow).on("child", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(child()));
        elements.add(new ButtonElement(common("delete", -2, 1, Set.of(MenuInteraction.PRIMARY)).toBuilder()
            .style(MenuStyle.DESTRUCTIVE)
            .chestSlot(new MenuSlot(-2, 1).withClick(ChestClick.SHIFT_LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY)))
            .build()));
        bindings.guard("delete", allow)
            .on("delete", MenuInteraction.PRIMARY, (ctx, action) -> {
                deleted = true;
                return MenuResult.close();
            })
            .confirm("delete", (ctx, onConfirm) -> new MenuConfirmRequest("Delete?", List.of("Really delete it?"), "Delete", "Cancel",
                onConfirm, ChestConfirmLayout.standard("Confirm")));
        elements.add(new ButtonElement(press("notice", -3, 1)));
        bindings.guard("notice", allow).on("notice", MenuInteraction.PRIMARY,
            (ctx, action) -> MenuResult.stay(MenuNotices.text("Saved", MenuFeedbackStyle.SUCCESS, NoticeChannel.SUCCESS_EFFECT)));
        elements.add(new LabelElement(common("label", -4, 1, Set.of())));
        elements.add(new ButtonElement(press("hidden", 1, 1).toBuilder().nativeHidden(true).build()));
        bindings.guard("hidden", allow).on("hidden", MenuInteraction.PRIMARY, (ctx, action) -> {
            handled.add("hidden");
            return MenuResult.stay();
        });
        elements.add(new ButtonElement(MenuElementCommon.builder("native-only").label("native-only").interactions(Set.of(MenuInteraction.PRIMARY)).build()));
        bindings.guard("native-only", allow).on("native-only", MenuInteraction.PRIMARY, (ctx, action) -> {
            handled.add("native-only");
            return MenuResult.stay();
        });
        elements.add(new ButtonElement(press("slow", 2, 1)));
        bindings.guard("slow", allow).on("slow", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.pending(slow));
        elements.add(new ButtonElement(press("slow-notice", -4, 0)));
        bindings.guard("slow-notice", allow).on("slow-notice", MenuInteraction.PRIMARY,
            (ctx, action) -> MenuResult.pending(slow, MenuNotices.text("Loading", MenuFeedbackStyle.INFO, NoticeChannel.CHAT)));
        elements.add(new ButtonElement(press("home-page", -1, 0)));
        bindings.guard("home-page", allow).on("home-page", MenuInteraction.PRIMARY,
            (ctx, action) -> MenuResult.navigate(main(), NavigateMode.POP_TO, 1, null));
        elements.add(new ButtonElement(press("child-page", -2, 0)));
        bindings.guard("child-page", allow).on("child-page", MenuInteraction.PRIMARY,
            (ctx, action) -> MenuResult.navigate(child(), NavigateMode.PUSH, 2, null));
        elements.add(new ButtonElement(press("negative-page", -3, 0)));
        bindings.guard("negative-page", allow).on("negative-page", MenuInteraction.PRIMARY,
            (ctx, action) -> MenuResult.navigate(main(), NavigateMode.POP_TO, -1, null));
        elements.add(new ButtonElement(press("explode", 3, 1)));
        bindings.guard("explode", allow).on("explode", MenuInteraction.PRIMARY, (ctx, action) -> {
            throw new IllegalStateException("boom");
        });
        elements.add(new ButtonElement(press("disabled", 4, 1).toBuilder().enabled(false).build()));
        bindings.guard("disabled", allow).on("disabled", MenuInteraction.PRIMARY, (ctx, action) -> {
            handled.add("disabled");
            return MenuResult.stay();
        });
        elements.add(new ItemCaptureElement(press("held", 0, 2), List.of()));
        bindings.guard("held", allow).on("held", MenuInteraction.PRIMARY, (ctx, action) -> {
            captured = ctx.capturedItem();
            return MenuResult.stay();
        });
        elements.add(new ButtonElement(press("suspend", 1, 2)));
        bindings.guard("suspend", allow).on("suspend", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.suspend());
        elements.add(new ButtonElement(press("next-page", 2, 2)));
        bindings.guard("next-page", allow).on("next-page", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.page(ctx.page() + 1));
        elements.add(new ButtonElement(press("farewell", 3, 2)));
        bindings.guard("farewell", allow).on("farewell", MenuInteraction.PRIMARY,
            (ctx, action) -> MenuResult.close(MenuNotices.text("Bye", MenuFeedbackStyle.SUCCESS, NoticeChannel.CHAT)));
        elements.add(new ButtonElement(press("guard-explode", 4, 2)));
        bindings.guard("guard-explode", (ctx, action) -> {
            throw new IllegalStateException("guard boom");
        }).on("guard-explode", MenuInteraction.PRIMARY, (ctx, action) -> {
            handled.add("guard-explode");
            return MenuResult.stay();
        });
        elements.add(new ButtonElement(press("confirm-explode", -1, 2)));
        bindings.guard("confirm-explode", allow)
            .on("confirm-explode", MenuInteraction.PRIMARY, (ctx, action) -> {
                handled.add("confirm-explode");
                return MenuResult.stay();
            })
            .confirm("confirm-explode", (ctx, onConfirm) -> {
                throw new IllegalStateException("confirm boom");
            });
        elements.add(new ButtonElement(press("null-result", -2, 2)));
        bindings.guard("null-result", allow).on("null-result", MenuInteraction.PRIMARY, (ctx, action) -> null);
        elements.add(new ButtonElement(press("vanish", -3, 2)));
        bindings.guard("vanish", allow).on("vanish", MenuInteraction.PRIMARY, (ctx, action) -> {
            buildThrows = true;
            return MenuResult.stay(MenuNotices.text("Removed", MenuFeedbackStyle.SUCCESS, NoticeChannel.SUCCESS_EFFECT));
        });
        elements.add(new ButtonElement(press("handoff", -4, 2)));
        bindings.guard("handoff", allow).on("handoff", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.closeThen(handoff));
        MenuScreen screen = MenuScreen.builder(context.route().screen())
            .title("Test " + counter)
            .paging(new MenuPaging(Math.min(context.page(), 2), 3))
            .elements(elements)
            .build();
        MenuRoute escape = context.route().screen().equals(CHILD) ? main() : null;
        return new MenuBuild(screen, new ChestLayout("Test", 3, ChestPane.GRAY, escape), bindings.build());
    }

    private static MenuElementCommon press(String id, int position, int row) {
        return common(id, position, row, Set.of(MenuInteraction.PRIMARY));
    }

    private static MenuElementCommon common(String id, int position, int row, Set<MenuInteraction> interactions) {
        return MenuElementCommon.builder(id).label(id).interactions(interactions).chestSlot(new MenuSlot(position, row)).build();
    }
}
