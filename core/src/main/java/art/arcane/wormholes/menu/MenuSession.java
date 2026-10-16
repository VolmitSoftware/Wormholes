package art.arcane.wormholes.menu;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.MenuMessages;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;

public final class MenuSession {
    public static final String CONFIRM_SCREEN_SUFFIX = ".confirm";
    public static final String CONFIRM_HEADER = "confirm-header";
    public static final String CONFIRM_ACCEPT = "confirm-accept";
    public static final String CONFIRM_CANCEL = "confirm-cancel";

    private final UUID viewerId;
    private final MenuService service;
    private final ArrayDeque<MenuRoute> stack = new ArrayDeque<>();
    private final Map<MenuRoute, Integer> pages = new HashMap<>();
    private final MenuState state = new MenuState();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private MenuRenderer renderer;
    private boolean nativeRenderer;
    private MenuBuild current;
    private long revision;
    private PendingConfirmation confirming;
    private CompletableFuture<MenuResult> pending;
    private boolean suspended;
    private boolean shown;
    private boolean ended;
    private int refreshInterval;
    private volatile MenuRoute currentRoute;
    private volatile long refreshDueTick = Long.MAX_VALUE;

    MenuSession(UUID viewerId, MenuService service, MenuRenderer renderer, boolean nativeRenderer) {
        this.viewerId = viewerId;
        this.service = service;
        this.renderer = renderer;
        this.nativeRenderer = nativeRenderer;
    }

    long revision() {
        return revision;
    }

    boolean ended() {
        return ended;
    }

    boolean nativeScreen() {
        return nativeRenderer;
    }

    MenuRoute currentRoute() {
        return currentRoute;
    }

    long refreshDueTick() {
        return refreshDueTick;
    }

    boolean queueRefresh() {
        return refreshQueued.compareAndSet(false, true);
    }

    void refreshRejected() {
        refreshQueued.set(false);
        refreshDueTick = Long.MAX_VALUE;
    }

    MenuDecision admit(MenuViewer viewer, MenuRoute route) {
        MenuDecision decision = guard(viewer, route);
        return decision instanceof MenuDecision.Redirect redirect ? followable(viewer, redirect) : decision;
    }

    void start(MenuViewer viewer, MenuRoute route, MenuDecision admission, long tick) {
        stack.clear();
        if (admission instanceof MenuDecision.Redirect redirect) {
            stack.push(redirect.route());
            enter(viewer, redirect.route(), null, tick);
            notice(viewer, redirect.notice());
            return;
        }
        stack.push(route);
        enter(viewer, route, null, tick);
    }

    void act(MenuViewer viewer, MenuAction action, MenuActionOrigin origin, boolean preconfirmed, CapturedItem capturedItem, long tick) {
        if (ended || current == null || pending != null) {
            return;
        }
        if (origin == MenuActionOrigin.NATIVE && action.revision() != revision) {
            resendRefreshed(viewer);
            return;
        }
        if (confirming != null) {
            if (action.interaction() != MenuInteraction.PRIMARY || action.value() != null) {
                violation(origin, "confirmation answered with " + action.interaction());
                return;
            }
            if (action.elementId().equals(CONFIRM_ACCEPT)) {
                confirmed(viewer, true, tick);
            } else if (action.elementId().equals(CONFIRM_CANCEL)) {
                confirmed(viewer, false, tick);
            } else {
                violation(origin, "element " + action.elementId() + " is not part of the confirmation");
            }
            return;
        }
        Optional<MenuElement> found = reachable(action.elementId(), origin);
        if (found.isEmpty()) {
            violation(origin, "unknown element " + action.elementId());
            return;
        }
        MenuElement element = found.get();
        if (!element.enabled()) {
            notice(viewer, MenuNotices.nativeOnly(viewer, MenuMessages.FEEDBACK_UNAVAILABLE, MessageArgs.empty(), MenuFeedbackStyle.WARNING));
            return;
        }
        if (!element.accepts(action.interaction())) {
            violation(origin, "element " + element.id() + " does not declare " + action.interaction());
            return;
        }
        if (!valueFits(element, action)) {
            notice(viewer, MenuNotices.nativeOnly(viewer, MenuMessages.FEEDBACK_INVALID_VALUE, MessageArgs.empty(), MenuFeedbackStyle.ERROR));
            violation(origin, "invalid value for " + element.id());
            return;
        }
        Optional<MenuBinding> binding = current.bindings().find(element.id());
        Optional<MenuHandler> handler = binding.flatMap(entry -> entry.handler(action.interaction()));
        if (handler.isEmpty()) {
            service.logger().fine("Menu element " + element.id() + " on " + current.screen().screenId() + " has no handler for "
                + action.interaction());
            return;
        }
        CapturedItem item = element instanceof ItemCaptureElement ? captured(viewer, origin, capturedItem) : null;
        MenuContext context = context(viewer, top(), item, origin);
        if (!allowed(viewer, context, binding.get().guard(), action, tick)) {
            return;
        }
        MenuConfirmFactory confirmation = binding.get().confirmation();
        Trigger trigger = new Trigger(binding.get().guard(), action, element);
        if (confirmation != null && !(origin == MenuActionOrigin.CHEST && preconfirmed)) {
            requestConfirmation(viewer, context, confirmation, handler.get(), trigger, tick);
            return;
        }
        run(viewer, context, handler.get(), trigger, tick);
    }

    void back(MenuViewer viewer, long clientRevision, long tick) {
        if (ended || current == null || pending != null) {
            return;
        }
        if (clientRevision != revision) {
            resendRefreshed(viewer);
            return;
        }
        if (confirming != null) {
            confirmed(viewer, false, tick);
            return;
        }
        apply(viewer, MenuResult.back(), null, tick);
    }

    void page(MenuViewer viewer, long clientRevision, int requested, long tick) {
        if (ended || current == null || pending != null) {
            return;
        }
        if (clientRevision != revision) {
            resendRefreshed(viewer);
            return;
        }
        MenuPaging paging = current.screen().paging();
        if (confirming != null || paging == null || !paging.contains(requested)) {
            violation(MenuActionOrigin.NATIVE, "page " + requested + " is outside the screen paging");
            return;
        }
        apply(viewer, MenuResult.page(requested), null, tick);
    }

    void confirm(MenuViewer viewer, long clientRevision, boolean accept, long tick) {
        if (ended || current == null || pending != null) {
            return;
        }
        if (clientRevision != revision) {
            resendRefreshed(viewer);
            return;
        }
        if (confirming == null) {
            violation(MenuActionOrigin.NATIVE, "confirmation answered while none is showing");
            return;
        }
        confirmed(viewer, accept, tick);
    }

    void clientClosed() {
        end();
    }

    boolean precheck(MenuViewer viewer, String elementId, MenuInteraction interaction, long tick) {
        if (ended || current == null || pending != null || confirming != null) {
            return false;
        }
        Optional<MenuElement> found = reachable(elementId, MenuActionOrigin.CHEST);
        if (found.isEmpty() || !found.get().enabled() || !found.get().accepts(interaction)) {
            return false;
        }
        Optional<MenuBinding> binding = current.bindings().find(elementId);
        if (binding.isEmpty()) {
            return false;
        }
        MenuAction action = new MenuAction(revision, elementId, interaction, null);
        return allowed(viewer, context(viewer, top(), null, MenuActionOrigin.CHEST), binding.get().guard(), action, tick);
    }

    void escape(MenuViewer viewer, long tick) {
        if (ended || current == null) {
            return;
        }
        pending = null;
        if (confirming != null) {
            confirmed(viewer, false, tick);
            return;
        }
        MenuRoute escapeRoute = current.chest().escapeRoute();
        if (escapeRoute == null) {
            end();
            return;
        }
        apply(viewer, MenuResult.navigate(escapeRoute, NavigateMode.POP_TO, null), null, tick);
    }

    void resume(MenuViewer viewer, long tick) {
        if (ended || !suspended) {
            return;
        }
        suspended = false;
        if (!nativeRenderer) {
            end();
            return;
        }
        if (permitted(viewer, tick)) {
            rebuild(viewer, true, true, tick);
        }
    }

    void refresh(MenuViewer viewer, long tick) {
        refreshQueued.set(false);
        if (ended || current == null) {
            return;
        }
        if (pending != null || suspended || confirming != null) {
            refreshDueTick = refreshInterval > 0 ? tick + refreshInterval : Long.MAX_VALUE;
            return;
        }
        if (permitted(viewer, tick)) {
            rebuild(viewer, false, false, tick);
        }
    }

    void revoke(MenuViewer viewer, long tick) {
        if (ended || current == null || suspended) {
            return;
        }
        permitted(viewer, tick);
    }

    void close(MenuViewer viewer) {
        if (ended) {
            return;
        }
        renderer.close(viewer);
        end();
    }

    void discard() {
        end();
    }

    void completePending(MenuViewer viewer, CompletableFuture<MenuResult> awaited, MenuResult result, Throwable error, long tick) {
        if (ended || pending != awaited) {
            return;
        }
        pending = null;
        if (error == null && result != null) {
            apply(viewer, result, null, tick);
            return;
        }
        if (error == null) {
            failed(viewer, "Menu work for " + top().screen() + " completed without a result",
                new IllegalStateException("pending menu work completed without a result"), tick);
            return;
        }
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof TimeoutException) {
            rebuild(viewer, false, true, tick);
            notice(viewer, MenuNotices.hud(viewer, MenuMessages.FEEDBACK_TIMEOUT));
            return;
        }
        failed(viewer, "Menu work for " + top().screen() + " failed", cause, tick);
    }

    private void run(MenuViewer viewer, MenuContext context, MenuHandler handler, Trigger trigger, long tick) {
        MenuResult result;
        try {
            result = Objects.requireNonNull(handler.handle(context, trigger.action()), "menu result");
        } catch (RuntimeException exception) {
            failed(viewer, "Menu handler for " + trigger.action().elementId() + " on " + current.screen().screenId() + " failed", exception, tick);
            return;
        }
        apply(viewer, result, trigger, tick);
    }

    private void apply(MenuViewer viewer, MenuResult result, Trigger trigger, long tick) {
        switch (result) {
            case MenuResult.Stay stay -> {
                rebuild(viewer, false, true, tick);
                notice(viewer, stay.notice());
            }
            case MenuResult.Navigate navigate -> navigate(viewer, navigate, tick);
            case MenuResult.Back back -> {
                if (stack.size() <= 1) {
                    closeWith(viewer, back.notice());
                    return;
                }
                stack.pop();
                if (permitted(viewer, tick)) {
                    rebuild(viewer, true, true, tick);
                    notice(viewer, back.notice());
                }
            }
            case MenuResult.Close close -> {
                closeWith(viewer, close.notice());
                afterClose(viewer, close.afterClose());
            }
            case MenuResult.Confirm confirm -> showConfirmation(viewer, confirm.request(), trigger == null ? acceptTrigger() : trigger);
            case MenuResult.Pending waiting -> {
                notice(viewer, waiting.notice());
                waitFor(waiting);
            }
            case MenuResult.Deferred deferred -> resolve(viewer, deferred, trigger, tick);
            case MenuResult.Page requested -> {
                pages.put(top(), requested.page());
                rebuild(viewer, false, true, tick);
            }
            case MenuResult.Suspend _ -> {
                suspended = true;
                renderer.close(viewer);
            }
        }
    }

    private void navigate(MenuViewer viewer, MenuResult.Navigate navigate, long tick) {
        MenuRoute route = navigate.route();
        MenuDecision decision = admit(viewer, route);
        if (!(decision instanceof MenuDecision.Allow)) {
            refuse(viewer, decision, tick);
            return;
        }
        boolean entering = switch (navigate.mode()) {
            case PUSH -> {
                stack.push(route);
                yield true;
            }
            case REPLACE -> {
                stack.pop();
                stack.push(route);
                yield true;
            }
            case POP_TO -> popTo(route);
        };
        if (entering) {
            enter(viewer, route, navigate.page(), tick);
        } else {
            if (navigate.page() != null) {
                pages.put(top(), navigate.page());
            }
            rebuild(viewer, true, true, tick);
        }
        notice(viewer, navigate.notice());
    }

    private boolean popTo(MenuRoute route) {
        while (!stack.isEmpty() && !stack.peek().sameScreen(route)) {
            stack.pop();
        }
        if (!stack.isEmpty()) {
            return false;
        }
        stack.push(route);
        return true;
    }

    private void enter(MenuViewer viewer, MenuRoute route, Integer explicitPage, long tick) {
        pages.remove(route);
        MenuController controller = service.registry().require(route.controllerId());
        try {
            controller.entered(context(viewer, route, null, origin()));
        } catch (RuntimeException exception) {
            service.logger().log(Level.SEVERE, "Menu screen " + route.screen() + " could not be entered", exception);
            closeWith(viewer, unavailable(viewer));
            return;
        }
        if (explicitPage != null) {
            pages.put(route, explicitPage);
        } else {
            anchorPage(viewer, controller, route);
        }
        rebuild(viewer, true, true, tick);
    }

    private void anchorPage(MenuViewer viewer, MenuController controller, MenuRoute route) {
        int page;
        try {
            page = controller.initialPage(context(viewer, route, null, origin()));
        } catch (RuntimeException exception) {
            service.logger().log(Level.SEVERE, "Menu initial page for " + route.screen() + " could not be resolved", exception);
            page = 0;
        }
        pages.put(route, Math.max(0, page));
    }

    private void afterClose(MenuViewer viewer, Supplier<MenuNotice> afterClose) {
        if (afterClose == null) {
            return;
        }
        MenuNotice notice;
        try {
            notice = afterClose.get();
        } catch (RuntimeException exception) {
            service.logger().log(Level.SEVERE, "Menu follow-up after closing " + top().screen() + " failed", exception);
            notice = unavailable(viewer);
        }
        notice(viewer, notice);
    }

    private void resolve(MenuViewer viewer, MenuResult.Deferred deferred, Trigger trigger, long tick) {
        MenuResult result;
        try {
            result = Objects.requireNonNull(deferred.result().get(), "menu result");
        } catch (RuntimeException exception) {
            failed(viewer, "Menu result for " + top().screen() + " could not be resolved", exception, tick);
            return;
        }
        apply(viewer, result, trigger, tick);
    }

    private void waitFor(MenuResult.Pending waiting) {
        CompletableFuture<MenuResult> awaited = waiting.future();
        pending = awaited;
        awaited.orTimeout(MenuLimits.PENDING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .whenComplete((result, error) -> service.completePending(viewerId, this, awaited, result, error));
    }

    private void failed(MenuViewer viewer, String message, Throwable cause, long tick) {
        service.logger().log(Level.SEVERE, message, cause);
        rebuild(viewer, false, true, tick);
        if (!ended) {
            notice(viewer, unavailable(viewer));
        }
    }

    private void requestConfirmation(MenuViewer viewer, MenuContext context, MenuConfirmFactory factory, MenuHandler onConfirm, Trigger trigger,
                                     long tick) {
        MenuConfirmRequest request;
        try {
            request = Objects.requireNonNull(factory.request(context, onConfirm), "confirmation request");
        } catch (RuntimeException exception) {
            failed(viewer, "Menu confirmation for " + trigger.action().elementId() + " on " + current.screen().screenId() + " failed",
                exception, tick);
            return;
        }
        showConfirmation(viewer, request, trigger);
    }

    private void showConfirmation(MenuViewer viewer, MenuConfirmRequest request, Trigger trigger) {
        confirming = new PendingConfirmation(request, trigger);
        ChestConfirmLayout layout = request.chest();
        MenuStyle acceptStyle = trigger.source() != null && trigger.source().style() == MenuStyle.DESTRUCTIVE
            ? MenuStyle.DESTRUCTIVE
            : MenuStyle.NORMAL;
        LabelElement header = new LabelElement(MenuElementCommon.builder(CONFIRM_HEADER)
            .label(request.title())
            .description(request.messageLines())
            .icon(MenuIcon.item(layout.headerItemId()))
            .chestSlot(layout.header())
            .build());
        ButtonElement accept = new ButtonElement(MenuElementCommon.builder(CONFIRM_ACCEPT)
            .label(request.confirmLabel())
            .icon(MenuIcon.item(layout.confirmItemId()))
            .style(acceptStyle)
            .interactions(Set.of(MenuInteraction.PRIMARY))
            .chestSlot(layout.confirm())
            .build());
        ButtonElement cancel = new ButtonElement(MenuElementCommon.builder(CONFIRM_CANCEL)
            .label(request.cancelLabel())
            .icon(MenuIcon.item(layout.cancelItemId()))
            .interactions(Set.of(MenuInteraction.PRIMARY))
            .chestSlot(layout.cancel())
            .build());
        MenuScreen screen = MenuScreen.builder(current.screen().screenId() + CONFIRM_SCREEN_SUFFIX)
            .title(current.screen().title())
            .context(current.screen().context())
            .elements(List.of(header, accept, cancel))
            .confirmation(new MenuConfirmation(request.messageLines(), CONFIRM_ACCEPT, CONFIRM_CANCEL))
            .build();
        push(viewer, new MenuBuild(screen, new ChestLayout(layout.title(), layout.rows(), layout.pane(), null), MenuBindings.builder().build()),
            true);
    }

    private void confirmed(MenuViewer viewer, boolean accept, long tick) {
        PendingConfirmation confirmation = confirming;
        confirming = null;
        if (!accept) {
            rebuild(viewer, true, true, tick);
            return;
        }
        MenuContext context = context(viewer, top(), null, origin());
        MenuDecision decision = decide(viewer, context, confirmation.trigger().guard(), confirmation.trigger().action());
        if (!(decision instanceof MenuDecision.Allow)) {
            refuse(viewer, decision, tick);
            if (decision instanceof MenuDecision.Deny && !ended) {
                rebuild(viewer, true, true, tick);
            }
            return;
        }
        MenuResult result;
        try {
            result = Objects.requireNonNull(confirmation.request().onConfirm().handle(context, confirmation.trigger().action()), "menu result");
        } catch (RuntimeException exception) {
            failed(viewer, "Menu confirmation for " + confirmation.trigger().action().elementId() + " failed", exception, tick);
            return;
        }
        apply(viewer, result, confirmation.trigger(), tick);
    }

    private boolean allowed(MenuViewer viewer, MenuContext context, MenuGuard guard, MenuAction action, long tick) {
        MenuDecision decision = decide(viewer, context, guard, action);
        if (decision instanceof MenuDecision.Allow) {
            return true;
        }
        refuse(viewer, decision, tick);
        return false;
    }

    private MenuDecision decide(MenuViewer viewer, MenuContext context, MenuGuard guard, MenuAction action) {
        try {
            MenuController controller = service.registry().require(context.route().controllerId());
            MenuDecision decision = Objects.requireNonNull(controller.routeGuard(context), "route guard decision");
            if (decision instanceof MenuDecision.Allow) {
                decision = Objects.requireNonNull(guard.check(context, action), "guard decision");
            }
            return decision instanceof MenuDecision.Redirect redirect ? followable(viewer, redirect) : decision;
        } catch (RuntimeException exception) {
            service.logger().log(Level.SEVERE, "Menu guard for " + action.elementId() + " on " + context.route().screen() + " failed", exception);
            return MenuDecision.deny(unavailable(viewer));
        }
    }

    private MenuDecision guard(MenuViewer viewer, MenuRoute route) {
        try {
            MenuController controller = service.registry().require(route.controllerId());
            return Objects.requireNonNull(controller.routeGuard(context(viewer, route, null, origin())), "route guard decision");
        } catch (RuntimeException exception) {
            service.logger().log(Level.SEVERE, "Menu route guard for " + route.screen() + " failed", exception);
            return MenuDecision.deny(unavailable(viewer));
        }
    }

    private MenuDecision followable(MenuViewer viewer, MenuDecision.Redirect redirect) {
        return guard(viewer, redirect.route()) instanceof MenuDecision.Allow ? redirect : MenuDecision.denyAndClose(redirect.notice());
    }

    private void refuse(MenuViewer viewer, MenuDecision decision, long tick) {
        switch (decision) {
            case MenuDecision.Allow _ -> {
            }
            case MenuDecision.Deny deny when deny.close() -> closeWith(viewer, deny.notice());
            case MenuDecision.Deny deny -> notice(viewer, deny.notice());
            case MenuDecision.Redirect redirect -> redirect(viewer, redirect, tick);
        }
    }

    private void redirect(MenuViewer viewer, MenuDecision.Redirect redirect, long tick) {
        if (!top().sameScreen(redirect.route())) {
            confirming = null;
            pending = null;
            if (popTo(redirect.route())) {
                enter(viewer, redirect.route(), null, tick);
            } else {
                rebuild(viewer, true, true, tick);
            }
        }
        notice(viewer, redirect.notice());
    }

    private boolean permitted(MenuViewer viewer, long tick) {
        switch (admit(viewer, top())) {
            case MenuDecision.Allow _ -> {
                return true;
            }
            case MenuDecision.Deny deny -> closeWith(viewer, deny.notice());
            case MenuDecision.Redirect redirect -> redirect(viewer, redirect, tick);
        }
        return false;
    }

    private void rebuild(MenuViewer viewer, boolean opening, boolean force, long tick) {
        MenuRoute route = top();
        MenuController controller = service.registry().require(route.controllerId());
        MenuBuild build;
        try {
            build = Objects.requireNonNull(controller.build(context(viewer, route, null, origin())), "menu build");
            refreshInterval = controller.refreshIntervalTicks(route);
        } catch (RuntimeException exception) {
            service.logger().log(Level.SEVERE, "Menu screen " + route.screen() + " could not be built", exception);
            closeWith(viewer, unavailable(viewer));
            return;
        }
        refreshDueTick = refreshInterval > 0 ? tick + refreshInterval : Long.MAX_VALUE;
        currentRoute = route;
        MenuScreen screen = build.screen().toBuilder()
            .canGoBack(stack.size() > 1)
            .chrome(chrome(viewer, build.screen().paging()))
            .build();
        if (!force && current != null && current.screen().sameContent(screen)) {
            current = build.withScreen(screen.withRevision(revision));
            return;
        }
        boolean replacing = current == null || !current.screen().screenId().equals(screen.screenId());
        push(viewer, build.withScreen(screen), opening || replacing);
    }

    private void push(MenuViewer viewer, MenuBuild build, boolean opening) {
        revision = service.nextRevision(viewerId);
        MenuBuild stamped = build.withScreen(build.screen().withRevision(revision));
        current = stamped;
        try {
            renderer.show(viewer, stamped, opening);
            shown = true;
        } catch (MenuProtocolException exception) {
            if (!nativeRenderer) {
                throw new IllegalStateException("chest renderer failed for " + viewer.name(), exception);
            }
            service.nativeFailed(viewer, exception);
            if (shown) {
                renderer.close(viewer);
            }
            renderer = service.host().chestRenderer();
            nativeRenderer = false;
            showChest(viewer, stamped);
        }
    }

    private void showChest(MenuViewer viewer, MenuBuild build) {
        try {
            renderer.show(viewer, build, true);
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException("chest renderer failed for " + viewer.name(), exception);
        }
    }

    private void resendRefreshed(MenuViewer viewer) {
        MenuScreen screen = current.screen().withFeedback(new MenuFeedback(MenuFeedbackStyle.INFO,
            viewer.strings().text(MenuMessages.FEEDBACK_REFRESHED)));
        try {
            renderer.show(viewer, current.withScreen(screen), false);
        } catch (MenuProtocolException exception) {
            service.nativeFailed(viewer, exception);
        }
    }

    private void notice(MenuViewer viewer, MenuNotice notice) {
        if (notice == null) {
            return;
        }
        (ended ? service.host().chestRenderer() : renderer).notice(viewer, notice);
    }

    private void closeWith(MenuViewer viewer, MenuNotice notice) {
        close(viewer);
        if (notice != null) {
            service.host().chestRenderer().notice(viewer, notice);
        }
    }

    private void violation(MenuActionOrigin origin, String reason) {
        if (origin == MenuActionOrigin.NATIVE) {
            service.violation(viewerId, reason);
        }
    }

    private Optional<MenuElement> reachable(String elementId, MenuActionOrigin origin) {
        Optional<MenuElement> element = current.screen().element(elementId);
        if (element.isEmpty()) {
            return element;
        }
        if (origin == MenuActionOrigin.NATIVE) {
            return element.get().common().nativeHidden() ? Optional.empty() : element;
        }
        if (element.get().chestSlot() != null || delegationTargets().contains(elementId)) {
            return element;
        }
        return Optional.empty();
    }

    private Set<String> delegationTargets() {
        Set<String> targets = new HashSet<>();
        for (MenuElement element : current.screen().elements()) {
            MenuSlot slot = element.chestSlot();
            if (slot == null) {
                continue;
            }
            for (ChestTarget target : slot.clicks().values()) {
                if (target.elementId() != null) {
                    targets.add(target.elementId());
                }
            }
        }
        return targets;
    }

    private CapturedItem captured(MenuViewer viewer, MenuActionOrigin origin, CapturedItem chestItem) {
        if (origin == MenuActionOrigin.CHEST) {
            return chestItem;
        }
        return service.host().captures().heldItem(viewer).orElse(null);
    }

    private MenuChrome chrome(MenuViewer viewer, MenuPaging paging) {
        MenuStrings strings = viewer.strings();
        String pageLabel = paging == null ? "" : strings.text(MenuMessages.CHROME_PAGE, MessageArgs.builder()
            .untrusted("page", paging.page() + 1)
            .untrusted("pages", paging.pageCount())
            .build());
        return new MenuChrome(
            strings.text(MenuMessages.CHROME_BACK),
            strings.text(MenuMessages.CHROME_CLOSE),
            strings.text(MenuMessages.CHROME_APPLY),
            strings.text(MenuMessages.CHROME_CONFIRM),
            strings.text(MenuMessages.CHROME_CANCEL),
            strings.text(MenuMessages.CHROME_PREVIOUS_PAGE),
            strings.text(MenuMessages.CHROME_NEXT_PAGE),
            pageLabel,
            strings.text(MenuMessages.CHROME_USE_HELD_ITEM),
            strings.text(MenuMessages.CHROME_AIM),
            strings.text(MenuMessages.CHROME_TIMEOUT));
    }

    private MenuContext context(MenuViewer viewer, MenuRoute route, CapturedItem item, MenuActionOrigin origin) {
        return new MenuContext(viewer, route, pages.getOrDefault(route, 0), state, item, origin);
    }

    private MenuActionOrigin origin() {
        return nativeRenderer ? MenuActionOrigin.NATIVE : MenuActionOrigin.CHEST;
    }

    private MenuRoute top() {
        return stack.peek();
    }

    private void end() {
        ended = true;
        confirming = null;
        pending = null;
        refreshDueTick = Long.MAX_VALUE;
    }

    private static MenuNotice unavailable(MenuViewer viewer) {
        return MenuNotices.hud(viewer, MenuMessages.FEEDBACK_UNAVAILABLE);
    }

    private static boolean valueFits(MenuElement element, MenuAction action) {
        MenuValue value = action.value();
        return switch (action.interaction()) {
            case SET_VALUE -> element instanceof NumberElement number && value instanceof MenuValue.Number given
                && number.containsValue(given.number());
            case SET_TEXT -> element instanceof TextElement text && value instanceof MenuValue.Text given && text.fits(given.text())
                && MenuText.plainInput(given.text());
            case SELECT -> element instanceof CycleElement cycle && value instanceof MenuValue.Index given
                && cycle.containsIndex(given.index());
            default -> value == null;
        };
    }

    private Trigger acceptTrigger() {
        return new Trigger((context, action) -> MenuDecision.allow(), new MenuAction(revision, CONFIRM_ACCEPT, MenuInteraction.PRIMARY, null),
            null);
    }

    private record Trigger(MenuGuard guard, MenuAction action, MenuElement source) {
    }

    private record PendingConfirmation(MenuConfirmRequest request, Trigger trigger) {
    }
}
